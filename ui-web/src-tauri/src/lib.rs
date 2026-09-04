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

#[cfg(target_os = "windows")]
use std::os::windows::process::CommandExt;

const CONTRACT_VERSION: &str = "3.0-v1";
const MAX_REQUEST_BYTES: usize = 1_048_576;
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);
#[cfg(target_os = "windows")]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;
const ALLOWED_METHODS: &[&str] = &[
    "account.login",
    "account.register",
    "account.logout",
    "account.password.change",
    "account.password.reset.request",
    "account.sessions",
    "account.session.revoke",
    "account.export.request",
    "account.export.get",
    "account.deletion.request",
    "account.deletion.cancel",
    "account.deletion.status",
    "system.health",
    "session.current",
    "home.summary",
    "home.action.dismiss",
    "course.workspace",
    "activity.definition",
    "activity.submit",
    "knowledge.article",
    "knowledge.search",
    "knowledge.read.mark",
    "knowledge.index.status",
    "knowledge.index.rebuild",
    "knowledge.article.import",
    "knowledge.article.revise",
    "knowledge.article.visibility",
    "knowledge.article.delete",
    "knowledge.import.preview",
    "knowledge.import.execute",
    "practice.catalog",
    "practice.preview",
    "practice.start",
    "practice.run",
    "practice.submit",
    "practice.hint",
    "practice.reset",
    "practice.close",
    "runner.capabilities",
    "runner.run",
    "data.connections",
    "data.connection.save",
    "data.connection.test",
    "data.connection.select",
    "data.connection.delete",
    "data.schema",
    "sql.analyze",
    "sql.execute",
    "sql.result.page",
    "ai.knowledge.ask",
    "ai.sql.preview",
    "ai.sql.generate",
    "teaching.workspace",
    "teaching.exercise.toggle",
    "teaching.exercise.detail",
    "teaching.exercise.save",
    "teaching.exercise.copy",
    "teaching.exercise.import",
    "teaching.exercise.export",
    "teaching.analytics",
    "teaching.interventions",
    "teaching.intervention.update",
    "cloud.workspace",
    "cloud.sync",
    "cloud.class.create",
    "cloud.class.member.add",
    "cloud.assignments",
    "cloud.assignment.create",
    "cloud.assignment.update",
    "cloud.assignment.copy",
    "cloud.assignment.status",
    "cloud.class.analytics",
    "cloud.class.analytics.export",
    "cloud.assignment.analytics",
    "cloud.assignment.analytics.export",
    "cloud.assignment.snapshot",
    "cloud.assignment.submit",
    "cloud.feedback.list",
    "cloud.feedback.save",
    "cloud.feedback.draft",
    "cloud.mastery",
    "cloud.notifications",
    "cloud.notification.read",
    "cloud.courses",
    "cloud.course.create",
    "cloud.course.content",
    "cloud.course.section.create",
    "cloud.course.knowledge.create",
    "cloud.course.exercise.publish",
    "cloud.assignment.create-versioned",
    "cloud.course.export",
    "cloud.course.import",
    "cloud.course.package.preview",
    "cloud.course.package.import",
    "learning.portfolio",
    "learning.portfolio.export",
    "settings.workspace",
    "settings.preferences",
    "settings.environment",
    "settings.storage",
    "settings.update",
    "settings.component.install",
    "settings.component.cancel",
    "settings.backups",
    "settings.backup.create",
    "settings.backup.restore",
    "settings.demo.restore",
    "settings.learning.reset",
    "settings.cache.clear",
    "settings.update.check",
    "settings.notifications.read",
    "settings.help",
    "editor.languages",
    "system.cancel",
    "system.shutdown",
];

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
#[serde(deny_unknown_fields)]
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

    fn request(&self, request: IpcRequest) -> Result<Value, BridgeError> {
        self.validate(&request)?;
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
        if request.request_id.is_empty()
            || request.request_id.len() > 128
            || request.method.is_empty()
            || request.method.len() > 128
        {
            return Err(BridgeError::new(
                "INVALID_REQUEST",
                "Request must match the frozen v1 envelope",
                false,
            ));
        }
        if !request.params.is_object() {
            return Err(BridgeError::new(
                "INVALID_REQUEST",
                "Local application parameters must be an object",
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
                "Bundled Java runtime is missing; run the v3 sidecar build first",
                false,
            ));
        }
        let separator = if cfg!(target_os = "windows") {
            ";"
        } else {
            ":"
        };
        let classpath = format!("app/*{separator}app/lib/*");
        let mut command = Command::new(java);
        command.current_dir(&root);
        #[cfg(feature = "e2e")]
        if let Ok(data_directory) = std::env::var("SQLTEACHER_E2E_DATA_DIR") {
            command.arg(format!("-Dsqlteacher.data.dir={data_directory}"));
        }
        command
            .args([
                "-Dfile.encoding=UTF-8",
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                &classpath,
                "com.sqlteacher.desktop.bridge.LocalAppHost",
            ])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null());
        #[cfg(target_os = "windows")]
        command.creation_flags(CREATE_NO_WINDOW);
        let mut child = command.spawn().map_err(|_| {
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
        if (cfg!(debug_assertions) || cfg!(feature = "e2e")) && development.is_dir() {
            return Ok(development);
        }
        let bundled = self
            .app
            .path()
            .resource_dir()
            .map(|path| path.join("sidecar"))
            .map_err(|_| {
                BridgeError::new(
                    "SIDECAR_RUNTIME_MISSING",
                    "Unable to resolve bundled resources",
                    false,
                )
            })?;
        if bundled.is_dir() {
            return Ok(bundled);
        }
        std::env::current_exe()
            .ok()
            .and_then(|path| path.parent().map(|parent| parent.join("sidecar")))
            .filter(|path| path.is_dir())
            .ok_or_else(|| {
                BridgeError::new(
                    "SIDECAR_RUNTIME_MISSING",
                    "Bundled Java runtime is missing; run the v3 sidecar build first",
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
    let builder = tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(
            |app, _arguments, _working_directory| {
                if let Some(window) = app.get_webview_window("main") {
                    let _ = window.unminimize();
                    let _ = window.show();
                    let _ = window.set_focus();
                }
            },
        ))
        .plugin(tauri_plugin_window_state::Builder::default().build())
        .plugin(tauri_plugin_notification::init());
    #[cfg(feature = "e2e")]
    let builder = builder
        .plugin(tauri_plugin_wdio::init())
        .plugin(tauri_plugin_wdio_webdriver::init());
    builder
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn frozen_contract_includes_role_and_lifecycle_methods() {
        assert_eq!(CONTRACT_VERSION, "3.0-v1");
        assert!(ALLOWED_METHODS.contains(&"session.current"));
        assert!(ALLOWED_METHODS.contains(&"system.cancel"));
        assert!(ALLOWED_METHODS.contains(&"system.shutdown"));
        assert!(ALLOWED_METHODS.contains(&"knowledge.import.preview"));
        assert!(ALLOWED_METHODS.contains(&"runner.run"));
        assert!(ALLOWED_METHODS.contains(&"sql.analyze"));
        assert!(ALLOWED_METHODS.contains(&"ai.knowledge.ask"));
        assert!(ALLOWED_METHODS.contains(&"teaching.workspace"));
        assert!(ALLOWED_METHODS.contains(&"settings.workspace"));
        assert!(ALLOWED_METHODS.contains(&"settings.preferences"));
        assert!(ALLOWED_METHODS.contains(&"settings.environment"));
        assert!(ALLOWED_METHODS.contains(&"settings.storage"));
    }

    #[test]
    fn frozen_request_rejects_unknown_envelope_fields() {
        let json = r#"{"requestId":"r1","method":"system.health","params":{},"contractVersion":"3.0-v1","extra":true}"#;
        assert!(serde_json::from_str::<IpcRequest>(json).is_err());
    }

    #[test]
    fn rust_method_whitelist_matches_machine_readable_manifest() {
        let manifest: Value =
            serde_json::from_str(include_str!("../../../contracts/ipc/v1/manifest.json"))
                .expect("IPC manifest must be valid JSON");
        let expected: HashSet<&str> = manifest["methods"]
            .as_array()
            .expect("methods must be an array")
            .iter()
            .map(|item| item.as_str().expect("method must be text"))
            .collect();
        let actual: HashSet<&str> = ALLOWED_METHODS.iter().copied().collect();
        assert_eq!(expected, actual);
    }

    #[cfg(target_os = "windows")]
    #[test]
    fn java_sidecar_uses_windows_no_console_creation_flag() {
        assert_eq!(CREATE_NO_WINDOW, 0x0800_0000);
    }
}
