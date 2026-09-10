import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { ExerciseCatalogItem } from "../../shared/types";
import {
  ExerciseCatalogPanel,
  filterCatalogItems,
} from "./ExerciseCatalog";

function item(overrides: Partial<ExerciseCatalogItem>): ExerciseCatalogItem {
  return {
    id: "ex-1",
    title: "题目",
    knowledgePoint: "基础查询",
    difficulty: "BEGINNER",
    exerciseType: "QUERY",
    version: 3,
    attempts: 0,
    passed: false,
    lastAttemptAt: null,
    ...overrides,
  };
}

const catalogItems: ExerciseCatalogItem[] = [
  item({
    id: "query-01",
    title: "查询全部学生",
    difficulty: "BEGINNER",
    attempts: 3,
    passed: true,
  }),
  item({ id: "filter-01", title: "筛选及格学生", difficulty: "BEGINNER" }),
  item({
    id: "join-01",
    title: "查询选课明细",
    knowledgePoint: "内连接",
    difficulty: "INTERMEDIATE",
    attempts: 1,
  }),
  item({
    id: "subquery-03",
    title: "未选数据库课程的学生",
    knowledgePoint: "NOT EXISTS",
    difficulty: "ADVANCED",
  }),
];

const baseFilters = { query: "", difficulty: "", status: "" };

describe("ExerciseCatalogPanel", () => {
  it("groups items by difficulty with counts and status badges", () => {
    const { container } = render(
      <ExerciseCatalogPanel
        items={catalogItems}
        isPending={false}
        filters={baseFilters}
        onFilterChange={vi.fn()}
        onSelect={vi.fn()}
      />,
    );

    const headers = Array.from(
      container.querySelectorAll(".catalog-group-title"),
    ).map((element) => element.textContent);
    expect(headers).toEqual(["入门 · 2 题", "进阶 · 1 题", "高级 · 1 题"]);
    // 未完成的题排在前面，已通过的沉底。
    const badges = Array.from(
      container.querySelectorAll(".catalog-badge"),
    ).map((element) => element.textContent);
    expect(badges).toEqual(["未做", "已通过", "未通过", "未做"]);
  });

  it("filters by difficulty, status, and search text", () => {
    const filtered = filterCatalogItems(catalogItems, {
      ...baseFilters,
      difficulty: "BEGINNER",
    });
    expect(filtered.map((entry) => entry.id)).toEqual([
      "query-01",
      "filter-01",
    ]);

    const passedOnly = filterCatalogItems(catalogItems, {
      ...baseFilters,
      status: "passed",
    });
    expect(passedOnly.map((entry) => entry.id)).toEqual(["query-01"]);

    const searched = filterCatalogItems(catalogItems, {
      ...baseFilters,
      query: "选课",
    });
    expect(searched.map((entry) => entry.id)).toEqual(["join-01"]);
  });

  it("reports empty results and notifies selection", () => {
    const onSelect = vi.fn();
    const { rerender } = render(
      <ExerciseCatalogPanel
        items={catalogItems}
        isPending={false}
        filters={{ ...baseFilters, query: "不存在" }}
        onFilterChange={vi.fn()}
        onSelect={onSelect}
      />,
    );
    expect(screen.getByText("没有匹配的题目。")).toBeInTheDocument();

    rerender(
      <ExerciseCatalogPanel
        items={catalogItems}
        isPending={false}
        selectedId="query-01"
        filters={baseFilters}
        onFilterChange={vi.fn()}
        onSelect={onSelect}
      />,
    );
    fireEvent.click(screen.getByText("查询全部学生"));
    expect(onSelect).toHaveBeenCalledWith("query-01");
  });

  it("shows the loading state instead of the empty hint", () => {
    render(
      <ExerciseCatalogPanel
        items={[]}
        isPending
        filters={baseFilters}
        onFilterChange={vi.fn()}
        onSelect={vi.fn()}
      />,
    );
    expect(screen.getByText("加载中…")).toBeInTheDocument();
    expect(screen.queryByText("没有匹配的题目。")).not.toBeInTheDocument();
  });
});
