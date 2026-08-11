import { describe, expect, it } from "vitest";
import { installEnglishUi, translateUiText } from "./uiI18n";

describe("English UI compatibility layer", () => {
  it("translates complete workflow phrases before generic labels", () => {
    expect(translateUiText("确认题目并开始作答")).toBe("Confirm and start");
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
