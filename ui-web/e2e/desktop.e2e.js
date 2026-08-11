import fs from "node:fs";
import path from "node:path";

describe("SQLTeacher Alpha.7 packaged desktop", () => {
  it("loads the Java-backed shell, guards roles and captures the visual baseline", async () => {
    await expect($(".brand small")).toHaveText("3.0 Alpha.7");
    await expect($(".sidebar-status strong")).toHaveText("Java 核心已连接", { wait: 15_000 });
    await $("a[href='#/teaching']").click();
    await expect($(".ui-feedback.warning")).toBeDisplayed();
    await $("a[href='#/knowledge']").click();
    await expect($(".knowledge-workspace")).toBeDisplayed({ wait: 15_000 });
    await $("a[href='#/practice']").click();
    await expect($(".practice-workspace")).toBeDisplayed({ wait: 15_000 });
    await $("a[href='#/data']").click();
    await expect($(".data-workspace")).toBeDisplayed({ wait: 15_000 });
    await $("a[href='#/cloud']").click();
    await expect($(".ui-empty, .platform-workspace")).toBeDisplayed({ wait: 15_000 });
    await $("a[href='#/settings']").click();
    await expect($(".platform-workspace")).toBeDisplayed({ wait: 15_000 });
    await $("a[href='#/migration']").click();
    await expect($(".parity-list")).toBeDisplayed({ wait: 15_000 });
    const artifactDir = path.resolve("..", "target", "e2e-artifacts");
    fs.mkdirSync(artifactDir, { recursive: true });
    await browser.saveScreenshot(path.join(artifactDir, "alpha7-migration-workspace.png"));
    await $("a[href='#/design-system']").click();
    await expect($(".design-grid")).toBeDisplayed();
    await browser.saveScreenshot(path.join(artifactDir, "alpha7-design-system.png"));
  });
});
