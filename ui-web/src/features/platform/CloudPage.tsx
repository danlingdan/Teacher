// Cloud workspace page (v3.4.0 REF-13): extracted from PlatformPages.tsx, then
// split into domain hooks under ./hooks (useClassroom / useCourseAuthoring /
// useAccountSecurity). This component keeps the workspace query, sync/refresh/
// logout actions, the portfolio panel, and rendering; all domain state and
// mutation/query logic lives in the hooks. Read-only loads use useQuery; all
// write/action mutations stay mutations.
import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { localAppRequest } from "../../shared/ipc";
import { assignmentStatusLabel, syncStateLabel } from "../../shared/labels";
import { datetimeLocalFromDate } from "../../shared/datetime";
import { copyToClipboard } from "../../shared/clipboard";
import { downloadText } from "../../shared/download";
import { formatInstant } from "../../shared/instant";
import type {
  CloudAssignment,
  CloudWorkspace,
  ExerciseSummary,
  PortfolioEntry,
  SubmissionFeedback,
} from "../../shared/types";
import { Button, Dialog, Feedback, FormField, useToast } from "../../shared/ui";
import { Loading, Metric, analyticsMetricLabel, formatAccountDate } from "./shared";
import { cloudKey, portfolioKey } from "./hooks/cloudShared";
import { useClassroom } from "./hooks/useClassroom";
import { useCourseAuthoring } from "./hooks/useCourseAuthoring";
import { useAccountSecurity } from "./hooks/useAccountSecurity";

export function CloudPage() {
  const client = useQueryClient();
  const navigate = useNavigate();
  const toast = useToast();
  const query = useQuery({
    queryKey: cloudKey,
    queryFn: () => localAppRequest<CloudWorkspace>("cloud.workspace"),
    staleTime: 15_000,
  });
  // variables 为 silent 标记：创建班级后的自动刷新不弹“班级已刷新”，避免双 toast。
  const refresh = useMutation<CloudWorkspace, Error, boolean | undefined>({
    mutationFn: () =>
      localAppRequest<CloudWorkspace>("cloud.workspace", {
        refreshRemote: true,
      }),
    onSuccess: (data, silent) => {
      client.setQueryData(cloudKey, data);
      if (!silent) toast("success", "班级已刷新");
    },
    onError: (error: Error) => toast("error", `刷新班级失败：${error.message}`),
  });
  const sync = useMutation({
    mutationFn: () => localAppRequest<{ uploaded: number; downloaded: number }>("cloud.sync"),
    onSuccess: (value) => {
      void client.invalidateQueries({ queryKey: cloudKey });
      toast("success", `同步完成：上传 ${value.uploaded} 项，下载 ${value.downloaded} 项`);
    },
    onError: (error: Error) => toast("error", `同步失败：${error.message}`),
  });
  const logout = useMutation({
    mutationFn: () => localAppRequest("account.logout"),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: ["session", "current"] });
      await client.invalidateQueries({ queryKey: cloudKey });
    },
    onError: (error: Error) => toast("error", `退出登录失败：${error.message}`),
  });
  const exercises = useQuery({
    queryKey: ["cloud", "exercise-catalog"],
    queryFn: () => localAppRequest<{ items: ExerciseSummary[] }>("practice.catalog"),
    enabled: Boolean(query.data?.signedIn),
  });
  const {
    className,
    setClassName,
    classroomId,
    setClassroomId,
    memberEmail,
    setMemberEmail,
    memberRole,
    setMemberRole,
    joinCode,
    setJoinCode,
    joinCodeQuery,
    rotateJoinCode,
    joinByCode,
    pendingTransition,
    setPendingTransition,
    editingAssignment,
    startAssignmentEdit,
    cancelAssignmentEdit,
    assignmentTitle,
    setAssignmentTitle,
    assignmentExerciseId,
    setAssignmentExerciseId,
    assignmentDescription,
    setAssignmentDescription,
    assignmentDueAt,
    setAssignmentDueAt,
    feedbackAssignmentId,
    feedbackDirtyIds,
    setFeedbackDirtyIds,
    analyticsResult,
    analyticsStatus,
    setAnalyticsStatus,
    analyticsFrom,
    setAnalyticsFrom,
    analyticsTo,
    setAnalyticsTo,
    isTeacherRole,
    roster,
    assignments,
    createClass,
    addMember,
    createAssignment,
    updateAssignment,
    changeAssignmentStatus,
    copyAssignment,
    classAnalytics,
    assignmentAnalytics,
    exportClassAnalytics,
    exportAssignmentAnalytics,
    feedbackQuery,
    patchFeedbackItem,
    saveFeedback,
    draftFeedback,
    mastery,
    openMastery,
    openFeedback,
  } = useClassroom({
    workspace: query.data,
    refreshWorkspace: (silent) => refresh.mutate(silent),
  });
  const {
    courseId,
    setCourseId,
    contentOpen,
    setContentOpen,
    courseName,
    setCourseName,
    courseDescription,
    setCourseDescription,
    sectionName,
    setSectionName,
    knowledgeName,
    setKnowledgeName,
    knowledgeDescription,
    setKnowledgeDescription,
    knowledgeSectionId,
    setKnowledgeSectionId,
    sharedLocalExerciseId,
    setSharedLocalExerciseId,
    sharedKnowledgePointId,
    setSharedKnowledgePointId,
    coursePackage,
    setCoursePackage,
    packagePreview,
    setPackagePreview,
    courseJson,
    setCourseJson,
    courses,
    createCourse,
    courseContent,
    createSection,
    createKnowledgePoint,
    publishSharedExercise,
    createVersionedAssignment,
    exportCourse,
    previewCoursePackage,
    importCoursePackage,
    importCourseJson,
    openCourses,
    refreshCourses,
    openCourseContent,
  } = useCourseAuthoring({
    classroomId,
    assignmentTitle,
    assignmentDescription,
    assignmentDueAt,
  });
  const {
    currentPassword,
    setCurrentPassword,
    newPassword,
    setNewPassword,
    accountMessage,
    exportTaskId,
    sessions,
    openAccount,
    revokeSession,
    changePassword,
    requestExport,
    getExport,
    requestDeletion,
    cancelDeletion,
    deletionStatus,
  } = useAccountSecurity();
  const [portfolioOpen, setPortfolioOpen] = useState(false);
  // v3.4.2 LEG-12：作业卡片点「编辑」时自动展开任务表单。
  const [assignmentFormOpen, setAssignmentFormOpen] = useState(false);
  const portfolio = useQuery({
    queryKey: portfolioKey,
    queryFn: () => localAppRequest<{ items: PortfolioEntry[] }>("learning.portfolio"),
    enabled: portfolioOpen,
    retry: false,
  });
  useEffect(() => {
    if (portfolio.isError) toast("error", `加载作品集失败：${portfolio.error?.message ?? ""}`);
  }, [portfolio.isError, portfolio.error, toast]);
  const exportPortfolio = useMutation({
    mutationFn: () =>
      localAppRequest<{ content: string }>("learning.portfolio.export", {
        confirmed: true,
      }),
    onSuccess: (value) => downloadText("sqlteacher-portfolio.json", value.content),
    onError: (error: Error) => toast("error", `导出作品集失败：${error.message}`),
  });
  // 首屏云端班级列表为空时自动刷新一次（issue #20/#23）：cloud.workspace 不带
  // refreshRemote 只回本地状态，班级面板会一直空着，添加成员与发布任务的入口
  // 也随之不可见。DEGRADED（刷新已失败）与已尝试标记共同避免循环请求。
  const autoRefreshed = useRef(false);
  useEffect(() => {
    if (autoRefreshed.current) return;
    const value = query.data;
    if (!value?.signedIn || value.classes.length > 0) return;
    if (value.state === "DEGRADED") return;
    autoRefreshed.current = true;
    refresh.mutate(true);
  }, [query.data]);
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
          <Button onClick={() => navigate("/login?returnTo=%2Fcloud")}>登录或创建账号</Button>
          <Button variant="secondary" onClick={() => navigate("/today")}>
            继续离线学习
          </Button>
        </div>
      </section>
    );
  const assignmentItems = assignments.data?.items ?? [];
  const feedbackItems = feedbackQuery.data?.items ?? [];
  const masteryItems = mastery.data?.items ?? [];
  const portfolioItems = portfolio.data?.items ?? [];
  const courseItems = courses.data?.items ?? [];
  const courseContentData = courseContent.data;
  const courseCached = Boolean(courseContentData?.cached);
  const sessionItems = sessions.data?.items ?? [];
  const selectedClassName = data.classes.find((item) => item.id === classroomId)?.name;
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
            onClick={() => refresh.mutate(false)}
          >
            刷新班级
          </Button>
          <Button busy={sync.isPending} onClick={() => sync.mutate()}>
            立即同步
          </Button>
          <Button variant="secondary" busy={logout.isPending} onClick={() => logout.mutate()}>
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
        {!(data.role === "TEACHER" || data.role === "ADMINISTRATOR") && (
          // v3.4.1 CLS-5：学生凭班级码自助加入，不再只能等教师按邮箱添加。
          <div className="button-row">
            <input
              aria-label="班级码"
              value={joinCode}
              onChange={(event) => setJoinCode(event.target.value.toUpperCase())}
              placeholder="输入教师提供的班级码"
              maxLength={8}
              style={{ maxWidth: 220 }}
            />
            <Button
              variant="secondary"
              busy={joinByCode.isPending}
              disabled={joinCode.trim().length < 4}
              onClick={() => joinByCode.mutate()}
            >
              凭班级码加入
            </Button>
          </div>
        )}
        {data.classes.length === 0 ? (
          <p className="muted">
            {data.role === "TEACHER" || data.role === "ADMINISTRATOR"
              ? "尚无班级。输入班级名称点击「创建班级」，或点击「刷新班级」同步云端班级。"
              : "尚无班级。向教师索取班级码，在上方输入后即可加入；教师也可在班级里通过成员邮箱添加你。"}
          </p>
        ) : (
          <ul className="plain-list class-list">
            {data.classes.map((item) => (
              <li key={item.id} className={item.id === classroomId ? "selected" : ""}>
                <button
                  type="button"
                  className="table-link"
                  aria-current={item.id === classroomId ? "true" : undefined}
                  onClick={() => {
                    setClassroomId(item.id);
                  }}
                >
                  <strong>{item.name}</strong>
                </button>
                <span>{item.members.length} 名成员</span>
                {item.id === classroomId && <span className="policy-chip">当前班级</span>}
              </li>
            ))}
          </ul>
        )}
      </section>
      {classroomId && (
        <section className="content-card class-assignments">
          <div className="section-heading">
            <h2>
              班级任务
              {selectedClassName ? `：${selectedClassName}` : ""}
            </h2>
            <span className="policy-chip">{assignmentItems.length} 项</span>
          </div>
          {isTeacherRole && (
            // issue #26：查看班级具体成员；学情按任务在下方「查看学情」中呈现。
            <details className="class-roster">
              <summary>
                <strong>成员名单</strong>
                {roster.data?.members ? `（${roster.data.members.length} 人）` : ""}
              </summary>
              {isTeacherRole && (
                // v3.4.1 CLS-4：班级码供学生自助加入；教师可复制或重置（旧码立即失效）。
                <div className="button-row">
                  <span className="policy-chip" aria-label="当前班级码">
                    班级码：{joinCodeQuery.data?.joinCode ?? "…"}
                  </span>
                  <Button
                    variant="secondary"
                    disabled={!joinCodeQuery.data?.joinCode}
                    onClick={() =>
                      copyToClipboard(
                        joinCodeQuery.data?.joinCode ?? "",
                        "班级码已复制",
                        "复制失败，请手动记录班级码",
                        toast,
                      )
                    }
                  >
                    复制
                  </Button>
                  <Button
                    variant="secondary"
                    busy={rotateJoinCode.isPending}
                    onClick={() => {
                      if (
                        window.confirm(
                          "重置后旧班级码立即失效，已用它加入的成员不受影响。确定重置？",
                        )
                      ) {
                        rotateJoinCode.mutate();
                      }
                    }}
                  >
                    重置班级码
                  </Button>
                </div>
              )}
              {roster.isPending ? (
                <p className="muted">正在加载成员名单…</p>
              ) : roster.isError ? (
                <p className="muted">成员名单暂时不可用，请稍后重试。</p>
              ) : roster.data?.members.length ? (
                <ul className="plain-list">
                  {roster.data.members.map((member) => (
                    <li key={member.userId}>
                      <strong>{member.displayName || member.email}</strong>
                      <span>
                        {member.role === "TEACHER"
                          ? "教师"
                          : member.role === "ADMINISTRATOR"
                            ? "管理员"
                            : "学生"}
                        {member.email ? ` · ${member.email}` : ""}
                      </span>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="muted">班级暂无成员。展开「添加成员」通过邮箱邀请学生加入。</p>
              )}
            </details>
          )}
          {(data.role === "TEACHER" || data.role === "ADMINISTRATOR") && (
            <details className="class-members">
              <summary>
                <strong>添加成员</strong>
                {memberEmail ? <span className="policy-chip">待提交</span> : null}
              </summary>
              {/* issue #20：目标班级必须在此显式可选，不能只靠隐式选中的班级。 */}
              <div className="settings-grid">
                <FormField label="目标班级">
                  {(ids) => (
                    <select
                      {...ids}
                      value={classroomId}
                      onChange={(event) => {
                        setClassroomId(event.target.value);
                      }}
                    >
                      {data.classes.map((item) => (
                        <option key={item.id} value={item.id}>
                          {item.name}
                        </option>
                      ))}
                    </select>
                  )}
                </FormField>
              </div>
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
              <div className="button-row">
                <Button
                  variant="secondary"
                  disabled={!memberEmail || !classroomId}
                  busy={addMember.isPending}
                  onClick={() => addMember.mutate()}
                >
                  添加成员
                </Button>
                <span className="muted">
                  {selectedClassName ? `新成员将加入「${selectedClassName}」` : "请先选择目标班级"}
                </span>
              </div>
            </details>
          )}
          {(data.role === "TEACHER" || data.role === "ADMINISTRATOR") && (
            <details
              className="class-assignment-create"
              open={assignmentFormOpen}
              onToggle={(event) => setAssignmentFormOpen(event.currentTarget.open)}
            >
              <summary>
                <strong>{editingAssignment ? "编辑任务" : "新建任务"}</strong>
                {editingAssignment ? (
                  <span className="policy-chip">编辑中：{editingAssignment.title}</span>
                ) : assignmentTitle ? (
                  <span className="policy-chip">草稿未保存</span>
                ) : null}
              </summary>
              <div className="settings-grid">
                <FormField label="任务标题">
                  {(ids) => (
                    <input
                      {...ids}
                      value={assignmentTitle}
                      onChange={(event) => setAssignmentTitle(event.target.value)}
                    />
                  )}
                </FormField>
                <FormField
                  label="练习"
                  hint={editingAssignment ? "编辑时练习不可更换" : undefined}
                >
                  {(ids) => (
                    <select
                      {...ids}
                      value={editingAssignment?.exerciseId ?? assignmentExerciseId}
                      disabled={Boolean(editingAssignment)}
                      onChange={(event) => setAssignmentExerciseId(event.target.value)}
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
                      onChange={(event) => setAssignmentDueAt(event.target.value)}
                    />
                  )}
                </FormField>
                <FormField label="任务说明">
                  {(ids) => (
                    <textarea
                      {...ids}
                      value={assignmentDescription}
                      onChange={(event) => setAssignmentDescription(event.target.value)}
                    />
                  )}
                </FormField>
              </div>
              <div className="deadline-presets">
                <span className="muted">截止快捷：</span>
                {deadlinePresetLabels.map((preset) => (
                  <button
                    type="button"
                    key={preset.label}
                    className="preset-chip"
                    onClick={() => setAssignmentDueAt(preset.value())}
                  >
                    {preset.label}
                  </button>
                ))}
                {assignmentDueAt ? (
                  <button
                    type="button"
                    className="preset-chip"
                    onClick={() => setAssignmentDueAt("")}
                  >
                    清除截止时间
                  </button>
                ) : null}
              </div>
              <div className="button-row">
                {editingAssignment ? (
                  <>
                    <Button
                      disabled={!assignmentTitle || updateAssignment.isPending}
                      busy={updateAssignment.isPending}
                      onClick={() => updateAssignment.mutate()}
                    >
                      保存修改
                    </Button>
                    <Button variant="secondary" onClick={cancelAssignmentEdit}>
                      取消编辑
                    </Button>
                  </>
                ) : (
                  <>
                    <Button
                      disabled={!assignmentTitle || !assignmentExerciseId}
                      busy={createAssignment.isPending}
                      onClick={() => createAssignment.mutate(true)}
                    >
                      创建并发布
                    </Button>
                    <Button
                      variant="secondary"
                      disabled={!assignmentTitle || !assignmentExerciseId || createAssignment.isPending}
                      onClick={() => createAssignment.mutate(false)}
                    >
                      存为草稿
                    </Button>
                  </>
                )}
              </div>
            </details>
          )}
          {(data.role === "TEACHER" || data.role === "ADMINISTRATOR") && (
            <div className="class-analytics-bar">
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
              <div className="button-row">
                <Button
                  variant="secondary"
                  busy={classAnalytics.isPending}
                  onClick={() => classAnalytics.mutate()}
                >
                  查看学情
                </Button>
                <Button
                  variant="secondary"
                  busy={exportClassAnalytics.isPending}
                  onClick={() => exportClassAnalytics.mutate()}
                >
                  导出学情
                </Button>
              </div>
            </div>
          )}
          <ul className="plain-list">
            {assignmentItems.map((item) => (
              <li key={item.id}>
                <strong>{item.title}</strong>
                <span>
                  {assignmentStatusLabel(item.status)}
                  {item.dueAt ? ` · 截止 ${formatInstant(item.dueAt)}` : ""}
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
                    <Button variant="secondary" onClick={() => assignmentAnalytics.mutate(item.id)}>
                      查看学情
                    </Button>
                    <Button
                      variant="secondary"
                      busy={exportAssignmentAnalytics.isPending}
                      onClick={() => exportAssignmentAnalytics.mutate(item.id)}
                    >
                      导出 CSV
                    </Button>
                    <Button variant="secondary" onClick={() => openFeedback(item.id)}>
                      批阅反馈
                    </Button>
                    {/* v3.4.2 LEG-12：归档任务云端拒绝编辑，入口直接隐藏。 */}
                    {item.status !== "ARCHIVED" && (
                      <Button
                        variant="secondary"
                        onClick={() => {
                          startAssignmentEdit(item);
                          setAssignmentFormOpen(true);
                        }}
                      >
                        编辑
                      </Button>
                    )}
                    <Button variant="secondary" onClick={() => copyAssignment.mutate(item)}>
                      复制
                    </Button>
                    <select
                      aria-label={`${item.title} 状态变更`}
                      value=""
                      onChange={(event) => {
                        const next = event.target.value as CloudAssignment["status"];
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
                  <Button variant="secondary" onClick={() => openFeedback(item.id)}>
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
              {assignmentStatusLabel(pendingTransition?.item.status ?? "DRAFT")}
              」变更为「
              {assignmentStatusLabel(pendingTransition?.next ?? "DRAFT")}」？
              发布后学生立即可见并提交；关闭或撤回后学生无法继续提交。
            </p>
            <div className="button-row">
              <Button variant="secondary" onClick={() => setPendingTransition(undefined)}>
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
            <AnalyticsStructuredView
              report={
                analyticsResult as unknown as {
                  overview: Record<string, number>;
                  exercises?: Array<Record<string, unknown>>;
                  knowledgePoints?: Array<Record<string, unknown>>;
                  commonErrors?: Array<Record<string, unknown>>;
                }
              }
            />
          )}
          <div className="button-row class-actions">
            <Button variant="secondary" busy={mastery.isFetching} onClick={openMastery}>
              {data.role === "STUDENT" ? "我的掌握度" : "当前账号掌握度"}
            </Button>
          </div>
          {masteryItems.length > 0 && (
            <ul className="plain-list">
              {masteryItems.map((item) => (
                <li key={item.knowledgePointId}>
                  <strong>{item.knowledgePointName}</strong>
                  <span>
                    {item.masteryPercent}% · {item.passes}/{item.attempts} 次通过
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
                      {data.role === "TEACHER" || data.role === "ADMINISTRATOR" ? (
                        <>
                          <select
                            aria-label="反馈状态"
                            value={item.status}
                            onChange={(event) =>
                              patchFeedbackItem(item.submissionId, (candidate) => ({
                                ...candidate,
                                status: event.target.value as SubmissionFeedback["status"],
                              }))
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
                              patchFeedbackItem(item.submissionId, (candidate) => ({
                                ...candidate,
                                comment: event.target.value,
                              }));
                              setFeedbackDirtyIds((ids) =>
                                ids.includes(item.submissionId) ? ids : [...ids, item.submissionId],
                              );
                            }}
                          />
                          <Button
                            variant="secondary"
                            busy={
                              draftFeedback.isPending &&
                              draftFeedback.variables?.submissionId === item.submissionId
                            }
                            onClick={() => {
                              if (
                                item.comment &&
                                !window.confirm("AI 起草会覆盖当前评语，确定继续？")
                              )
                                return;
                              draftFeedback.mutate(item);
                            }}
                          >
                            AI 起草
                          </Button>
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
            if (event.currentTarget.open) openCourses();
          }}
        >
          <summary>
            <strong>共享课程、知识点与版本化任务</strong>
          </summary>
          <p className="muted"></p>
          <div className="settings-grid">
            <FormField label="课程">
              {(ids) => (
                <select
                  {...ids}
                  value={courseId}
                  onChange={(event) => {
                    setCourseId(event.target.value);
                    setContentOpen(false);
                  }}
                >
                  <option value="">选择课程</option>
                  {courseItems.map((item) => (
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
            <Button busy={courses.isFetching} onClick={refreshCourses}>
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
              busy={courseContent.isFetching}
              onClick={openCourseContent}
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
          {contentOpen && courseContentData && (
            <>
              {courseCached && (
                <Feedback tone="warning" title="当前显示的是离线缓存内容">
                  <p>
                    云端暂时不可用，下方是上次同步的课程内容；恢复连接前，添加章节、知识点等写操作可能失败。
                  </p>
                </Feedback>
              )}
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
                      onChange={(event) => setKnowledgeSectionId(event.target.value)}
                    >
                      <option value="">选择章节</option>
                      {courseContentData.sections.map((item) => (
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
                      onChange={(event) => setKnowledgeDescription(event.target.value)}
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
                      onChange={(event) => setSharedLocalExerciseId(event.target.value)}
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
                      onChange={(event) => setSharedKnowledgePointId(event.target.value)}
                    >
                      <option value="">不关联</option>
                      {courseContentData.knowledgePoints.map((item) => (
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
              {courseContentData.exercises.length === 0 ? (
                <p className="muted">尚未发布练习版本。</p>
              ) : (
                <ul className="plain-list">
                  {courseContentData.exercises.map((item) => (
                    <li key={item.id}>
                      <strong>{item.title}</strong>
                      <span>
                        版本 {item.version} · {item.status}
                      </span>
                      <Button
                        disabled={!classroomId}
                        busy={createVersionedAssignment.isPending}
                        onClick={() => createVersionedAssignment.mutate(item.id)}
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
            <FormField label="课程包 JSON" hint="先预览摘要、许可证与冲突，再明确确认导入。">
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
                tone={packagePreview.conflict === "VERSION_CONFLICT" ? "warning" : "info"}
                title={packagePreview.courseTitle}
              >
                <p>
                  版本 {packagePreview.courseVersion} · 许可证 {packagePreview.license}
                </p>
                <p>
                  {packagePreview.sections} 个章节，
                  {packagePreview.knowledgePoints} 个知识点，
                  {packagePreview.exercises} 个练习。
                </p>
              </Feedback>
            )}
          </section>
          {/* v3.4.2 LEG-13：单课程 JSON 导入——与整包导入互补，单文件直接导入、无预览流程。 */}
          <section className="account-section">
            <h3>导入课程 JSON</h3>
            <FormField
              label="课程 JSON"
              hint="单个课程导出文件，粘贴后直接导入；整包分发请用上方「安全课程包导入」。"
            >
              {(ids) => (
                <textarea
                  {...ids}
                  value={courseJson}
                  onChange={(event) => setCourseJson(event.target.value)}
                />
              )}
            </FormField>
            <div className="button-row">
              <Button
                disabled={!courseJson.trim()}
                busy={importCourseJson.isPending}
                onClick={() => importCourseJson.mutate(courseJson)}
              >
                导入课程 JSON
              </Button>
            </div>
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
              busy={portfolio.isFetching}
              onClick={() => {
                setPortfolioOpen(true);
                void client.invalidateQueries({ queryKey: portfolioKey });
              }}
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
          if (event.currentTarget.open) openAccount();
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
          {sessions.isPending && <p className="muted">正在读取会话…</p>}
          <ul className="plain-list">
            {sessionItems.map((item) => (
              <li key={item.id}>
                <strong>{item.current ? "当前会话" : item.userAgent || "其他会话"}</strong>
                <span>{formatAccountDate(item.lastSeenAt)}</span>
                {!item.current && (
                  <Button variant="secondary" onClick={() => revokeSession.mutate(item.id)}>
                    撤销
                  </Button>
                )}
              </li>
            ))}
          </ul>
          {!sessions.isPending && sessionItems.length === 0 && (
            <p className="muted">没有可显示的其他会话。</p>
          )}
        </section>
        <section className="account-section danger-zone">
          <h3>数据导出与账号删除</h3>
          <p className="muted">导出不会修改账号；删除申请进入可撤销期，请谨慎操作。</p>
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

function feedbackStatusLabel(value: SubmissionFeedback["status"]) {
  return ({ NEEDS_WORK: "需要改进", REVIEWED: "已批阅", RESOLVED: "已解决" } as const)[value];
}
/**
 * 学情报告结构化呈现（W5.1）：概览指标中文化 + 知识点/常见错误分表，
 * 替代直接把报告 JSON dump 给教师查看的旧形态。
 */
function AnalyticsStructuredView({
  report,
}: {
  report: {
    overview: Record<string, number>;
    exercises?: Array<Record<string, unknown>>;
    knowledgePoints?: Array<Record<string, unknown>>;
    commonErrors?: Array<Record<string, unknown>>;
  };
}) {
  const entries = Object.entries(report.overview);
  const points = (report.knowledgePoints ?? []) as Array<Record<string, unknown>>;
  const errors = (report.commonErrors ?? []) as Array<Record<string, unknown>>;
  return (
    <div className="analytics-structured">
      <div className="metric-row">
        {entries.map(([key, value]) => (
          <Metric
            key={key}
            label={analyticsMetricLabel(key)}
            value={
              typeof value === "number" && (key === "passRate" || key === "completionRate")
                ? `${Math.round(value * 100)}%`
                : value
            }
          />
        ))}
      </div>
      {points.length > 0 && (
        <>
          <p className="eyebrow">知识点统计</p>
          <ul className="plain-list">
            {points.slice(0, 8).map((point, index) => (
              <li key={index}>
                <strong>{String(point.knowledgePoint ?? point.name ?? "未命名")}</strong>
                <span>
                  尝试 {String(point.attempts ?? 0)} · 完成 {String(point.completedExercises ?? 0)}{" "}
                  · 薄弱率{" "}
                  {typeof point.weaknessRate === "number"
                    ? `${Math.round(point.weaknessRate * 100)}%`
                    : String(point.weaknessRate ?? "—")}
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
      {errors.length > 0 && (
        <>
          <p className="eyebrow">常见错误</p>
          <ul className="plain-list">
            {errors.slice(0, 6).map((error, index) => (
              <li key={index}>
                <code>{String(error.errorCode ?? "UNKNOWN")}</code>
                <span>出现 {String(error.count ?? 0)} 次</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}

/** 截止时间快捷项；datetime-local 需要本地时间格式的字符串。 */
function datetimeLocalAt(offsetDays: number, hour: number): string {
  const date = new Date();
  date.setDate(date.getDate() + offsetDays);
  date.setHours(hour, 0, 0, 0);
  return datetimeLocalFromDate(date);
}

const deadlinePresetLabels: Array<{ label: string; value: () => string }> = [
  { label: "今天 18:00", value: () => datetimeLocalAt(0, 18) },
  { label: "明天 18:00", value: () => datetimeLocalAt(1, 18) },
  { label: "7 天后 18:00", value: () => datetimeLocalAt(7, 18) },
];
