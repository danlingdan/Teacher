import { useMutation, useQuery } from "@tanstack/react-query";
import Editor, {
  loader,
  type BeforeMount,
  type OnMount,
} from "@monaco-editor/react";
import * as monaco from "monaco-editor/editor/editor.api";
import EditorWorker from "monaco-editor/editor/editor.worker?worker";
import "monaco-editor/languages/definitions/sql/register";
import "monaco-editor/languages/definitions/java/register";
import "monaco-editor/languages/definitions/python/register";
import "monaco-editor/languages/definitions/cpp/register";
import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import {
  cancelLocalAppRequest,
  localAppRequest,
  localAppRequestWithId,
  subscribeLocalAppEvents,
} from "../../shared/ipc";
import type {
  ActivityDefinition,
  ActivitySubmission,
  AssignmentDelivery,
  AssignmentSnapshot,
  CourseWorkspace,
  ExerciseAttempt,
  ExerciseHint,
  ExerciseSession,
  ExerciseSummary,
  ExerciseView,
  RunnerCapability,
  RunnerResult,
} from "../../shared/types";
import { Button, EmptyState, Feedback, Stepper } from "../../shared/ui";

self.MonacoEnvironment = { getWorker: () => new EditorWorker() };
loader.config({ monaco });

const templates: Record<string, string> = {
  JAVA: 'public class Main {\n    public static void main(String[] args) {\n        System.out.println("Hello, SQLTeacher");\n    }\n}\n',
  PYTHON: 'print("Hello, SQLTeacher")\n',
  C: '#include <stdio.h>\nint main(void) { puts("Hello, SQLTeacher"); return 0; }\n',
  CPP: '#include <iostream>\nint main() { std::cout << "Hello, SQLTeacher\\n"; }\n',
};
const monacoLanguage: Record<string, string> = {
  JAVA: "java",
  PYTHON: "python",
  C: "cpp",
  CPP: "cpp",
  SQL: "sql",
};

export default function EditorPage() {
  const [searchParams] = useSearchParams();
  const [mode, setMode] = useState<"exercise" | "activity" | "runner">(() =>
    searchParams.has("activity") ? "activity" : "exercise",
  );
  return (
    <section className="practice-workspace">
      <div className="segmented-control workspace-tabs" aria-label="练习类型">
        <button
          type="button"
          className={mode === "exercise" ? "selected" : ""}
          onClick={() => setMode("exercise")}
        >
          SQL 练习
        </button>
        <button
          type="button"
          className={mode === "activity" ? "selected" : ""}
          onClick={() => setMode("activity")}
        >
          课程活动
        </button>
        <button
          type="button"
          className={mode === "runner" ? "selected" : ""}
          onClick={() => setMode("runner")}
        >
          自由编程
        </button>
      </div>
      {mode === "exercise" ? (
        <ExerciseFlow />
      ) : mode === "activity" ? (
        <ActivityFlow />
      ) : (
        <RunnerFlow />
      )}
    </section>
  );
}

function ActivityFlow() {
  const [searchParams] = useSearchParams();
  const workspace = useQuery({
    queryKey: ["course", "workspace"],
    queryFn: () => localAppRequest<CourseWorkspace>("course.workspace"),
  });
  const [selectedId, setSelectedId] = useState<string | undefined>(
    () => searchParams.get("activity") ?? undefined,
  );
  const [confirmedId, setConfirmedId] = useState<string>();
  const courses = useMemo(
    () =>
      workspace.data?.courses
        .map((course) => ({
          id: course.id,
          title: course.title,
          activities: course.sections.flatMap((section) =>
            section.activities
              .filter((activity) => activity.enabled && activity.type !== "SQL")
              .map((activity) => ({
                ...activity,
                courseTitle: course.title,
                sectionTitle: section.title,
              })),
          ),
        }))
        .filter((course) => course.activities.length > 0) ?? [],
    [workspace.data],
  );
  const [selectedCourseId, setSelectedCourseId] = useState<string>();
  const activities =
    courses.find((course) => course.id === selectedCourseId)?.activities ?? [];
  const definition = useQuery({
    queryKey: ["activity", "definition", selectedId],
    queryFn: () =>
      localAppRequest<ActivityDefinition>("activity.definition", {
        activityId: selectedId,
      }),
    enabled: Boolean(selectedId),
  });
  useEffect(() => {
    const linkedCourse = selectedId
      ? courses.find((course) =>
          course.activities.some((activity) => activity.id === selectedId),
        )
      : undefined;
    if (linkedCourse && selectedCourseId !== linkedCourse.id)
      setSelectedCourseId(linkedCourse.id);
    else if (!selectedCourseId && courses.length > 0)
      setSelectedCourseId(courses[0].id);
  }, [courses, selectedCourseId, selectedId]);
  useEffect(() => {
    if (
      selectedCourseId &&
      !activities.some((activity) => activity.id === selectedId)
    )
      setSelectedId(activities[0]?.id);
  }, [activities, selectedCourseId, selectedId]);
  return (
    <div className="flow-layout">
      <aside className="content-card selection-panel">
        <p className="eyebrow">课程活动</p>
        <select
          aria-label="课程"
          value={selectedCourseId ?? ""}
          onChange={(event) => {
            setSelectedCourseId(event.target.value);
            setSelectedId(undefined);
            setConfirmedId(undefined);
          }}
        >
          {courses.map((course) => (
            <option key={course.id} value={course.id}>
              {course.title}
            </option>
          ))}
        </select>
        {activities.map((item) => (
          <button
            type="button"
            className={selectedId === item.id ? "selected" : ""}
            key={item.id}
            onClick={() => {
              setSelectedId(item.id);
              setConfirmedId(undefined);
            }}
          >
            {item.title}
            <small>
              {item.sectionTitle} · {item.type} · {item.estimatedMinutes} 分钟
            </small>
          </button>
        ))}
      </aside>
      <main className="flow-main">
        <Stepper
          steps={["选择活动", "预览", "运行与评价"]}
          current={
            !definition.data ? 0 : confirmedId === definition.data.id ? 2 : 1
          }
        />
        {workspace.isPending ||
        (Boolean(selectedId) && definition.isPending) ? (
          <section className="page-skeleton">
            <span className="spinner" />
            正在加载确定性活动
          </section>
        ) : workspace.isError || definition.isError ? (
          <Feedback tone="error" title="课程活动无法加载">
            {(workspace.error ?? definition.error)?.message}
          </Feedback>
        ) : courses.length === 0 ? (
          <EmptyState title="暂无可用课程活动">
            教师启用活动后会显示在这里。
          </EmptyState>
        ) : !definition.data ? (
          <EmptyState title="选择一项课程活动">
            测验、跟踪、模拟、代码、项目、实验和阅读活动都在 Java 核心中评价。
          </EmptyState>
        ) : confirmedId !== definition.data.id ? (
          <section className="content-card preview-card">
            <p className="eyebrow">
              {definition.data.type} · {definition.data.difficulty}
            </p>
            <h2>{definition.data.title}</h2>
            <p>{definition.data.description}</p>
            <dl>
              <div>
                <dt>预计用时</dt>
                <dd>{definition.data.estimatedMinutes} 分钟</dd>
              </div>
              <div>
                <dt>评价方式</dt>
                <dd>Java 确定性评价</dd>
              </div>
            </dl>
            <Button onClick={() => setConfirmedId(definition.data?.id)}>
              确认并开始活动
            </Button>
          </section>
        ) : (
          <ActivityInteraction
            key={definition.data.id}
            definition={definition.data}
          />
        )}
      </main>
    </div>
  );
}

type ActivityOption = {
  id: string;
  text?: string;
  label?: string;
  title?: string;
  prompt?: string;
  instruction?: string;
  observationKey?: string;
  acceptanceCriterion?: string;
  fromStateId?: string;
  toStateId?: string;
  description?: string;
};
function ActivityInteraction({
  definition,
}: {
  definition: ActivityDefinition;
}) {
  const spec = definition.specification as Record<string, unknown>;
  const [selections, setSelections] = useState<Record<string, string>>({});
  const [sequence, setSequence] = useState<string[]>([]);
  const [checked, setChecked] = useState<string[]>([]);
  const [texts, setTexts] = useState<Record<string, string>>({});
  const [source, setSource] = useState(String(spec.starterCode ?? ""));
  const [readToEnd, setReadToEnd] = useState(false);
  const [result, setResult] = useState<ActivitySubmission>();
  const submit = useMutation({
    mutationFn: (artifact: Record<string, unknown>) =>
      localAppRequest<ActivitySubmission>("activity.submit", {
        activityId: definition.id,
        type: definition.type,
        artifact,
      }),
    onSuccess: setResult,
  });
  const list = (name: string) =>
    (Array.isArray(spec[name]) ? spec[name] : []) as ActivityOption[];
  const prompt = String(spec.prompt ?? definition.description);
  function toggle(id: string) {
    setChecked((values) =>
      values.includes(id)
        ? values.filter((value) => value !== id)
        : [...values, id],
    );
  }
  function finish(artifact: Record<string, unknown>) {
    setResult(undefined);
    submit.mutate(artifact);
  }
  let body;
  if (definition.type === "QUIZ")
    body = (
      <>
        {list("questions").map((question) => (
          <fieldset key={question.id}>
            <legend>{question.prompt}</legend>
            {(
              (question as unknown as { options: ActivityOption[] }).options ??
              []
            ).map((option) => (
              <label key={option.id}>
                <input
                  type="radio"
                  name={question.id}
                  checked={selections[question.id] === option.id}
                  onChange={() =>
                    setSelections((value) => ({
                      ...value,
                      [question.id]: option.id,
                    }))
                  }
                />{" "}
                {option.text}
              </label>
            ))}
          </fieldset>
        ))}
        <Button
          disabled={submit.isPending}
          onClick={() => finish({ selectedOptionIds: selections })}
        >
          提交测验
        </Button>
      </>
    );
  else if (definition.type === "TRACE")
    body = (
      <>
        <p>{String(spec.traversal ?? "")}</p>
        <div className="button-row">
          {list("nodes").map((node) => (
            <Button
              variant="secondary"
              key={node.id}
              disabled={sequence.includes(node.id)}
              onClick={() => setSequence((value) => [...value, node.id])}
            >
              {node.label}
            </Button>
          ))}
        </div>
        <p>
          访问顺序：
          {sequence
            .map((id) => list("nodes").find((node) => node.id === id)?.label)
            .join(" → ") || "尚未选择"}
        </p>
        <Button
          variant="secondary"
          onClick={() => setSequence((value) => value.slice(0, -1))}
        >
          撤销
        </Button>{" "}
        <Button
          disabled={submit.isPending}
          onClick={() => finish({ visitedNodeIds: sequence })}
        >
          提交顺序
        </Button>
      </>
    );
  else if (definition.type === "SIMULATION")
    body = (
      <>
        <div className="button-row">
          {list("actions").map((action) => (
            <Button
              variant="secondary"
              key={action.id}
              onClick={() => setSequence((value) => [...value, action.id])}
            >
              {action.label}
            </Button>
          ))}
        </div>
        <p>
          操作序列：
          {sequence
            .map(
              (id) => list("actions").find((action) => action.id === id)?.label,
            )
            .join(" → ") || "尚未操作"}
        </p>
        <Button variant="secondary" onClick={() => setSequence([])}>
          重置
        </Button>{" "}
        <Button
          disabled={submit.isPending}
          onClick={() => finish({ actionIds: sequence })}
        >
          提交模拟
        </Button>
      </>
    );
  else if (definition.type === "CODE")
    body = (
      <>
        <CodeEditor
          language={String(spec.language ?? "PYTHON")}
          value={source}
          onChange={setSource}
          onRun={() => finish({ language: spec.language, sourceCode: source })}
        />
        <Button
          disabled={submit.isPending}
          onClick={() =>
            finish({ language: spec.language, sourceCode: source })
          }
        >
          运行并评价
        </Button>
      </>
    );
  else if (definition.type === "PROJECT")
    body = (
      <>
        {list("milestones").map((item) => (
          <label key={item.id}>
            <input
              type="checkbox"
              checked={checked.includes(item.id)}
              onChange={() => toggle(item.id)}
            />{" "}
            {item.title} — {item.acceptanceCriterion}
          </label>
        ))}
        <label>
          证据摘要
          <textarea
            value={texts.evidence ?? ""}
            onChange={(event) =>
              setTexts((value) => ({ ...value, evidence: event.target.value }))
            }
          />
        </label>
        <label>
          反思
          <textarea
            value={texts.reflection ?? ""}
            onChange={(event) =>
              setTexts((value) => ({
                ...value,
                reflection: event.target.value,
              }))
            }
          />
        </label>
        <Button
          disabled={submit.isPending}
          onClick={() =>
            finish({
              submissionVersion: definition.nextSubmissionVersion,
              completedMilestoneIds: checked,
              evidenceSummary: texts.evidence ?? "",
              reflection: texts.reflection ?? "",
            })
          }
        >
          提交第 {definition.nextSubmissionVersion} 版
        </Button>
      </>
    );
  else if (definition.type === "LAB")
    body = (
      <>
        {list("steps").map((item) => (
          <section className="content-card" key={item.id}>
            <label>
              <input
                type="checkbox"
                checked={checked.includes(item.id)}
                onChange={() => toggle(item.id)}
              />{" "}
              {item.title}
            </label>
            <p>{item.instruction}</p>
            <textarea
              aria-label={`${item.title}观察记录`}
              value={texts[item.observationKey ?? item.id] ?? ""}
              onChange={(event) =>
                setTexts((value) => ({
                  ...value,
                  [item.observationKey ?? item.id]: event.target.value,
                }))
              }
            />
          </section>
        ))}
        <label>
          实验结论
          <textarea
            value={texts.conclusion ?? ""}
            onChange={(event) =>
              setTexts((value) => ({
                ...value,
                conclusion: event.target.value,
              }))
            }
          />
        </label>
        <Button
          disabled={submit.isPending}
          onClick={() =>
            finish({
              completedStepIds: checked,
              observations: Object.fromEntries(
                list("steps").map((item) => [
                  item.observationKey ?? item.id,
                  texts[item.observationKey ?? item.id] ?? "",
                ]),
              ),
              conclusion: texts.conclusion ?? "",
            })
          }
        >
          提交实验
        </Button>
      </>
    );
  else if (definition.type === "READING")
    body = (
      <>
        <p className="policy-chip">
          {String(spec.sourceTitle ?? "")} · {String(spec.license ?? "")}
        </p>
        <article className="reading-content">
          {String(spec.content ?? "")}
        </article>
        <label>
          <input
            type="checkbox"
            checked={readToEnd}
            onChange={(event) => setReadToEnd(event.target.checked)}
          />{" "}
          我已阅读到末尾
        </label>
        {list("checks").map((item) => (
          <label key={item.id}>
            {item.prompt}
            <textarea
              value={texts[item.id] ?? ""}
              onChange={(event) =>
                setTexts((value) => ({
                  ...value,
                  [item.id]: event.target.value,
                }))
              }
            />
          </label>
        ))}
        <Button
          disabled={submit.isPending}
          onClick={() =>
            finish({
              readToEnd,
              answers: Object.fromEntries(
                list("checks").map((item) => [item.id, texts[item.id] ?? ""]),
              ),
            })
          }
        >
          提交阅读检查
        </Button>
      </>
    );
  else
    body = (
      <EmptyState title="请使用 SQL 练习">
        SQL 类型活动由专用安全练习流程执行。
      </EmptyState>
    );
  return (
    <section className="content-card activity-interaction">
      <header className="editor-toolbar">
        <div>
          <p className="eyebrow">
            {definition.type} · {definition.difficulty} ·{" "}
            {definition.estimatedMinutes} 分钟
          </p>
          <h2>{definition.title}</h2>
        </div>
        <span className="policy-chip">Java 确定性评价</span>
      </header>
      <p>{prompt}</p>
      {definition.latestFeedback && (
        <Feedback tone="info" title="教师反馈">
          {definition.latestFeedback.comment}
        </Feedback>
      )}
      {body}
      {submit.isError && (
        <Feedback tone="error" title="活动提交失败">
          {submit.error.message}
        </Feedback>
      )}
      {result && (
        <Feedback
          tone={result.evaluation.passed ? "success" : "warning"}
          title={result.evaluation.summary}
        >
          {result.evaluation.criteria.map((item) => (
            <p key={item.criterion}>
              {item.passed ? "✓" : "×"} {item.criterion}：{item.feedback}
            </p>
          ))}
        </Feedback>
      )}
    </section>
  );
}

function ExerciseFlow() {
  const [searchParams] = useSearchParams();
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
  const catalog = useQuery({
    queryKey: ["practice", "catalog"],
    queryFn: () =>
      localAppRequest<{ items: ExerciseSummary[] }>("practice.catalog"),
  });
  const [selectedId, setSelectedId] = useState<string | undefined>(
    () => searchParams.get("exercise") ?? undefined,
  );
  const [answer, setAnswer] = useState("SELECT *\nFROM ");
  const [session, setSession] = useState<ExerciseSession>();
  const [feedback, setFeedback] = useState<ExerciseAttempt>();
  const [hint, setHint] = useState<ExerciseHint>();
  const [delivery, setDelivery] = useState<AssignmentDelivery>();
  const [catalogQuery, setCatalogQuery] = useState("");
  const [catalogPage, setCatalogPage] = useState(0);
  const preview = useQuery({
    queryKey: ["practice", "preview", selectedId],
    queryFn: () =>
      localAppRequest<ExerciseView>("practice.preview", {
        exerciseId: selectedId,
      }),
    enabled: Boolean(selectedId),
  });
  const start = useMutation({
    mutationFn: () =>
      localAppRequest<ExerciseSession>("practice.start", {
        exerciseId: selectedId,
      }),
    onSuccess: (value) => {
      setSession(value);
      setFeedback(undefined);
    },
  });
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
  const attempt = useMutation({
    mutationFn: async (submit: boolean) => ({
      submit,
      result: await localAppRequest<ExerciseAttempt>(
        submit ? "practice.submit" : "practice.run",
        { sessionId: session?.id, answer },
      ),
    }),
    onSuccess: ({ result, submit }) => {
      setFeedback(result);
      if (submit && assignmentContext) deliverAssignment.mutate(result);
    },
  });
  const requestHint = useMutation({
    mutationFn: () =>
      localAppRequest<ExerciseHint>("practice.hint", {
        sessionId: session?.id,
      }),
    onSuccess: (value) => {
      setHint(value);
      setSession((current) =>
        current ? { ...current, hintsUsed: value.level } : current,
      );
    },
  });
  const reset = useMutation({
    mutationFn: () =>
      localAppRequest<ExerciseSession>("practice.reset", {
        sessionId: session?.id,
      }),
    onSuccess: (value) => {
      setSession(value);
      setAnswer("SELECT *\nFROM ");
      setFeedback(undefined);
      setHint(undefined);
    },
  });
  const close = useMutation({
    mutationFn: (sessionId: string) =>
      localAppRequest("practice.close", { sessionId }),
  });
  const filteredCatalog = useMemo(
    () =>
      (catalog.data?.items ?? []).filter((item) =>
        `${item.title} ${item.knowledgePoint}`
          .toLowerCase()
          .includes(catalogQuery.trim().toLowerCase()),
      ),
    [catalog.data, catalogQuery],
  );
  const catalogPageSize = 10;
  const catalogPages = Math.max(
    1,
    Math.ceil(filteredCatalog.length / catalogPageSize),
  );
  const visibleCatalog = filteredCatalog.slice(
    catalogPage * catalogPageSize,
    (catalogPage + 1) * catalogPageSize,
  );
  useEffect(() => {
    setCatalogPage(0);
  }, [catalogQuery]);
  const step = feedback
    ? 4
    : session
      ? 3
      : preview.data
        ? 1
        : selectedId
          ? 0
          : 0;
  return (
    <div className="flow-layout">
      <aside className="content-card selection-panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">题目目录</p>
            <strong>{filteredCatalog.length} 道题</strong>
          </div>
        </div>
        <input
          aria-label="搜索练习题"
          value={catalogQuery}
          onChange={(event) => setCatalogQuery(event.target.value)}
          placeholder="搜索题目或知识点"
        />
        {visibleCatalog.map((item) => (
          <button
            type="button"
            className={selectedId === item.id ? "selected" : ""}
            key={item.id}
            onClick={() => {
              if (session) close.mutate(session.id);
              setSelectedId(item.id);
              setSession(undefined);
              setFeedback(undefined);
              setHint(undefined);
              setDelivery(undefined);
            }}
          >
            {item.title}
            <small>
              {knowledgePointLabel(item.knowledgePoint)} ·{" "}
              {difficultyLabel(item.difficulty)}
            </small>
          </button>
        ))}
        {filteredCatalog.length === 0 && (
          <p className="muted">没有匹配的题目。</p>
        )}
        {filteredCatalog.length > catalogPageSize && (
          <div className="compact-pager">
            <Button
              variant="secondary"
              disabled={catalogPage === 0}
              onClick={() => setCatalogPage((value) => value - 1)}
            >
              上一页
            </Button>
            <span>
              {catalogPage + 1} / {catalogPages}
            </span>
            <Button
              variant="secondary"
              disabled={catalogPage + 1 >= catalogPages}
              onClick={() => setCatalogPage((value) => value + 1)}
            >
              下一页
            </Button>
          </div>
        )}
      </aside>
      <main className="flow-main">
        {assignmentContext && (
          <Feedback tone="info" title={assignmentTitle || "班级任务"}>
            <p>本次提交将记录到班级任务；网络不可用时会进入本地待同步队列。</p>
          </Feedback>
        )}
        <Stepper
          steps={["选题", "预览", "确认", "作答", "反馈"]}
          current={step}
        />
        {!selectedId && (
          <EmptyState title="先选择练习">
            编辑器会在预览并确认练习后加载。
          </EmptyState>
        )}
        {preview.data && !session && (
          <section className="content-card preview-card">
            <p className="eyebrow">作答前预览</p>
            <h2>{assignmentSnapshot.data?.title ?? preview.data.title}</h2>
            <p>{assignmentSnapshot.data?.prompt ?? preview.data.description}</p>
            {assignmentSnapshot.data && (
              <p className="muted">
                任务内容快照：
                {assignmentSnapshot.data.snapshotHash.slice(0, 12)} · 数据集{" "}
                {assignmentSnapshot.data.datasetVersion}
              </p>
            )}
            <dl>
              <div>
                <dt>知识点</dt>
                <dd>{preview.data.knowledgePoint}</dd>
              </div>
              <div>
                <dt>难度</dt>
                <dd>{preview.data.difficulty}</dd>
              </div>
            </dl>
            <pre>{preview.data.schemaSummary}</pre>
            <Button disabled={start.isPending} onClick={() => start.mutate()}>
              确认并开始作答
            </Button>
          </section>
        )}
        {session && (
          <section className="content-card coding-card">
            <header className="editor-toolbar">
              <div>
                <p className="eyebrow">确定性 SQL 练习</p>
                <h2>{session.exercise.title}</h2>
              </div>
              <span className="policy-chip">
                Java 评价 · 提示 {session.hintsUsed}/3
              </span>
            </header>
            <CodeEditor
              language="SQL"
              value={answer}
              onChange={setAnswer}
              schema={session.exercise.schemaSummary}
              onRun={() => attempt.mutate(false)}
              onSubmit={() => attempt.mutate(true)}
              onHint={() => {
                if (!requestHint.isPending && session.hintsUsed < 3)
                  requestHint.mutate();
              }}
            />
            {hint && (
              <Feedback tone="info" title={`第 ${hint.level} 级提示`}>
                {hint.text}
              </Feedback>
            )}
            <footer className="editor-actions">
              <span>
                {answer.length.toLocaleString()} 字符 · Ctrl+Enter 运行 ·
                Ctrl+Shift+Enter 提交 · F1 提示
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
                onClick={() => reset.mutate()}
              >
                重置练习
              </Button>
              <Button
                variant="secondary"
                disabled={attempt.isPending}
                onClick={() => attempt.mutate(false)}
              >
                运行
              </Button>
              <Button
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
            title={
              feedback.evaluation?.passed
                ? "练习通过"
                : `状态：${feedback.status}`
            }
          >
            <p>
              {feedback.evaluation?.feedback ?? feedback.execution?.message}
            </p>
            {feedback.evaluation?.criteria.map((item) => (
              <p key={item.criterion}>
                {item.passed ? "✓" : "×"} {item.criterion}：{item.feedback}
              </p>
            ))}
          </Feedback>
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
      </main>
    </div>
  );
}

function difficultyLabel(value: string) {
  return (
    (
      { BEGINNER: "入门", INTERMEDIATE: "进阶", ADVANCED: "高级" } as Record<
        string,
        string
      >
    )[value] ?? value
  );
}
function knowledgePointLabel(value: string) {
  return !value || value === "NOT EXISTS" ? "未设置知识点" : value;
}

function RunnerFlow() {
  const capabilities = useQuery({
    queryKey: ["runner", "capabilities"],
    queryFn: () =>
      localAppRequest<{ items: RunnerCapability[] }>("runner.capabilities"),
  });
  const available = useMemo(
    () => capabilities.data?.items ?? [],
    [capabilities.data],
  );
  const [language, setLanguage] = useState<"JAVA" | "PYTHON" | "C" | "CPP">(
    "JAVA",
  );
  const [source, setSource] = useState(templates.JAVA);
  const [input, setInput] = useState("");
  const [result, setResult] = useState<RunnerResult>();
  const [requestId, setRequestId] = useState<string>();
  const [phase, setPhase] = useState("");
  useEffect(() => {
    let unlisten: (() => void) | undefined;
    void subscribeLocalAppEvents((event) => {
      if (event.requestId === requestId && event.event === "runner.progress")
        setPhase(String(event.payload.phase ?? ""));
    }).then((value) => {
      unlisten = value;
    });
    return () => unlisten?.();
  }, [requestId]);
  const run = useMutation({
    mutationFn: async () => {
      const id = crypto.randomUUID();
      setRequestId(id);
      setPhase("starting");
      setResult(undefined);
      return localAppRequestWithId<RunnerResult>(
        "runner.run",
        { language, sourceCode: source, standardInput: input },
        id,
      );
    },
    onSuccess: (value) => {
      setResult(value);
      setPhase("completed");
    },
    onSettled: () => setRequestId(undefined),
  });
  function selectLanguage(next: typeof language) {
    setLanguage(next);
    setSource(templates[next]);
    setResult(undefined);
  }
  const capability = available.find((item) => item.language === language);
  return (
    <div className="runner-layout">
      <section className="content-card coding-card">
        <header className="editor-toolbar">
          <div>
            <p className="eyebrow">本地编程实验</p>
            <h2>多语言 Runner</h2>
          </div>
          <div className="segmented-control">
            {(["JAVA", "PYTHON", "C", "CPP"] as const).map((item) => (
              <button
                type="button"
                className={language === item ? "selected" : ""}
                key={item}
                onClick={() => selectLanguage(item)}
              >
                {item}
              </button>
            ))}
          </div>
        </header>
        <CodeEditor
          language={language}
          value={source}
          onChange={setSource}
          onRun={() => run.mutate()}
        />
        <label className="stdin-field">
          标准输入
          <textarea
            value={input}
            onChange={(event) => setInput(event.target.value)}
          />
        </label>
        <footer className="editor-actions">
          <span>
            {capability?.available
              ? "工具链可用"
              : (capability?.reasonCode ?? "正在探测")}{" "}
            · 源码上限 256 KiB · 输出上限 64 KiB
          </span>
          {run.isPending && requestId && (
            <Button
              variant="danger"
              onClick={() => void cancelLocalAppRequest(requestId)}
            >
              取消
            </Button>
          )}
          <Button
            disabled={
              !capability?.available ||
              run.isPending ||
              source.length > 256 * 1024
            }
            onClick={() => run.mutate()}
          >
            运行实验
          </Button>
        </footer>
      </section>
      <section className="content-card output-panel" aria-live="polite">
        <div className="section-heading">
          <h2>运行反馈</h2>
          <span className="policy-chip">{phase || "idle"}</span>
        </div>
        {!result && !run.isError ? (
          <EmptyState title="等待运行">
            编译、运行、超时和取消状态会显示在这里。
          </EmptyState>
        ) : result ? (
          <>
            <Feedback
              tone={result.failureReason === "NONE" ? "success" : "warning"}
              title={
                result.failureReason === "NONE"
                  ? "运行成功"
                  : result.failureReason
              }
            >
              退出码 {result.exitCode}
            </Feedback>
            <pre>
              {result.standardOutput || result.standardError || "（无输出）"}
            </pre>
          </>
        ) : (
          <Feedback tone="error" title="Runner 失败">
            {run.error?.message}
          </Feedback>
        )}
      </section>
    </div>
  );
}

function CodeEditor({
  language,
  value,
  onChange,
  schema = "",
  onRun,
  onSubmit,
  onHint,
}: {
  language: string;
  value: string;
  onChange: (value: string) => void;
  schema?: string;
  onRun: () => void;
  onSubmit?: () => void;
  onHint?: () => void;
}) {
  const configure: BeforeMount = (api) => {
    api.languages.registerCompletionItemProvider("sql", {
      provideCompletionItems: (
        model: monaco.editor.ITextModel,
        position: monaco.Position,
      ) => {
        const word = model.getWordUntilPosition(position);
        const range = new api.Range(
          position.lineNumber,
          word.startColumn,
          position.lineNumber,
          word.endColumn,
        );
        const names = Array.from(
          new Set(schema.match(/[A-Za-z_][A-Za-z0-9_]*/g) ?? []),
        );
        const suggestions: monaco.languages.CompletionItem[] = names
          .slice(0, 200)
          .map((label) => ({
            label,
            kind: api.languages.CompletionItemKind.Field,
            insertText: label,
            detail: "当前练习结构",
            range,
          }));
        suggestions.push({
          label: "safe select",
          kind: api.languages.CompletionItemKind.Snippet,
          insertText: "SELECT ${1:*} FROM ${2:table} LIMIT ${3:100};",
          insertTextRules:
            api.languages.CompletionItemInsertTextRule.InsertAsSnippet,
          range,
        });
        return { suggestions };
      },
    });
  };
  const mount: OnMount = (editor, api) => {
    editor.addCommand(api.KeyMod.CtrlCmd | api.KeyCode.Enter, onRun);
    if (onSubmit)
      editor.addCommand(
        api.KeyMod.CtrlCmd | api.KeyMod.Shift | api.KeyCode.Enter,
        onSubmit,
      );
    if (onHint) editor.addCommand(api.KeyCode.F1, onHint);
    const model = editor.getModel();
    if (!model) return;
    const markers =
      value.length > 256 * 1024
        ? [
            {
              severity: api.MarkerSeverity.Warning,
              message: "内容超过 Runner 的 256 KiB 上限",
              startLineNumber: 1,
              startColumn: 1,
              endLineNumber: 1,
              endColumn: 2,
            },
          ]
        : [];
    api.editor.setModelMarkers(model, "sqlteacher", markers);
  };
  return (
    <div className="editor-frame">
      <Editor
        beforeMount={configure}
        onMount={mount}
        height="100%"
        language={monacoLanguage[language]}
        path={`sqlteacher://${language.toLowerCase()}/workspace`}
        value={value}
        onChange={(next) => onChange(next ?? "")}
        options={{
          automaticLayout: true,
          fontFamily: "'Cascadia Code', Consolas, monospace",
          fontSize: 14,
          minimap: { enabled: false },
          padding: { top: 16 },
          scrollBeyondLastLine: false,
          wordWrap: "on",
          quickSuggestions: true,
        }}
      />
    </div>
  );
}
