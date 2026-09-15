import { describe, expect, it } from "vitest";
import { splitTeachingNote } from "./teachingNote";

describe("splitTeachingNote", () => {
  it("无标记时原文返回、解读为空", () => {
    const result = splitTeachingNote("SQL 执行失败，请检查语法。");
    expect(result.main).toBe("SQL 执行失败，请检查语法。");
    expect(result.note).toBe("");
  });

  it("拆出标记后的教学解读", () => {
    const result = splitTeachingNote("SQL 执行失败。\n教学解读：违反了外键约束（参照完整性）。");
    expect(result.main).toBe("SQL 执行失败。");
    expect(result.note).toBe("违反了外键约束（参照完整性）。");
  });

  it("空输入返回空对", () => {
    expect(splitTeachingNote(undefined)).toEqual({ main: "", note: "" });
    expect(splitTeachingNote("")).toEqual({ main: "", note: "" });
  });
});
