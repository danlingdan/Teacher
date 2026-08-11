import fs from "node:fs";
import path from "node:path";

async function chooseLanguage(value) {
  await browser.execute(next => {
    const select = document.querySelector(".settings-grid select");
    const setter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, "value")?.set;
    if (!(select instanceof HTMLSelectElement) || !setter) throw new Error("language selector is unavailable");
    setter.call(select, next);
    select.dispatchEvent(new Event("change", { bubbles: true }));
  }, value);
  await expect($(".settings-grid select")).toHaveValue(value);
}

describe("SQLTeacher 3.0 packaged desktop", () => {
  it("loads every production workspace through the Java-backed shell", async () => {
    await expect($(".brand small")).toHaveText("3.0");
    await $(".sidebar-status strong").waitForDisplayed({ timeout: 15_000 });
    if ((await $(".sidebar-status strong").getText()) === "Java core connected") {
      await $("a[href='#/settings']").click();
      await chooseLanguage("zh");
      await $(".hero-card button").click();
    }
    await expect($(".sidebar-status strong")).toHaveText("Java 核心已连接", { wait: 15_000 });
    await expect($$("a[href='#/teaching']")).toBeElementsArrayOfSize(0);
    await $("a[href='#/knowledge']").click();
    await expect($(".knowledge-workspace")).toBeDisplayed({ wait: 15_000 });
    await $("a[href='#/practice']").click();
    await expect($(".practice-workspace")).toBeDisplayed({ wait: 15_000 });
    await $("button=课程活动").click();
    await expect($(".selection-panel")).toBeDisplayed({ wait: 15_000 });
    await expect($(".preview-card")).toBeDisplayed({ wait: 15_000 });
    await $("button=确认并开始活动").click();
    await expect($(".activity-interaction")).toBeDisplayed({ wait: 15_000 });
    await $("a[href='#/data']").click();
    await expect($(".data-workspace")).toBeDisplayed({ wait: 15_000 });
    await expect($(".ai-panel h2")).toHaveText("自然语言生成 SQL");
    await $("a[href='#/cloud']").click();
    await expect($(".account-login, .platform-workspace")).toBeDisplayed({ wait: 15_000 });
    await $("a[href='#/settings']").click();
    await expect($(".platform-workspace")).toBeDisplayed({ wait: 15_000 });
    const artifactDir = path.resolve("..", "target", "e2e-artifacts");
    fs.mkdirSync(artifactDir, { recursive: true });
    await browser.saveScreenshot(path.join(artifactDir, "v3-settings-workspace.png"));
    await expect($$("a[href='#/migration'], a[href='#/design-system']")).toBeElementsArrayOfSize(0);
    await expect($("body")).not.toHaveText(expect.stringContaining("界面基线"));
    await chooseLanguage("en");
    await $(".hero-card button").click();
    await expect($(".sidebar-status strong")).toHaveText("Java core connected", { wait: 15_000 });
    await expect($("a[href='#/settings'] span")).toHaveText("Settings");
    await $("a[href='#/settings']").click();
    await expect($(".hero-card h2")).toHaveText("Common settings", { wait: 15_000 });
    await chooseLanguage("zh");
    await $(".hero-card button").click();
    await expect($(".sidebar-status strong")).toHaveText("Java 核心已连接", { wait: 15_000 });
  });
});
