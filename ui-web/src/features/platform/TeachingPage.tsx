// Teaching workspace page (v3.4.0 REF-13): extracted from PlatformPages.tsx.
// Read-only loads use useQuery; all write/action mutations stay mutations.
import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { localAppRequest } from "../../shared/ipc";
import {
  difficultyLabel,
  knowledgePointLabel,
  roleLabel,
} from "../../shared/labels";
import type {
  ExerciseDefinition,
  ExerciseImportPreview,
  ExerciseTextDraft,
  InterventionCandidate,
  LearningAnalytics,
  TeachingWorkspace,
} from "../../shared/types";
import {
  Button,
  DataTable,
  Feedback,
  FormField,
  useToast,
} from "../../shared/ui";
import {
  Loading,
  Metric,
  Toggle,
  analyticsMetricLabel,
  formatAccountDate,
} from "./shared";

const teachingKey = ["teaching", "workspace"] as const;
const interventionsKey = ["teaching", "interventions"] as const;
const analyticsKey = ["teaching", "analytics"] as const;

type ExerciseDraftUi = {
  id: string;
  title: string;
  description: string;
  knowledgePoint: string;
  difficulty: string;
  datasetId: string;
  referenceSql: string;
  compareColumns: boolean;
  compareRows: boolean;
  rowOrderMatters: boolean;
  expectedRowCount: string;
  requiredSqlKeywords: string;
  hints: string;
  expectedVersion?: number;
  enabled: boolean;
};
const emptyExercise = (): ExerciseDraftUi => ({
  id: "",
  title: "",
  description: "",
  knowledgePoint: "",
  difficulty: "BEGINNER",
  datasetId: "",
  referenceSql: "SELECT 1",
  compareColumns: true,
  compareRows: true,
  rowOrderMatters: false,
  expectedRowCount: "",
  requiredSqlKeywords: "SELECT",
  hints: "",
  enabled: true,
});

export function TeachingPage() {
  const client = useQueryClient();
  const toast = useToast();
  const [selectedId, setSelectedId] = useState("");
  const [draft, setDraft] = useState<ExerciseDraftUi>(emptyExercise);
  const [editorOpen, setEditorOpen] = useState(false);
  const editorRef = useRef<HTMLDetailsElement | null>(null);
  const [importText, setImportText] = useState("");
  const [importPreview, setImportPreview] = useState<ExerciseImportPreview>();
  const [analyticsOpen, setAnalyticsOpen] = useState(false);
  const [exerciseQuery, setExerciseQuery] = useState("");
  const [exercisePage, setExercisePage] = useState(0);
  const [progressPage, setProgressPage] = useState(0);
  const query = useQuery({
    queryKey: teachingKey,
    queryFn: () => localAppRequest<TeachingWorkspace>("teaching.workspace"),
    staleTime: 15_000,
  });
  const detail = useQuery({
    queryKey: ["teaching", "exercise", selectedId],
    queryFn: () =>
      localAppRequest<ExerciseDefinition>("teaching.exercise.detail", {
        exerciseId: selectedId,
      }),
    enabled: Boolean(selectedId),
  });
  useEffect(() => {
    if (detail.data) setDraft(definitionToDraft(detail.data));
  }, [detail.data]);
  // 选中题目或点“新建题目”时展开编辑器并滚动到位。
  useEffect(() => {
    if (!selectedId) return;
    setEditorOpen(true);
    requestAnimationFrame(() =>
      editorRef.current?.scrollIntoView({ behavior: "smooth", block: "start" }),
    );
  }, [selectedId]);
  const openEditorForNew = (datasetId?: string) => {
    setSelectedId("");
    setDraft({ ...emptyExercise(), datasetId: datasetId ?? "" });
    setEditorOpen(true);
    requestAnimationFrame(() =>
      editorRef.current?.scrollIntoView({ behavior: "smooth", block: "start" }),
    );
  };
  const toggle = useMutation({
    mutationFn: (item: { id: string; enabled: boolean; version: number }) =>
      localAppRequest("teaching.exercise.toggle", {
        exerciseId: item.id,
        enabled: !item.enabled,
        expectedVersion: item.version,
      }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: teachingKey });
      toast("success", "题目状态已更新");
    },
    onError: (error: Error) => toast("error", `状态更新失败：${error.message}`),
  });
  const saveExercise = useMutation({
    mutationFn: () =>
      localAppRequest<ExerciseDefinition>("teaching.exercise.save", {
        ...draft,
        expectedRowCount:
          draft.expectedRowCount === "" ? null : Number(draft.expectedRowCount),
        requiredSqlKeywords: splitLines(draft.requiredSqlKeywords),
        hints: splitLines(draft.hints),
      }),
    onSuccess: (value) => {
      setSelectedId(value.id);
      void client.invalidateQueries({ queryKey: teachingKey });
      toast("success", `题目「${value.title || value.id}」已保存`);
    },
    onError: (error: Error) => toast("error", `题目保存失败：${error.message}`),
  });
  const copyExercise = useMutation({
    mutationFn: () =>
      localAppRequest<ExerciseDefinition>("teaching.exercise.copy", {
        exerciseId: selectedId,
        title: `${draft.title}（副本）`,
      }),
    onSuccess: (value) => {
      setSelectedId(value.id);
      void client.invalidateQueries({ queryKey: teachingKey });
      toast("success", "已创建副本");
    },
    onError: (error: Error) => toast("error", `复制失败：${error.message}`),
  });
  const exportExercises = useMutation({
    mutationFn: () =>
      localAppRequest<{ text: string }>("teaching.exercise.export", {
        exerciseIds: selectedId
          ? [selectedId]
          : (query.data?.exercises.map((item) => item.id) ?? []),
      }),
    onSuccess: (value) => {
      setImportText(value.text);
      setImportPreview(undefined);
      toast("success", "导出完成，文字包已填入下方文本框");
    },
    onError: (error: Error) => toast("error", `导出失败：${error.message}`),
  });
  const parseExercises = useMutation({
    mutationFn: () =>
      localAppRequest<ExerciseImportPreview>("teaching.exercise.parse", {
        text: importText,
      }),
    onSuccess: (value) => {
      setImportPreview(value);
      toast("success", "解析完成，请核对后导入");
    },
    onError: (error: Error) => {
      setImportPreview(undefined);
      toast("error", `解析失败：${error.message}`);
    },
  });
  const publishExercises = useMutation({
    mutationFn: () =>
      localAppRequest<{ bankVersion: number }>("teaching.exercise.publish", {
        text: importText,
      }),
    onSuccess: (value) =>
      toast("success", `已发布到服务器，题库版本 ${value.bankVersion}`),
    onError: (error: Error) => toast("error", `发布失败：${error.message}`),
  });
  // 题库体检（W3.4）：对库内全部题目批量执行导入自测同等校验，只读无副作用。
  const [healthReport, setHealthReport] = useState<
    Array<{ exerciseId: string; title: string; passed: boolean; message: string }>
  >([]);
  const runHealthCheck = useMutation({
    mutationFn: () =>
      localAppRequest<{
        items: Array<{
          exerciseId: string;
          title: string;
          passed: boolean;
          message: string;
        }>;
      }>("teaching.exercise.health"),
    onSuccess: (value) => {
      setHealthReport(value.items);
      toast(
        value.items.every((item) => item.passed) ? "success" : "error",
        `题库体检完成：${value.items.filter((item) => item.passed).length}/${value.items.length} 道题通过`,
      );
    },
    onError: (error: Error) => toast("error", `题库体检失败：${error.message}`),
  });
  const exportHealthReport = () => {
    const lines = healthReport.map(
      (item) =>
        `${item.passed ? "通过" : "未通过"}\t${item.exerciseId}\t${item.title}\t${item.message}`,
    );
    const report = ["状态\t题目ID\t题目\t说明", ...lines].join("\n");
    void navigator.clipboard
      ?.writeText(report)
      .then(() => toast("success", "体检报告已复制到剪贴板，可粘贴保存"))
      .catch(() => toast("error", "剪贴板不可用，请手动记录"));
  };
  const draftExercises = useMutation({
    mutationFn: () =>
      localAppRequest<ExerciseTextDraft>("teaching.exercise.draft", {
        text: importText,
      }),
    onSuccess: (value) => {
      setImportText(value.text);
      setImportPreview(undefined);
      toast("success", "已生成格式草稿，请核对");
    },
    onError: (error: Error) => toast("error", `AI 解析失败：${error.message}`),
  });
  const importExercises = useMutation({
    mutationFn: () =>
      localAppRequest("teaching.exercise.import", { text: importText }),
    onSuccess: () => {
      setImportText("");
      setImportPreview(undefined);
      void client.invalidateQueries({ queryKey: teachingKey });
      toast("success", "导入成功，题库已更新");
    },
    onError: (error: Error) => toast("error", `导入失败：${error.message}`),
  });
  // 挂载即加载干预队列，让折叠区外的“待处理 N”徽章有数据。
  const interventions = useQuery({
    queryKey: interventionsKey,
    queryFn: () =>
      localAppRequest<{ items: InterventionCandidate[] }>(
        "teaching.interventions",
      ),
    retry: false,
  });
  useEffect(() => {
    if (interventions.isError)
      toast(
        "error",
        `加载干预队列失败：${interventions.error?.message ?? ""}`,
      );
  }, [interventions.isError, interventions.error, toast]);
  const updateIntervention = useMutation({
    mutationFn: (value: {
      candidateId: string;
      status: InterventionCandidate["status"];
    }) => localAppRequest("teaching.intervention.update", value),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: interventionsKey });
    },
    onError: (error: Error) =>
      toast("error", `更新干预状态失败：${error.message}`),
  });
  const analytics = useQuery({
    queryKey: analyticsKey,
    queryFn: () => localAppRequest<LearningAnalytics>("teaching.analytics"),
    enabled: analyticsOpen,
    retry: false,
  });
  useEffect(() => {
    if (analytics.isError)
      toast("error", `加载学情分析失败：${analytics.error?.message ?? ""}`);
  }, [analytics.isError, analytics.error, toast]);
  if (query.isPending) return <Loading label="正在读取本地题库与学情" />;
  if (query.isError)
    return (
      <Feedback tone="error" title="教学工作台不可用">
        <p>{query.error.message}</p>
      </Feedback>
    );
  const data = query.data;
  const interventionItems = interventions.data?.items ?? [];
  const filteredExercises = data.exercises.filter((item) =>
    `${item.title} ${item.knowledgePoint}`
      .toLowerCase()
      .includes(exerciseQuery.trim().toLowerCase()),
  );
  const exercisePageSize = 50;
  const exercisePages = Math.max(
    1,
    Math.ceil(filteredExercises.length / exercisePageSize),
  );
  const visibleExercisePage = Math.min(exercisePage, exercisePages - 1);
  const visibleExercises = filteredExercises.slice(
    visibleExercisePage * exercisePageSize,
    (visibleExercisePage + 1) * exercisePageSize,
  );
  const progressPageSize = 50;
  const progressPages = Math.max(
    1,
    Math.ceil(data.progressItems.length / progressPageSize),
  );
  const visibleProgressPage = Math.min(progressPage, progressPages - 1);
  const visibleProgressItems = data.progressItems.slice(
    visibleProgressPage * progressPageSize,
    (visibleProgressPage + 1) * progressPageSize,
  );
  return (
    <div className="platform-workspace page-grid">
      <section className="hero-card">
        <div>
          <p className="eyebrow">题库与学情</p>
          <h2>教学工作台</h2>
        </div>
        <span className="policy-chip">{roleLabel(data.role)}</span>
      </section>
      <section className="metric-row">
        <Metric label="题目" value={data.exercises.length} />
        <Metric label="练习会话" value={data.progressOverview.sessions} />
        <Metric label="提交" value={data.progressOverview.submissions} />
        <Metric
          label="已通过"
          value={data.progressOverview.passedSubmissions}
        />
      </section>
      <p className="muted">
        以下统计与学情均为本机作答记录（学生练习发生在各自的电脑上）；班级维度的提交与学情请前往「班级与云端」。
      </p>
      <section className="content-card teaching-bank">
        <div className="section-heading">
          <div>
            <p className="eyebrow">题库管理</p>
            <h2>本地题库</h2>
          </div>
          <span className="policy-chip">
            {data.canPublish ? "可发布" : "只读"}
          </span>
        </div>
        <div className="bank-toolbar">
          <input
            aria-label="搜索题库"
            value={exerciseQuery}
            onChange={(event) => {
              setExerciseQuery(event.target.value);
              setExercisePage(0);
            }}
            placeholder="搜索题目或知识点"
          />
          <Button onClick={() => openEditorForNew(data.datasets[0]?.id)}>
            新建题目
          </Button>
        </div>
        <DataTable
          caption={`教师题库，共 ${filteredExercises.length} 道题`}
          rows={visibleExercises}
          rowKey={(row) => row.id}
          columns={[
            {
              key: "title",
              title: "题目",
              render: (row) => (
                <button
                  type="button"
                  className="table-link"
                  onClick={() => setSelectedId(row.id)}
                >
                  {row.title}
                </button>
              ),
            },
            {
              key: "knowledge",
              title: "知识点",
              render: (row) => knowledgePointLabel(row.knowledgePoint, "未设置"),
            },
            {
              key: "difficulty",
              title: "难度",
              render: (row) => difficultyLabel(row.difficulty),
            },
            {
              key: "state",
              title: "状态",
              render: (row) => (
                <span className="state-cell">
                  <span className={`policy-chip ${row.enabled ? "" : "muted"}`}>
                    {row.enabled ? "已启用" : "已停用"}
                  </span>
                  <Button
                    variant="secondary"
                    busy={toggle.isPending}
                    onClick={() => toggle.mutate(row)}
                  >
                    {row.enabled ? "停用" : "启用"}
                  </Button>
                </span>
              ),
            },
          ]}
        />
        {filteredExercises.length > exercisePageSize && (
          <div className="compact-pager" aria-label="题库分页">
            <Button
              variant="secondary"
              disabled={visibleExercisePage === 0}
              onClick={() => setExercisePage(visibleExercisePage - 1)}
            >
              上一页
            </Button>
            <span>
              第 {visibleExercisePage + 1} / {exercisePages} 页
            </span>
            <Button
              variant="secondary"
              disabled={visibleExercisePage + 1 >= exercisePages}
              onClick={() => setExercisePage(visibleExercisePage + 1)}
            >
              下一页
            </Button>
          </div>
        )}
      </section>
      <details
        ref={editorRef}
        className="content-card teaching-editor"
        open={editorOpen}
        onToggle={(event) =>
          setEditorOpen((event.target as HTMLDetailsElement).open)
        }
      >
        <summary>
          <strong>
            {selectedId
              ? `编辑：${draft.title || "所选题目"}`
              : "新建题目与题库导入导出"}
          </strong>
        </summary>
        <div className="button-row">
          <Button
            variant="secondary"
            onClick={() => openEditorForNew(data.datasets[0]?.id)}
          >
            新建题目
          </Button>
          <Button
            variant="secondary"
            disabled={!selectedId}
            busy={copyExercise.isPending}
            onClick={() => copyExercise.mutate()}
          >
            复制所选
          </Button>
          <Button
            variant="secondary"
            busy={exportExercises.isPending}
            onClick={() => exportExercises.mutate()}
          >
            {selectedId ? "导出所选" : "导出全部"}
          </Button>
        </div>
        <div className="settings-grid">
          <FormField label="题目标题">
            {(ids) => (
              <input
                {...ids}
                value={draft.title}
                onChange={(event) =>
                  setDraft({ ...draft, title: event.target.value })
                }
              />
            )}
          </FormField>
          <FormField label="知识点">
            {(ids) => (
              <input
                {...ids}
                value={draft.knowledgePoint}
                onChange={(event) =>
                  setDraft({ ...draft, knowledgePoint: event.target.value })
                }
              />
            )}
          </FormField>
          <FormField label="难度">
            {(ids) => (
              <select
                {...ids}
                value={draft.difficulty}
                onChange={(event) =>
                  setDraft({ ...draft, difficulty: event.target.value })
                }
              >
                <option value="BEGINNER">入门</option>
                <option value="INTERMEDIATE">进阶</option>
                <option value="ADVANCED">高级</option>
              </select>
            )}
          </FormField>
          <FormField label="数据集">
            {(ids) => (
              <select
                {...ids}
                value={draft.datasetId}
                onChange={(event) =>
                  setDraft({ ...draft, datasetId: event.target.value })
                }
              >
                <option value="">选择数据集</option>
                {data.datasets.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.name}
                  </option>
                ))}
              </select>
            )}
          </FormField>
          <FormField label="题目说明">
            {(ids) => (
              <textarea
                {...ids}
                value={draft.description}
                onChange={(event) =>
                  setDraft({ ...draft, description: event.target.value })
                }
              />
            )}
          </FormField>
          {/* issue #22/#24：参考 SQL 的类型边界和替代写法必须在出题处可见。 */}
          <FormField
            label="参考 SQL"
            hint="查询题必须是单条只读 SELECT；GRANT/REVOKE 等语法考察可写成 SELECT '语句' AS answer；CREATE VIEW 等 DDL 考点请用题库文本导入（TYPE: STATE + ALLOWED: CREATE 或 SCRIPT）。"
          >
            {(ids) => (
              <textarea
                {...ids}
                value={draft.referenceSql}
                onChange={(event) =>
                  setDraft({ ...draft, referenceSql: event.target.value })
                }
              />
            )}
          </FormField>
          <FormField label="必需关键字" hint="每行一个">
            {(ids) => (
              <textarea
                {...ids}
                value={draft.requiredSqlKeywords}
                onChange={(event) =>
                  setDraft({
                    ...draft,
                    requiredSqlKeywords: event.target.value,
                  })
                }
              />
            )}
          </FormField>
          <FormField label="分级提示" hint="最多三行">
            {(ids) => (
              <textarea
                {...ids}
                value={draft.hints}
                onChange={(event) =>
                  setDraft({ ...draft, hints: event.target.value })
                }
              />
            )}
          </FormField>
        </div>
        <div className="button-row">
          <Toggle
            label="比较列"
            checked={draft.compareColumns}
            onChange={() =>
              setDraft({ ...draft, compareColumns: !draft.compareColumns })
            }
          />
          <Toggle
            label="比较行"
            checked={draft.compareRows}
            onChange={() =>
              setDraft({ ...draft, compareRows: !draft.compareRows })
            }
          />
          <Toggle
            label="行顺序敏感"
            checked={draft.rowOrderMatters}
            onChange={() =>
              setDraft({ ...draft, rowOrderMatters: !draft.rowOrderMatters })
            }
          />
        </div>
        <Button
          disabled={
            !draft.title ||
            !draft.description ||
            !draft.knowledgePoint ||
            !draft.datasetId ||
            !draft.referenceSql
          }
          busy={saveExercise.isPending}
          onClick={() => saveExercise.mutate()}
        >
          保存题目
        </Button>
        {saveExercise.isError && (
          <Feedback tone="error" title="题目保存失败">
            {saveExercise.error.message}
          </Feedback>
        )}
        <FormField
          label="题库导入文字"
          hint="粘贴自由文本可点 AI 解析；先解析预览，由 Java 校验后再导入。"
        >
          {(ids) => (
            <textarea
              {...ids}
              value={importText}
              onChange={(event) => {
                setImportText(event.target.value);
                setImportPreview(undefined);
              }}
              placeholder={EXERCISE_IMPORT_TEMPLATE}
            />
          )}
        </FormField>
        {parseExercises.isError && (
          <Feedback tone="error" title="解析失败">
            {parseExercises.error.message}
          </Feedback>
        )}
        {importPreview && (
          <Feedback
            tone={
              importPreview.exercises.every((item) => item.selfTest.passed) &&
              importPreview.datasets.every((item) => item.selfTest.passed)
                ? "info"
                : "error"
            }
            title="导入预览"
          >
            <p>
              将导入 {importPreview.datasets.length} 个数据集、
              {importPreview.exercises.length} 道题。导入前会按数据集试跑每道题的参考答案。
            </p>
            {importPreview.datasets.some((item) => !item.selfTest.passed) && (
              <ul className="plain-list">
                {importPreview.datasets
                  .filter((item) => !item.selfTest.passed)
                  .map((item) => (
                    <li key={item.id}>
                      数据集 {item.name}：{item.selfTest.message}
                    </li>
                  ))}
              </ul>
            )}
            <ul className="plain-list">
              {importPreview.exercises.map((item) => (
                <li key={item.id}>
                  {item.selfTest.passed ? "✓" : "✕"}{" "}
                  <span>{item.title}</span>
                  {!item.selfTest.passed && <>—— {item.selfTest.message}</>}
                </li>
              ))}
            </ul>
          </Feedback>
        )}
        {healthReport.length > 0 && (
          <Feedback
            tone={
              healthReport.every((item) => item.passed) ? "success" : "warning"
            }
            title={`题库体检报告（${healthReport.filter((item) => item.passed).length}/${healthReport.length} 通过）`}
          >
            <ul className="plain-list">
              {healthReport.map((item) => (
                <li key={item.exerciseId}>
                  {item.passed ? "✓" : "✕"} {item.title}
                  {!item.passed && <>—— {item.message}</>}
                </li>
              ))}
            </ul>
            <div className="button-row">
              <Button variant="secondary" onClick={() => exportHealthReport()}>
                复制文字报告
              </Button>
              <Button
                variant="secondary"
                onClick={() => setHealthReport([])}
              >
                关闭报告
              </Button>
            </div>
          </Feedback>
        )}
        <div className="button-row">
          <Button
            variant="secondary"
            busy={runHealthCheck.isPending}
            onClick={() => runHealthCheck.mutate()}
          >
            题库体检
          </Button>
          <Button
            variant="secondary"
            disabled={!importText.trim()}
            busy={draftExercises.isPending}
            onClick={() => draftExercises.mutate()}
          >
            AI 解析
          </Button>
          <Button
            variant="secondary"
            disabled={!importText.trim()}
            busy={parseExercises.isPending}
            onClick={() => parseExercises.mutate()}
          >
            解析预览
          </Button>
          <Button
            disabled={
              !importPreview ||
              !importPreview.exercises.every((item) => item.selfTest.passed) ||
              !importPreview.datasets.every((item) => item.selfTest.passed)
            }
            busy={importExercises.isPending}
            onClick={() => importExercises.mutate()}
          >
            导入题库包
          </Button>
          <Button
            variant="secondary"
            disabled={!importText.trim()}
            busy={publishExercises.isPending}
            onClick={() => publishExercises.mutate()}
          >
            发布到服务器
          </Button>
        </div>
      </details>
      <section className="content-card">
        <div className="section-heading">
          <div>
            <p className="eyebrow">学习分析</p>
            <h2>学习进度</h2>
          </div>
        </div>
        {data.progressItems.length === 0 ? (
          <p className="muted">尚无练习记录。</p>
        ) : (
          <DataTable
            caption="练习学情"
            rows={visibleProgressItems}
            columns={[
              { key: "title", title: "题目", render: (row) => row.title },
              { key: "attempts", title: "尝试", render: (row) => row.attempts },
              {
                key: "failed",
                title: "失败提交",
                render: (row) => row.failedSubmissions,
              },
              {
                key: "passed",
                title: "结果",
                render: (row) => (row.passed ? "通过" : "练习中"),
              },
            ]}
          />
        )}
        {data.progressItems.length > progressPageSize && (
          <div className="compact-pager" aria-label="学习进度分页">
            <Button
              variant="secondary"
              disabled={visibleProgressPage === 0}
              onClick={() => setProgressPage(visibleProgressPage - 1)}
            >
              上一页
            </Button>
            <span>
              第 {visibleProgressPage + 1} / {progressPages} 页
            </span>
            <Button
              variant="secondary"
              disabled={visibleProgressPage + 1 >= progressPages}
              onClick={() => setProgressPage(visibleProgressPage + 1)}
            >
              下一页
            </Button>
          </div>
        )}
      </section>
      <details
        className="content-card"
        onToggle={(event) => {
          if (event.currentTarget.open) {
            setAnalyticsOpen(true);
            void client.invalidateQueries({ queryKey: analyticsKey });
          }
        }}
      >
        <summary>
          <strong>完整学情分析</strong>
        </summary>
        {analytics.isPending ? (
          <p className="muted">正在生成本地学情分析…</p>
        ) : analytics.data ? (
          <>
            <p>生成时间：{formatAccountDate(analytics.data.generatedAt)}</p>
            <div className="metric-row">
              {Object.entries(analytics.data.overview).map(([key, value]) => (
                <Metric
                  key={key}
                  label={analyticsMetricLabel(key)}
                  value={value}
                />
              ))}
            </div>
            <p>
              题目统计 {analytics.data.exercises.length} 项，知识点统计{" "}
              {analytics.data.knowledgePoints.length} 项，常见错误{" "}
              {analytics.data.commonErrors.length} 项。
            </p>
          </>
        ) : (
          <p className="muted">
            暂无学情数据：此处统计的是本机作答记录，学生完成练习后即可看到分析。
          </p>
        )}
      </details>
      <details
        className="content-card"
        onToggle={(event) => {
          if (event.currentTarget.open)
            void client.invalidateQueries({ queryKey: interventionsKey });
        }}
      >
        <summary>
          <strong>
            教师干预队列
            {interventionItems.length > 0 && (
              <span className="policy-chip">待处理 {interventionItems.length}</span>
            )}
          </strong>
        </summary>
        {interventionItems.length === 0 ? (
          <p className="muted">暂无待处理干预。</p>
        ) : (
          <ul className="plain-list">
            {interventionItems.map((item) => (
              <li key={item.id}>
                <strong>
                  {item.studentDisplayName} · {item.assignmentTitle}
                </strong>
                <span>
                  {item.reason} · 优先级 {item.priority} ·{" "}
                  {item.evidenceSummary}
                </span>
                <select
                  aria-label={`${item.studentDisplayName} 干预状态`}
                  value={item.status}
                  onChange={(event) =>
                    updateIntervention.mutate({
                      candidateId: item.id,
                      status: event.target
                        .value as InterventionCandidate["status"],
                    })
                  }
                >
                  <option value="OPEN">待处理</option>
                  <option value="ACKNOWLEDGED">已确认</option>
                  <option value="RESOLVED">已解决</option>
                  <option value="DISMISSED">已忽略</option>
                </select>
              </li>
            ))}
          </ul>
        )}
      </details>
    </div>
  );
}

function splitLines(value: string) {
  return value
    .split(/[,\n]/)
    .map((item) => item.trim())
    .filter(Boolean);
}
const EXERCISE_IMPORT_TEMPLATE = [
  "===[DATASET]===",
  "ID: my-dataset",
  "NAME: 我的数据集",
  "SQL:",
  "create table student(id integer primary key, name text not null);",
  "insert into student values (1, 'Alice');",
  "",
  "===[EXERCISE]===",
  "TITLE: 查询全部学生",
  "KNOWLEDGE: 基础查询",
  "DIFFICULTY: BEGINNER",
  "DATASET: my-dataset",
  "DESCRIPTION:",
  "返回 student 表的全部列。",
  "SQL:",
  "select id, name from student order by id",
  "RULE: EXACT",
].join("\n");
function definitionToDraft(value: ExerciseDefinition): ExerciseDraftUi {
  return {
    id: value.id,
    title: value.title,
    description: value.description,
    knowledgePoint: value.knowledgePoint,
    difficulty: value.difficulty,
    datasetId: value.datasetId,
    referenceSql: value.referenceSql,
    compareColumns: value.evaluationRule.compareColumns,
    compareRows: value.evaluationRule.compareRows,
    rowOrderMatters: value.evaluationRule.rowOrderMatters,
    expectedRowCount:
      value.evaluationRule.expectedRowCount == null
        ? ""
        : String(value.evaluationRule.expectedRowCount),
    requiredSqlKeywords: value.evaluationRule.requiredSqlKeywords.join("\n"),
    hints: value.hints.join("\n"),
    expectedVersion: value.version,
    enabled: value.enabled,
  };
}
