import { describe, expect, it } from "vitest";
import { installEnglishUi, translateUiText, translations } from "./uiI18n";

describe("English UI compatibility layer", () => {
  it("translates complete workflow phrases before generic labels", () => {
    expect(translateUiText("确认并开始活动")).toBe("Confirm and start activity");
    expect(translateUiText("正在读取本地学习摘要")).toBe("Loading local learning summary");
  });

  it("translates newly rendered UI but leaves course markdown alone", async () => {
    document.body.innerHTML = '<main><button aria-label="快速导航">继续学习</button><article class="knowledge-markdown">课程正文</article></main>';
    const stop = installEnglishUi();
    expect(document.querySelector("button")?.textContent).toBe("Continue learning");
    expect(document.querySelector("button")?.getAttribute("aria-label")).toBe("Quick navigation");
    const span = document.createElement("span");
    span.textContent = "设置已保存";
    document.querySelector("main")?.append(span);
    await new Promise(resolve => setTimeout(resolve));
    expect(span.textContent).toBe("Settings saved");
    expect(document.querySelector("article")?.textContent).toBe("课程正文");
    stop();
  });
});

describe("translation table integrity", () => {
  it("has no duplicate source strings (later entries used to silently override earlier ones)", () => {
    const sources = translations.map(([source]) => source);
    expect(new Set(sources).size).toBe(sources.length);
  });

  it("maps previously conflicting labels to their canonical translations", () => {
    expect(translateUiText("复制")).toBe("Duplicate");
    expect(translateUiText("待处理")).toBe("Pending");
  });
});
