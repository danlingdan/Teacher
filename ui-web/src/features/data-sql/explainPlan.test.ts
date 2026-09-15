import { describe, expect, it } from "vitest";
import { extractExplainDetails, interpretExplainRows } from "./explainPlan";

describe("interpretExplainRows", () => {
  it("SCAN → 全表扫描并给优化提示", () => {
    const [result] = interpretExplainRows(["SCAN student"]);
    expect(result!.reading).toBe("对 student 全表扫描");
    expect(result!.hint).toContain("索引");
  });

  it("SEARCH USING INDEX → 索引命中；主键查找单独识别", () => {
    const results = interpretExplainRows([
      "SEARCH sc USING INDEX idx_sc_sno (sno=?)",
      "SEARCH student USING INTEGER PRIMARY KEY (rowid=?)",
    ]);
    expect(results[0]!.reading).toContain("idx_sc_sno");
    expect(results[1]!.reading).toContain("主键");
  });

  it("TEMP B-TREE 的排序/分组/去重各有结论", () => {
    const readings = interpretExplainRows([
      "USE TEMP B-TREE FOR ORDER BY",
      "USE TEMP B-TREE FOR GROUP BY",
      "USE TEMP B-TREE FOR DISTINCT",
    ]).map((item) => item.reading);
    expect(readings[0]).toContain("排序");
    expect(readings[1]).toContain("分组");
    expect(readings[2]).toContain("去重");
  });

  it("未命中的行给出通用说明且保留原文", () => {
    const detail = "COMPOSITE SUBQUERIES 0 AND 1";
    const [result] = interpretExplainRows([detail]);
    expect(result!.detail).toBe(detail);
    expect(result!.reading).toBe("其他计划步骤");
  });
});

describe("extractExplainDetails", () => {
  it("提取 detail 列并丢弃空值", () => {
    expect(
      extractExplainDetails([
        { detail: "SCAN student" },
        { detail: null },
        { other: 1 },
      ]),
    ).toEqual(["SCAN student"]);
  });
});
