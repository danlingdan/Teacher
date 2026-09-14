// app/queries.ts 直接测试（v3.4.0 TST-7）：不渲染组件，直接驱动 queryFn，
// 验证桥接方法映射、错误传播与查询键稳定性。
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  courseWorkspaceQuery,
  healthQuery,
  homeQuery,
  sessionQuery,
  settingsPreferencesQuery,
} from "./queries";

const requestMock = vi.fn();
vi.mock("../shared/ipc", () => ({
  localAppRequest: (...args: unknown[]) => requestMock(...args),
}));

type QueryShape = {
  queryKey: readonly unknown[];
  queryFn: (context: { signal: AbortSignal }) => Promise<unknown>;
};

async function runQueryFn(options: QueryShape): Promise<unknown> {
  return options.queryFn({ signal: new AbortController().signal });
}

const cases: Array<{ name: string; options: QueryShape; method: string }> = [
  { name: "healthQuery", options: healthQuery as unknown as QueryShape, method: "system.health" },
  {
    name: "sessionQuery",
    options: sessionQuery as unknown as QueryShape,
    method: "session.current",
  },
  { name: "homeQuery", options: homeQuery as unknown as QueryShape, method: "home.summary" },
  {
    name: "courseWorkspaceQuery",
    options: courseWorkspaceQuery as unknown as QueryShape,
    method: "course.workspace",
  },
  {
    name: "settingsPreferencesQuery",
    options: settingsPreferencesQuery as unknown as QueryShape,
    method: "settings.preferences",
  },
];

describe("app/queries", () => {
  beforeEach(() => {
    requestMock.mockReset();
  });

  it.each(cases)(
    "$name calls $method and returns the payload unchanged",
    async ({ options, method }) => {
      const payload = { marker: method };
      requestMock.mockResolvedValue(payload);

      await expect(runQueryFn(options)).resolves.toBe(payload);
      expect(requestMock).toHaveBeenCalledTimes(1);
      expect(requestMock).toHaveBeenCalledWith(method);
    },
  );

  it("propagates bridge errors so react-query can surface the failure", async () => {
    const failure = new Error("本地桥接不可用");
    requestMock.mockRejectedValue(failure);

    await expect(runQueryFn(settingsPreferencesQuery as unknown as QueryShape)).rejects.toBe(
      failure,
    );
  });

  it("keeps query keys stable and unique across queries", () => {
    // 缓存失效（如 logout 后 invalidate ["session","current"]）依赖这些键不变。
    expect(healthQuery.queryKey).toEqual(["local-app", "health"]);
    expect(sessionQuery.queryKey).toEqual(["session", "current"]);
    expect(homeQuery.queryKey).toEqual(["learning", "home"]);
    expect(courseWorkspaceQuery.queryKey).toEqual(["course", "workspace"]);
    expect(settingsPreferencesQuery.queryKey).toEqual(["settings", "preferences"]);
    const serialized = cases.map(({ options }) => JSON.stringify(options.queryKey));
    expect(new Set(serialized).size).toBe(serialized.length);
  });
});
