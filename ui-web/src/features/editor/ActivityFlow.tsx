import { useMutation, useQuery } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { localAppRequest } from "../../shared/ipc";
import type {
  ActivityDefinition,
  ActivityOption,
  ActivityQuestion,
  ActivitySubmission,
  CourseWorkspace,
} from "../../shared/types";
import { activityTypeLabel, difficultyLabel } from "../../shared/labels";
import { Button, EmptyState, Feedback, Stepper } from "../../shared/ui";
import { CodeEditor } from "./EditorPage";

export function ActivityFlow() {
  const [searchParams, setSearchParams] = useSearchParams();
  const workspace = useQuery({
    queryKey: ["course", "workspace"],
    queryFn: () => localAppRequest<CourseWorkspace>("course.workspace"),
    staleTime: 30_000,
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
  // 选中活动写回 URL，刷新后可恢复。
  useEffect(() => {
    if (!selectedId || searchParams.get("activity") === selectedId) return;
    const params = new URLSearchParams(searchParams);
    params.set("activity", selectedId);
    setSearchParams(params, { replace: true });
  }, [selectedId, searchParams, setSearchParams]);
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
            正在加载活动
          </section>
        ) : workspace.isError || definition.isError ? (
          <Feedback tone="error" title="课程活动无法加载">
            {(workspace.error ?? definition.error)?.message}
          </Feedback>
        ) : courses.length === 0 ? (
          <EmptyState title="暂无课程活动" />
        ) : !definition.data ? (
          <EmptyState title="选择一项课程活动" />
        ) : confirmedId !== definition.data.id ? (
          <section className="content-card preview-card">
            <p className="eyebrow">
              {activityTypeLabel(definition.data.type)} ·{" "}
              {difficultyLabel(definition.data.difficulty)}
            </p>
            <h2>{definition.data.title}</h2>
            <p>{definition.data.description}</p>
            <dl>
              <div>
                <dt>预计用时</dt>
                <dd>{definition.data.estimatedMinutes} 分钟</dd>
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

function ActivityInteraction({
  definition,
}: {
  definition: ActivityDefinition;
}) {
  const [searchParams, setSearchParams] = useSearchParams();
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
  function list<T extends ActivityOption = ActivityOption>(name: string) {
    return (Array.isArray(spec[name]) ? spec[name] : []) as T[];
  }
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
        {list<ActivityQuestion>("questions").map((question) => (
          <fieldset key={question.id}>
            <legend>{question.prompt}</legend>
            {(question.options ?? []).map((option) => (
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
      <EmptyState
        title="请使用 SQL 练习"
        action={
          <Button
            variant="secondary"
            onClick={() => {
              const params = new URLSearchParams(searchParams);
              params.delete("activity");
              params.set("tab", "exercise");
              setSearchParams(params);
            }}
          >
            切换到 SQL 练习
          </Button>
        }
      >
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
        <span className="policy-chip">Java 评价</span>
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
