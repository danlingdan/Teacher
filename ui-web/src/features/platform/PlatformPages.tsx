import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { localAppRequest } from "../../shared/ipc";
import type {
  ActiveSession,
  BackupSnapshot,
  CloudAssignment,
  CloudCourse,
  CloudCourseContent,
  CoursePackagePreview,
  CloudWorkspace,
  ExerciseDefinition,
  ExerciseSummary,
  InterventionCandidate,
  LearningAnalytics,
  KnowledgeMastery,
  PortfolioEntry,
  SettingsEnvironment,
  SettingsPreferences,
  SettingsStorage,
  SubmissionFeedback,
  TeachingWorkspace,
  UpdateCheck,
} from "../../shared/types";
import {
  Button,
  DataTable,
  Dialog,
  Feedback,
  FormField,
  useToast,
} from "../../shared/ui";

const teachingKey = ["teaching", "workspace"] as const;
const cloudKey = ["cloud", "workspace"] as const;
const settingsKey = ["settings", "preferences"] as const;
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
  const [packageJson, setPackageJson] = useState("");
  const [analytics, setAnalytics] = useState<LearningAnalytics>();
  const [interventions, setInterventions] = useState<InterventionCandidate[]>(
    [],
  );
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
    if (selectedId) setEditorOpen(true);
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
      localAppRequest<{ packageJson: string }>("teaching.exercise.export", {
        exerciseIds: selectedId
          ? [selectedId]
          : (query.data?.exercises.map((item) => item.id) ?? []),
      }),
    onSuccess: (value) => {
      setPackageJson(value.packageJson);
      toast("success", "导出完成，JSON 已填入下方文本框");
    },
    onError: (error: Error) => toast("error", `导出失败：${error.message}`),
  });
  const importExercises = useMutation({
    mutationFn: () =>
      localAppRequest("teaching.exercise.import", { packageJson }),
    onSuccess: () => {
      setPackageJson("");
      void client.invalidateQueries({ queryKey: teachingKey });
      toast("success", "导入成功，题库已更新");
    },
    onError: (error: Error) => toast("error", `导入失败：${error.message}`),
  });
  const loadAnalytics = useMutation({
    mutationFn: () => localAppRequest<LearningAnalytics>("teaching.analytics"),
    onSuccess: setAnalytics,
  });
  const loadInterventions = useMutation({
    mutationFn: () =>
      localAppRequest<{ items: InterventionCandidate[] }>(
        "teaching.interventions",
      ),
    onSuccess: (value) => setInterventions(value.items),
  });
  const updateIntervention = useMutation({
    mutationFn: (value: {
      candidateId: string;
      status: InterventionCandidate["status"];
    }) => localAppRequest("teaching.intervention.update", value),
    onSuccess: () => loadInterventions.mutate(),
  });
  // 挂载即加载干预队列，让折叠区外的“待处理 N”徽章有数据。
  useEffect(() => {
    loadInterventions.mutate();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  if (query.isPending) return <Loading label="正在读取本地题库与学情" />;
  if (query.isError)
    return (
      <Feedback tone="error" title="教学工作台不可用">
        <p>{query.error.message}</p>
      </Feedback>
    );
  const data = query.data;
  const filteredExercises = data.exercises.filter((item) =>
    `${item.title} ${item.knowledgePoint}`
      .toLowerCase()
      .includes(exerciseQuery.trim().toLowerCase()),
  );
  const exercisePageSize = 8;
  const exercisePages = Math.max(
    1,
    Math.ceil(filteredExercises.length / exercisePageSize),
  );
  const visibleExercises = filteredExercises.slice(
    exercisePage * exercisePageSize,
    (exercisePage + 1) * exercisePageSize,
  );
  const progressPageSize = 8;
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
              render: (row) => knowledgePointLabel(row.knowledgePoint),
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
                <Button
                  variant="secondary"
                  busy={toggle.isPending}
                  onClick={() => toggle.mutate(row)}
                >
                  {row.enabled ? "停用" : "启用"}
                </Button>
              ),
            },
          ]}
        />
        {filteredExercises.length > exercisePageSize && (
          <div className="compact-pager">
            <Button
              variant="secondary"
              disabled={exercisePage === 0}
              onClick={() => setExercisePage((value) => value - 1)}
            >
              上一页
            </Button>
            <span>
              第 {exercisePage + 1} / {exercisePages} 页
            </span>
            <Button
              variant="secondary"
              disabled={exercisePage + 1 >= exercisePages}
              onClick={() => setExercisePage((value) => value + 1)}
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
          <FormField label="参考 SQL">
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
          label="题库包 JSON"
          hint="导出结果可复制保存；导入前由 Java 校验版本与内容。"
        >
          {(ids) => (
            <textarea
              {...ids}
              value={packageJson}
              onChange={(event) => setPackageJson(event.target.value)}
            />
          )}
        </FormField>
        <Button
          variant="secondary"
          disabled={!packageJson.trim()}
          busy={importExercises.isPending}
          onClick={() => importExercises.mutate()}
        >
          导入题库包
        </Button>
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
          if (event.currentTarget.open) loadAnalytics.mutate();
        }}
      >
        <summary>
          <strong>完整学情分析</strong>
        </summary>
        {analytics && (
          <>
            <p>生成时间：{formatAccountDate(analytics.generatedAt)}</p>
            <div className="metric-row">
              {Object.entries(analytics.overview).map(([key, value]) => (
                <Metric
                  key={key}
                  label={analyticsMetricLabel(key)}
                  value={value}
                />
              ))}
            </div>
            <p>
              题目统计 {analytics.exercises.length} 项，知识点统计{" "}
              {analytics.knowledgePoints.length} 项，常见错误{" "}
              {analytics.commonErrors.length} 项。
            </p>
          </>
        )}
      </details>
      <details
        className="content-card"
        onToggle={(event) => {
          if (event.currentTarget.open) loadInterventions.mutate();
        }}
      >
        <summary>
          <strong>
            教师干预队列
            {interventions.length > 0 && (
              <span className="policy-chip">待处理 {interventions.length}</span>
            )}
          </strong>
        </summary>
        {interventions.length === 0 ? (
          <p className="muted">暂无待处理干预。</p>
        ) : (
          <ul className="plain-list">
            {interventions.map((item) => (
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

export function CloudPage() {
  const client = useQueryClient();
  const navigate = useNavigate();
  const toast = useToast();
  const [className, setClassName] = useState("");
  const [classroomId, setClassroomId] = useState("");
  const [memberEmail, setMemberEmail] = useState("");
  const [memberRole, setMemberRole] = useState("STUDENT");
  const [assignments, setAssignments] = useState<CloudAssignment[]>([]);
  const [pendingTransition, setPendingTransition] = useState<{
    item: CloudAssignment;
    next: CloudAssignment["status"];
  }>();
  const [assignmentTitle, setAssignmentTitle] = useState("");
  const [assignmentExerciseId, setAssignmentExerciseId] = useState("");
  const [assignmentDescription, setAssignmentDescription] = useState("");
  const [assignmentDueAt, setAssignmentDueAt] = useState("");
  const [sessions, setSessions] = useState<ActiveSession[]>([]);
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [accountMessage, setAccountMessage] = useState("");
  const [exportTaskId, setExportTaskId] = useState("");
  const [analyticsResult, setAnalyticsResult] =
    useState<Record<string, unknown>>();
  const [feedbackItems, setFeedbackItems] = useState<SubmissionFeedback[]>([]);
  const [feedbackDirtyIds, setFeedbackDirtyIds] = useState<string[]>([]);
  const [feedbackAssignmentId, setFeedbackAssignmentId] = useState("");
  const [masteryItems, setMasteryItems] = useState<KnowledgeMastery[]>([]);
  const [portfolioItems, setPortfolioItems] = useState<PortfolioEntry[]>([]);
  const [courses, setCourses] = useState<CloudCourse[]>([]);
  const [courseId, setCourseId] = useState("");
  const [courseContent, setCourseContent] = useState<CloudCourseContent>();
  const [courseName, setCourseName] = useState("");
  const [courseDescription, setCourseDescription] = useState("");
  const [sectionName, setSectionName] = useState("");
  const [knowledgeName, setKnowledgeName] = useState("");
  const [knowledgeDescription, setKnowledgeDescription] = useState("");
  const [knowledgeSectionId, setKnowledgeSectionId] = useState("");
  const [sharedLocalExerciseId, setSharedLocalExerciseId] = useState("");
  const [sharedKnowledgePointId, setSharedKnowledgePointId] = useState("");
  const [coursePackage, setCoursePackage] = useState("");
  const [packagePreview, setPackagePreview] = useState<CoursePackagePreview>();
  const [analyticsStatus, setAnalyticsStatus] = useState("");
  const [analyticsFrom, setAnalyticsFrom] = useState("");
  const [analyticsTo, setAnalyticsTo] = useState("");
  const query = useQuery({
    queryKey: cloudKey,
    queryFn: () => localAppRequest<CloudWorkspace>("cloud.workspace"),
    staleTime: 15_000,
  });
  const refresh = useMutation({
    mutationFn: () =>
      localAppRequest<CloudWorkspace>("cloud.workspace", {
        refreshRemote: true,
      }),
    onSuccess: (data) => client.setQueryData(cloudKey, data),
  });
  const sync = useMutation({
    mutationFn: () =>
      localAppRequest<{ uploaded: number; downloaded: number }>("cloud.sync"),
    onSuccess: () => void client.invalidateQueries({ queryKey: cloudKey }),
  });
  const logout = useMutation({
    mutationFn: () => localAppRequest("account.logout"),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: ["session", "current"] });
      await client.invalidateQueries({ queryKey: cloudKey });
    },
  });
  const createClass = useMutation({
    mutationFn: () =>
      localAppRequest("cloud.class.create", { name: className }),
    onSuccess: async () => {
      setClassName("");
      toast("success", `班级「${className}」已创建`);
      const refreshed = await localAppRequest<CloudWorkspace>(
        "cloud.workspace",
        { refreshRemote: true },
      );
      client.setQueryData(cloudKey, refreshed);
    },
    onError: (error: Error) => toast("error", `班级创建失败：${error.message}`),
  });
  const exercises = useQuery({
    queryKey: ["cloud", "exercise-catalog"],
    queryFn: () =>
      localAppRequest<{ items: ExerciseSummary[] }>("practice.catalog"),
    enabled: Boolean(query.data?.signedIn),
  });
  const loadAssignments = useMutation({
    mutationFn: (id: string) =>
      localAppRequest<{ items: CloudAssignment[] }>("cloud.assignments", {
        classroomId: id,
      }),
    onSuccess: (value) => setAssignments(value.items),
  });
  const addMember = useMutation({
    mutationFn: () =>
      localAppRequest("cloud.class.member.add", {
        classroomId,
        email: memberEmail,
        role: memberRole,
      }),
    onSuccess: async () => {
      setMemberEmail("");
      toast("success", "成员已添加");
      const refreshed = await localAppRequest<CloudWorkspace>(
        "cloud.workspace",
        { refreshRemote: true },
      );
      client.setQueryData(cloudKey, refreshed);
    },
    onError: (error: Error) => toast("error", `添加成员失败：${error.message}`),
  });
  const createAssignment = useMutation<CloudAssignment, Error, boolean>({
    mutationFn: () =>
      localAppRequest<CloudAssignment>("cloud.assignment.create", {
        classroomId,
        exerciseId: assignmentExerciseId,
        title: assignmentTitle,
        description: assignmentDescription,
        dueAt: assignmentDueAt ? new Date(assignmentDueAt).toISOString() : "",
      }),
    onSuccess: (created, publish) => {
      setAssignmentTitle("");
      setAssignmentDescription("");
      setAssignmentDueAt("");
      loadAssignments.mutate(classroomId);
      if (publish) {
        changeAssignmentStatus.mutate({ ...created, next: "PUBLISHED" });
        toast("success", `任务「${created.title}」已创建并发布，学生端立即可见`);
      } else {
        toast("success", `任务「${created.title}」已保存为草稿`);
      }
    },
    onError: (error: Error) => toast("error", `任务创建失败：${error.message}`),
  });
  const changeAssignmentStatus = useMutation({
    mutationFn: (item: CloudAssignment & { next: CloudAssignment["status"] }) =>
      localAppRequest("cloud.assignment.status", {
        classroomId,
        assignmentId: item.id,
        status: item.next,
        expectedVersion: item.version,
      }),
    onSuccess: (_value, item) => {
      loadAssignments.mutate(classroomId);
      toast(
        "success",
        `任务「${item.title}」状态已更新为「${assignmentStatusLabel(item.next)}」`,
      );
    },
    onError: (error: Error) => toast("error", `状态变更失败：${error.message}`),
  });
  const copyAssignment = useMutation({
    mutationFn: (item: CloudAssignment) =>
      localAppRequest("cloud.assignment.copy", {
        classroomId,
        assignmentId: item.id,
        title: `${item.title} - 副本`,
      }),
    onSuccess: () => {
      loadAssignments.mutate(classroomId);
      toast("success", "已复制任务草稿");
    },
    onError: (error: Error) => toast("error", `复制失败：${error.message}`),
  });
  const classAnalytics = useMutation({
    mutationFn: () =>
      localAppRequest<Record<string, unknown>>("cloud.class.analytics", {
        classroomId,
      }),
    onSuccess: setAnalyticsResult,
  });
  const assignmentAnalytics = useMutation({
    mutationFn: (assignmentId: string) =>
      localAppRequest<Record<string, unknown>>("cloud.assignment.analytics", {
        classroomId,
        assignmentId,
        status: analyticsStatus,
        from: analyticsFrom ? new Date(analyticsFrom).toISOString() : "",
        to: analyticsTo ? new Date(analyticsTo).toISOString() : "",
      }),
    onSuccess: setAnalyticsResult,
  });
  const loadFeedback = useMutation({
    mutationFn: (assignmentId: string) =>
      localAppRequest<{ items: SubmissionFeedback[]; cached: boolean }>(
        "cloud.feedback.list",
        {
          classroomId,
          assignmentId,
          refreshRemote: true,
        },
      ),
    onSuccess: (value, assignmentId) => {
      setFeedbackAssignmentId(assignmentId);
      setFeedbackItems(value.items);
    },
  });
  const saveFeedback = useMutation({
    mutationFn: (item: SubmissionFeedback) =>
      localAppRequest<SubmissionFeedback>("cloud.feedback.save", {
        classroomId,
        assignmentId: item.assignmentId,
        submissionId: item.submissionId,
        status: item.status,
        comment: item.comment,
        knowledgePointIds: item.knowledgePointIds,
        expectedVersion: item.version,
      }),
    onSuccess: (saved) => {
      setFeedbackItems((items) =>
        items.map((item) =>
          item.submissionId === saved.submissionId ? saved : item,
        ),
      );
      setFeedbackDirtyIds((ids) =>
        ids.filter((id) => id !== saved.submissionId),
      );
      toast("success", "反馈已保存，学生端可见");
    },
    onError: (error: Error) => toast("error", `反馈保存失败：${error.message}`),
  });
  const loadMastery = useMutation({
    mutationFn: () =>
      localAppRequest<{ items: KnowledgeMastery[]; cached: boolean }>(
        "cloud.mastery",
        {
          classroomId,
          refreshRemote: true,
        },
      ),
    onSuccess: (value) => setMasteryItems(value.items),
  });
  const loadPortfolio = useMutation({
    mutationFn: () =>
      localAppRequest<{ items: PortfolioEntry[] }>("learning.portfolio"),
    onSuccess: (value) => setPortfolioItems(value.items),
  });
  const exportPortfolio = useMutation({
    mutationFn: () =>
      localAppRequest<{ content: string }>("learning.portfolio.export", {
        confirmed: true,
      }),
    onSuccess: (value) =>
      downloadText("sqlteacher-portfolio.json", value.content),
  });
  const loadCourses = useMutation({
    mutationFn: () =>
      localAppRequest<{ items: CloudCourse[]; cached: boolean }>(
        "cloud.courses",
        { refreshRemote: true },
      ),
    onSuccess: (value) => {
      setCourses(value.items);
      if (!courseId && value.items[0]) setCourseId(value.items[0].id);
    },
  });
  const createCourse = useMutation({
    mutationFn: () =>
      localAppRequest<CloudCourse>("cloud.course.create", {
        name: courseName,
        description: courseDescription,
      }),
    onSuccess: (value) => {
      setCourseName("");
      setCourseDescription("");
      setCourseId(value.id);
      loadCourses.mutate();
    },
  });
  const loadCourseContent = useMutation({
    mutationFn: () =>
      localAppRequest<CloudCourseContent>("cloud.course.content", {
        courseId,
        refreshRemote: true,
      }),
    onSuccess: (value) => {
      setCourseContent(value);
      if (!knowledgeSectionId && value.sections[0])
        setKnowledgeSectionId(value.sections[0].id);
    },
  });
  const createSection = useMutation({
    mutationFn: () =>
      localAppRequest("cloud.course.section.create", {
        courseId,
        name: sectionName,
        sortOrder: courseContent?.sections.length ?? 0,
      }),
    onSuccess: () => {
      setSectionName("");
      loadCourseContent.mutate();
    },
  });
  const createKnowledgePoint = useMutation({
    mutationFn: () =>
      localAppRequest("cloud.course.knowledge.create", {
        courseId,
        sectionId: knowledgeSectionId,
        name: knowledgeName,
        description: knowledgeDescription,
        sortOrder: courseContent?.knowledgePoints.length ?? 0,
      }),
    onSuccess: () => {
      setKnowledgeName("");
      setKnowledgeDescription("");
      loadCourseContent.mutate();
    },
  });
  const publishSharedExercise = useMutation({
    mutationFn: async () => {
      const exercise = await localAppRequest<ExerciseDefinition>(
        "teaching.exercise.detail",
        { exerciseId: sharedLocalExerciseId },
      );
      return localAppRequest("cloud.course.exercise.publish", {
        courseId,
        exerciseId: exercise.id,
        title: exercise.title,
        prompt: exercise.description,
        datasetVersion: `${exercise.datasetId}@${exercise.version}`,
        evaluationRule: JSON.stringify(exercise.evaluationRule),
        knowledgePointIds: sharedKnowledgePointId
          ? [sharedKnowledgePointId]
          : [],
      });
    },
    onSuccess: () => loadCourseContent.mutate(),
  });
  const createVersionedAssignment = useMutation({
    mutationFn: (exerciseVersionId: string) =>
      localAppRequest("cloud.assignment.create-versioned", {
        classroomId,
        exerciseVersionId,
        title:
          assignmentTitle ||
          courseContent?.exercises.find((item) => item.id === exerciseVersionId)
            ?.title,
        description: assignmentDescription,
        dueAt: assignmentDueAt ? new Date(assignmentDueAt).toISOString() : "",
      }),
    onSuccess: () => loadAssignments.mutate(classroomId),
  });
  const exportCourse = useMutation({
    mutationFn: () =>
      localAppRequest<{ content: string }>("cloud.course.export", { courseId }),
    onSuccess: (value) =>
      downloadText(`sqlteacher-course-${courseId}.json`, value.content),
  });
  const previewCoursePackage = useMutation({
    mutationFn: () =>
      localAppRequest<CoursePackagePreview>("cloud.course.package.preview", {
        content: coursePackage,
      }),
    onSuccess: setPackagePreview,
  });
  const importCoursePackage = useMutation({
    mutationFn: () =>
      localAppRequest("cloud.course.package.import", {
        content: coursePackage,
        expectedSha256: packagePreview?.contentSha256,
        licenseConfirmed: true,
      }),
    onSuccess: () => {
      setCoursePackage("");
      setPackagePreview(undefined);
      loadCourses.mutate();
    },
  });
  const exportClassAnalytics = useMutation({
    mutationFn: () =>
      localAppRequest<{ csv: string }>("cloud.class.analytics.export", {
        classroomId,
      }),
    onSuccess: (value) =>
      downloadText(`class-${classroomId}-analytics.csv`, value.csv),
  });
  const exportAssignmentAnalytics = useMutation({
    mutationFn: (assignmentId: string) =>
      localAppRequest<{ csv: string }>("cloud.assignment.analytics.export", {
        classroomId,
        assignmentId,
        status: analyticsStatus,
        from: analyticsFrom ? new Date(analyticsFrom).toISOString() : "",
        to: analyticsTo ? new Date(analyticsTo).toISOString() : "",
      }),
    onSuccess: (value, assignmentId) =>
      downloadText(`assignment-${assignmentId}-analytics.csv`, value.csv),
  });
  const loadSessions = useMutation({
    mutationFn: () =>
      localAppRequest<{ items: ActiveSession[] }>("account.sessions"),
    onSuccess: (value) => setSessions(value.items),
  });
  const revokeSession = useMutation({
    mutationFn: (sessionId: string) =>
      localAppRequest("account.session.revoke", { sessionId }),
    onSuccess: () => loadSessions.mutate(),
  });
  const changePassword = useMutation({
    mutationFn: () =>
      localAppRequest("account.password.change", {
        currentPassword,
        newPassword,
      }),
    onSuccess: () => {
      setCurrentPassword("");
      setNewPassword("");
      setAccountMessage("密码已修改，其他会话将按服务器策略处理。");
      toast("success", "密码已修改");
    },
    onError: (error: Error) => {
      // 失败时保留输入，用户可直接修改后重试。
      setAccountMessage(`密码修改失败：${error.message}`);
      toast("error", `密码修改失败：${error.message}`);
    },
  });
  const requestExport = useMutation({
    mutationFn: () =>
      localAppRequest<Record<string, unknown>>("account.export.request"),
    onSuccess: (value) => {
      const id = String(value.id ?? value.taskId ?? "");
      setExportTaskId(id);
      setAccountMessage(`数据导出任务已创建：${id || "请稍后刷新"}`);
    },
  });
  const getExport = useMutation({
    mutationFn: () =>
      localAppRequest<unknown>("account.export.get", { taskId: exportTaskId }),
    onSuccess: (value) =>
      downloadJson(`sqlteacher-account-export-${exportTaskId}.json`, value),
  });
  const requestDeletion = useMutation({
    mutationFn: () =>
      localAppRequest<Record<string, unknown>>("account.deletion.request"),
    onSuccess: (value) =>
      setAccountMessage(
        `账号删除已进入撤销期：${String(value.status ?? "PENDING")}`,
      ),
  });
  const cancelDeletion = useMutation({
    mutationFn: () => localAppRequest("account.deletion.cancel"),
    onSuccess: () => setAccountMessage("账号删除已取消。"),
  });
  const deletionStatus = useMutation({
    mutationFn: () =>
      localAppRequest<Record<string, unknown>>("account.deletion.status"),
    onSuccess: (value) =>
      setAccountMessage(`账号删除状态：${String(value.status ?? "NONE")}`),
  });
  useEffect(() => {
    if (!classroomId && query.data?.classes[0]) {
      setClassroomId(query.data.classes[0].id);
      loadAssignments.mutate(query.data.classes[0].id);
    }
  }, [classroomId, query.data?.classes]);
  if (query.isPending) return <Loading label="正在读取账号与同步队列" />;
  if (query.isError)
    return (
      <Feedback tone="error" title="云端状态不可用">
        <p>{query.error.message}</p>
      </Feedback>
    );
  const data = query.data;
  if (!data.signedIn)
    return (
      <section className="content-card cloud-signin-empty">
        <span className="cloud-signin-icon" aria-hidden="true">
          ☁
        </span>
        <p className="eyebrow">SQLTeacher Cloud</p>
        <h2>连接你的班级与学习记录</h2>
        <p>登录后同步班级与学习进度；离线功能无需登录。</p>
        <div className="button-row">
          <Button onClick={() => navigate("/login?returnTo=%2Fcloud")}>
            登录或创建账号
          </Button>
          <Button variant="secondary" onClick={() => navigate("/today")}>
            继续离线学习
          </Button>
        </div>
      </section>
    );
  return (
    <div className="platform-workspace page-grid">
      <section className="hero-card">
        <div>
          <p className="eyebrow">账号与同步</p>
          <h2>{data.displayName ?? "云端账号"}</h2>
          <p>{data.message}</p>
        </div>
        <div className="button-row">
          <Button
            variant="secondary"
            busy={refresh.isPending}
            onClick={() => refresh.mutate()}
          >
            刷新班级
          </Button>
          <Button busy={sync.isPending} onClick={() => sync.mutate()}>
            立即同步
          </Button>
          <Button
            variant="secondary"
            busy={logout.isPending}
            onClick={() => logout.mutate()}
          >
            退出登录
          </Button>
        </div>
      </section>
      {data.state === "DEGRADED" && (
        <Feedback tone="warning" title="云端连接降级">
          <p>本地学习不受影响，可稍后手动重试。</p>
        </Feedback>
      )}
      <section className="metric-row">
        <Metric label="班级" value={data.classes.length} />
        <Metric label="同步状态" value={syncStateLabel(data.sync.state)} />
        <Metric label="待同步" value={data.sync.pending} />
        <Metric label="重试次数" value={data.sync.attempt} />
      </section>
      <section className="content-card">
        <div className="section-heading">
          <div>
            <h2>可见班级</h2>
          </div>
          {(data.role === "TEACHER" || data.role === "ADMINISTRATOR") && (
            <div className="button-row">
              <input
                aria-label="新班级名称"
                value={className}
                onChange={(event) => setClassName(event.target.value)}
                placeholder="新班级名称"
              />
              <Button
                busy={createClass.isPending}
                disabled={!className.trim()}
                onClick={() => createClass.mutate()}
              >
                创建班级
              </Button>
            </div>
          )}
        </div>
        {data.classes.length === 0 ? (
          <p className="muted">尚未从云端刷新班级。</p>
        ) : (
          <ul className="plain-list">
            {data.classes.map((item) => (
              <li key={item.id}>
                <button
                  type="button"
                  className="table-link"
                  onClick={() => {
                    setClassroomId(item.id);
                    loadAssignments.mutate(item.id);
                  }}
                >
                  <strong>{item.name}</strong>
                </button>
                <span>{item.members.length} 名成员</span>
              </li>
            ))}
          </ul>
        )}
      </section>
      {classroomId && (
        <section className="content-card class-assignments">
          <div className="section-heading">
            <h2>班级任务</h2>
            <span className="policy-chip">{assignments.length} 项</span>
          </div>
          {(data.role === "TEACHER" || data.role === "ADMINISTRATOR") && (
            <details>
              <summary>
                <strong>添加成员与创建任务</strong>
              </summary>
              <div className="settings-grid">
                <FormField label="成员邮箱">
                  {(ids) => (
                    <input
                      {...ids}
                      type="email"
                      value={memberEmail}
                      onChange={(event) => setMemberEmail(event.target.value)}
                    />
                  )}
                </FormField>
                <FormField label="成员角色">
                  {(ids) => (
                    <select
                      {...ids}
                      value={memberRole}
                      onChange={(event) => setMemberRole(event.target.value)}
                    >
                      <option value="STUDENT">学生</option>
                      <option value="TEACHER">教师</option>
                    </select>
                  )}
                </FormField>
              </div>
              <Button
                variant="secondary"
                disabled={!memberEmail}
                busy={addMember.isPending}
                onClick={() => addMember.mutate()}
              >
                添加成员
              </Button>
              <div className="settings-grid">
                <FormField label="任务标题">
                  {(ids) => (
                    <input
                      {...ids}
                      value={assignmentTitle}
                      onChange={(event) =>
                        setAssignmentTitle(event.target.value)
                      }
                    />
                  )}
                </FormField>
                <FormField label="练习">
                  {(ids) => (
                    <select
                      {...ids}
                      value={assignmentExerciseId}
                      onChange={(event) =>
                        setAssignmentExerciseId(event.target.value)
                      }
                    >
                      <option value="">选择练习</option>
                      {exercises.data?.items
                        .filter((item) => item.enabled)
                        .map((item) => (
                          <option key={item.id} value={item.id}>
                            {item.title}
                          </option>
                        ))}
                    </select>
                  )}
                </FormField>
                <FormField label="截止时间">
                  {(ids) => (
                    <input
                      {...ids}
                      type="datetime-local"
                      value={assignmentDueAt}
                      onChange={(event) =>
                        setAssignmentDueAt(event.target.value)
                      }
                    />
                  )}
                </FormField>
                <FormField label="任务说明">
                  {(ids) => (
                    <textarea
                      {...ids}
                      value={assignmentDescription}
                      onChange={(event) =>
                        setAssignmentDescription(event.target.value)
                      }
                    />
                  )}
                </FormField>
              </div>
              <div className="button-row">
                <Button
                  disabled={!assignmentTitle || !assignmentExerciseId}
                  busy={createAssignment.isPending}
                  onClick={() => createAssignment.mutate(true)}
                >
                  创建并发布
                </Button>
                <Button
                  variant="secondary"
                  disabled={
                    !assignmentTitle ||
                    !assignmentExerciseId ||
                    createAssignment.isPending
                  }
                  onClick={() => createAssignment.mutate(false)}
                >
                  存为草稿
                </Button>
              </div>
            </details>
          )}
          {(data.role === "TEACHER" || data.role === "ADMINISTRATOR") && (
            <div className="button-row class-actions">
              <Button
                variant="secondary"
                busy={classAnalytics.isPending}
                onClick={() => classAnalytics.mutate()}
              >
                班级学情
              </Button>
              <Button
                variant="secondary"
                busy={exportClassAnalytics.isPending}
                onClick={() => exportClassAnalytics.mutate()}
              >
                导出班级学情
              </Button>
            </div>
          )}
          {(data.role === "TEACHER" || data.role === "ADMINISTRATOR") && (
            <div className="settings-grid analytics-filters">
              <FormField label="提交状态">
                {(ids) => (
                  <select
                    {...ids}
                    value={analyticsStatus}
                    onChange={(event) => setAnalyticsStatus(event.target.value)}
                  >
                    <option value="">全部</option>
                    <option value="NOT_SUBMITTED">未提交</option>
                    <option value="SUBMITTED">已提交</option>
                    <option value="PASSED">已通过</option>
                    <option value="FAILED">未通过</option>
                  </select>
                )}
              </FormField>
              <FormField label="开始时间">
                {(ids) => (
                  <input
                    {...ids}
                    type="datetime-local"
                    value={analyticsFrom}
                    onChange={(event) => setAnalyticsFrom(event.target.value)}
                  />
                )}
              </FormField>
              <FormField label="结束时间">
                {(ids) => (
                  <input
                    {...ids}
                    type="datetime-local"
                    value={analyticsTo}
                    onChange={(event) => setAnalyticsTo(event.target.value)}
                  />
                )}
              </FormField>
            </div>
          )}
          <ul className="plain-list">
            {assignments.map((item) => (
              <li key={item.id}>
                <strong>{item.title}</strong>
                <span>
                  {assignmentStatusLabel(item.status)}
                  {item.dueAt
                    ? ` · 截止 ${new Date(item.dueAt).toLocaleString()}`
                    : ""}
                </span>
                {data.role === "STUDENT" && item.status === "PUBLISHED" && (
                  <Button
                    onClick={() =>
                      navigate(
                        `/practice?exercise=${encodeURIComponent(item.exerciseId)}&classroom=${encodeURIComponent(classroomId)}&assignment=${encodeURIComponent(item.id)}&assignmentTitle=${encodeURIComponent(item.title)}`,
                      )
                    }
                  >
                    开始任务
                  </Button>
                )}
                {data.role === "STUDENT" && item.status === "CLOSED" && (
                  <span className="policy-chip">任务已截止</span>
                )}
                {(data.role === "TEACHER" || data.role === "ADMINISTRATOR") && (
                  <>
                    <Button
                      variant="secondary"
                      onClick={() => assignmentAnalytics.mutate(item.id)}
                    >
                      查看学情
                    </Button>
                    <Button
                      variant="secondary"
                      busy={exportAssignmentAnalytics.isPending}
                      onClick={() => exportAssignmentAnalytics.mutate(item.id)}
                    >
                      导出 CSV
                    </Button>
                    <Button
                      variant="secondary"
                      onClick={() => loadFeedback.mutate(item.id)}
                    >
                      批阅反馈
                    </Button>
                    <Button
                      variant="secondary"
                      onClick={() => copyAssignment.mutate(item)}
                    >
                      复制
                    </Button>
                    <select
                      aria-label={`${item.title} 状态变更`}
                      value=""
                      onChange={(event) => {
                        const next = event.target
                          .value as CloudAssignment["status"];
                        if (next) setPendingTransition({ item, next });
                      }}
                    >
                      <option value="">更改状态…</option>
                      <option value="DRAFT">转为草稿</option>
                      <option value="PUBLISHED">发布</option>
                      <option value="CLOSED">关闭（学生停止提交）</option>
                      <option value="WITHDRAWN">撤回</option>
                      <option value="ARCHIVED">归档</option>
                    </select>
                  </>
                )}
                {data.role === "STUDENT" && (
                  <Button
                    variant="secondary"
                    onClick={() => loadFeedback.mutate(item.id)}
                  >
                    查看反馈
                  </Button>
                )}
              </li>
            ))}
          </ul>
          <Dialog
            open={Boolean(pendingTransition)}
            title="确认变更任务状态"
            onClose={() => setPendingTransition(undefined)}
          >
            <p>
              将任务「{pendingTransition?.item.title}」从「
              {assignmentStatusLabel(
                pendingTransition?.item.status ?? "DRAFT",
              )}
              」变更为「
              {assignmentStatusLabel(pendingTransition?.next ?? "DRAFT")}」？
              发布后学生立即可见并提交；关闭或撤回后学生无法继续提交。
            </p>
            <div className="button-row">
              <Button
                variant="secondary"
                onClick={() => setPendingTransition(undefined)}
              >
                取消
              </Button>
              <Button
                busy={changeAssignmentStatus.isPending}
                onClick={() => {
                  if (pendingTransition)
                    changeAssignmentStatus.mutate({
                      ...pendingTransition.item,
                      next: pendingTransition.next,
                    });
                  setPendingTransition(undefined);
                }}
              >
                确认变更
              </Button>
            </div>
          </Dialog>
          {analyticsResult && (
            <pre className="help-content">
              {JSON.stringify(analyticsResult, null, 2)}
            </pre>
          )}
          <div className="button-row class-actions">
            <Button
              variant="secondary"
              busy={loadMastery.isPending}
              onClick={() => loadMastery.mutate()}
            >
              {data.role === "STUDENT" ? "我的掌握度" : "当前账号掌握度"}
            </Button>
          </div>
          {masteryItems.length > 0 && (
            <ul className="plain-list">
              {masteryItems.map((item) => (
                <li key={item.knowledgePointId}>
                  <strong>{item.knowledgePointName}</strong>
                  <span>
                    {item.masteryPercent}% · {item.passes}/{item.attempts}{" "}
                    次通过
                  </span>
                </li>
              ))}
            </ul>
          )}
          {feedbackAssignmentId && (
            <section className="account-section">
              <h3>任务反馈</h3>
              {feedbackItems.length === 0 ? (
                <p className="muted">暂无反馈。</p>
              ) : (
                <ul className="plain-list">
                  {feedbackItems.map((item) => (
                    <li key={item.submissionId}>
                      <strong>{item.studentUserId}</strong>
                      {data.role === "TEACHER" ||
                      data.role === "ADMINISTRATOR" ? (
                        <>
                          <select
                            aria-label="反馈状态"
                            value={item.status}
                            onChange={(event) =>
                              setFeedbackItems((items) =>
                                items.map((candidate) =>
                                  candidate.submissionId === item.submissionId
                                    ? {
                                        ...candidate,
                                        status: event.target
                                          .value as SubmissionFeedback["status"],
                                      }
                                    : candidate,
                                ),
                              )
                            }
                          >
                            <option value="NEEDS_WORK">需要改进</option>
                            <option value="REVIEWED">已批阅</option>
                            <option value="RESOLVED">已解决</option>
                          </select>
                          <textarea
                            aria-label="反馈内容"
                            value={item.comment}
                            onChange={(event) => {
                              setFeedbackItems((items) =>
                                items.map((candidate) =>
                                  candidate.submissionId === item.submissionId
                                    ? {
                                        ...candidate,
                                        comment: event.target.value,
                                      }
                                    : candidate,
                                ),
                              );
                              setFeedbackDirtyIds((ids) =>
                                ids.includes(item.submissionId)
                                  ? ids
                                  : [...ids, item.submissionId],
                              );
                            }}
                          />
                          <Button
                            busy={saveFeedback.isPending}
                            onClick={() => saveFeedback.mutate(item)}
                          >
                            {feedbackDirtyIds.includes(item.submissionId)
                              ? "保存反馈 ·"
                              : "保存反馈"}
                          </Button>
                        </>
                      ) : (
                        <>
                          <span>{feedbackStatusLabel(item.status)}</span>
                          <p>{item.comment || "教师尚未填写评语。"}</p>
                        </>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </section>
          )}
        </section>
      )}
      {(data.role === "TEACHER" || data.role === "ADMINISTRATOR") && (
        <details
          className="content-card"
          onToggle={(event) => {
            if (event.currentTarget.open && courses.length === 0)
              loadCourses.mutate();
          }}
        >
          <summary>
            <strong>共享课程、知识点与版本化任务</strong>
          </summary>
          <p className="muted">
          </p>
          <div className="settings-grid">
            <FormField label="课程">
              {(ids) => (
                <select
                  {...ids}
                  value={courseId}
                  onChange={(event) => {
                    setCourseId(event.target.value);
                    setCourseContent(undefined);
                  }}
                >
                  <option value="">选择课程</option>
                  {courses.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.name}
                    </option>
                  ))}
                </select>
              )}
            </FormField>
            <FormField label="新课程名称">
              {(ids) => (
                <input
                  {...ids}
                  value={courseName}
                  onChange={(event) => setCourseName(event.target.value)}
                />
              )}
            </FormField>
            <FormField label="课程说明">
              {(ids) => (
                <input
                  {...ids}
                  value={courseDescription}
                  onChange={(event) => setCourseDescription(event.target.value)}
                />
              )}
            </FormField>
          </div>
          <div className="button-row">
            <Button
              busy={loadCourses.isPending}
              onClick={() => loadCourses.mutate()}
            >
              刷新课程
            </Button>
            <Button
              disabled={!courseName.trim()}
              busy={createCourse.isPending}
              onClick={() => createCourse.mutate()}
            >
              创建课程
            </Button>
            <Button
              variant="secondary"
              disabled={!courseId}
              busy={loadCourseContent.isPending}
              onClick={() => loadCourseContent.mutate()}
            >
              打开课程
            </Button>
            <Button
              variant="secondary"
              disabled={!courseId}
              busy={exportCourse.isPending}
              onClick={() => exportCourse.mutate()}
            >
              导出课程包
            </Button>
          </div>
          {courseContent && (
            <>
              <div className="settings-grid">
                <FormField label="新章节">
                  {(ids) => (
                    <input
                      {...ids}
                      value={sectionName}
                      onChange={(event) => setSectionName(event.target.value)}
                    />
                  )}
                </FormField>
                <FormField label="知识点所在章节">
                  {(ids) => (
                    <select
                      {...ids}
                      value={knowledgeSectionId}
                      onChange={(event) =>
                        setKnowledgeSectionId(event.target.value)
                      }
                    >
                      <option value="">选择章节</option>
                      {courseContent.sections.map((item) => (
                        <option key={item.id} value={item.id}>
                          {item.name}
                        </option>
                      ))}
                    </select>
                  )}
                </FormField>
                <FormField label="新知识点">
                  {(ids) => (
                    <input
                      {...ids}
                      value={knowledgeName}
                      onChange={(event) => setKnowledgeName(event.target.value)}
                    />
                  )}
                </FormField>
                <FormField label="知识点说明">
                  {(ids) => (
                    <input
                      {...ids}
                      value={knowledgeDescription}
                      onChange={(event) =>
                        setKnowledgeDescription(event.target.value)
                      }
                    />
                  )}
                </FormField>
              </div>
              <div className="button-row">
                <Button
                  variant="secondary"
                  disabled={!sectionName.trim()}
                  busy={createSection.isPending}
                  onClick={() => createSection.mutate()}
                >
                  添加章节
                </Button>
                <Button
                  variant="secondary"
                  disabled={!knowledgeSectionId || !knowledgeName.trim()}
                  busy={createKnowledgePoint.isPending}
                  onClick={() => createKnowledgePoint.mutate()}
                >
                  添加知识点
                </Button>
              </div>
              <div className="settings-grid">
                <FormField label="发布本地题目">
                  {(ids) => (
                    <select
                      {...ids}
                      value={sharedLocalExerciseId}
                      onChange={(event) =>
                        setSharedLocalExerciseId(event.target.value)
                      }
                    >
                      <option value="">选择题目</option>
                      {exercises.data?.items
                        .filter((item) => item.enabled)
                        .map((item) => (
                          <option key={item.id} value={item.id}>
                            {item.title}
                          </option>
                        ))}
                    </select>
                  )}
                </FormField>
                <FormField label="关联知识点">
                  {(ids) => (
                    <select
                      {...ids}
                      value={sharedKnowledgePointId}
                      onChange={(event) =>
                        setSharedKnowledgePointId(event.target.value)
                      }
                    >
                      <option value="">不关联</option>
                      {courseContent.knowledgePoints.map((item) => (
                        <option key={item.id} value={item.id}>
                          {item.name}
                        </option>
                      ))}
                    </select>
                  )}
                </FormField>
              </div>
              <Button
                disabled={!sharedLocalExerciseId}
                busy={publishSharedExercise.isPending}
                onClick={() => publishSharedExercise.mutate()}
              >
                发布为新版本
              </Button>
              <h3>已发布练习版本</h3>
              {courseContent.exercises.length === 0 ? (
                <p className="muted">尚未发布练习版本。</p>
              ) : (
                <ul className="plain-list">
                  {courseContent.exercises.map((item) => (
                    <li key={item.id}>
                      <strong>{item.title}</strong>
                      <span>
                        版本 {item.version} · {item.status}
                      </span>
                      <Button
                        disabled={!classroomId}
                        busy={createVersionedAssignment.isPending}
                        onClick={() =>
                          createVersionedAssignment.mutate(item.id)
                        }
                      >
                        发布到当前班级
                      </Button>
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
          <section className="account-section">
            <h3>安全课程包导入</h3>
            <FormField
              label="课程包 JSON"
              hint="先预览摘要、许可证与冲突，再明确确认导入。"
            >
              {(ids) => (
                <textarea
                  {...ids}
                  value={coursePackage}
                  onChange={(event) => {
                    setCoursePackage(event.target.value);
                    setPackagePreview(undefined);
                  }}
                />
              )}
            </FormField>
            <div className="button-row">
              <Button
                variant="secondary"
                disabled={!coursePackage.trim()}
                busy={previewCoursePackage.isPending}
                onClick={() => previewCoursePackage.mutate()}
              >
                安全预览
              </Button>
              {packagePreview && (
                <Button
                  disabled={packagePreview.conflict === "VERSION_CONFLICT"}
                  busy={importCoursePackage.isPending}
                  onClick={() => importCoursePackage.mutate()}
                >
                  确认许可证并导入
                </Button>
              )}
            </div>
            {packagePreview && (
              <Feedback
                tone={
                  packagePreview.conflict === "VERSION_CONFLICT"
                    ? "warning"
                    : "info"
                }
                title={packagePreview.courseTitle}
              >
                <p>
                  版本 {packagePreview.courseVersion} · 许可证{" "}
                  {packagePreview.license}
                </p>
                <p>
                  {packagePreview.sections} 个章节，
                  {packagePreview.knowledgePoints} 个知识点，
                  {packagePreview.exercises} 个练习。
                </p>
              </Feedback>
            )}
          </section>
        </details>
      )}
      <section className="content-card">
        <div className="section-heading">
          <div>
            <p className="eyebrow">学习成果</p>
            <h2>我的作品集</h2>
          </div>
          <div className="button-row">
            <Button
              variant="secondary"
              busy={loadPortfolio.isPending}
              onClick={() => loadPortfolio.mutate()}
            >
              刷新作品集
            </Button>
            <Button
              variant="secondary"
              busy={exportPortfolio.isPending}
              disabled={portfolioItems.length === 0}
              onClick={() => exportPortfolio.mutate()}
            >
              确认并导出
            </Button>
          </div>
        </div>
        {portfolioItems.length === 0 ? (
          <p className="muted">暂无成果记录。</p>
        ) : (
          <ul className="plain-list">
            {portfolioItems.map((item) => (
              <li key={`${item.activityId}:${item.submissionVersion}`}>
                <strong>{item.title}</strong>
                <span>
                  版本 {item.submissionVersion} · {item.reviewState}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
      <details
        className="content-card account-governance"
        onToggle={(event) => {
          if (event.currentTarget.open) loadSessions.mutate();
        }}
      >
        <summary>
          <strong>账号安全与数据治理</strong>
        </summary>
        <section className="account-section">
          <h3>修改密码</h3>
          <div className="settings-grid">
            <FormField label="当前密码">
              {(ids) => (
                <input
                  {...ids}
                  type="password"
                  autoComplete="current-password"
                  value={currentPassword}
                  onChange={(event) => setCurrentPassword(event.target.value)}
                />
              )}
            </FormField>
            <FormField label="新密码" hint="12-128 个字符">
              {(ids) => (
                <input
                  {...ids}
                  type="password"
                  autoComplete="new-password"
                  value={newPassword}
                  onChange={(event) => setNewPassword(event.target.value)}
                />
              )}
            </FormField>
          </div>
          <div className="button-row">
            <Button
              disabled={!currentPassword || newPassword.length < 12}
              busy={changePassword.isPending}
              onClick={() => changePassword.mutate()}
            >
              修改密码
            </Button>
          </div>
        </section>
        <section className="account-section">
          <h3>登录会话</h3>
          {loadSessions.isPending && <p className="muted">正在读取会话…</p>}
          <ul className="plain-list">
            {sessions.map((item) => (
              <li key={item.id}>
                <strong>
                  {item.current ? "当前会话" : item.userAgent || "其他会话"}
                </strong>
                <span>{formatAccountDate(item.lastSeenAt)}</span>
                {!item.current && (
                  <Button
                    variant="secondary"
                    onClick={() => revokeSession.mutate(item.id)}
                  >
                    撤销
                  </Button>
                )}
              </li>
            ))}
          </ul>
          {!loadSessions.isPending && sessions.length === 0 && (
            <p className="muted">没有可显示的其他会话。</p>
          )}
        </section>
        <section className="account-section danger-zone">
          <h3>数据导出与账号删除</h3>
          <p className="muted">
            导出不会修改账号；删除申请进入可撤销期，请谨慎操作。
          </p>
          <div className="button-row">
            <Button
              variant="secondary"
              busy={requestExport.isPending}
              onClick={() => requestExport.mutate()}
            >
              申请导出我的数据
            </Button>
            {exportTaskId && (
              <Button
                variant="secondary"
                busy={getExport.isPending}
                onClick={() => getExport.mutate()}
              >
                获取导出结果
              </Button>
            )}
            <Button
              variant="danger"
              busy={requestDeletion.isPending}
              onClick={() => requestDeletion.mutate()}
            >
              申请删除账号
            </Button>
            <Button
              variant="secondary"
              busy={deletionStatus.isPending}
              onClick={() => deletionStatus.mutate()}
            >
              查询删除状态
            </Button>
            <Button
              variant="secondary"
              busy={cancelDeletion.isPending}
              onClick={() => cancelDeletion.mutate()}
            >
              取消账号删除
            </Button>
          </div>
        </section>
        {accountMessage && (
          <Feedback tone="info" title="账号状态">
            {accountMessage}
          </Feedback>
        )}
      </details>
    </div>
  );
}

type SettingsDraft = SettingsPreferences["general"] & {
  developerMode: boolean;
};

export function SettingsPage() {
  const client = useQueryClient();
  const query = useQuery({
    queryKey: settingsKey,
    queryFn: () => localAppRequest<SettingsPreferences>("settings.preferences"),
    staleTime: 15_000,
  });
  const environment = useQuery({
    queryKey: ["settings", "environment"],
    queryFn: () => localAppRequest<SettingsEnvironment>("settings.environment"),
    enabled: false,
    retry: false,
  });
  const storage = useQuery({
    queryKey: ["settings", "storage"],
    queryFn: () => localAppRequest<SettingsStorage>("settings.storage"),
    enabled: false,
    retry: false,
  });
  const [draft, setDraft] = useState<SettingsDraft | null>(null);
  const [backups, setBackups] = useState<BackupSnapshot[]>([]);
  const [resetPhrase, setResetPhrase] = useState("");
  const [restoreTarget, setRestoreTarget] = useState<BackupSnapshot>();
  const [helpContent, setHelpContent] = useState("");
  const [updateResult, setUpdateResult] = useState<UpdateCheck>();
  useEffect(() => {
    if (!query.data) return;
    // 上次离开时有未保存的更改会暂存在 sessionStorage，优先恢复，避免静默丢失。
    let stored: SettingsDraft | null = null;
    try {
      stored = JSON.parse(
        sessionStorage.getItem("sqlteacher.settings.draft") ?? "null",
      );
    } catch {
      stored = null;
    }
    setDraft(
      stored ?? {
        ...query.data.general,
        developerMode: query.data.developerMode,
      },
    );
  }, [query.data]);
  const savedPreferencesEarly: SettingsDraft | undefined = query.data
    ? { ...query.data.general, developerMode: query.data.developerMode }
    : undefined;
  const dirtyEarly =
    Boolean(savedPreferencesEarly) &&
    JSON.stringify(savedPreferencesEarly) !== JSON.stringify(draft);
  // dirty 时暂存草稿；保存或放弃后清除。必须位于任何条件返回之前（Hooks 规则）。
  useEffect(() => {
    try {
      if (dirtyEarly && draft)
        sessionStorage.setItem(
          "sqlteacher.settings.draft",
          JSON.stringify(draft),
        );
      else sessionStorage.removeItem("sqlteacher.settings.draft");
    } catch {
      // ignore
    }
  }, [dirtyEarly, draft]);
  const save = useMutation({
    mutationFn: (value: SettingsDraft) =>
      localAppRequest("settings.update", value),
    onSuccess: (_result, value) => {
      void client.invalidateQueries({ queryKey: settingsKey });
      if (query.data?.general.language !== value.language)
        window.location.reload();
    },
  });
  const install = useMutation({
    mutationFn: (componentId: string) =>
      localAppRequest("settings.component.install", { componentId }),
    onSuccess: () => void environment.refetch(),
  });
  const cancelInstall = useMutation({
    mutationFn: (componentId: string) =>
      localAppRequest("settings.component.cancel", { componentId }),
  });
  const loadBackups = useMutation({
    mutationFn: () =>
      localAppRequest<{ items: BackupSnapshot[] }>("settings.backups"),
    onSuccess: (value) => setBackups(value.items),
  });
  const createBackup = useMutation({
    mutationFn: () => localAppRequest<BackupSnapshot>("settings.backup.create"),
    onSuccess: () => loadBackups.mutate(),
  });
  const restoreBackup = useMutation({
    mutationFn: () =>
      localAppRequest("settings.backup.restore", {
        backupId: restoreTarget?.id,
      }),
    onSuccess: () => setRestoreTarget(undefined),
  });
  const restoreDemo = useMutation({
    mutationFn: () => localAppRequest("settings.demo.restore"),
  });
  const resetLearning = useMutation({
    mutationFn: () =>
      localAppRequest("settings.learning.reset", { confirmation: resetPhrase }),
    onSuccess: () => setResetPhrase(""),
  });
  const clearCache = useMutation({
    mutationFn: () =>
      localAppRequest<{ clearedBytes: number }>("settings.cache.clear"),
  });
  const checkUpdate = useMutation({
    mutationFn: () => localAppRequest<UpdateCheck>("settings.update.check"),
    onSuccess: setUpdateResult,
  });
  const loadHelp = useMutation({
    mutationFn: (topicId: string) =>
      localAppRequest<{ content: string }>("settings.help", { topicId }),
    onSuccess: (value) => setHelpContent(value.content),
  });
  if (query.isPending || !draft) return <Loading label="正在读取设置" />;
  if (query.isError)
    return (
      <Feedback tone="error" title="设置不可用">
        <p>{query.error.message}</p>
      </Feedback>
    );
  const data = query.data;
  const toggle = (key: keyof SettingsDraft) =>
    setDraft((value) => (value ? { ...value, [key]: !value[key] } : value));
  const savedPreferences: SettingsDraft | undefined = query.data
    ? { ...query.data.general, developerMode: query.data.developerMode }
    : undefined;
  const dirty =
    Boolean(savedPreferences) &&
    JSON.stringify(savedPreferences) !== JSON.stringify(draft);
  const discardChanges = () => {
    if (savedPreferences) setDraft(savedPreferences);
    try {
      sessionStorage.removeItem("sqlteacher.settings.draft");
    } catch {
      // ignore
    }
  };
  return (
    <div className="platform-workspace settings-workspace">
      <section className="settings-intro">
        <div>
          <p className="eyebrow">个性化设置</p>
          <h2>按你的方式使用 SQLTeacher</h2>
        </div>
        <div className="button-row">
          {dirty && (
            <>
              <span className="policy-chip">有未保存的更改</span>
              <Button variant="secondary" onClick={discardChanges}>
                放弃更改
              </Button>
            </>
          )}
          <Button busy={save.isPending} onClick={() => save.mutate(draft)}>
            保存更改
          </Button>
        </div>
      </section>
      {save.isSuccess && (
        <Feedback tone="success" title="设置已保存" />
      )}
      <section className="content-card settings-card">
        <div className="settings-section-title">
          <span className="settings-symbol">Aa</span>
          <div>
            <h3>外观与使用体验</h3>
            <p>语言、主题和辅助功能</p>
          </div>
        </div>
        <div className="settings-grid">
          <FormField label="界面语言">
            {(ids) => (
              <select
                {...ids}
                value={draft.language}
                onChange={(event) =>
                  setDraft({
                    ...draft,
                    language: event.target.value as SettingsDraft["language"],
                  })
                }
              >
                <option value="zh">简体中文</option>
                <option value="en">English</option>
              </select>
            )}
          </FormField>
          <FormField label="主题">
            {(ids) => (
              <select
                {...ids}
                value={draft.theme}
                onChange={(event) =>
                  setDraft({
                    ...draft,
                    theme: event.target.value as SettingsDraft["theme"],
                  })
                }
              >
                <option value="system">跟随系统</option>
                <option value="light">浅色</option>
                <option value="dark">深色</option>
              </select>
            )}
          </FormField>
          <FormField label="界面字体">
            {(ids) => (
              <select
                {...ids}
                value={draft.font}
                onChange={(event) =>
                  setDraft({
                    ...draft,
                    font: event.target.value as SettingsDraft["font"],
                  })
                }
              >
                <option value="modern">现代中文</option>
                <option value="system">系统默认</option>
                <option value="classic">经典清晰</option>
              </select>
            )}
          </FormField>
          <FormField label="界面密度">
            {(ids) => (
              <select
                {...ids}
                value={draft.density}
                onChange={(event) =>
                  setDraft({
                    ...draft,
                    density: event.target.value as SettingsDraft["density"],
                  })
                }
              >
                <option value="comfortable">舒适</option>
                <option value="compact">紧凑</option>
              </select>
            )}
          </FormField>
          <FormField label="代理模式">
            {(ids) => (
              <select
                {...ids}
                value={draft.proxyMode}
                onChange={(event) =>
                  setDraft({
                    ...draft,
                    proxyMode: event.target.value as SettingsDraft["proxyMode"],
                  })
                }
              >
                <option value="SYSTEM">跟随系统</option>
                <option value="DIRECT">直接连接</option>
                <option value="MANUAL">手动代理</option>
              </select>
            )}
          </FormField>
          {draft.proxyMode === "MANUAL" && (
            <>
              <FormField label="代理主机">
                {(ids) => (
                  <input
                    {...ids}
                    value={draft.proxyHost}
                    onChange={(event) =>
                      setDraft({ ...draft, proxyHost: event.target.value })
                    }
                  />
                )}
              </FormField>
              <FormField label="代理端口">
                {(ids) => (
                  <input
                    {...ids}
                    type="number"
                    min={1}
                    max={65535}
                    value={draft.proxyPort || ""}
                    onChange={(event) =>
                      setDraft({
                        ...draft,
                        proxyPort: Number(event.target.value),
                      })
                    }
                  />
                )}
              </FormField>
            </>
          )}
          <Toggle
            label="自动检查更新"
            checked={draft.automaticUpdateChecks}
            onChange={() => toggle("automaticUpdateChecks")}
          />
          <Toggle
            label="减少动态效果"
            checked={draft.reducedMotion}
            onChange={() => toggle("reducedMotion")}
          />
          <Toggle
            label="高对比度"
            checked={draft.highContrast}
            onChange={() => toggle("highContrast")}
          />
          <Toggle
            label="原生通知"
            checked={draft.nativeNotificationsEnabled}
            onChange={() => toggle("nativeNotificationsEnabled")}
          />
          <Toggle
            label="按流量计费网络"
            checked={draft.meteredNetwork}
            onChange={() => toggle("meteredNetwork")}
          />
          <Toggle
            label="临时支持日志"
            checked={draft.supportLogging}
            onChange={() => toggle("supportLogging")}
            hint="开启后 24 小时自动关闭"
          />
          <Toggle
            label="允许可信更新镜像"
            checked={draft.updateMirrorsEnabled}
            onChange={() => toggle("updateMirrorsEnabled")}
          />
          <Toggle
            label="SQL 开发者模式"
            checked={draft.developerMode}
            onChange={() => toggle("developerMode")}
            hint="减少常规确认，安全边界不变"
          />
        </div>
      </section>
      <details className="content-card settings-panel">
        <summary>
          <span className="settings-symbol">⌘</span>
          <span>
            <strong>本机环境与组件</strong>
            <small>默认不检测，需要时手动运行</small>
          </span>
          <span className="settings-chevron">›</span>
        </summary>
        <div className="settings-panel-body">
          <div className="on-demand-callout">
            <div>
              <strong>手动检测</strong>
              <p>检测 Cloud、Runner、JDK、Python、Ollama、MSVC 与 WSL。</p>
            </div>
            <Button
              variant="secondary"
              busy={environment.isFetching}
              onClick={() => void environment.refetch()}
            >
              {environment.data ? "重新检测" : "开始检测"}
            </Button>
          </div>
          {environment.isError && (
            <Feedback tone="warning" title="环境检测未完成">
              {environment.error.message}
            </Feedback>
          )}
          {environment.data && (
            <>
              <p>连接状态：{environment.data.connectivity}</p>
              <div className="component-grid">
                {environment.data.components.map((item) => (
                  <article key={item.id} className="subtle-card">
                    <strong>{item.displayName}</strong>
                    <span
                      className={`component-state state-${item.state.toLowerCase()}`}
                    >
                      {item.state}
                    </span>
                    <small>{item.detail || item.source}</small>
                    <small>
                      {item.license}
                      {item.requiresAdministrator ? " · 需要管理员确认" : ""}
                    </small>
                    {install.isPending && install.variables === item.id ? (
                      <Button
                        variant="danger"
                        busy={cancelInstall.isPending}
                        onClick={() => cancelInstall.mutate(item.id)}
                      >
                        取消安装
                      </Button>
                    ) : (
                      item.state !== "READY" && (
                        <Button
                          variant="secondary"
                          onClick={() => install.mutate(item.id)}
                        >
                          安装或修复
                        </Button>
                      )
                    )}
                  </article>
                ))}
              </div>
            </>
          )}
          {install.isError && (
            <Feedback tone="error" title="组件安装失败">
              {install.error.message}
            </Feedback>
          )}
        </div>
      </details>
      <details
        className="content-card settings-panel"
        onToggle={(event) => {
          if (event.currentTarget.open && data.canMaintainLocalData) {
            void storage.refetch();
            loadBackups.mutate();
          }
        }}
      >
        <summary>
          <span className="settings-symbol">↺</span>
          <span>
            <strong>备份与本地数据</strong>
            <small>空间、恢复和数据维护</small>
          </span>
          <span className="settings-chevron">›</span>
        </summary>
        <div className="settings-panel-body">
          {!data.canMaintainLocalData ? (
            <Feedback tone="warning" title="当前身份无维护权限" />
          ) : (
            <>
              {storage.isFetching && !storage.data ? (
                <Loading label="正在统计本地空间" />
              ) : (
                <p>
                  可用空间：
                  {formatBytes(storage.data?.storage.usableBytes ?? -1)}
                </p>
              )}
              <div className="button-row">
                <Button
                  busy={createBackup.isPending}
                  onClick={() => createBackup.mutate()}
                >
                  创建完整备份
                </Button>
                <Button
                  variant="secondary"
                  busy={restoreDemo.isPending}
                  onClick={() => restoreDemo.mutate()}
                >
                  恢复演示数据库
                </Button>
                <Button
                  variant="secondary"
                  busy={clearCache.isPending}
                  onClick={() => clearCache.mutate()}
                >
                  清理可重建缓存
                </Button>
              </div>
              {clearCache.data && (
                <Feedback tone="success" title="缓存已清理">
                  释放 {formatBytes(clearCache.data.clearedBytes)}。
                </Feedback>
              )}
              <ul className="plain-list">
                {backups.map((item) => (
                  <li key={item.id}>
                    <strong>{new Date(item.createdAt).toLocaleString()}</strong>
                    <span>
                      {formatBytes(item.sizeBytes)}
                      {item.automatic ? " · 自动" : ""}
                    </span>
                    <Button
                      variant="secondary"
                      onClick={() => setRestoreTarget(item)}
                    >
                      恢复
                    </Button>
                  </li>
                ))}
              </ul>
              <FormField
                label="清空学习数据确认词"
                hint="输入 RESET LEARNING DATA 解锁按钮"
              >
                {(ids) => (
                  <input
                    {...ids}
                    value={resetPhrase}
                    onChange={(event) => setResetPhrase(event.target.value)}
                  />
                )}
              </FormField>
              <Button
                variant="danger"
                disabled={
                  resetPhrase !== "RESET LEARNING DATA" ||
                  resetLearning.isPending
                }
                onClick={() => resetLearning.mutate()}
              >
                清空学习数据
              </Button>
              {resetLearning.isSuccess && (
                <Feedback tone="success" title="学习数据已重置">
                  已清除全部学习记录。
                </Feedback>
              )}
            </>
          )}
        </div>
      </details>
      <details className="content-card settings-panel">
        <summary>
          <span className="settings-symbol">?</span>
          <span>
            <strong>更新、通知与帮助</strong>
            <small>版本检查和使用支持</small>
          </span>
          <span className="settings-chevron">›</span>
        </summary>
        <div className="settings-panel-body">
          <div className="button-row">
            <Button
              busy={checkUpdate.isPending}
              onClick={() => checkUpdate.mutate()}
            >
              检查更新
            </Button>
            {data.notifications.some((item) => !item.read) && (
              <Button
                variant="secondary"
                onClick={() =>
                  localAppRequest("settings.notifications.read").then(() =>
                    client.invalidateQueries({ queryKey: settingsKey }),
                  )
                }
              >
                全部标为已读
              </Button>
            )}
          </div>
          {updateResult && (
            <Feedback
              tone={updateResult.status === "FAILED" ? "warning" : "info"}
              title={`更新状态：${updateResult.status}`}
            >
              {updateResult.message}
            </Feedback>
          )}
          <ul className="plain-list">
            {data.notifications.map((item) => (
              <li key={item.id}>
                <strong>{item.title}</strong>
                <span>{item.message}</span>
              </li>
            ))}
          </ul>
          <div className="button-row">
            {data.helpTopics.map((topic) => (
              <Button
                key={topic}
                variant="secondary"
                busy={loadHelp.isPending && loadHelp.variables === topic}
                onClick={() => loadHelp.mutate(topic)}
              >
                {helpTopicLabel(topic)}
              </Button>
            ))}
          </div>
          {helpContent && <pre className="help-content">{helpContent}</pre>}
        </div>
      </details>
      <Dialog
        open={Boolean(restoreTarget)}
        title="恢复应用备份"
        onClose={() => setRestoreTarget(undefined)}
      >
        <p>
          恢复会覆盖当前应用数据库。确认恢复{" "}
          {restoreTarget
            ? new Date(restoreTarget.createdAt).toLocaleString()
            : ""}{" "}
          的备份？
        </p>
        <div className="button-row">
          <Button
            variant="secondary"
            onClick={() => setRestoreTarget(undefined)}
          >
            取消
          </Button>
          <Button
            variant="danger"
            busy={restoreBackup.isPending}
            onClick={() => restoreBackup.mutate()}
          >
            确认恢复
          </Button>
        </div>
      </Dialog>
    </div>
  );
}

function Toggle({
  label,
  checked,
  onChange,
  hint,
}: {
  label: string;
  checked: boolean;
  onChange: () => void;
  hint?: string;
}) {
  return (
    <label className="setting-toggle">
      <input type="checkbox" checked={checked} onChange={onChange} />
      <span>
        <strong>{label}</strong>
        {hint && <small>{hint}</small>}
      </span>
    </label>
  );
}
function Metric({ label, value }: { label: string; value: string | number }) {
  return (
    <article className="metric">
      <span>{label}</span>
      <strong>{value}</strong>
    </article>
  );
}
function Loading({ label }: { label: string }) {
  return (
    <section className="page-skeleton" aria-live="polite">
      <span className="spinner" />
      {label}
    </section>
  );
}
function formatBytes(value: number) {
  if (!Number.isFinite(value) || value < 0) return "未知";
  if (value < 1024) return `${value} B`;
  const units = ["KiB", "MiB", "GiB", "TiB"];
  let size = value / 1024;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit++;
  }
  return `${size.toFixed(size >= 10 ? 1 : 2)} ${units[unit]}`;
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
  return !value || value === "NOT EXISTS" ? "未设置" : value;
}
function syncStateLabel(value: string) {
  return (
    (
      {
        IDLE: "空闲",
        SYNCING: "同步中",
        READY: "已同步",
        FAILED: "同步失败",
        DEGRADED: "等待重试",
      } as Record<string, string>
    )[value] ?? value
  );
}
function assignmentStatusLabel(value: CloudAssignment["status"]) {
  return (
    {
      DRAFT: "草稿",
      PUBLISHED: "已发布",
      CLOSED: "已截止",
      WITHDRAWN: "已撤回",
      ARCHIVED: "已归档",
    } as const
  )[value];
}
function feedbackStatusLabel(value: SubmissionFeedback["status"]) {
  return (
    { NEEDS_WORK: "需要改进", REVIEWED: "已批阅", RESOLVED: "已解决" } as const
  )[value];
}
function formatAccountDate(value: string) {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp) || timestamp < Date.UTC(2000, 0, 1))
    return "时间未知";
  return new Date(timestamp).toLocaleString("zh-CN", { hour12: false });
}
function helpTopicLabel(value: string) {
  return (
    (
      {
        "getting-started": "快速入门",
        updates: "更新说明",
        feedback: "反馈与建议",
        privacy: "隐私与数据",
        shortcuts: "快捷键",
        troubleshooting: "故障排查",
      } as Record<string, string>
    )[value] ?? value
  );
}
function analyticsMetricLabel(value: string) {
  return (
    (
      {
        attempts: "尝试",
        averageAttemptsPerCompletedExercise: "完成题目平均尝试",
        averageSubmissionDuration: "平均提交耗时",
        completedExercises: "已完成题目",
        completionRate: "完成率",
        passRate: "通过率",
        passedSubmissions: "通过提交",
        sessions: "练习会话",
        submissions: "提交",
        totalExercises: "题目总数",
      } as Record<string, string>
    )[value] ?? value
  );
}
function roleLabel(value: string) {
  return (
    (
      {
        STUDENT: "学生",
        TEACHER: "教师",
        ADMINISTRATOR: "管理员",
        GUEST: "访客",
      } as Record<string, string>
    )[value] ?? value
  );
}
function downloadJson(filename: string, value: unknown) {
  const url = URL.createObjectURL(
    new Blob([JSON.stringify(value, null, 2)], { type: "application/json" }),
  );
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}
function downloadText(filename: string, value: string) {
  const url = URL.createObjectURL(
    new Blob([value], { type: "application/json;charset=utf-8" }),
  );
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}
