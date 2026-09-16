import type { LearningActionSummary } from "./types";

/**
 * 首页学习动作的确定性路由。动作类型优先；exerciseId / knowledgePoint 只作
 * 缺失字段时的逐级兜底——"巩固：选择列"这类 RETRY_EXERCISE 动作同时携带
 * 知识点，若先判知识点会把用户劫持到知识页而不是对应题目。
 */
export function actionRoute(action: LearningActionSummary): string {
  if (action.type === "REVIEW_KNOWLEDGE") {
    return `/knowledge?query=${encodeURIComponent(action.knowledgePoint)}`;
  }
  if (action.type === "RETRY_ACTIVITY") {
    return `/practice?activity=${encodeURIComponent(action.exerciseId)}`;
  }
  if (action.type === "COMPLETE_ASSIGNMENT" || action.type === "REVIEW_FEEDBACK") {
    return "/cloud";
  }
  if (action.exerciseId) {
    return `/practice?exercise=${encodeURIComponent(action.exerciseId)}`;
  }
  if (action.knowledgePoint) {
    return `/knowledge?query=${encodeURIComponent(action.knowledgePoint)}`;
  }
  return "/practice";
}
