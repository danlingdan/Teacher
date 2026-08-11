import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { RoleGuard } from "./RoleGuard";
import { sessionQuery } from "./queries";
import type { SessionResult } from "../shared/types";

function renderGuard(role: SessionResult["role"]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(sessionQuery.queryKey, { subjectId: "test", displayName: "测试", role, authenticated: true, permissions: [] } satisfies SessionResult);
  return render(<QueryClientProvider client={client}><RoleGuard allow={["TEACHER", "ADMINISTRATOR"]}><div>教师内容</div></RoleGuard></QueryClientProvider>);
}

describe("RoleGuard", () => {
  it("denies student sessions sourced from the Java contract", () => { renderGuard("STUDENT"); expect(screen.getByText("当前角色无法访问")).toBeInTheDocument(); });
  it("allows teacher sessions", () => { renderGuard("TEACHER"); expect(screen.getByText("教师内容")).toBeInTheDocument(); });
});
