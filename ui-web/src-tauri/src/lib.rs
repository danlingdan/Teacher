use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::io::{BufRead, BufReader, Write};
use std::path::PathBuf;
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{self, Sender};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager, State};

const CONTRACT_VERSION: &str = "3.0-alpha.1";
const MAX_REQUEST_BYTES: usize = 1_048_576;
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);
const ALLOWED_METHODS: &[&str] = &[
    "system.health",
    "home.summary",
    "knowledge.sample",
    "editor.languages",
    "benchmark.echo",
    "task.demo",
    "system.cancel",
    "system.shutdown",
];

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct IpcRequest {
    request_id: String,
    method: String,
    params: Value,
    contract_version: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct BridgeError {
    code: String,
    message: String,
    retryable: bool,
}

impl BridgeError {
    fn new(code: &str, message: impl Into<String>, retryable: bool) -> Self {
        Self {
            code: code.to_owned(),
            message: message.into(),
            retryable,
        }
    }
}

struct SidecarProcess {
    child: Child,
    stdin: ChildStdin,
}

struct SidecarManager {
    app: AppHandle,
    process: Mutex<Option<SidecarProcess>>,
    pending: Arc<Mutex<HashMap<String, Sender<Value>>>>,
    sequence: AtomicU64,
}

impl SidecarManager {
    fn new(app: AppHandle) -> Self {
        Self {
            app,
            process: Mutex::new(None),
            pending: Arc::new(Mutex::new(HashMap::new())),
            sequence: AtomicU64::new(1),
        }
    }

    fn request(&self, mut request: IpcRequest) -> Result<Value, BridgeError> {
        self.validate(&request)?;
        if request.request_id.is_empty() {
            request.request_id = format!("rust-{}", self.sequence.fetch_add(1, Ordering::Relaxed));
        }
        let payload = serde_json::to_string(&request).map_err(|_| {
            BridgeError::new(
                "SERIALIZATION_FAILED",
                "Unable to encode local request",
                false,
            )
        })?;
        if payload.len() > MAX_REQUEST_BYTES {
            return Err(BridgeError::new(
                "PAYLOAD_TOO_LARGE",
                "Local application request exceeds the one MiB limit",
                false,
            ));
        }

        let (sender, receiver) = mpsc::channel();
        self.pending
            .lock()
            .map_err(|_| {
                BridgeError::new(
                    "BRIDGE_UNAVAILABLE",
                    "Local bridge state is unavailable",
                    true,
                )
            })?
            .insert(request.request_id.clone(), sender);

        let write_result = (|| {
            let mut process_guard = self.process.lock().map_err(|_| {
                BridgeError::new(
                    "BRIDGE_UNAVAILABLE",
                    "Local sidecar lock is unavailable",
                    true,
                )
            })?;
            if process_guard.is_none() {
                *process_guard = Some(self.start()?);
            }
            let process = process_guard.as_mut().expect("sidecar was initialized");
            writeln!(process.stdin, "{payload}")
                .and_then(|_| process.stdin.flush())
                .map_err(|_| {
                    BridgeError::new(
                        "SIDECAR_WRITE_FAILED",
                        "Unable to send request to Java core",
                        true,
                    )
                })
        })();
        if let Err(error) = write_result {
            self.remove_pending(&request.request_id);
            return Err(error);
        }

        let response = receiver.recv_timeout(REQUEST_TIMEOUT).map_err(|_| {
            self.remove_pending(&request.request_id);
            BridgeError::new(
                "SIDECAR_TIMEOUT",
                "Java core did not respond before the timeout",
                true,
            )
        })?;
        if let Some(error) = response.get("error") {
            return Err(BridgeError::new(
                error
                    .get("code")
                    .and_then(Value::as_str)
                    .unwrap_or("LOCAL_APP_FAILURE"),
                error
                    .get("message")
                    .and_then(Value::as_str)
                    .unwrap_or("Local application operation failed"),
                error
                    .get("retryable")
                    .and_then(Value::as_bool)
                    .unwrap_or(false),
            ));
        }
        Ok(response.get("result").cloned().unwrap_or(Value::Null))
    }

    fn validate(&self, request: &IpcRequest) -> Result<(), BridgeError> {
        if request.contract_version != CONTRACT_VERSION {
            return Err(BridgeError::new(
                "CONTRACT_VERSION_UNSUPPORTED",
                "Unsupported local application contract version",
                false,
            ));
        }
        if !ALLOWED_METHODS.contains(&request.method.as_str()) {
            return Err(BridgeError::new(
                "METHOD_NOT_ALLOWED",
                "The requested local application method is not allowed",
                false,
            ));
        }
        Ok(())
    }

    fn start(&self) -> Result<SidecarProcess, BridgeError> {
        let root = self.sidecar_root()?;
        let java = if cfg!(target_os = "windows") {
            root.join("runtime").join("bin").join("java.exe")
        } else {
            root.join("runtime").join("bin").join("java")
        };
        if !java.is_file() {
            return Err(BridgeError::new(
                "SIDECAR_RUNTIME_MISSING",
                "Bundled Java runtime is missing; run the Alpha.1 sidecar build first",
                false,
            ));
        }
        let separator = if cfg!(target_os = "windows") {
            ";"
        } else {
            ":"
        };
        let classpath = format!("app/*{separator}app/lib/*");
        let mut child = Command::new(java)
            .current_dir(&root)
            .args([
                "-Dfile.encoding=UTF-8",
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                &classpath,
                "com.sqlteacher.desktop.bridge.LocalAppHost",
            ])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()
            .map_err(|_| {
                BridgeError::new(
                    "SIDECAR_START_FAILED",
                    "Unable to start the Java core",
                    true,
                )
            })?;
        let stdin = child.stdin.take().ok_or_else(|| {
            BridgeError::new(
                "SIDECAR_START_FAILED",
                "Java core stdin is unavailable",
                true,
            )
        })?;
        let stdout = child.stdout.take().ok_or_else(|| {
            BridgeError::new(
                "SIDECAR_START_FAILED",
                "Java core stdout is unavailable",
                true,
            )
        })?;
        let pending = Arc::clone(&self.pending);
        let app = self.app.clone();
        std::thread::Builder::new()
            .name("sqlteacher-sidecar-output".to_owned())
            .spawn(move || {
                for line in BufReader::new(stdout).lines() {
                    let Ok(line) = line else { break };
                    let Ok(message) = serde_json::from_str::<Value>(&line) else {
                        continue;
                    };
                    if message.get("type").and_then(Value::as_str) == Some("event") {
                        let _ = app.emit("local-app-event", &message);
                        continue;
                    }
                    let Some(request_id) = message.get("requestId").and_then(Value::as_str) else {
                        continue;
                    };
                    let sender = pending
                        .lock()
                        .ok()
                        .and_then(|mut items| items.remove(request_id));
                    if let Some(sender) = sender {
                        let _ = sender.send(message);
                    }
                }
                if let Ok(mut items) = pending.lock() {
                    let failure = json!({
                        "error": {
                            "code": "SIDECAR_EXITED",
                            "message": "Java core exited before completing the request",
                            "retryable": true
                        }
                    });
                    for (_, sender) in items.drain() {
                        let _ = sender.send(failure.clone());
                    }
                }
            })
            .map_err(|_| {
                BridgeError::new(
                    "SIDECAR_START_FAILED",
                    "Unable to monitor the Java core",
                    true,
                )
            })?;
        Ok(SidecarProcess { child, stdin })
    }

    fn sidecar_root(&self) -> Result<PathBuf, BridgeError> {
        let development = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("sidecar");
        if cfg!(debug_assertions) && development.is_dir() {
            return Ok(development);
        }
        self.app
            .path()
            .resource_dir()
            .map(|path| path.join("sidecar"))
            .map_err(|_| {
                BridgeError::new(
                    "SIDECAR_RUNTIME_MISSING",
                    "Unable to resolve bundled resources",
                    false,
                )
            })
    }

    fn remove_pending(&self, request_id: &str) {
        if let Ok(mut pending) = self.pending.lock() {
            pending.remove(request_id);
        }
    }

    fn shutdown(&self) {
        if let Ok(mut process) = self.process.lock() {
            if let Some(mut process) = process.take() {
                let request = json!({
                    "requestId": format!("rust-shutdown-{}", self.sequence.fetch_add(1, Ordering::Relaxed)),
                    "method": "system.shutdown",
                    "params": {},
                    "contractVersion": CONTRACT_VERSION
                });
                let _ = writeln!(process.stdin, "{request}");
                let _ = process.stdin.flush();
                for _ in 0..20 {
                    match process.child.try_wait() {
                        Ok(Some(_)) => return,
                        Ok(None) => std::thread::sleep(Duration::from_millis(25)),
                        Err(_) => break,
                    }
                }
                let _ = process.child.kill();
                let _ = process.child.wait();
            }
        }
    }
}

impl Drop for SidecarManager {
    fn drop(&mut self) {
        self.shutdown();
    }
}

struct AppState {
    sidecar: Arc<SidecarManager>,
}

#[tauri::command]
async fn local_app_request(
    state: State<'_, AppState>,
    request: IpcRequest,
) -> Result<Value, BridgeError> {
    let sidecar = Arc::clone(&state.sidecar);
    tauri::async_runtime::spawn_blocking(move || sidecar.request(request))
        .await
        .map_err(|_| {
            BridgeError::new(
                "BRIDGE_UNAVAILABLE",
                "Local bridge worker stopped unexpectedly",
                true,
            )
        })?
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            app.manage(AppState {
                sidecar: Arc::new(SidecarManager::new(app.handle().clone())),
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![local_app_request])
        .run(tauri::generate_context!())
        .expect("error while running SQLTeacher");
}
