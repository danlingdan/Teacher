import fs from "node:fs";
import path from "node:path";

const artifactDir = path.resolve("..", "target", "e2e-artifacts");
function screenshot(name) {
  fs.mkdirSync(artifactDir, { recursive: true });
  return browser.saveScreenshot(path.join(artifactDir, `exercise-${name}.png`));
}

async function chooseCatalogFilter(label, value) {
  await browser.execute(
    (fieldLabel, next) => {
      const select = [
        ...document.querySelectorAll(".catalog-filters select"),
      ].find((candidate) => candidate.getAttribute("aria-label") === fieldLabel);
      const setter = Object.getOwnPropertyDescriptor(
        HTMLSelectElement.prototype,
        "value",
      )?.set;
      if (!(select instanceof HTMLSelectElement) || !setter)
        throw new Error(`${fieldLabel} filter is unavailable`);
      setter.call(select, next);
      select.dispatchEvent(new Event("change", { bubbles: true }));
    },
    label,
    value,
  );
}

describe("练习题目体系增强视觉巡检", () => {
  it("renders the grouped practice catalog with status badges and FK-aware preview", async () => {
    await expect($(".brand small")).toHaveText("Learning Studio");
    await $(".sidebar-status strong").waitForDisplayed({ timeout: 15_000 });
    if ((await $(".sidebar-status strong").getText()) === "Local core ready") {
      await $("a[href='#/settings']").click();
      const languageSelect = await $(".settings-grid select");
      await languageSelect.waitForDisplayed({ timeout: 10_000 });
      await browser.execute(() => {
        const select = document.querySelector(".settings-grid select");
        const setter = Object.getOwnPropertyDescriptor(
          HTMLSelectElement.prototype,
          "value",
        )?.set;
        setter.call(select, "zh");
        select.dispatchEvent(new Event("change", { bubbles: true }));
      });
      await $("button=保存更改").click();
    }
    await expect($(".sidebar-status strong")).toHaveText("本地核心已就绪", {
      wait: 15_000,
    });

    await $("a[href='#/practice']").click();
    await expect($(".practice-workspace")).toBeDisplayed({ wait: 15_000 });
    await $(".catalog-group-title").waitForDisplayed({ timeout: 15_000 });
    // 目录按难度分组、题目来自真实 school-core-v2、题库更新入口存在。
    await expect($$(".catalog-group")).toBeElementsArrayOfSize(3);
    await expect($$(".catalog-badge")).toBeElementsArrayOfSize(20);
    await expect(await $("button=题库更新").isDisplayed()).toBe(true);
    await expect($(".selection-panel input")).toHaveValue("");
    await screenshot("catalog-grouped");

    // 难度筛选：只显示入门组。
    await chooseCatalogFilter("按难度筛选", "BEGINNER");
    await browser.waitUntil(
      async () => (await $$(".catalog-group")).length === 1,
      { timeout: 10_000, timeoutMsg: "difficulty filter did not apply" },
    );
    await screenshot("catalog-filtered-beginner");
    await chooseCatalogFilter("按难度筛选", "");
    await browser.waitUntil(
      async () => (await $$(".catalog-group")).length === 3,
      { timeout: 10_000, timeoutMsg: "difficulty filter was not cleared" },
    );

    // 打开一道连接题：预览展示数据集摘要与外键关系（真实内省）。
    await $("button*=查询选课明细").click();
    await expect($(".preview-card")).toBeDisplayed({ wait: 15_000 });
    await browser.waitUntil(
      async () =>
        (await $(".preview-card pre").getText()).includes(
          "enrollment.student_id → student.id",
        ),
      {
        timeout: 15_000,
        timeoutMsg: "schema summary did not include FK relations",
      },
    );
    await screenshot("preview-fk-summary");

    // 进入作答，确认会话正常建立后退出。
    await $("button=确认并开始作答").click();
    await expect($(".coding-card")).toBeDisplayed({ wait: 15_000 });
    await expect($(".coding-card h2")).toHaveText("查询选课明细");
    await screenshot("answer-editor");
  });

  it("renders the teacher workspace with the server publish entry", async () => {
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
            id: "query-01",
            title: "查询全部学生",
            knowledgePoint: "基础查询",
            difficulty: "BEGINNER",
            version: 3,
            enabled: true,
          },
        ],
        progressOverview: {
          sessions: 3,
          attempts: 5,
          submissions: 4,
          passedSubmissions: 2,
          submissionPassRate: 0.5,
          averageSubmissionDuration: 900,
          hintsUsed: 1,
          completedExercises: 2,
        },
        progressItems: [],
        datasets: [],
      });
    });
    await expect($("a[href='#/teaching']")).toBeDisplayed({ wait: 15_000 });
    await $("a[href='#/teaching']").click();
    await expect($(".platform-workspace .hero-card h2")).toHaveText(
      "教学工作台",
      { wait: 15_000 },
    );
    await $("details summary").click();
    await expect($("button=发布到服务器")).toBeDisplayed({ wait: 15_000 });
    await screenshot("teaching-publish-entry");
  });
});
