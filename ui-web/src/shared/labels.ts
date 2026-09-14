// Shared Chinese label mappings. Feature-local copies of these maps drifted apart
// (v3.4.0 REF-16); add new enum labels here instead of local maps so wording stays
// consistent across workspaces.
const EXERCISE_TYPE_LABELS: Record<string, string> = {
  QUERY: "查询",
  STATE: "写操作",
  SCRIPT: "脚本",
  TRIGGER: "触发器",
};

/** Short exercise type label (catalog, tables). */
export function exerciseTypeLabel(value: string): string {
  return EXERCISE_TYPE_LABELS[value] ?? "查询";
}

/** Sentence-style exercise type label (editor flows, guidance copy). */
export function exerciseTypeTitleLabel(value: string | undefined): string {
  const titles: Record<string, string> = {
    QUERY: "查询题",
    STATE: "写操作题",
    SCRIPT: "脚本题",
    TRIGGER: "触发器题",
  };
  return titles[value ?? "QUERY"] ?? "查询题";
}

export function exerciseStatusLabel(value: string): string {
  return (
    ({ passed: "已通过", failed: "未通过", todo: "未做" } as Record<string, string>)[value] ?? value
  );
}

export function difficultyLabel(value: string): string {
  return (
    ({ BEGINNER: "入门", INTERMEDIATE: "进阶", ADVANCED: "高级" } as Record<string, string>)[
      value
    ] ?? value
  );
}

export function knowledgePointLabel(value: string, missingText = "未设置知识点"): string {
  return !value || value === "NOT EXISTS" ? missingText : value;
}

export function roleLabel(value: string): string {
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

export function activityTypeLabel(value: string): string {
  return (
    (
      {
        READING: "阅读",
        QUIZ: "测验",
        SIMULATION: "模拟实验",
        TRACE: "追踪实验",
        CODE: "编程实践",
        PROJECT: "项目实践",
        LAB: "实验",
      } as Record<string, string>
    )[value] ?? value
  );
}

export function syncStateLabel(value: string): string {
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

export function assignmentStatusLabel(value: string): string {
  return (
    (
      {
        DRAFT: "草稿",
        PUBLISHED: "已发布",
        CLOSED: "已截止",
        WITHDRAWN: "已撤回",
        ARCHIVED: "已归档",
      } as Record<string, string>
    )[value] ?? value
  );
}
