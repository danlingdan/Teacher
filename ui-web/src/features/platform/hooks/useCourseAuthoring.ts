// 共享课程创作域 hook（v3.4.0 REF-13）：从 CloudPage.tsx 原样搬移，覆盖课程列表、
// 课程内容（章节/知识点/练习版本）、安全课程包导入导出与版本化任务创建；
// 不改任何用户可见行为。版本化任务的草稿默认值来自班级域，由 CloudPage 注入。
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { localAppRequest } from "../../../shared/ipc";
import { downloadText } from "../../../shared/download";
import type {
  CloudCourse,
  CloudCourseContent,
  CoursePackagePreview,
  ExerciseDefinition,
} from "../../../shared/types";
import { useToast } from "../../../shared/ui";
import { assignmentsKey, cloudFailureText, courseContentKey, coursesKey } from "./cloudShared";

export function useCourseAuthoring({
  classroomId,
  assignmentTitle,
  assignmentDescription,
  assignmentDueAt,
}: {
  /** 当前选中班级；版本化任务发布到该班级。 */
  classroomId: string;
  /** 班级域“新建任务”表单草稿，作为版本化任务的默认标题/说明/截止时间。 */
  assignmentTitle: string;
  assignmentDescription: string;
  assignmentDueAt: string;
}) {
  const client = useQueryClient();
  const toast = useToast();
  const [coursesOpen, setCoursesOpen] = useState(false);
  const [courseId, setCourseId] = useState("");
  const [contentOpen, setContentOpen] = useState(false);
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
  const courses = useQuery({
    queryKey: coursesKey,
    queryFn: () =>
      localAppRequest<{ items: CloudCourse[]; cached: boolean }>("cloud.courses", {
        refreshRemote: true,
      }),
    enabled: coursesOpen,
    retry: false,
  });
  useEffect(() => {
    if (courses.isError) toast("error", `刷新课程失败：${courses.error?.message ?? ""}`);
  }, [courses.isError, courses.error, toast]);
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
      toast("success", `课程「${value.name}」已创建`);
      void client.invalidateQueries({ queryKey: coursesKey });
    },
    onError: (error: Error) => toast("error", `课程创建失败：${error.message}`),
  });
  const courseContent = useQuery({
    queryKey: [...courseContentKey, courseId],
    queryFn: () =>
      localAppRequest<CloudCourseContent>("cloud.course.content", {
        courseId,
        refreshRemote: true,
      }),
    enabled: contentOpen && Boolean(courseId),
    retry: false,
  });
  useEffect(() => {
    if (courseContent.isError)
      toast("error", `打开课程失败：${cloudFailureText(courseContent.error ?? new Error())}`);
  }, [courseContent.isError, courseContent.error, toast]);
  useEffect(() => {
    if (!courseId && courses.data?.items[0]) setCourseId(courses.data.items[0].id);
  }, [courses.data, courseId]);
  useEffect(() => {
    if (!knowledgeSectionId && courseContent.data?.sections[0])
      setKnowledgeSectionId(courseContent.data.sections[0].id);
  }, [courseContent.data, knowledgeSectionId]);
  const createSection = useMutation({
    mutationFn: () =>
      localAppRequest("cloud.course.section.create", {
        courseId,
        name: sectionName,
        sortOrder: courseContent.data?.sections.length ?? 0,
      }),
    onSuccess: () => {
      toast("success", `章节「${sectionName}」已添加`);
      setSectionName("");
      void client.invalidateQueries({ queryKey: courseContentKey });
    },
    onError: (error: Error) => toast("error", `章节添加失败：${cloudFailureText(error)}`),
  });
  const createKnowledgePoint = useMutation({
    mutationFn: () =>
      localAppRequest("cloud.course.knowledge.create", {
        courseId,
        sectionId: knowledgeSectionId,
        name: knowledgeName,
        description: knowledgeDescription,
        sortOrder: courseContent.data?.knowledgePoints.length ?? 0,
      }),
    onSuccess: () => {
      toast("success", `知识点「${knowledgeName}」已添加`);
      setKnowledgeName("");
      setKnowledgeDescription("");
      void client.invalidateQueries({ queryKey: courseContentKey });
    },
    onError: (error: Error) => toast("error", `知识点添加失败：${cloudFailureText(error)}`),
  });
  const publishSharedExercise = useMutation({
    mutationFn: async () => {
      const exercise = await localAppRequest<ExerciseDefinition>("teaching.exercise.detail", {
        exerciseId: sharedLocalExerciseId,
      });
      return localAppRequest("cloud.course.exercise.publish", {
        courseId,
        exerciseId: exercise.id,
        title: exercise.title,
        prompt: exercise.description,
        datasetVersion: `${exercise.datasetId}@${exercise.version}`,
        evaluationRule: JSON.stringify(exercise.evaluationRule),
        knowledgePointIds: sharedKnowledgePointId ? [sharedKnowledgePointId] : [],
      });
    },
    onSuccess: () => {
      toast("success", "本地题目已发布到共享课程");
      void client.invalidateQueries({ queryKey: courseContentKey });
    },
    onError: (error: Error) => toast("error", `题目发布失败：${error.message}`),
  });
  const createVersionedAssignment = useMutation({
    mutationFn: (exerciseVersionId: string) =>
      localAppRequest("cloud.assignment.create-versioned", {
        classroomId,
        exerciseVersionId,
        title:
          assignmentTitle ||
          courseContent.data?.exercises.find((item) => item.id === exerciseVersionId)?.title,
        description: assignmentDescription,
        dueAt: assignmentDueAt ? new Date(assignmentDueAt).toISOString() : "",
      }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: assignmentsKey });
      toast("success", "版本化任务已创建");
    },
    onError: (error: Error) => toast("error", `任务创建失败：${error.message}`),
  });
  const exportCourse = useMutation({
    mutationFn: () => localAppRequest<{ content: string }>("cloud.course.export", { courseId }),
    onSuccess: (value) => downloadText(`sqlteacher-course-${courseId}.json`, value.content),
    onError: (error: Error) => toast("error", `课程导出失败：${error.message}`),
  });
  const previewCoursePackage = useMutation({
    mutationFn: () =>
      localAppRequest<CoursePackagePreview>("cloud.course.package.preview", {
        content: coursePackage,
      }),
    onSuccess: setPackagePreview,
    onError: (error: Error) => toast("error", `课程包解析失败：${error.message}`),
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
      toast("success", "课程包已导入");
      void client.invalidateQueries({ queryKey: coursesKey });
    },
    onError: (error: Error) => toast("error", `课程包导入失败：${error.message}`),
  });
  /** 展开课程面板：首次打开且课程列表为空时触发一次刷新。 */
  const openCourses = () => {
    setCoursesOpen(true);
    if ((courses.data?.items ?? []).length === 0)
      void client.invalidateQueries({ queryKey: coursesKey });
  };
  /** 「刷新课程」按钮：打开课程查询并强制刷新缓存。 */
  const refreshCourses = () => {
    setCoursesOpen(true);
    void client.invalidateQueries({ queryKey: coursesKey });
  };
  /** 「打开课程」按钮：打开内容查询并强制刷新当前课程缓存。 */
  const openCourseContent = () => {
    setContentOpen(true);
    void client.invalidateQueries({ queryKey: [...courseContentKey, courseId] });
  };
  return {
    coursesOpen,
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
    openCourses,
    refreshCourses,
    openCourseContent,
  };
}
