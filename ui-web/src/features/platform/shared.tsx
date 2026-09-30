// Cross-page presentation helpers for the platform workspace pages
// (TeachingPage / CloudPage / SettingsPage). Page-specific helpers stay next to
// their page; enum labels live in src/shared/labels.ts (v3.4.0 REF-13/REF-16).
import { formatInstant } from "../../shared/instant";

export function Toggle({
  label,
  checked,
  onChange,
  hint,
}: {
  label: string;
  checked: boolean;
  onChange: () => void;
  hint?: string;
}) {
  return (
    <label className="setting-toggle">
      <input type="checkbox" checked={checked} onChange={onChange} />
      <span>
        <strong>{label}</strong>
        {hint && <small>{hint}</small>}
      </span>
    </label>
  );
}
export function Metric({ label, value }: { label: string; value: string | number }) {
  return (
    <article className="metric">
      <span>{label}</span>
      <strong>{value}</strong>
    </article>
  );
}
// v3.8.0 UIX-4：Loading 统一收口到 shared/ui,这里保留导出兼容既有页面导入。
export { Loading } from "../../shared/ui";
export function formatAccountDate(value: string) {
  return formatInstant(value);
}
// v3.8.0 修复：补充班级/任务学情报告（ClassLearningSummary / AssignmentAnalyticsReport）
// 实际返回字段的中文标签，供 CloudPage 学情面板归一化概览使用。
export function analyticsMetricLabel(value: string) {
  return (
    (
      {
        activeStudentCount: "活跃学员",
        attempts: "尝试",
        averageAttemptsPerCompletedExercise: "完成题目平均尝试",
        averageSubmissionDuration: "平均提交耗时",
        completedExercises: "已完成题目",
        completionRate: "完成率",
        passRate: "通过率",
        passedStudents: "已通过学员",
        passedSubmissions: "通过提交",
        sessions: "练习会话",
        studentCount: "学员总数",
        submissions: "提交",
        submittedStudents: "已提交学员",
        successfulEvents: "成功事件",
        syncedEvents: "同步事件",
        totalAttempts: "尝试总数",
        totalExercises: "题目总数",
        totalStudents: "学员总数",
      } as Record<string, string>
    )[value] ?? value
  );
}
