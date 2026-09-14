import { afterEach, describe, expect, it, vi } from "vitest";
import { log, measure } from "./telemetry";

describe("telemetry", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("routes each level to its console writer and emits one JSON record", () => {
    const info = vi.spyOn(console, "info").mockImplementation(() => {});
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const error = vi.spyOn(console, "error").mockImplementation(() => {});

    log("info", "ui.event", { method: "sql.execute" });
    log("warn", "ui.degraded");
    log("error", "ui.failed", { code: "LOCAL_BRIDGE_FAILED" });

    expect(info).toHaveBeenCalledTimes(1);
    expect(warn).toHaveBeenCalledTimes(1);
    expect(error).toHaveBeenCalledTimes(1);
    const record = JSON.parse(String(info.mock.calls[0]?.[0])) as {
      timestamp: string;
      level: string;
      event: string;
      method?: string;
    };
    expect(record.level).toBe("info");
    expect(record.event).toBe("ui.event");
    expect(record.method).toBe("sql.execute");
    expect(Number.isNaN(new Date(record.timestamp).getTime())).toBe(false);
    expect(String(error.mock.calls[0]?.[0])).toContain('"code":"LOCAL_BRIDGE_FAILED"');
  });

  it("measures elapsed time and records a performance entry", () => {
    const info = vi.spyOn(console, "info").mockImplementation(() => {});
    const measureSpy = vi
      .spyOn(performance, "measure")
      .mockReturnValue({} as PerformanceMeasure);

    const started = performance.now() - 100;
    measure("ipc:sql.execute", started, { method: "sql.execute" });

    expect(measureSpy).toHaveBeenCalledWith(
      "ipc:sql.execute",
      expect.objectContaining({ start: started, end: expect.any(Number) }),
    );
    const record = JSON.parse(String(info.mock.calls[0]?.[0])) as {
      event: string;
      name: string;
      durationMs: number;
      method?: string;
    };
    expect(record.event).toBe("performance.measure");
    expect(record.name).toBe("ipc:sql.execute");
    expect(record.method).toBe("sql.execute");
    expect(record.durationMs).toBeGreaterThanOrEqual(99);
    expect(record.durationMs).toBeLessThanOrEqual(101);
  });
});
