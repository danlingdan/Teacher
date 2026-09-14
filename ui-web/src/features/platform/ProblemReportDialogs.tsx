// 问题反馈（v3.4.2 LEG-10）：桌面端唯一的反馈入口。表单 → 预览诊断字段 → 提交三步，
// 诊断项默认全关；预览内容就是提交时发送的诊断内容（Java 侧共用同一构造路径）。
// 提交成功返回的 queryToken 是查询进度/撤回的唯一凭据，仅显示一次。
import { useEffect, useState } from "react";
import { localAppRequest } from "../../shared/ipc";
import { formatInstant } from "../../shared/instant";
import { Button, Dialog, Feedback, FormField, useToast } from "../../shared/ui";

type ProblemReportReceipt = {
  reportId: string;
  queryToken: string;
  status: string;
  submittedAt: string;
};

type DiagnosticFlags = {
  environment: boolean;
  recentErrors: boolean;
  networkSummary: boolean;
  updateState: boolean;
};

const NO_DIAGNOSTICS: DiagnosticFlags = {
  environment: false,
  recentErrors: false,
  networkSummary: false,
  updateState: false,
};

const REPORT_TYPES: Array<{ value: string; label: string }> = [
  { value: "BUG", label: "功能缺陷" },
  { value: "UPDATE_PROBLEM", label: "更新问题" },
  { value: "USABILITY", label: "易用性问题" },
  { value: "SUGGESTION", label: "功能建议" },
  { value: "OTHER", label: "其他" },
];

const REPORT_SEVERITIES: Array<{ value: string; label: string }> = [
  { value: "MINOR", label: "轻微：不影响正常使用" },
  { value: "PARTIAL_FAILURE", label: "部分功能受损" },
  { value: "MAIN_FLOW_BLOCKED", label: "主要流程被阻塞" },
  { value: "DATA_OR_STARTUP_RISK", label: "数据丢失或无法启动" },
];

const DIAGNOSTIC_OPTIONS: Array<{ key: keyof DiagnosticFlags; label: string; hint: string }> = [
  { key: "environment", label: "本机环境", hint: "Java 与操作系统版本" },
  { key: "recentErrors", label: "最近失败的任务", hint: "失败任务的类型与标题，经脱敏处理" },
  { key: "networkSummary", label: "网络连通性摘要", hint: "各服务连通状态，不含地址与凭据" },
  { key: "updateState", label: "更新设置状态", hint: "自动检查开关、跳过的版本号" },
];

function emptyForm() {
  return {
    type: "BUG",
    severity: "MINOR",
    summary: "",
    description: "",
    reproductionSteps: "",
    expectedResult: "",
    actualResult: "",
    contact: "",
  };
}

/** 新建问题反馈：预览诊断字段后才允许提交；成功后明示仅显示一次的查询凭据。 */
export function ProblemReportDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const toast = useToast();
  const [form, setForm] = useState(emptyForm);
  const [diagnostics, setDiagnostics] = useState<DiagnosticFlags>(NO_DIAGNOSTICS);
  const [preview, setPreview] = useState("");
  const [receipt, setReceipt] = useState<ProblemReportReceipt | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  // 每次重新打开都是一份新表单和新的幂等键，避免把上一次的草稿误发出去。
  useEffect(() => {
    if (open) {
      setForm(emptyForm());
      setDiagnostics(NO_DIAGNOSTICS);
      setPreview("");
      setReceipt(null);
      setError("");
    }
  }, [open]);

  const canSubmit = form.summary.trim().length > 0 && form.description.trim().length > 0 && !submitting;
  const update = (key: keyof ReturnType<typeof emptyForm>, value: string) =>
    setForm((current) => ({ ...current, [key]: value }));

  const loadPreview = () => {
    setError("");
    localAppRequest<Record<string, unknown>>("support.report.preview", { diagnostics })
      .then((value) => setPreview(JSON.stringify(value, null, 2) || "{}"))
      .catch((submitError: Error) => setError(`生成预览失败：${submitError.message}`));
  };

  const submit = () => {
    setSubmitting(true);
    setError("");
    localAppRequest<ProblemReportReceipt>("support.report.submit", { ...form, diagnostics })
      .then(setReceipt)
      .catch((submitError: Error) => {
        setError(`提交失败，内容未被发送：${submitError.message}`);
        toast("error", "问题反馈提交失败");
      })
      .finally(() => setSubmitting(false));
  };

  const copyQueryToken = () => {
    if (!receipt) return;
    void navigator.clipboard
      ?.writeText(receipt.queryToken)
      .then(() => toast("success", "查询凭据已复制到剪贴板"))
      .catch(() => toast("error", "剪贴板不可用，请手动抄录"));
  };

  return (
    <Dialog open={open} title="问题反馈" onClose={onClose}>
      {receipt ? (
        <>
          <Feedback tone="success" title="反馈已提交">
            <p>反馈编号：{receipt.reportId}</p>
            <p>当前状态：{receipt.status}（{formatInstant(receipt.submittedAt)}）</p>
          </Feedback>
          <Feedback tone="warning" title="请立即保存查询凭据">
            <p>
              查询凭据（queryToken）<strong>仅显示这一次</strong>，之后无法找回。
              凭 {receipt.reportId} 与该凭据可查询进度、撤回或导出这份反馈。
            </p>
            <pre className="help-content">{receipt.queryToken}</pre>
            <div className="button-row">
              <Button variant="secondary" onClick={copyQueryToken}>
                复制查询凭据
              </Button>
              <Button variant="secondary" onClick={onClose}>
                我已保存，关闭
              </Button>
            </div>
          </Feedback>
        </>
      ) : (
        <>
          <p className="muted">
            反馈内容经云端问题反馈通道提交；诊断字段默认全部关闭，发送前可逐项勾选并预览，预览内容与实际发送内容完全一致。
          </p>
          <div className="settings-grid">
            <FormField label="反馈类型">
              {(ids) => (
                <select {...ids} value={form.type} onChange={(event) => update("type", event.target.value)}>
                  {REPORT_TYPES.map((item) => (
                    <option key={item.value} value={item.value}>
                      {item.label}
                    </option>
                  ))}
                </select>
              )}
            </FormField>
            <FormField label="严重程度">
              {(ids) => (
                <select
                  {...ids}
                  value={form.severity}
                  onChange={(event) => update("severity", event.target.value)}
                >
                  {REPORT_SEVERITIES.map((item) => (
                    <option key={item.value} value={item.value}>
                      {item.label}
                    </option>
                  ))}
                </select>
              )}
            </FormField>
          </div>
          <FormField label="摘要" hint="一句话说明问题（必填，160 字以内）">
            {(ids) => (
              <input
                {...ids}
                value={form.summary}
                maxLength={160}
                onChange={(event) => update("summary", event.target.value)}
              />
            )}
          </FormField>
          <FormField label="详细描述" hint="发生了什么、在哪个页面（必填）">
            {(ids) => (
              <textarea
                {...ids}
                rows={4}
                value={form.description}
                maxLength={4000}
                onChange={(event) => update("description", event.target.value)}
              />
            )}
          </FormField>
          <FormField label="复现步骤" hint="可选：我们按这些步骤就能看到同样的问题">
            {(ids) => (
              <textarea
                {...ids}
                rows={3}
                value={form.reproductionSteps}
                maxLength={4000}
                onChange={(event) => update("reproductionSteps", event.target.value)}
              />
            )}
          </FormField>
          <div className="settings-grid">
            <FormField label="期望结果" hint="可选">
              {(ids) => (
                <textarea
                  {...ids}
                  rows={2}
                  value={form.expectedResult}
                  maxLength={2000}
                  onChange={(event) => update("expectedResult", event.target.value)}
                />
              )}
            </FormField>
            <FormField label="实际结果" hint="可选">
              {(ids) => (
                <textarea
                  {...ids}
                  rows={2}
                  value={form.actualResult}
                  maxLength={2000}
                  onChange={(event) => update("actualResult", event.target.value)}
                />
              )}
            </FormField>
          </div>
          <FormField label="联系方式" hint="可选：邮箱或其他联系方式，便于回访">
            {(ids) => (
              <input
                {...ids}
                value={form.contact}
                maxLength={254}
                onChange={(event) => update("contact", event.target.value)}
              />
            )}
          </FormField>
          <fieldset className="report-diagnostics">
            <legend>随反馈发送的诊断字段（默认全关）</legend>
            {DIAGNOSTIC_OPTIONS.map((item) => (
              <label className="setting-toggle" key={item.key}>
                <input
                  type="checkbox"
                  checked={diagnostics[item.key]}
                  onChange={(event) =>
                    setDiagnostics((current) => ({ ...current, [item.key]: event.target.checked }))
                  }
                />
                <span>
                  <strong>{item.label}</strong>
                  <small>{item.hint}</small>
                </span>
              </label>
            ))}
          </fieldset>
          {preview !== "" && (
            <div>
              <p>
                <strong>将随反馈发送的诊断内容</strong>（与实际发送完全一致）：
              </p>
              <pre className="help-content">{preview}</pre>
            </div>
          )}
          {error && <Feedback tone="error" title="操作未完成">{error}</Feedback>}
          <div className="button-row">
            <Button variant="secondary" onClick={loadPreview}>
              预览诊断字段
            </Button>
            <Button disabled={!canSubmit || preview === ""} busy={submitting} onClick={submit}>
              提交反馈
            </Button>
            <Button variant="secondary" onClick={onClose}>
              取消
            </Button>
          </div>
        </>
      )}
    </Dialog>
  );
}

type ReportProgress = {
  reportId: string;
  status: string;
  submittedAt: string;
};

/** 查询已提交反馈的进度或撤回：凭反馈编号 + 查询凭据。 */
export function ReportStatusDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const toast = useToast();
  const [reportId, setReportId] = useState("");
  const [queryToken, setQueryToken] = useState("");
  const [progress, setProgress] = useState<ReportProgress | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (open) {
      setReportId("");
      setQueryToken("");
      setProgress(null);
      setError("");
    }
  }, [open]);

  const ready = reportId.trim().length > 0 && queryToken.trim().length > 0 && !busy;
  const params = () => ({ reportId: reportId.trim(), queryToken: queryToken.trim() });

  const queryStatus = () => {
    setBusy(true);
    setError("");
    localAppRequest<ReportProgress>("support.report.status", params())
      .then(setProgress)
      .catch((statusError: Error) => {
        setProgress(null);
        setError(`查询失败：${statusError.message}`);
      })
      .finally(() => setBusy(false));
  };

  const withdraw = () => {
    setBusy(true);
    setError("");
    localAppRequest("support.report.withdraw", params())
      .then(() => localAppRequest<ReportProgress>("support.report.status", params()))
      .then(setProgress)
      .then(() => toast("success", "反馈已撤回"))
      .catch((withdrawError: Error) => setError(`撤回失败：${withdrawError.message}`))
      .finally(() => setBusy(false));
  };

  return (
    <Dialog open={open} title="查询反馈进度 / 撤回" onClose={onClose}>
      <p className="muted">
        提交反馈时会返回反馈编号与仅显示一次的查询凭据；两者都需要才能查询或撤回。凭据丢失后无法找回。
      </p>
      <FormField label="反馈编号">
        {(ids) => (
          <input {...ids} value={reportId} onChange={(event) => setReportId(event.target.value)} />
        )}
      </FormField>
      <FormField label="查询凭据（queryToken）">
        {(ids) => (
          <input
            {...ids}
            value={queryToken}
            onChange={(event) => setQueryToken(event.target.value)}
          />
        )}
      </FormField>
      {progress && (
        <Feedback tone="info" title={`当前状态：${progress.status}`}>
          <p>提交时间：{formatInstant(progress.submittedAt)}</p>
          {progress.status === "WITHDRAWN" && <p>这份反馈已撤回，服务端不再保留待处理内容。</p>}
        </Feedback>
      )}
      {error && <Feedback tone="error" title="操作未完成">{error}</Feedback>}
      <div className="button-row">
        <Button disabled={!ready} busy={busy} onClick={queryStatus}>
          查询进度
        </Button>
        <Button
          variant="danger"
          disabled={!ready || progress?.status === "WITHDRAWN"}
          busy={busy}
          onClick={withdraw}
        >
          撤回反馈
        </Button>
        <Button variant="secondary" onClick={onClose}>
          关闭
        </Button>
      </div>
    </Dialog>
  );
}
