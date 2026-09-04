import fs from "node:fs";
import path from "node:path";

const artifactDir = path.resolve("..", "target", "e2e-artifacts");
function screenshot(name) {
  fs.mkdirSync(artifactDir, { recursive: true });
  return browser.saveScreenshot(path.join(artifactDir, `v3-${name}.png`));
}

async function chooseLanguage(value) {
  await browser.execute((next) => {
    const select = document.querySelector(".settings-grid select");
    const setter = Object.getOwnPropertyDescriptor(
      HTMLSelectElement.prototype,
      "value",
    )?.set;
    if (!(select instanceof HTMLSelectElement) || !setter)
      throw new Error("language selector is unavailable");
    setter.call(select, next);
    select.dispatchEvent(new Event("change", { bubbles: true }));
  }, value);
  await expect($(".settings-grid select")).toHaveValue(value);
}

async function chooseSetting(label, value) {
  await browser.execute(
    (fieldLabel, next) => {
      const field = [...document.querySelectorAll(".ui-field")].find(
        (candidate) =>
          candidate.querySelector("label")?.textContent?.trim() === fieldLabel,
      );
      const select = field?.querySelector("select");
      const setter = Object.getOwnPropertyDescriptor(
        HTMLSelectElement.prototype,
        "value",
      )?.set;
      if (!(select instanceof HTMLSelectElement) || !setter)
        throw new Error(`${fieldLabel} selector is unavailable`);
      setter.call(select, next);
      select.dispatchEvent(new Event("change", { bubbles: true }));
    },
    label,
    value,
  );
}

async function contrastRatios(selector) {
  return browser.execute((targetSelector) => {
    const channel = (value) => {
      const normalized = value / 255;
      return normalized <= 0.03928
        ? normalized / 12.92
        : ((normalized + 0.055) / 1.055) ** 2.4;
    };
    const luminance = (color) => {
      const values =
        color
          .match(/[\d.]+/g)
          ?.slice(0, 3)
          .map(Number) ?? [];
      if (values.length !== 3) throw new Error(`Unsupported color: ${color}`);
      return (
        0.2126 * channel(values[0]) +
        0.7152 * channel(values[1]) +
        0.0722 * channel(values[2])
      );
    };
    return [...document.querySelectorAll(targetSelector)].map((element) => {
      const style = getComputedStyle(element);
      const foreground = luminance(style.color);
      const background = luminance(style.backgroundColor);
      return (
        (Math.max(foreground, background) + 0.05) /
        (Math.min(foreground, background) + 0.05)
      );
    });
  }, selector);
}

describe("SQLTeacher 3.0 packaged desktop", () => {
  it("loads every production workspace through the Java-backed shell", async () => {
    await expect($(".brand small")).toHaveText("Learning Studio");
    await $(".sidebar-status strong").waitForDisplayed({ timeout: 15_000 });
    if ((await $(".sidebar-status strong").getText()) === "Local core ready") {
      await $("a[href='#/settings']").click();
      await chooseLanguage("zh");
      await $("button=保存更改").click();
    }
    await expect($(".sidebar-status strong")).toHaveText("本地核心已就绪", {
      wait: 15_000,
    });
    await $("a[href='#/settings']").click();
    await $(".settings-intro").waitForDisplayed({ timeout: 15_000 });
    await chooseSetting("主题", "dark");
    await $("button=保存更改").click();
    await browser.waitUntil(
      () =>
        browser.execute(() =>
          document.documentElement.classList.contains("theme-dark"),
        ),
      { timeout: 15_000, timeoutMsg: "dark theme was not applied" },
    );
    await expect($$("a[href='#/teaching']")).toBeElementsArrayOfSize(0);
    await $("a[href='#/today']").click();
    await expect($(".page-grid")).toBeDisplayed({ wait: 15_000 });
    await screenshot("today");
    await $("a[href='#/knowledge']").click();
    await expect($(".knowledge-workspace")).toBeDisplayed({ wait: 15_000 });
    await expect($$(".course-tree details[open]")).toBeElementsArrayOfSize(0);
    await screenshot("knowledge");
    await $("a[href='#/practice']").click();
    await expect($(".practice-workspace")).toBeDisplayed({ wait: 15_000 });
    for (const ratio of await contrastRatios(".compact-pager .ui-button")) {
      expect(ratio).toBeGreaterThanOrEqual(4.5);
    }
    await screenshot("practice");
    await $("button=课程活动").click();
    await expect($(".selection-panel")).toBeDisplayed({ wait: 15_000 });
    await expect($(".preview-card")).toBeDisplayed({ wait: 15_000 });
    await $("button=确认并开始活动").click();
    await expect($(".activity-interaction")).toBeDisplayed({ wait: 15_000 });
    await $("a[href='#/data']").click();
    await expect($(".data-workspace")).toBeDisplayed({ wait: 15_000 });
    await expect($(".ai-panel h2")).toHaveText("自然语言生成 SQL");
    await screenshot("data-sql");
    await $("a[href='#/cloud']").click();
    await expect($(".cloud-signin-empty, .platform-workspace")).toBeDisplayed({
      wait: 15_000,
    });
    await screenshot("cloud-signed-out");
    if (await $(".cloud-signin-empty").isDisplayed()) {
      await $("button=登录或创建账号").click();
      await expect($(".auth-shell")).toBeDisplayed({ wait: 15_000 });
      await screenshot("login");
      await $(".auth-back").click();
      await expect($(".app-shell")).toBeDisplayed({ wait: 15_000 });
    }
    await browser.execute(() => {
      window.location.hash = "#/settings";
    });
    await expect($(".settings-intro")).toBeDisplayed({ wait: 15_000 });
    for (const ratio of await contrastRatios(
      ".settings-card .setting-toggle strong",
    )) {
      expect(ratio).toBeGreaterThanOrEqual(4.5);
    }
    await screenshot("settings");
    await expect(
      $$("a[href='#/migration'], a[href='#/design-system']"),
    ).toBeElementsArrayOfSize(0);
    await expect($("body")).not.toHaveText(expect.stringContaining("界面基线"));
    await chooseLanguage("en");
    await $("button=保存更改").click();
    await expect($(".sidebar-status strong")).toHaveText("Local core ready", {
      wait: 15_000,
    });
    await expect($("a[href='#/settings'] strong")).toHaveText("Settings");
    await $("a[href='#/settings']").click();
    await expect($(".settings-intro h2")).toHaveText(
      "Use SQLTeacher your way",
      { wait: 15_000 },
    );
    await chooseLanguage("zh");
    await $("button=Save changes").click();
    await expect($(".sidebar-status strong")).toHaveText("本地核心已就绪", {
      wait: 15_000,
    });
  });

  it("renders the role-protected teaching workspace without a production preview route", async () => {
    await browser.execute(() => {
      const client = window.__SQLTEACHER_E2E_QUERY_CLIENT__;
      if (!client) throw new Error("E2E query client is unavailable");
      client.setQueryData(["session", "current"], {
        subjectId: "visual-teacher",
        displayName: "视觉巡检教师",
        role: "TEACHER",
        roleLabel: "教师",
        authenticated: true,
        permissions: ["TEACHING_READ", "TEACHING_WRITE"],
      });
      client.setQueryData(["teaching", "workspace"], {
        role: "TEACHER",
        canPublish: true,
        authority: "java-and-cloud-server",
        exercises: [
          {
            id: "sql-01",
            title: "学生选课平均分",
            knowledgePoint: "连接与聚合",
            difficulty: "BEGINNER",
            version: 3,
            enabled: true,
          },
          {
            id: "sql-02",
            title: "未选数据库课程的学生",
            knowledgePoint: "NOT EXISTS",
            difficulty: "ADVANCED",
            version: 2,
            enabled: true,
          },
        ],
        progressOverview: {
          sessions: 18,
          attempts: 31,
          submissions: 22,
          passedSubmissions: 16,
          submissionPassRate: 0.73,
          averageSubmissionDuration: 940,
          hintsUsed: 9,
          completedExercises: 11,
        },
        progressItems: Array.from({ length: 17 }, (_, index) => ({
          exerciseId: `sql-${String(index + 1).padStart(2, "0")}`,
          title: `学习进度题目 ${index + 1}`,
          knowledgePoint: "连接与聚合",
          attempts: index + 1,
          failedSubmissions: index % 3,
          passed: index % 2 === 0,
          lastAttemptAt: new Date().toISOString(),
        })),
        datasets: [
          { id: "demo", name: "教学演示库", setupSql: "", version: 1 },
        ],
      });
    });
    await expect($("a[href='#/teaching']")).toBeDisplayed({ wait: 15_000 });
    await $("a[href='#/teaching']").click();
    await expect($(".platform-workspace .hero-card h2")).toHaveText(
      "教学工作台",
      { wait: 15_000 },
    );
    await expect($("[aria-label='学习进度分页']")).toBeDisplayed();
    await expect($("[aria-label='学习进度分页'] span")).toHaveText(
      "第 1 / 3 页",
    );
    await screenshot("teaching");
    await $("a[href='#/knowledge']").click();
    await expect($(".knowledge-admin-panel")).toBeDisplayed({
      wait: 15_000,
    });
    const adminSpacing = await browser.execute(() => {
      const panel = document.querySelector(".knowledge-admin-panel");
      if (!(panel instanceof HTMLElement))
        throw new Error("knowledge administration panel is unavailable");
      const style = getComputedStyle(panel);
      panel.scrollIntoView({ block: "start" });
      return { padding: style.paddingTop, gap: style.rowGap };
    });
    expect(adminSpacing).toEqual({ padding: "24px", gap: "16px" });
    await screenshot("knowledge-teacher-admin");
  });

  it("renders authenticated teacher and student cloud workspaces with role-specific controls", async () => {
    const setCloudRole = async (role) =>
      browser.execute((nextRole) => {
        const client = window.__SQLTEACHER_E2E_QUERY_CLIENT__;
        if (!client) throw new Error("E2E query client is unavailable");
        client.setQueryData(["session", "current"], {
          subjectId: `visual-${nextRole.toLowerCase()}`,
          displayName: nextRole === "TEACHER" ? "云端教师" : "云端学生",
          role: nextRole,
          roleLabel: nextRole === "TEACHER" ? "教师" : "学生",
          authenticated: true,
          permissions: [],
        });
        client.setQueryData(["cloud", "workspace"], {
          signedIn: true,
          state: "READY",
          message: "云端状态已刷新。",
          displayName: nextRole === "TEACHER" ? "云端教师" : "云端学生",
          role: nextRole,
          recoverable: true,
          sync: { state: "READY", pending: 0, attempt: 0 },
          classes: [
            {
              id: "class-ui-check",
              name: "数据库系统 2026",
              createdAt: new Date().toISOString(),
              members: [{ userId: "visual-student", role: "STUDENT" }],
            },
          ],
        });
        window.location.hash = "#/cloud";
      }, role);

    await setCloudRole("TEACHER");
    await expect($(".platform-workspace .hero-card h2")).toHaveText(
      "云端教师",
      { wait: 15_000 },
    );
    await expect($("summary=共享课程、知识点与版本化任务")).toBeDisplayed();
    await expect($("button=创建班级")).toBeDisplayed();
    await screenshot("cloud-teacher");

    await setCloudRole("STUDENT");
    await expect($(".platform-workspace .hero-card h2")).toHaveText(
      "云端学生",
      { wait: 15_000 },
    );
    await expect($$("button=创建班级")).toBeElementsArrayOfSize(0);
    await expect(
      $$("summary=共享课程、知识点与版本化任务"),
    ).toBeElementsArrayOfSize(0);
    await screenshot("cloud-student");
  });
});
