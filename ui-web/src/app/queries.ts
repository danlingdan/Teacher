import { queryOptions } from "@tanstack/react-query";
import { localAppRequest } from "../shared/ipc";
import type { CourseWorkspace, HealthResult, HomeSummary, KnowledgeSample, SessionResult } from "../shared/types";

export const healthQuery = queryOptions({ queryKey: ["local-app", "health"], queryFn: () => localAppRequest<HealthResult>("system.health"), staleTime: 30_000 });
export const sessionQuery = queryOptions({ queryKey: ["session", "current"], queryFn: () => localAppRequest<SessionResult>("session.current"), staleTime: 5 * 60_000 });
export const homeQuery = queryOptions({ queryKey: ["learning", "home"], queryFn: () => localAppRequest<HomeSummary>("home.summary"), staleTime: 15_000 });
export const knowledgeQuery = queryOptions({ queryKey: ["knowledge", "sample"], queryFn: () => localAppRequest<KnowledgeSample>("knowledge.sample"), staleTime: Infinity });
export const courseWorkspaceQuery = queryOptions({ queryKey: ["course", "workspace"], queryFn: () => localAppRequest<CourseWorkspace>("course.workspace"), staleTime: 30_000 });
