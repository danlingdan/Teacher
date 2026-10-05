import { describe, expect, it } from "vitest";
import {
  AI_PROVIDER_PRESETS,
  AI_PROVIDER_PRESET_GROUPS,
  CUSTOM_PRESET_ID,
  matchPresetByEndpoint,
} from "./aiProviderPresets";

// v3.10.0 HAJ-8：预设是前端 UX 清单，但端点最终要过 Java 侧 AiProviderProfile 校验
// （必须 https，http 仅限本机回环；禁 query/userinfo/fragment）。这里守住同一条硬约束。
const GROUP_IDS = new Set(AI_PROVIDER_PRESET_GROUPS.map((group) => group.id));
const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "::1"]);

describe("AI provider presets", () => {
  it("keeps every endpoint Java-safe: https only, or http on a loopback host; no query, fragment or userinfo", () => {
    for (const preset of AI_PROVIDER_PRESETS) {
      const url = new URL(preset.endpoint);
      if (url.protocol === "http:") {
        expect(LOOPBACK_HOSTS.has(url.hostname.replace(/^\[|\]$/g, "")), preset.id).toBe(true);
      } else {
        expect(url.protocol, preset.id).toBe("https:");
      }
      expect(preset.endpoint, preset.id).not.toContain("?");
      expect(preset.endpoint, preset.id).not.toContain("#");
      expect(preset.endpoint, preset.id).not.toContain("@");
    }
  });

  it("has unique ids, non-empty display names, endpoints and models, and known groups", () => {
    const ids = AI_PROVIDER_PRESETS.map((preset) => preset.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const preset of AI_PROVIDER_PRESETS) {
      expect(preset.displayName.trim(), preset.id).not.toBe("");
      expect(preset.endpoint.trim(), preset.id).not.toBe("");
      expect(preset.defaultModel.trim(), preset.id).not.toBe("");
      expect(GROUP_IDS.has(preset.group), preset.id).toBe(true);
    }
    expect(CUSTOM_PRESET_ID).toBe("custom");
  });

  it("matches each preset by its endpoint and returns undefined for unknown endpoints", () => {
    for (const preset of AI_PROVIDER_PRESETS) {
      expect(matchPresetByEndpoint(preset.endpoint)?.id, preset.id).toBe(preset.id);
    }
    expect(matchPresetByEndpoint("https://api.example.com/v1")).toBeUndefined();
    expect(matchPresetByEndpoint("")).toBeUndefined();
  });

  it("keeps non-empty key URLs on https so they can be shown as text hints", () => {
    for (const preset of AI_PROVIDER_PRESETS) {
      if (preset.keyUrl) {
        expect(new URL(preset.keyUrl).protocol, preset.id).toBe("https:");
      }
    }
  });
});
