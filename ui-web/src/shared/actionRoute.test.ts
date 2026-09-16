import { describe, expect, it } from "vitest";
import { actionRoute } from "./actionRoute";
import type { LearningActionSummary } from "./types";

function action(overrides: Partial<LearningActionSummary>): LearningActionSummary {
  return {
    id: "a1",
    type: "RETRY_EXERCISE",
    title: "巩固：选择列",
    description: "最近提交出现连续失败，建议重练“查询学生姓名”。",
    priority: 75,
    exerciseId: "query-01",
    knowledgePoint: "选择列",
    reason: "REPEATED_FAILURE",
    ...overrides,
  };
}

describe("actionRoute", () => {
  it("routes RETRY_EXERCISE to its exercise even when a knowledge point is present", () => {
    expect(actionRoute(action({}))).toBe("/practice?exercise=query-01");
  });

  it("keeps REVIEW_KNOWLEDGE on the knowledge page", () => {
    expect(
      actionRoute(action({ type: "REVIEW_KNOWLEDGE", exerciseId: "", knowledgePoint: "连接" })),
    ).toBe(`/knowledge?query=${encodeURIComponent("连接")}`);
  });

  it("keeps RETRY_ACTIVITY on the activity runner", () => {
    expect(actionRoute(action({ type: "RETRY_ACTIVITY" }))).toBe(
      "/practice?activity=query-01",
    );
  });

  it("sends assignment and feedback actions to the cloud workspace even with an exerciseId", () => {
    expect(actionRoute(action({ type: "COMPLETE_ASSIGNMENT" }))).toBe("/cloud");
    expect(actionRoute(action({ type: "REVIEW_FEEDBACK", exerciseId: "" }))).toBe("/cloud");
  });

  it("falls back by exerciseId, then knowledge point, then practice", () => {
    expect(actionRoute(action({ type: "UNKNOWN" }))).toBe("/practice?exercise=query-01");
    expect(
      actionRoute(action({ type: "UNKNOWN", exerciseId: "", knowledgePoint: "排序" })),
    ).toBe(`/knowledge?query=${encodeURIComponent("排序")}`);
    expect(actionRoute(action({ type: "UNKNOWN", exerciseId: "", knowledgePoint: "" }))).toBe(
      "/practice",
    );
  });
});
