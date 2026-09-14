// CloudPage 域拆分（v3.4.0 REF-13）后共享的查询键与错误文案映射。
// 三个域 hooks（useClassroom / useCourseAuthoring / useAccountSecurity）
// 与 CloudPage 本体都从这里取键，避免循环依赖 CloudPage.tsx。
import { LocalAppError } from "../../../shared/ipc";

export const cloudKey = ["cloud", "workspace"] as const;
export const assignmentsKey = ["cloud", "assignments"] as const;
export const coursesKey = ["cloud", "courses"] as const;
export const courseContentKey = ["cloud", "course-content"] as const;
export const masteryKey = ["cloud", "mastery"] as const;
export const portfolioKey = ["learning", "portfolio"] as const;
export const sessionsKey = ["account", "sessions"] as const;

/**
 * 云端写操作失败的文案映射（issue #21）：Java 桥接层现在透传云端错误的
 * 结构化 code，这里把常见场景翻译成可行动的提示，其余原样透传。
 */
export function cloudFailureText(error: Error): string {
  const code = error instanceof LocalAppError ? error.code : "";
  if (code === "CLOUD_UNAVAILABLE") return "云端服务暂时不可用，请检查网络后重试";
  if (code === "UNAUTHORIZED") return "云端登录状态已过期，请重新登录";
  return error.message;
}
