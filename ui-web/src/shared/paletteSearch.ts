import { useQuery } from "@tanstack/react-query";
import { localAppRequest } from "./ipc";
import type {
  CloudWorkspace,
  ConnectionSummary,
  ExerciseCatalogItem,
  ExerciseCatalogPage,
  KnowledgeSearchItem,
  KnowledgeSearchResult,
} from "./types";

/** 命令面板的实体来源；组顺序即展示顺序。 */
export type PaletteGroup =
  | "page"
  | "exercise"
  | "knowledge"
  | "class"
  | "connection";

export interface PaletteEntry {
  key: string;
  group: PaletteGroup;
  title: string;
  detail?: string;
  path: string;
  /** 仅页面组携带 Ctrl+N 快捷键提示。 */
  shortcut?: string;
}

export interface PalettePageItem {
  label: string;
  detail?: string;
  to: string;
}

export interface PaletteSection {
  group: PaletteGroup;
  label: string;
  entries: PaletteEntry[];
}

export const paletteGroupLabels: Record<PaletteGroup, string> = {
  page: "页面",
  exercise: "题目",
  knowledge: "知识文档",
  class: "班级",
  connection: "连接",
};

const maxPerGroup = 5;

function matches(query: string, text: string): boolean {
  return text.toLowerCase().includes(query.trim().toLowerCase());
}

/**
 * 合并页面与实体搜索结果；每组最多 5 条，组内或组级无数据时省略该组。
 * 班级与连接数据源无服务端过滤，这里统一做标题子串过滤兜底。
 */
export function buildPaletteSections(args: {
  query: string;
  pages: PalettePageItem[];
  exercises?: ExerciseCatalogItem[];
  knowledge?: KnowledgeSearchItem[];
  classes?: CloudWorkspace["classes"];
  connections?: ConnectionSummary[];
}): PaletteSection[] {
  const query = args.query.trim();
  const sections: PaletteSection[] = [];
  const pageEntries = args.pages
    .filter((page) => matches(query, `${page.label} ${page.detail ?? ""} ${page.to}`))
    .slice(0, maxPerGroup)
    .map((page, index) => ({
      key: `page:${page.to}`,
      group: "page" as const,
      title: page.label,
      detail: page.detail,
      path: page.to,
      shortcut: index < 6 ? `Ctrl ${index + 1}` : undefined,
    }));
  if (pageEntries.length > 0)
    sections.push({ group: "page", label: paletteGroupLabels.page, entries: pageEntries });

  if (args.exercises?.length) {
    const entries = args.exercises
      .filter((item) => matches(query, item.title))
      .slice(0, maxPerGroup)
      .map((item) => ({
        key: `exercise:${item.id}`,
        group: "exercise" as const,
        title: item.title,
        detail: item.knowledgePoint || "练习",
        path: `/practice?exercise=${encodeURIComponent(item.id)}`,
      }));
    if (entries.length > 0)
      sections.push({ group: "exercise", label: paletteGroupLabels.exercise, entries });
  }

  if (args.knowledge?.length) {
    const entries = args.knowledge
      .filter((item) => matches(query, item.title))
      .slice(0, maxPerGroup)
      .map((item) => ({
        key: `knowledge:${item.articleId}`,
        group: "knowledge" as const,
        title: item.title,
        detail: item.sourceName || "知识文档",
        path: `/knowledge?article=${encodeURIComponent(item.articleId)}`,
      }));
    if (entries.length > 0)
      sections.push({ group: "knowledge", label: paletteGroupLabels.knowledge, entries });
  }

  if (args.classes?.length) {
    const entries = args.classes
      .filter((item) => matches(query, item.name))
      .slice(0, maxPerGroup)
      .map((item) => ({
        key: `class:${item.id}`,
        group: "class" as const,
        title: item.name,
        detail: `${item.members.length} 名成员`,
        path: `/cloud?class=${encodeURIComponent(item.id)}`,
      }));
    if (entries.length > 0)
      sections.push({ group: "class", label: paletteGroupLabels.class, entries });
  }

  if (args.connections?.length) {
    const entries = args.connections
      .filter((item) => matches(query, `${item.displayName} ${item.dialect}`))
      .slice(0, maxPerGroup)
      .map((item) => ({
        key: `connection:${item.id}`,
        group: "connection" as const,
        title: item.displayName,
        detail: item.dialect,
        path: `/data?connection=${encodeURIComponent(item.id)}`,
      }));
    if (entries.length > 0)
      sections.push({ group: "connection", label: paletteGroupLabels.connection, entries });
  }

  return sections;
}

/** 实体搜索仅在面板打开且输入至少 2 个字符时触发。 */
function paletteSearchEnabled(open: boolean, query: string): boolean {
  return open && query.trim().length >= 2;
}

/** 题目：复用练习目录的服务端关键字过滤。 */
export function usePaletteExercises(open: boolean, query: string) {
  return useQuery({
    queryKey: ["palette", "exercises", query.trim()],
    queryFn: () =>
      localAppRequest<ExerciseCatalogPage>("practice.catalog", {
        page: 0,
        pageSize: 5,
        q: query.trim(),
        difficulty: "",
        status: "",
      }),
    enabled: paletteSearchEnabled(open, query),
    staleTime: 60_000,
  });
}

/** 知识文档：与知识页检索同一端点。 */
export function usePaletteKnowledge(open: boolean, query: string) {
  return useQuery({
    queryKey: ["palette", "knowledge", query.trim()],
    queryFn: () =>
      localAppRequest<KnowledgeSearchResult>("knowledge.search", {
        query: query.trim(),
        limit: 5,
      }),
    enabled: paletteSearchEnabled(open, query),
    staleTime: 60_000,
  });
}

/** 班级：登录后才有数据；未登录或失败时该组静默隐藏。过滤在客户端完成，
 * 缓存 key 与 CloudPage 共用，避免每次按键都请求云端。 */
export function usePaletteClasses(open: boolean, query: string) {
  return useQuery({
    queryKey: ["cloud", "workspace"],
    queryFn: () => localAppRequest<CloudWorkspace>("cloud.workspace"),
    enabled: paletteSearchEnabled(open, query),
    staleTime: 60_000,
    retry: false,
  });
}

/** 数据库连接：本地列表，客户端过滤。 */
export function usePaletteConnections(open: boolean, query: string) {
  return useQuery({
    queryKey: ["palette", "connections"],
    queryFn: () =>
      localAppRequest<{ items: ConnectionSummary[] }>("data.connections"),
    enabled: paletteSearchEnabled(open, query),
    staleTime: 60_000,
    retry: false,
  });
}
