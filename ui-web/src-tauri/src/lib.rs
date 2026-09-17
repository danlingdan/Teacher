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

/// Per-method timeout budgets (v3.4.0 BUG-5): one uniform 30s deadline misjudged
/// long-running operations (backups, imports, index rebuilds, downloads, sandbox
/// runs) as failures while the Java side kept working, and the late response was
/// dropped on the floor.
fn request_timeout(method: &str) -> Duration {
    const MINUTE: u64 = 60;
    match method {
        // Downloads and component installs (bundled JDK) run far beyond any RPC budget.
        "settings.update.download" | "settings.update.forceDownload" | "settings.component.install" => {
            Duration::from_secs(30 * MINUTE)
        }
        // Backup/restore, imports, index rebuilds, data resets and sandbox/bank work
        // do real file and database work bounded only by their own internal limits.
        "settings.backup.restore"
        | "settings.backup.create"
        | "knowledge.index.rebuild"
        | "knowledge.import.execute"
        | "knowledge.bundle.import"
        | "knowledge.bundle.download"
        | "cloud.course.package.import"
        | "settings.learning.reset"
        | "settings.demo.restore"
        | "settings.cache.clear"
        | "runner.run"
        | "practice.run"
        | "practice.submit"
        | "teaching.exercise.health"
        | "practice.bank.check"
        | "practice.bank.update"
        | "settings.bank.update"
        | "account.export.request" => Duration::from_secs(10 * MINUTE),
        // Local AI drafts can take minutes on slow models.
        "ai.knowledge.ask" | "ai.sql.preview" | "ai.sql.generate" | "ai.exercise.explain" => {
            Duration::from_secs(3 * MINUTE)
        }
        "cloud.sync" => Duration::from_secs(5 * MINUTE),
        "runner.capabilities" | "settings.update.check" => Duration::from_secs(2 * MINUTE),
        // Problem reports (v3.4.2 LEG-10): submit travels to the cloud endpoint and can retry on
        // slow links; preview stays local and the rest are small request/response exchanges.
        "support.report.submit" => Duration::from_secs(2 * MINUTE),
        "support.report.preview" | "support.report.status" | "support.report.withdraw"
        | "support.report.export" => Duration::from_secs(MINUTE),
        _ => REQUEST_TIMEOUT,
    }
}

#[cfg(target_os = "windows")]
mod sidecar_job {
    //! Kill-on-close job ownership for the sidecar process tree (v3.4.0 BUG-4).
    use std::os::windows::io::AsRawHandle;
    use std::process::Child;
    use windows_sys::Win32::Foundation::{CloseHandle, HANDLE};
    use windows_sys::Win32::System::JobObjects::{
        AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
        SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
        JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
    };

    /// Owns a Windows job object with kill-on-close: when the desktop process exits —
    /// cleanly or not — the OS closes the handle and terminates the whole sidecar
    /// tree, so java.exe can no longer outlive a crash holding SQLite handles.
    pub(crate) struct SidecarJob(HANDLE);

    impl SidecarJob {
        pub(crate) fn create() -> Option<Self> {
            unsafe {
                let job = CreateJobObjectW(std::ptr::null(), std::ptr::null());
                if job.is_null() {
                    return None;
                }
                let mut limits: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
                limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
                if SetInformationJobObject(
                    job,
                    JobObjectExtendedLimitInformation,
                    &limits as *const _ as *const core::ffi::c_void,
                    std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
                ) == 0
                {
                    CloseHandle(job);
                    return None;
                }
                Some(Self(job))
            }
        }

        pub(crate) fn assign(&self, child: &Child) -> bool {
            unsafe { AssignProcessToJobObject(self.0, child.as_raw_handle() as HANDLE) != 0 }
        }
    }

    impl Drop for SidecarJob {
        fn drop(&mut self) {
            unsafe { CloseHandle(self.0) };
        }
    }

    // 内核对象句柄不绑定线程；CloseHandle/AssignProcessToJobObject 可从任意线程调用。
    unsafe impl Send for SidecarJob {}
}

#[cfg(target_os = "windows")]
use sidecar_job::SidecarJob;

/// 与 Java 侧日志同目录（`%LOCALAPPDATA%\SQLTeacher\logs`），便于一并收集诊断。
fn sidecar_log_path() -> std::io::Result<PathBuf> {
    let base = std::env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .unwrap_or_else(std::env::temp_dir);
    Ok(base.join("SQLTeacher").join("logs").join("sidecar.log"))
}

/// 滚动落盘 sidecar stderr：超过 1MB 时整体轮换为 `sidecar.old.log`（尽力而为）。
fn log_sidecar_stderr(stderr: impl std::io::Read, path: PathBuf) {
    let rotate = || -> std::io::Result<std::fs::File> {
        if let Ok(metadata) = std::fs::metadata(&path) {
            if metadata.len() > 1_000_000 {
                let _ = std::fs::rename(&path, path.with_extension("old.log"));
            }
        }
        std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&path)
    };
    let Ok(mut file) = rotate() else { return };
    for line in BufReader::new(stderr).lines().map_while(Result::ok) {
        let stamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or_default();
        let _ = writeln!(file, "[{stamp}] {line}");
    }
}

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
    "knowledge.article.asset",
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
    "knowledge.bundle.import",
    "knowledge.bundle.check",
    "knowledge.bundle.download",
    "knowledge.bundle.remove",
    "knowledge.overview",
    "practice.catalog",
    "practice.preview",
    "practice.start",
    "practice.run",
    "practice.submit",
    "practice.hint",
    "practice.reset",
    "practice.close",
    "practice.bank.check",
    "practice.bank.update",
    "practice.bank.channels",
    "practice.bank.notice",
    "practice.wrongbook",
    "practice.paths",
    "settings.bank.update",
    "runner.capabilities",
    "runner.run",
    "data.connections",
    "data.connection.save",
    "data.connection.dialects",
    "data.connection.test",
    "data.connection.select",
    "data.connection.delete",
    "data.connection.databases",
    "data.schema",
    "data.table.sample",
    "sql.analyze",
    "sql.history",
    "sql.history.clear",
    "sql.execute",
    "sql.result.page",
    "sql.result.export",
    "ai.knowledge.ask",
    "ai.sql.preview",
    "ai.sql.generate",
    "ai.exercise.explain",
    "ai.provider.list",
    "ai.provider.save",
    "ai.provider.activate",
    "ai.provider.deactivate",
    "ai.provider.remove",
    "ai.provider.test",
    "teaching.workspace",
    "teaching.exercise.toggle",
    "teaching.exercise.detail",
    "teaching.exercise.save",
    "teaching.exercise.copy",
    "teaching.exercise.import",
    "teaching.exercise.health",
    "teaching.bank.rollback",
    "teaching.exercise.parse",
    "teaching.exercise.draft",
    "teaching.exercise.export",
    "teaching.exercise.publish",
    "teaching.analytics",
    "teaching.interventions",
    "teaching.intervention.update",
    "cloud.workspace",
    "cloud.sync",
    "cloud.class.create",
    "cloud.class.member.add",
    "cloud.class.roster",
    "cloud.class.join",
    "cloud.class.join-code",
    "cloud.class.join-code.rotate",
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
    "settings.update.download",
    "settings.update.forceDownload",
    "settings.update.install",
    "settings.update.skip",
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
    "support.report.preview",
    "support.report.submit",
    "support.report.status",
    "support.report.withdraw",
    "support.report.export",
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
    /// Keeps the kill-on-close job alive as long as the sidecar runs (BUG-4).
    #[cfg(target_os = "windows")]
    _job: Option<SidecarJob>,
}

struct SidecarManager {
    app: AppHandle,
    process: Arc<Mutex<Option<SidecarProcess>>>,
    pending: Arc<Mutex<HashMap<String, Sender<Value>>>>,
    sequence: AtomicU64,
}

impl SidecarManager {
    fn new(app: AppHandle) -> Self {
        Self {
            app,
            process: Arc::new(Mutex::new(None)),
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
            let process = process_guard.as_mut().ok_or_else(|| {
                BridgeError::new(
                    "BRIDGE_UNAVAILABLE",
                    "Local sidecar state is unavailable",
                    true,
                )
            })?;
            if writeln!(process.stdin, "{payload}").and_then(|_| process.stdin.flush()).is_ok() {
                return Ok(());
            }
            // 写失败通常意味着 sidecar 已退出：清空槽位让下一次请求惰性重启，
            // 而不是在断开的管道上持续失败直到应用重启。
            if let Some(mut dead) = process_guard.take() {
                let _ = dead.child.wait();
            }
            Err(BridgeError::new(
                "SIDECAR_WRITE_FAILED",
                "Unable to send request to Java core",
                true,
            ))
        })();
        if let Err(error) = write_result {
            self.remove_pending(&request.request_id);
            return Err(error);
        }

        let response = receiver
            .recv_timeout(request_timeout(&request.method))
            .map_err(|_| {
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
                // IPv6 路由不完整的网络环境下，Java 默认优先 IPv6 会让云端请求
                // 全部超时（CLOUD_UNAVAILABLE）；强制 IPv4 栈避免卡死登录与同步。
                "-Djava.net.preferIPv4Stack=true",
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                &classpath,
                "com.sqlteacher.desktop.bridge.LocalAppHost",
            ])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        #[cfg(target_os = "windows")]
        command.creation_flags(CREATE_NO_WINDOW);
        let mut child = command.spawn().map_err(|_| {
            BridgeError::new(
                "SIDECAR_START_FAILED",
                "Unable to start the Java core",
                true,
            )
        })?;
        // Kill-on-close job：桌面进程无论正常退出还是崩溃/被强杀，操作系统都会
        // 关闭作业句柄并收割整个 sidecar 进程树（v3.4.0 BUG-4）。
        #[cfg(target_os = "windows")]
        let job = SidecarJob::create();
        #[cfg(target_os = "windows")]
        if let Some(job) = &job {
            job.assign(&child);
        }
        // sidecar stderr 曾被直接丢弃，崩溃堆栈无从诊断；现在滚动落盘（BUG-4）。
        if let Some(stderr) = child.stderr.take() {
            if let Ok(path) = sidecar_log_path() {
                if let Some(directory) = path.parent() {
                    let _ = std::fs::create_dir_all(directory);
                }
                let _ = std::thread::Builder::new()
                    .name("sqlteacher-sidecar-stderr".to_owned())
                    .spawn(move || log_sidecar_stderr(stderr, path));
            }
        }
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
        let process_slot = Arc::clone(&self.process);
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
                // sidecar 已退出：清空槽位（回收子进程），下一次请求会惰性重启它。
                if let Ok(mut process_guard) = process_slot.lock() {
                    if let Some(mut dead) = process_guard.take() {
                        let _ = dead.child.wait();
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
        Ok(SidecarProcess {
            child,
            stdin,
            #[cfg(target_os = "windows")]
            _job: job,
        })
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
                // W6.5: give the Java sidecar up to 5s to drain and flush SQLite before
                // killing it, matching the Java shutdown drain budget.
                for _ in 0..200 {
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
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_dialog::init());
    #[cfg(feature = "e2e")]
    let builder = builder
        .plugin(tauri_plugin_wdio::init())
        .plugin(tauri_plugin_wdio_webdriver::init());
    builder
        .setup(|app| {
            app.manage(AppState {
                sidecar: Arc::new(SidecarManager::new(app.handle().clone())),
            });
            // 窗口标题跟随包版本（tauri.conf.json 不支持 {{version}} 插值）。
            if let Some(window) = app.get_webview_window("main") {
                let version = app.package_info().version.clone();
                let _ = window.set_title(&format!("SQLTeacher {version}"));
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![local_app_request])
        .build(tauri::generate_context!())
        .expect("error while building SQLTeacher")
        .run(|app, event| {
            // BUG-4：Drop 只在正常析构路径可靠；应用退出事件里显式关停 sidecar，
            // 覆盖 Tauri 的其余退出路径，避免 java.exe 持有 SQLite 句柄残留。
            if matches!(event, tauri::RunEvent::Exit) {
                app.state::<AppState>().sidecar.shutdown();
            }
        });
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

    #[test]
    fn long_running_methods_get_extended_timeout_budgets() {
        assert_eq!(request_timeout("system.health"), REQUEST_TIMEOUT);
        assert_eq!(request_timeout("sql.execute"), REQUEST_TIMEOUT);
        assert!(request_timeout("settings.backup.restore") > REQUEST_TIMEOUT);
        assert!(request_timeout("knowledge.import.execute") > REQUEST_TIMEOUT);
        assert!(request_timeout("ai.knowledge.ask") > REQUEST_TIMEOUT);
        assert!(request_timeout("settings.update.download") > request_timeout("cloud.sync"));
        assert!(request_timeout("settings.update.forceDownload") > request_timeout("cloud.sync"));
    }
}
