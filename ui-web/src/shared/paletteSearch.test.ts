import { describe, expect, it } from "vitest";
import {
  buildPaletteSections,
  type PalettePageItem,
} from "./paletteSearch";

const pages: PalettePageItem[] = [
  { label: "今天", detail: "学习概览", to: "/today" },
  { label: "练习与实验", detail: "编码与活动", to: "/practice" },
];

const exercises = [
  {
    id: "ex-1",
    title: "查询 B 班学生",
    knowledgePoint: "文本条件",
    difficulty: "BEGINNER",
    exerciseType: "QUERY" as const,
    version: 1,
    attempts: 0,
    passed: false,
    lastAttemptAt: null,
    bestScore: null,
  },
];

const knowledge = [
  {
    articleId: "art-1",
    documentId: "doc-1",
    title: "进程调度",
    sourceName: "操作系统",
    chunkIndex: 0,
    snippet: "短作业优先",
    relevance: 1,
  },
];

const classes = [
  { id: "class-1", name: "软件2401", createdAt: "", members: [] },
];

const connections = [
  {
    id: "conn-1",
    displayName: "SQLite 演示数据库",
    dialect: "SQLITE",
    readOnly: false,
    enabled: true,
    builtIn: true,
    selected: true,
  },
];

describe("buildPaletteSections", () => {
  it("按 页面→题目→知识文档→班级→连接 分组并生成深链", () => {
    const sections = buildPaletteSections({
      query: "",
      pages,
      exercises,
      knowledge,
      classes,
      connections,
    });
    expect(sections.map((section) => section.group)).toEqual([
      "page",
      "exercise",
      "knowledge",
      "class",
      "connection",
    ]);
    const exerciseEntry = sections[1]?.entries[0];
    expect(exerciseEntry?.path).toBe("/practice?exercise=ex-1");
    expect(sections[2]?.entries[0]?.path).toBe("/knowledge?article=art-1");
    expect(sections[3]?.entries[0]?.path).toBe("/cloud?class=class-1");
    expect(sections[4]?.entries[0]?.path).toBe("/data?connection=conn-1");
  });

  it("按关键字过滤各组并保留页面组的 Ctrl+N 提示", () => {
    const sections = buildPaletteSections({
      query: "查询",
      pages,
      exercises,
      knowledge,
      classes,
      connections,
    });
    // "查询"只命中题目与练习页面（detail 含"编码与活动"不含"查询"→页面只剩无匹配）。
    expect(sections.map((section) => section.group)).toEqual(["exercise"]);
    expect(sections[0]?.entries[0]?.title).toBe("查询 B 班学生");
  });

  it("空查询只显示页面组且前六项带快捷键", () => {
    const manyPages: PalettePageItem[] = Array.from(
      { length: 8 },
      (_, index) => ({ label: `页面${index + 1}`, to: `/p${index + 1}` }),
    );
    const sections = buildPaletteSections({ query: "", pages: manyPages });
    expect(sections).toHaveLength(1);
    expect(sections[0]?.entries).toHaveLength(5);
    expect(sections[0]?.entries[0]?.shortcut).toBe("Ctrl 1");
    expect(sections[0]?.entries[4]?.shortcut).toBe("Ctrl 5");
  });

  it("每组最多 5 条并且无匹配的组被省略", () => {
    const manyExercises = Array.from({ length: 9 }, (_, index) => ({
      // 固定夹具必然有第一项；非空断言仅供类型检查。
      ...exercises[0]!,
      id: `ex-${index + 1}`,
      title: `题目 ${index + 1}`,
    }));
    const sections = buildPaletteSections({
      query: "题目",
      pages,
      exercises: manyExercises,
    });
    expect(sections).toHaveLength(1);
    expect(sections[0]?.entries).toHaveLength(5);
  });
});
