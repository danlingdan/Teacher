import { useMemo, type ReactNode } from "react";
import type { ExerciseCatalogItem } from "../../shared/types";

const difficultyOrder: Record<string, number> = {
  BEGINNER: 0,
  INTERMEDIATE: 1,
  ADVANCED: 2,
};

const difficultyNames: Record<string, string> = {
  BEGINNER: "入门",
  INTERMEDIATE: "进阶",
  ADVANCED: "高级",
};

const statusNames = {
  passed: "已通过",
  failed: "未通过",
  todo: "未做",
} as const;

const typeNames: Record<string, string> = {
  QUERY: "查询",
  STATE: "写操作",
  SCRIPT: "脚本",
  TRIGGER: "触发器",
};

export function exerciseTypeLabel(value: string) {
  return typeNames[value] ?? "查询";
}

export type CatalogStatus = keyof typeof statusNames;

export function catalogItemStatus(item: ExerciseCatalogItem): CatalogStatus {
  return item.passed ? "passed" : item.attempts > 0 ? "failed" : "todo";
}

function difficultyLabel(value: string) {
  return difficultyNames[value] ?? value;
}

function knowledgePointLabel(value: string) {
  return !value || value === "NOT EXISTS" ? "未设置知识点" : value;
}

export interface ExerciseCatalogFilters {
  query: string;
  difficulty: string;
  status: string;
}

interface ExerciseCatalogPanelProps {
  items: ExerciseCatalogItem[];
  isPending: boolean;
  selectedId?: string;
  filters: ExerciseCatalogFilters;
  onFilterChange: (key: "q" | "difficulty" | "status", value: string) => void;
  onSelect: (exerciseId: string) => void;
  /** Optional action (e.g. 题库更新) rendered in the panel header. */
  headerAction?: ReactNode;
  /** Server-side pagination (W4.4): total on the server and the page controls. */
  total?: number;
  page?: number;
  pageSize?: number;
  onPageChange?: (page: number) => void;
}

export function filterCatalogItems(
  items: ExerciseCatalogItem[],
  filters: ExerciseCatalogFilters,
) {
  const query = filters.query.trim().toLowerCase();
  return items.filter((item) => {
    const matchesQuery =
      query === "" ||
      `${item.title} ${item.knowledgePoint}`.toLowerCase().includes(query);
    const matchesDifficulty =
      filters.difficulty === "" || item.difficulty === filters.difficulty;
    const matchesStatus =
      filters.status === "" || catalogItemStatus(item) === filters.status;
    return matchesQuery && matchesDifficulty && matchesStatus;
  });
}

export function ExerciseCatalogPanel({
  items,
  isPending,
  selectedId,
  filters,
  onFilterChange,
  onSelect,
  headerAction,
  total,
  page = 0,
  pageSize = 50,
  onPageChange,
}: ExerciseCatalogPanelProps) {
  const filtered = useMemo(
    () => filterCatalogItems(items, filters),
    [items, filters],
  );
  const groups = useMemo(() => {
    const byDifficulty = new Map<string, ExerciseCatalogItem[]>();
    for (const item of filtered) {
      const list = byDifficulty.get(item.difficulty) ?? [];
      list.push(item);
      byDifficulty.set(item.difficulty, list);
    }
    return [...byDifficulty.entries()]
      .sort(
        (a, b) =>
          (difficultyOrder[a[0]] ?? 9) - (difficultyOrder[b[0]] ?? 9),
      )
      .map(
        ([difficulty, groupItems]) =>
          [
            difficulty,
            groupItems.sort(
              (a, b) =>
                (a.passed ? 1 : 0) - (b.passed ? 1 : 0) ||
                a.title.localeCompare(b.title, "zh"),
            ),
          ] as const,
      );
  }, [filtered]);

  return (
    <aside className="content-card selection-panel">
      <div className="section-heading">
        <div>
          <p className="eyebrow">题目目录</p>
          <strong>
            {isPending ? "加载中…" : `${filtered.length} 道题`}
          </strong>
        </div>
      </div>
      {headerAction && <div className="catalog-toolbar">{headerAction}</div>}
      <input
        aria-label="搜索练习题"
        value={filters.query}
        onChange={(event) => onFilterChange("q", event.target.value)}
        placeholder="搜索题目或知识点"
      />
      <div className="catalog-filters">
        <select
          aria-label="按难度筛选"
          value={filters.difficulty}
          onChange={(event) =>
            onFilterChange("difficulty", event.target.value)
          }
        >
          <option value="">全部难度</option>
          <option value="BEGINNER">入门</option>
          <option value="INTERMEDIATE">进阶</option>
          <option value="ADVANCED">高级</option>
        </select>
        <select
          aria-label="按状态筛选"
          value={filters.status}
          onChange={(event) => onFilterChange("status", event.target.value)}
        >
          <option value="">全部状态</option>
          <option value="todo">未做</option>
          <option value="failed">未通过</option>
          <option value="passed">已通过</option>
        </select>
      </div>
      {isPending ? (
        <section className="page-skeleton">
          <span className="spinner" />
          正在加载题目目录
        </section>
      ) : (
        groups.map(([difficulty, groupItems]) => (
          <div className="catalog-group" key={difficulty}>
            <p className="catalog-group-title">
              {difficultyLabel(difficulty)}
              <span> · {groupItems.length} 题</span>
            </p>
            {groupItems.map((item) => {
              const status = catalogItemStatus(item);
              return (
                <button
                  type="button"
                  className={selectedId === item.id ? "selected" : ""}
                  key={item.id}
                  onClick={() => onSelect(item.id)}
                >
                  <span className="catalog-item-title">{item.title}</span>
                  <small>
                    {exerciseTypeLabel(item.exerciseType)} ·{" "}
                    {knowledgePointLabel(item.knowledgePoint)} ·{" "}
                    {difficultyLabel(item.difficulty)}
                    {item.bestScore != null ? ` · ${item.bestScore} 分` : ""}
                    <span className={`catalog-badge ${status}`}>
                      {statusNames[status]}
                    </span>
                  </small>
                </button>
              );
            })}
          </div>
        ))
      )}
      {!isPending && filtered.length === 0 && (
        <p className="muted">没有匹配的题目。</p>
      )}
      {onPageChange && total != null && total > pageSize && (
        <div className="compact-pager" aria-label="目录分页">
          <button
            type="button"
            disabled={page === 0}
            onClick={() => onPageChange(page - 1)}
          >
            上一页
          </button>
          <span>
            第 {page + 1} / {Math.ceil(total / pageSize)} 页 · 共 {total} 题
          </span>
          <button
            type="button"
            disabled={(page + 1) * pageSize >= total}
            onClick={() => onPageChange(page + 1)}
          >
            下一页
          </button>
        </div>
      )}
    </aside>
  );
}
