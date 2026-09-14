import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { memo, useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { localAppRequest } from "../../shared/ipc";
import { clearDraft, loadDraft, saveDraft } from "../../shared/practiceDraft";
import type {
  AssignmentDelivery,
  AssignmentSnapshot,
  BankPendingNotice,
  ExerciseAttempt,
  ExerciseBankUpdateResult,
  ExerciseBankUpdateStatus,
  ExerciseCatalogPage,
  ExerciseHint,
  ExerciseSession,
  ExerciseView,
  RecommendationView,
  ResultComparison,
  SqlPage,
} from "../../shared/types";
import { difficultyLabel, exerciseTypeTitleLabel } from "../../shared/labels";
import { Button, Dialog, EmptyState, Feedback, Stepper, useToast } from "../../shared/ui";
import { ExerciseCatalogPanel } from "./ExerciseCatalog";
import { CodeEditor } from "./EditorPage";

const defaultSqlAnswer = "SELECT *\nFROM ";

/** 按题型给出起始模板；QUERY 保持原默认，写类题型给出可修改的起点。 */
function answerTemplate(exerciseType: string | undefined): string {
  switch (exerciseType) {
    case "STATE":
      return "UPDATE 表名\nSET 列名 = 新值\nWHERE 条件;";
    case "SCRIPT":
      return "-- 脚本题：多条语句按顺序执行，每条以分号结尾\n-- 需要事务时使用 BEGIN; ... COMMIT;\n";
    case "TRIGGER":
      return "CREATE TRIGGER 触发器名\nAFTER INSERT ON 表名\nFOR EACH ROW\nBEGIN\n  \nEND;";
    default:
      return defaultSqlAnswer;
  }
}

function typeGuidance(exerciseType: string | undefined): string | undefined {
  switch (exerciseType) {
    case "STATE":
      return "本题修改沙盒数据：执行一条写语句即可，随时可重置恢复初始数据；判分以验证查询结果与影响行数为准。";
    case "SCRIPT":
      return "本题按顺序执行多条语句（沙盒数据，可随时重置）；注意题目要求的事务关键字，最终以验证查询结果判分。";
    case "TRIGGER":
      return "本题提交一条 CREATE TRIGGER 定义（沙盒数据，可随时重置）；系统会执行触发场景语句并比对验证查询结果。";
    default:
      return undefined;
  }
}

export function ExerciseFlow() {
  const client = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const classroomId = searchParams.get("classroom") ?? "";
  const assignmentId = searchParams.get("assignment") ?? "";
  const assignmentTitle = searchParams.get("assignmentTitle") ?? "";
  const assignmentContext = Boolean(classroomId && assignmentId);
  const assignmentSnapshot = useQuery({
    queryKey: ["cloud", "assignment-snapshot", classroomId, assignmentId],
    queryFn: () =>
      localAppRequest<AssignmentSnapshot>("cloud.assignment.snapshot", {
        classroomId,
        assignmentId,
      }),
    enabled: assignmentContext,
    retry: false,
  });
  // 目录筛选进 URL：q 为搜索词，difficulty/status 为筛选，刷新与分享不丢状态。
  const catalogFilters = {
    query: searchParams.get("q") ?? "",
    difficulty: searchParams.get("difficulty") ?? "",
    status: searchParams.get("status") ?? "",
  };
  // 目录分页（W4.4）：服务端过滤 + 分页，渲染规模由 pageSize 约束。
  const catalogPageParam = Number(searchParams.get("catPage") ?? "0") || 0;
  const catalogPageSize = 50;
  const setCatalogPage = (page: number) => {
    const params = new URLSearchParams(searchParams);
    if (page > 0) params.set("catPage", String(page));
    else params.delete("catPage");
    setSearchParams(params, { replace: true });
  };
  const catalog = useQuery({
    queryKey: [
      "practice",
      "catalog",
      catalogPageParam,
      catalogFilters.query,
      catalogFilters.difficulty,
      catalogFilters.status,
    ],
    queryFn: () =>
      localAppRequest<ExerciseCatalogPage>("practice.catalog", {
        page: catalogPageParam,
        pageSize: catalogPageSize,
        q: catalogFilters.query,
        difficulty: catalogFilters.difficulty,
        status: catalogFilters.status,
      }),
    staleTime: 30_000,
  });
  // 定时检查产生的待更新通知（W4.3）：仅提示，不打断。
  const bankNotice = useQuery({
    queryKey: ["practice", "bank", "notice"],
    queryFn: () => localAppRequest<{ notice: BankPendingNotice | null }>("practice.bank.notice"),
    staleTime: 60_000,
  });
  // 确定性推荐下一题（本地作答历史重算，无随机、无 AI）。
  const recommendation = useQuery({
    queryKey: ["practice", "recommend"],
    queryFn: () =>
      localAppRequest<{ recommendation: RecommendationView | null }>("practice.recommend"),
    staleTime: 30_000,
  });
  const [selectedId, setSelectedId] = useState<string | undefined>(
    () => searchParams.get("exercise") ?? undefined,
  );
  const [answer, setAnswer] = useState(
    () => (selectedId ? loadDraft(selectedId) : undefined) ?? defaultSqlAnswer,
  );
  const [session, setSession] = useState<ExerciseSession>();
  const [feedback, setFeedback] = useState<ExerciseAttempt>();
  const [hint, setHint] = useState<ExerciseHint>();
  const [delivery, setDelivery] = useState<AssignmentDelivery>();
  const [resetOpen, setResetOpen] = useState(false);
  const toast = useToast();
  const preview = useQuery({
    queryKey: ["practice", "preview", selectedId],
    queryFn: () =>
      localAppRequest<ExerciseView>("practice.preview", {
        exerciseId: selectedId,
      }),
    enabled: Boolean(selectedId),
    staleTime: 30_000,
  });
  const start = useMutation({
    mutationFn: () =>
      localAppRequest<ExerciseSession>("practice.start", {
        exerciseId: selectedId,
      }),
    onSuccess: (value) => {
      setSession(value);
      setFeedback(undefined);
      // 从通用查询模板起步时按题型替换起始模板；已有草稿不动。
      if (answer === defaultSqlAnswer) setAnswer(answerTemplate(value.exercise.exerciseType));
    },
  });
  // 作答写入按题持久化的草稿；300ms 防抖避免每个按键都同步写 localStorage。
  // 定时器捕获选题 id，切题后迟到写入不会污染新题目的草稿。
  const draftTimer = useRef<number | undefined>(undefined);
  const pendingDraft = useRef<{ exerciseId: string; value: string } | undefined>(undefined);
  useEffect(() => {
    const pending = pendingDraft.current;
    return () => {
      if (pending) saveDraft(pending.exerciseId, pending.value);
    };
  }, []);
  const updateAnswer = (value: string) => {
    setAnswer(value);
    const exerciseId = selectedId;
    if (!exerciseId) return;
    pendingDraft.current = { exerciseId, value };
    window.clearTimeout(draftTimer.current);
    draftTimer.current = window.setTimeout(() => {
      saveDraft(exerciseId, value);
      if (pendingDraft.current?.exerciseId === exerciseId) pendingDraft.current = undefined;
    }, 300);
  };
  const deliverAssignment = useMutation({
    mutationFn: (result: ExerciseAttempt) =>
      localAppRequest<AssignmentDelivery>("cloud.assignment.submit", {
        classroomId,
        assignmentId,
        passed: Boolean(result.evaluation?.passed),
        errorCode: result.evaluation?.errorCode ?? "",
        completedAt: result.occurredAt,
      }),
    onSuccess: setDelivery,
  });
  const syncQueued = useMutation({
    mutationFn: () => localAppRequest("cloud.sync"),
    onSuccess: () => toast("success", "待同步记录已同步到云端"),
    onError: (error: Error) => toast("error", `同步失败：${error.message}`),
  });
  const attempt = useMutation({
    mutationFn: async (submit: boolean) => ({
      submit,
      result: await localAppRequest<ExerciseAttempt>(submit ? "practice.submit" : "practice.run", {
        sessionId: session?.id,
        answer,
      }),
    }),
    onSuccess: ({ result, submit }) => {
      setFeedback(result);
      if (submit && selectedId && result.evaluation?.passed) clearDraft(selectedId);
      if (submit && assignmentContext) deliverAssignment.mutate(result);
    },
  });
  // AI 讲解：按需起草展示文本，只读展示，不写任何学习状态。
  const explain = useMutation({
    mutationFn: () =>
      localAppRequest<{ explanation: string; model: string }>("ai.exercise.explain", {
        title: session?.exercise.title ?? "",
        description: session?.exercise.description ?? "",
        knowledgePoint: session?.exercise.knowledgePoint ?? "",
        exerciseType: session?.exercise.exerciseType ?? "QUERY",
        answer,
        feedback: (feedback?.evaluation?.criteria ?? [])
          .filter((item) => !item.passed)
          .map((item) => `${item.criterion}：${item.feedback}`),
      }),
  });
  const requestHint = useMutation({
    mutationFn: () =>
      localAppRequest<ExerciseHint>("practice.hint", {
        sessionId: session?.id,
      }),
    onSuccess: (value) => {
      setHint(value);
      setSession((current) => (current ? { ...current, hintsUsed: value.level } : current));
    },
  });
  const reset = useMutation({
    mutationFn: () =>
      localAppRequest<ExerciseSession>("practice.reset", {
        sessionId: session?.id,
      }),
    onSuccess: (value) => {
      setSession(value);
      setAnswer(answerTemplate(value.exercise.exerciseType));
      if (selectedId) clearDraft(selectedId);
      setFeedback(undefined);
      setHint(undefined);
      setResetOpen(false);
      toast("success", "练习已重置");
    },
  });
  // 选题写回 URL：刷新、复制链接都能回到同一道题。
  useEffect(() => {
    if (!selectedId || searchParams.get("exercise") === selectedId) return;
    const params = new URLSearchParams(searchParams);
    params.set("exercise", selectedId);
    setSearchParams(params, { replace: true });
  }, [selectedId, searchParams, setSearchParams]);
  // 搜索输入与 URL 解耦（issue #25）：按键只更新本地 state，300ms 防抖后才写入
  // URL 并触发目录查询。受控值若逐键经 setSearchParams 回写，会打断中文输入法
  // 合成导致乱码；解耦后 IPC 也从每键一次降为每次停顿一次。
  // appliedQueryRef 记录已写入 URL 的词，用于区分自身防抖写入与外部跳转携带的 q。
  const [queryInput, setQueryInput] = useState(() => searchParams.get("q") ?? "");
  const appliedQueryRef = useRef(searchParams.get("q") ?? "");
  const queryWriteTimer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(queryWriteTimer.current), []);
  const setCatalogFilter = (key: "q" | "difficulty" | "status", value: string) => {
    if (key === "q") {
      setQueryInput(value);
      window.clearTimeout(queryWriteTimer.current);
      queryWriteTimer.current = window.setTimeout(() => {
        appliedQueryRef.current = value;
        setSearchParams(
          (current) => {
            const params = new URLSearchParams(current);
            if (value) params.set("q", value);
            else params.delete("q");
            // 搜索词变化后旧页码可能已越界，回到第一页。
            params.delete("catPage");
            return params;
          },
          { replace: true },
        );
      }, 300);
      return;
    }
    const params = new URLSearchParams(searchParams);
    if (value) params.set(key, value);
    else params.delete(key);
    params.delete("catPage");
    setSearchParams(params, { replace: true });
  };
  // 外部跳转（分享链接、其他页面带 q 进入）携带 q 时回填输入框；自身防抖
  // 写入的 q 与 appliedQueryRef 一致，不触碰输入框，避免打断输入。
  useEffect(() => {
    const fromUrl = searchParams.get("q") ?? "";
    if (fromUrl !== appliedQueryRef.current) {
      appliedQueryRef.current = fromUrl;
      window.clearTimeout(queryWriteTimer.current);
      setQueryInput(fromUrl);
    }
  }, [searchParams]);
  const close = useMutation({
    mutationFn: (sessionId: string) => localAppRequest("practice.close", { sessionId }),
  });
  // 题库更新：先检查差集，再流式应用；任何失败都不影响本地练习。
  const [bankStatus, setBankStatus] = useState<ExerciseBankUpdateStatus>();
  const bankCheck = useMutation({
    mutationFn: () => localAppRequest<ExerciseBankUpdateStatus>("practice.bank.check"),
    onSuccess: (value) => {
      setBankStatus(value);
      if (value.upToDate) toast("success", value.message);
    },
    onError: (error: Error) => toast("error", `检查更新失败：${error.message}`),
  });
  const bankUpdate = useMutation({
    mutationFn: () => localAppRequest<ExerciseBankUpdateResult>("practice.bank.update"),
    onSuccess: (value) => {
      setBankStatus(undefined);
      toast("success", value.message);
      void catalog.refetch();
      void client.invalidateQueries({ queryKey: ["practice", "bank", "notice"] });
    },
    onError: (error: Error) => toast("error", `题库更新失败：${error.message}`),
  });
  const handleCatalogSelect = (exerciseId: string) => {
    if (session) close.mutate(session.id);
    setSelectedId(exerciseId);
    setSession(undefined);
    setFeedback(undefined);
    setHint(undefined);
    setDelivery(undefined);
    // 载入该题自己的草稿；不同题的代码互不覆盖。
    setAnswer(loadDraft(exerciseId) ?? defaultSqlAnswer);
  };
  const step = feedback ? 3 : session ? 2 : preview.data ? 1 : 0;
  const recommendationView = recommendation.data?.recommendation;
  return (
    <div className="flow-layout">
      <ExerciseCatalogPanel
        items={catalog.data?.items ?? []}
        isPending={catalog.isPending}
        selectedId={selectedId}
        filters={catalogFilters}
        queryInput={queryInput}
        onQueryInput={(value) => setCatalogFilter("q", value)}
        onFilterChange={setCatalogFilter}
        onSelect={handleCatalogSelect}
        total={catalog.data?.total}
        page={catalog.data?.page ?? catalogPageParam}
        pageSize={catalogPageSize}
        onPageChange={setCatalogPage}
        headerAction={
          <Button
            variant="secondary"
            disabled={bankCheck.isPending || bankUpdate.isPending}
            busy={bankCheck.isPending}
            onClick={() => bankCheck.mutate()}
          >
            题库更新
          </Button>
        }
      />
      <main className="flow-main">
        {assignmentContext && (
          <Feedback tone="info" title={assignmentTitle || "班级任务"}>
            <p>提交将计入班级任务。</p>
          </Feedback>
        )}
        {recommendationView && !session && (
          <section className="content-card recommend-card">
            <div>
              <p className="eyebrow">推荐下一题</p>
              <strong>{recommendationView.title}</strong>
              <p className="muted">{recommendationView.reason}</p>
            </div>
            <Button
              variant="secondary"
              onClick={() => handleCatalogSelect(recommendationView.exerciseId)}
            >
              去练习
            </Button>
          </section>
        )}
        <Stepper steps={["选题", "预览确认", "作答", "反馈"]} current={step} />
        {bankNotice.data?.notice && (
          <Feedback tone="info" title="题库有可用更新">
            <p>
              频道 {bankNotice.data.notice.channel} 在服务器已更新到第{" "}
              {bankNotice.data.notice.bankVersion} 版（定时检查发现）。
            </p>
            <Button
              disabled={bankUpdate.isPending}
              busy={bankUpdate.isPending}
              onClick={() => bankUpdate.mutate()}
            >
              应用更新
            </Button>
          </Feedback>
        )}
        {bankStatus && !bankStatus.upToDate && (
          <Feedback tone="info" title="题库更新">
            <p>{bankStatus.message}</p>
            <Button
              disabled={bankUpdate.isPending}
              busy={bankUpdate.isPending}
              onClick={() => bankUpdate.mutate()}
            >
              应用更新
            </Button>
          </Feedback>
        )}
        {bankUpdate.isError && (
          <Feedback tone="error" title="题库更新失败">
            本地题库保持不变：{bankUpdate.error.message}
          </Feedback>
        )}
        {!selectedId && <EmptyState title="先选择练习" />}
        {preview.data && !session && (
          <section className="content-card preview-card">
            <p className="eyebrow">
              作答前预览 · {exerciseTypeTitleLabel(preview.data.exerciseType)}
            </p>
            {assignmentContext && assignmentSnapshot.isError && (
              <p className="muted">任务快照不可用，正在显示本地预览，不影响作答与提交。</p>
            )}
            <h2>{assignmentSnapshot.data?.title ?? preview.data.title}</h2>
            <p>{assignmentSnapshot.data?.prompt ?? preview.data.description}</p>
            {preview.data.expectedColumns.length > 0 && (
              <p className="muted">期望列：{preview.data.expectedColumns.join("、")}</p>
            )}
            {assignmentSnapshot.data && (
              <p className="muted">
                快照 {assignmentSnapshot.data.snapshotHash.slice(0, 12)} · 数据集{" "}
                {assignmentSnapshot.data.datasetVersion}
              </p>
            )}
            {typeGuidance(preview.data.exerciseType) && (
              <p className="muted">{typeGuidance(preview.data.exerciseType)}</p>
            )}
            <dl>
              <div>
                <dt>知识点</dt>
                <dd>{preview.data.knowledgePoint}</dd>
              </div>
              <div>
                <dt>难度</dt>
                <dd>{difficultyLabel(preview.data.difficulty)}</dd>
              </div>
            </dl>
            <pre>{preview.data.schemaSummary.replace(/；/g, "；\n")}</pre>
            <Button disabled={start.isPending} onClick={() => start.mutate()}>
              确认并开始作答
            </Button>
          </section>
        )}
        {session && (
          <section className="content-card coding-card">
            <header className="editor-toolbar">
              <div>
                <p className="eyebrow">
                  SQL 练习 · {exerciseTypeTitleLabel(session.exercise.exerciseType)}
                </p>
                <h2>{session.exercise.title}</h2>
              </div>
              <span className="policy-chip">Java 评价 · 提示 {session.hintsUsed}/3</span>
            </header>
            {typeGuidance(session.exercise.exerciseType) && (
              <p className="muted">{typeGuidance(session.exercise.exerciseType)}</p>
            )}
            <p className="exercise-prompt">{session.exercise.description}</p>
            {session.exercise.expectedColumns.length > 0 && (
              <p className="muted">期望列：{session.exercise.expectedColumns.join("、")}</p>
            )}
            <details className="schema-brief">
              <summary>数据表结构</summary>
              <pre>{session.exercise.schemaSummary.replace(/；/g, "；\n")}</pre>
            </details>
            <CodeEditor
              language="SQL"
              value={answer}
              onChange={updateAnswer}
              schema={session.exercise.schemaSummary}
              onRun={() => attempt.mutate(false)}
              onSubmit={() => attempt.mutate(true)}
              onHint={() => {
                if (!requestHint.isPending && session.hintsUsed < 3) requestHint.mutate();
              }}
            />
            {hint && (
              <Feedback tone="info" title={`第 ${hint.level} 级提示`}>
                {hint.text}
              </Feedback>
            )}
            <footer className="editor-actions">
              <span>
                {answer.length.toLocaleString()} 字符 · Ctrl+Enter 运行 · Ctrl+Shift+Enter 提交 · F1
                提示
              </span>
              <Button
                variant="secondary"
                disabled={requestHint.isPending || session.hintsUsed >= 3}
                onClick={() => requestHint.mutate()}
              >
                获取提示
              </Button>
              <Button
                variant="secondary"
                disabled={reset.isPending}
                onClick={() => setResetOpen(true)}
              >
                重置练习
              </Button>
              <Button
                variant="secondary"
                busy={attempt.isPending}
                disabled={attempt.isPending}
                onClick={() => attempt.mutate(false)}
              >
                运行
              </Button>
              <Button
                busy={attempt.isPending}
                disabled={attempt.isPending}
                onClick={() => attempt.mutate(true)}
              >
                提交评价
              </Button>
            </footer>
          </section>
        )}
        {feedback && (
          <Feedback
            tone={feedback.evaluation?.passed ? "success" : "warning"}
            title={feedback.evaluation?.passed ? "练习通过" : `状态：${feedback.status}`}
          >
            <p>{feedback.evaluation?.feedback ?? feedback.execution?.message}</p>
            {feedback.evaluation?.score != null && (
              <p className="score-line">
                得分：{feedback.evaluation.score} / 100 （仅用于反馈，通过仍需全部分项达标）
              </p>
            )}
            {feedback.evaluation?.criteria.map((item) => (
              <p key={item.criterion}>
                {item.passed ? "✓" : "×"} {item.criterion}：{item.feedback}
              </p>
            ))}
            {feedback.evaluation &&
              !feedback.evaluation.passed &&
              feedback.evaluation.comparison && (
                <ComparisonView comparison={feedback.evaluation.comparison} />
              )}
            {feedback.evaluation && !feedback.evaluation.passed && (
              <div className="button-row">
                <Button
                  variant="secondary"
                  disabled={explain.isPending}
                  busy={explain.isPending}
                  onClick={() => explain.mutate()}
                >
                  AI 讲解（草稿）
                </Button>
              </div>
            )}
            {explain.data && (
              <Feedback
                tone="info"
                title={`AI 讲解草稿 · ${explain.data.model}（仅供参考，不影响判分）`}
              >
                {explain.data.explanation}
              </Feedback>
            )}
          </Feedback>
        )}
        {feedback?.execution && feedback.execution.columns.length > 0 && (
          <PracticeResultTable execution={feedback.execution} />
        )}
        {delivery && (
          <Feedback
            tone={
              delivery.status === "REJECTED"
                ? "error"
                : delivery.status === "QUEUED"
                  ? "warning"
                  : "success"
            }
            title={
              delivery.status === "QUEUED"
                ? "提交已保存，等待同步"
                : delivery.status === "REJECTED"
                  ? "班级任务拒绝了本次提交"
                  : "班级任务已提交"
            }
          >
            <p>
              {delivery.status === "QUEUED"
                ? `当前还有 ${delivery.pending} 条记录待同步。`
                : `云端尝试次数：${delivery.attemptNumber}`}
            </p>
            {delivery.status === "QUEUED" && (
              <div className="button-row">
                <Button
                  variant="secondary"
                  busy={syncQueued.isPending}
                  disabled={syncQueued.isPending}
                  onClick={() => syncQueued.mutate()}
                >
                  立即同步
                </Button>
              </div>
            )}
          </Feedback>
        )}
        {(catalog.isError ||
          preview.isError ||
          start.isError ||
          attempt.isError ||
          requestHint.isError ||
          reset.isError ||
          close.isError ||
          deliverAssignment.isError) && (
          <Feedback tone="error" title="练习流程失败">
            {
              (
                catalog.error ??
                preview.error ??
                start.error ??
                attempt.error ??
                requestHint.error ??
                reset.error ??
                close.error ??
                deliverAssignment.error
              )?.message
            }
          </Feedback>
        )}
        <Dialog open={resetOpen} title="重置练习" onClose={() => setResetOpen(false)}>
          <p>将恢复初始代码并清除本题草稿，无法撤销。</p>
          <div className="button-row">
            <Button variant="secondary" onClick={() => setResetOpen(false)}>
              取消
            </Button>
            <Button variant="danger" busy={reset.isPending} onClick={() => reset.mutate()}>
              确认重置
            </Button>
          </div>
        </Dialog>
      </main>
    </div>
  );
}

/** 期望/实际并排对比视图（W2.4）：期望行受教师 REVEAL 控制，diff 由 Java 计算。 */
const ComparisonView = memo(function ComparisonView({
  comparison,
}: {
  comparison: ResultComparison;
}) {
  return (
    <section className="comparison-view">
      <p className="eyebrow">期望 / 实际对比（差异单元格已标红）</p>
      <div className="comparison-tables">
        <div className="virtual-table" role="region" aria-label="期望结果" tabIndex={0}>
          <p className="muted">期望结果</p>
          <table>
            <thead>
              <tr>
                {comparison.columns.map((column) => (
                  <th key={column}>{column}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {comparison.expectedRows.map((row, rowIndex) => (
                <tr key={`expected-${rowIndex}`}>
                  {row.cells.map((cell, cellIndex) => (
                    <td key={cellIndex} className={row.cellDiff[cellIndex] ? "diff-cell" : ""}>
                      {String(cell ?? "NULL")}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="virtual-table" role="region" aria-label="实际结果" tabIndex={0}>
          <p className="muted">你的结果</p>
          <table>
            <thead>
              <tr>
                {comparison.columns.map((column) => (
                  <th key={column}>{column}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {comparison.actualRows.map((row, rowIndex) => (
                <tr key={`actual-${rowIndex}`}>
                  {row.cells.map((cell, cellIndex) => (
                    <td key={cellIndex} className={row.cellDiff[cellIndex] ? "diff-cell" : ""}>
                      {String(cell ?? "NULL")}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </section>
  );
});

/** 独立 memo 化：作答每键重渲染时，几百行的结果表不随之重排。 */
const PracticeResultTable = memo(function PracticeResultTable({
  execution,
}: {
  execution: SqlPage;
}) {
  return (
    <section className="result-panel">
      <div className="section-heading">
        <h3>
          查询结果 · {execution.totalRows} 行{execution.truncated ? "（已截断）" : ""}
        </h3>
        <span className="safe-chip">{execution.durationMillis} ms</span>
      </div>
      <div className="virtual-table" role="region" aria-label="练习运行结果" tabIndex={0}>
        <table>
          <thead>
            <tr>
              {execution.columns.map((column) => (
                <th key={column}>{column}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {execution.rows.map((row, index) => (
              <tr key={index}>
                {execution.columns.map((column) => (
                  <td key={column}>{String(row[column] ?? "NULL")}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
});
