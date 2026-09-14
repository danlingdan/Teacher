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
export function Loading({ label }: { label: string }) {
  return (
    <section className="page-skeleton" aria-live="polite">
      <span className="spinner" />
      {label}
    </section>
  );
}
export function formatAccountDate(value: string) {
  return formatInstant(value);
}
export function analyticsMetricLabel(value: string) {
  return (
    (
      {
        attempts: "尝试",
        averageAttemptsPerCompletedExercise: "完成题目平均尝试",
        averageSubmissionDuration: "平均提交耗时",
        completedExercises: "已完成题目",
        completionRate: "完成率",
        passRate: "通过率",
        passedSubmissions: "通过提交",
        sessions: "练习会话",
        submissions: "提交",
        totalExercises: "题目总数",
      } as Record<string, string>
    )[value] ?? value
  );
}
