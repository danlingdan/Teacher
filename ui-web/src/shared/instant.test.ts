import { describe, expect, it } from "vitest";
import { formatInstant, parseInstant } from "./instant";

describe("parseInstant", () => {
  it("把秒级数值当作秒解析（Java Instant 默认序列化）", () => {
    const date = parseInstant(1757721600);
    expect(date).not.toBeNull();
    expect(date!.getTime()).toBe(1757721600_000);
  });

  it("把毫秒级数值当作毫秒解析", () => {
    const date = parseInstant(1757721600000);
    expect(date!.getTime()).toBe(1757721600000);
  });

  it("解析带小数秒的数值", () => {
    const date = parseInstant("1757721600.5");
    expect(date!.getTime()).toBe(1757721600500);
  });

  it("解析 ISO 字符串", () => {
    const date = parseInstant("2026-09-14T00:00:00Z");
    expect(date!.toISOString()).toBe("2026-09-14T00:00:00.000Z");
  });

  it("无法解析时返回 null", () => {
    expect(parseInstant("not-a-date")).toBeNull();
    expect(parseInstant(Number.NaN)).toBeNull();
  });
});

describe("formatInstant", () => {
  it("以 zh-CN 格式化有效时间", () => {
    const text = formatInstant("2026-09-14T00:05:00Z");
    expect(text).not.toBe("时间未知");
    expect(text).toContain("2026");
  });

  it("无法解析时返回占位文案", () => {
    expect(formatInstant("时间戳缺失")).toBe("时间未知");
  });
});
