// 班级域 hook（v3.4.0 REF-13）：从 CloudPage.tsx 原样搬移，覆盖班级选择/创建、
// 成员名单与添加、任务生命周期、学情分析与导出、提交反馈、掌握度；
// 不改任何用户可见行为。workspace 与静默刷新回调由 CloudPage 注入。
import { useEffect, useState } from "react";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSearchParams } from "react-router-dom";
import { localAppRequest } from "../../../shared/ipc";
import { assignmentStatusLabel } from "../../../shared/labels";
import { downloadText } from "../../../shared/download";
import type {
  CloudAssignment,
  CloudClassRosterMember,
  CloudWorkspace,
  KnowledgeMastery,
  SubmissionFeedback,
} from "../../../shared/types";
import { useToast } from "../../../shared/ui";
import { assignmentsKey, cloudFailureText, cloudKey, masteryKey } from "./cloudShared";

export function useClassroom({
  workspace,
  refreshWorkspace,
}: {
  /** cloud.workspace 查询数据；未就绪时为 undefined。 */
  workspace: CloudWorkspace | undefined;
  /** 触发一次 workspace 刷新；silent=true 不弹“班级已刷新”toast。 */
  refreshWorkspace: (silent: boolean) => void;
}) {
  const client = useQueryClient();
  const [searchParams] = useSearchParams();
  const toast = useToast();
  const [className, setClassName] = useState("");
  // 命令面板等入口通过 ?class= 深链到指定班级。
  const [classroomId, setClassroomId] = useState(() => searchParams.get("class") ?? "");
  const [memberEmail, setMemberEmail] = useState("");
  const [memberRole, setMemberRole] = useState("STUDENT");
  const [pendingTransition, setPendingTransition] = useState<{
    item: CloudAssignment;
    next: CloudAssignment["status"];
  }>();
  const [assignmentTitle, setAssignmentTitle] = useState("");
  const [assignmentExerciseId, setAssignmentExerciseId] = useState("");
  const [assignmentDescription, setAssignmentDescription] = useState("");
  const [assignmentDueAt, setAssignmentDueAt] = useState("");
  const [feedbackAssignmentId, setFeedbackAssignmentId] = useState("");
  const [feedbackDirtyIds, setFeedbackDirtyIds] = useState<string[]>([]);
  const [analyticsResult, setAnalyticsResult] = useState<Record<string, unknown>>();
  const [masteryOpen, setMasteryOpen] = useState(false);
  const [analyticsStatus, setAnalyticsStatus] = useState("");
  const [analyticsFrom, setAnalyticsFrom] = useState("");
  const [analyticsTo, setAnalyticsTo] = useState("");
  const createClass = useMutation({
    mutationFn: () =>
      localAppRequest<{ classroom: { id: string } }>("cloud.class.create", {
        name: className,
      }),
    onSuccess: (value) => {
      setClassName("");
      setClassroomId(value.classroom.id);
      toast("success", `班级「${className}」已创建，已自动选中，可在下方添加成员与任务`);
      refreshWorkspace(true);
    },
    onError: (error: Error) => toast("error", `班级创建失败：${error.message}`),
  });
  // 班级成员名单（issue #26）：仅教师可见。
  const isTeacherRole = workspace?.role === "TEACHER" || workspace?.role === "ADMINISTRATOR";
  const roster = useQuery({
    queryKey: ["cloud", "roster", classroomId],
    queryFn: () =>
      localAppRequest<{ members: CloudClassRosterMember[] }>("cloud.class.roster", { classroomId }),
    enabled: Boolean(classroomId) && isTeacherRole,
    retry: false,
    staleTime: 30_000,
  });
  // 班级任务按班级缓存：切换班级即取对应任务，过期响应只会写回各自的缓存键。
  const assignments = useQuery({
    queryKey: [...assignmentsKey, classroomId],
    queryFn: () =>
      localAppRequest<{ items: CloudAssignment[] }>("cloud.assignments", {
        classroomId,
      }),
    enabled: Boolean(classroomId),
    retry: false,
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
      const refreshed = await localAppRequest<CloudWorkspace>("cloud.workspace", {
        refreshRemote: true,
      });
      client.setQueryData(cloudKey, refreshed);
    },
    onError: (error: Error) => toast("error", `添加成员失败：${cloudFailureText(error)}`),
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
      void client.invalidateQueries({ queryKey: assignmentsKey });
      if (publish) {
        changeAssignmentStatus.mutate({ ...created, next: "PUBLISHED" });
        toast("success", `任务「${created.title}」已创建并发布，学生端立即可见`);
      } else {
        toast("success", `任务「${created.title}」已保存为草稿`);
      }
    },
    onError: (error: Error) => toast("error", `任务创建失败：${cloudFailureText(error)}`),
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
      void client.invalidateQueries({ queryKey: assignmentsKey });
      toast("success", `任务「${item.title}」状态已更新为「${assignmentStatusLabel(item.next)}」`);
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
      void client.invalidateQueries({ queryKey: assignmentsKey });
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
    onError: (error: Error) => toast("error", `班级分析失败：${error.message}`),
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
    onError: (error: Error) => toast("error", `作业分析失败：${error.message}`),
  });
  // 提交反馈按“班级 + 任务”缓存：切换目标即取对应反馈，保存后原地补丁缓存。
  const feedbackQuery = useQuery({
    queryKey: ["cloud", "feedback", classroomId, feedbackAssignmentId],
    queryFn: () =>
      localAppRequest<{ items: SubmissionFeedback[]; cached: boolean }>("cloud.feedback.list", {
        classroomId,
        assignmentId: feedbackAssignmentId,
        refreshRemote: true,
      }),
    enabled: Boolean(classroomId) && Boolean(feedbackAssignmentId),
    placeholderData: keepPreviousData,
    retry: false,
  });
  useEffect(() => {
    if (feedbackQuery.isError)
      toast("error", `加载提交反馈失败：${feedbackQuery.error?.message ?? ""}`);
  }, [feedbackQuery.isError, feedbackQuery.error, toast]);
  const patchFeedbackItem = (
    submissionId: string,
    patch: (item: SubmissionFeedback) => SubmissionFeedback,
  ) => {
    client.setQueryData<{ items: SubmissionFeedback[]; cached: boolean }>(
      ["cloud", "feedback", classroomId, feedbackAssignmentId],
      (current) =>
        current
          ? {
              ...current,
              items: current.items.map((candidate) =>
                candidate.submissionId === submissionId ? patch(candidate) : candidate,
              ),
            }
          : current,
    );
  };
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
      patchFeedbackItem(saved.submissionId, () => saved);
      setFeedbackDirtyIds((ids) => ids.filter((id) => id !== saved.submissionId));
      toast("success", "反馈已保存，学生端可见");
    },
    onError: (error: Error) => toast("error", `反馈保存失败：${error.message}`),
  });
  const mastery = useQuery({
    queryKey: [...masteryKey, classroomId],
    queryFn: () =>
      localAppRequest<{ items: KnowledgeMastery[]; cached: boolean }>("cloud.mastery", {
        classroomId,
        refreshRemote: true,
      }),
    enabled: masteryOpen && Boolean(classroomId),
    retry: false,
  });
  useEffect(() => {
    if (mastery.isError) toast("error", `加载掌握度失败：${mastery.error?.message ?? ""}`);
  }, [mastery.isError, mastery.error, toast]);
  const exportClassAnalytics = useMutation({
    mutationFn: () =>
      localAppRequest<{ csv: string }>("cloud.class.analytics.export", {
        classroomId,
      }),
    onSuccess: (value) => downloadText(`class-${classroomId}-analytics.csv`, value.csv),
    onError: (error: Error) => toast("error", `班级分析导出失败：${error.message}`),
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
    onError: (error: Error) => toast("error", `作业分析导出失败：${error.message}`),
  });
  useEffect(() => {
    if (!classroomId && workspace?.classes[0]) {
      setClassroomId(workspace.classes[0].id);
    }
  }, [classroomId, workspace?.classes]);
  // 命令面板深链 ?class=：页面已挂载时参数变化也要切换班级并加载其任务。
  useEffect(() => {
    const fromUrl = searchParams.get("class");
    if (
      fromUrl &&
      fromUrl !== classroomId &&
      workspace?.classes.some((item) => item.id === fromUrl)
    ) {
      setClassroomId(fromUrl);
    }
  }, [searchParams, classroomId, workspace?.classes]);
  /** 打开掌握度面板并强制刷新当前班级的掌握度缓存。 */
  const openMastery = () => {
    setMasteryOpen(true);
    void client.invalidateQueries({ queryKey: [...masteryKey, classroomId] });
  };
  /** 选中任务并强制刷新其提交反馈缓存（教师批阅 / 学生查看共用）。 */
  const openFeedback = (assignmentId: string) => {
    setFeedbackAssignmentId(assignmentId);
    void client.invalidateQueries({
      queryKey: ["cloud", "feedback", classroomId, assignmentId],
    });
  };
  return {
    className,
    setClassName,
    classroomId,
    setClassroomId,
    memberEmail,
    setMemberEmail,
    memberRole,
    setMemberRole,
    pendingTransition,
    setPendingTransition,
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
    changeAssignmentStatus,
    copyAssignment,
    classAnalytics,
    assignmentAnalytics,
    exportClassAnalytics,
    exportAssignmentAnalytics,
    feedbackQuery,
    patchFeedbackItem,
    saveFeedback,
    mastery,
    openMastery,
    openFeedback,
  };
}
