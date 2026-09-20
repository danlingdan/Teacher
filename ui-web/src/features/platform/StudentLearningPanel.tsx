// v3.7.0 TFB-T1/T2：教师端学情反馈呈现。
// ClassLearningOverviewCard：班级学情总览（汇总 + 近 7 日活跃 + 事件类型分布 + 14 日趋势条）；
// StudentLearningPanel：单个学生的学情画像——活动时间线（分页 + 类型过滤）与知识点掌握度。
// 数据全部来自学生端显式同步的 learning_events（服务端按班级成员鉴权并审计），本组件不做
// 任何额外推断；云端过旧不支持时降级为可读提示，不阻塞班级页其他功能。
import { useState } from "react";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { localAppRequest } from "../../shared/ipc";
import { formatInstant } from "../../shared/instant";
import type { CloudEventEntry, CloudEventPage, CloudLearningOverview, KnowledgeMastery } from "../../shared/types";
import { Button } from "../../shared/ui";

const EVENT_TYPE_LABELS: Record<string, string> = {
  SQL_EXECUTION: "SQL 执行",
  SQL_RISK_BLOCKED: "SQL 被拦截",
  SQL_CONFIRMATION_ISSUED: "弹出确认",
  SQL_CONFIRMED: "确认后执行",
  SQL_CONFIRMATION_CANCELLED: "取消确认",
  AI_SQL_GENERATED: "AI 生成 SQL",
  AI_GENERATION_FAILED: "AI 生成失败",
  EXERCISE_ATTEMPT: "练习作答",
  EXERCISE_PASSED: "练习通过",
  EXERCISE_FAILED: "练习失败",
  EXERCISE_HINT_USED: "查看提示",
  ACTIVITY_ATTEMPT: "活动作答",
  ACTIVITY_PASSED: "活动通过",
  ACTIVITY_FAILED: "活动失败",
  KNOWLEDGE_SEARCHED: "知识检索",
};

function eventTypeLabel(value: string): string {
  return EVENT_TYPE_LABELS[value] ?? value;
}

/** 把白名单内的证据字段拼成人话；字段缺失时保持简短，不展示占位噪声。 */
function describeEvent(entry: CloudEventEntry): string[] {
  const a = entry.attributes ?? {};
  const lines: string[] = [];
  switch (entry.eventType) {
    case "SQL_EXECUTION":
      lines.push(
        `${a.statementType ?? "SQL"} · ${a.resultCount ?? "?"} 行 · ${a.durationMs ?? "?"} ms` +
          (a.dialect ? ` · ${a.dialect}` : "") +
          (a.errorCode ? ` · 错误 ${a.errorCode}` : ""),
      );
      break;
    case "SQL_RISK_BLOCKED":
      lines.push(`${a.statementType ?? "SQL"} · 风险级别 ${a.riskLevel ?? "?"}${a.multiStatement === "true" ? " · 多语句" : ""}`);
      break;
    case "AI_SQL_GENERATED":
      lines.push(`模型 ${a.model ?? "?"}${a.promptVersion ? ` · 提示词 ${a.promptVersion}` : ""}`);
      break;
    case "AI_GENERATION_FAILED":
      lines.push(`模型 ${a.model ?? "?"} · 失败 ${a.errorCode ?? "?"}`);
      break;
    case "EXERCISE_PASSED":
    case "EXERCISE_FAILED":
    case "EXERCISE_ATTEMPT": {
      const parts = [a.score != null ? `得分 ${a.score}` : null, a.errorCode ? `错误 ${a.errorCode}` : null, `${a.durationMs ?? "?"} ms`];
      lines.push(parts.filter(Boolean).join(" · "));
      break;
    }
    case "EXERCISE_HINT_USED":
      lines.push(`第 ${a.hintLevel ?? "?"} 次提示`);
      break;
    case "KNOWLEDGE_SEARCHED":
      lines.push(`命中 ${a.resultCount ?? "?"} 条${a.queryPreview ? ` · “${a.queryPreview}”` : ""}`);
      break;
    default:
      break;
  }
  if (a.sqlText) lines.push(a.sqlText);
  return lines;
}

/** v3.7.0 TFB-T1：班级学情总览折叠卡；默认收起，教师展开即自动加载。 */
export function ClassLearningOverviewCard({ classroomId }: { classroomId: string }) {
  const overview = useQuery({
    queryKey: ["cloud", "class-overview", classroomId],
    queryFn: () =>
      localAppRequest<CloudLearningOverview>("cloud.class.analytics.overview", { classroomId }),
    enabled: Boolean(classroomId),
    retry: false,
    staleTime: 60_000,
  });
  const data = overview.data;
  const maxEvents = Math.max(1, ...(data?.trend ?? []).map((day) => day.events));
  return (
    <details className="class-overview">
      <summary>
        <strong>班级学情总览</strong>
        {data ? `（${data.activeStudents7d}/${data.summary.studentCount} 人近 7 日活跃）` : ""}
      </summary>
      {overview.isError ? (
        <p className="muted">
          学情总览暂不可用：云端服务版本较旧或尚未同步。学生端「班级与云端」页同步后，这里会呈现学习记录。
        </p>
      ) : overview.isPending || !data ? (
        <p className="muted">正在加载班级学情总览…</p>
      ) : (
        <>
          <ul className="plain-list">
            <li>
              <strong>{data.summary.studentCount}</strong>
              <span>学生 · 近 7 日活跃 {data.activeStudents7d} 人</span>
            </li>
            <li>
              <strong>{data.summary.syncedEvents}</strong>
              <span>已同步学习记录 · 成功 {data.summary.successfulEvents} 条</span>
            </li>
          </ul>
          <p className="muted">近 14 日活跃（事件数 / 天）</p>
          <div className="class-overview-trend" role="img" aria-label="近 14 日班级活动趋势">
            {data.trend.map((day) => (
              <div key={day.date} className="class-overview-bar" title={`${day.date}：${day.events} 条事件，${day.activeStudents} 人活跃`}>
                <div
                  className="class-overview-fill"
                  style={{ height: `${Math.round((day.events / maxEvents) * 48)}px` }}
                />
                <span>{day.date.slice(5)}</span>
              </div>
            ))}
          </div>
          <p className="muted">记录类型分布</p>
          <div className="button-row">
            {Object.entries(data.eventsByType)
              .sort((left, right) => right[1] - left[1])
              .map(([type, count]) => (
                <span key={type} className="policy-chip">
                  {eventTypeLabel(type)}：{count}
                </span>
              ))}
            {Object.keys(data.eventsByType).length === 0 && <span className="muted">暂无记录</span>}
          </div>
        </>
      )}
    </details>
  );
}

/** v3.7.0 TFB-T2：学生学情画像（活动时间线 + 掌握度）。 */
export function StudentLearningPanel({
  classroomId,
  student,
  onClose,
}: {
  classroomId: string;
  student: { userId: string; displayName: string; email: string };
  onClose: () => void;
}) {
  const [eventType, setEventType] = useState("");
  const events = useInfiniteQuery({
    queryKey: ["cloud", "student-events", classroomId, student.userId, eventType],
    queryFn: ({ pageParam }) =>
      localAppRequest<CloudEventPage>("cloud.class.events", {
        classroomId,
        studentUserId: student.userId,
        eventType,
        ...(pageParam ? { cursor: pageParam } : {}),
        limit: 50,
      }),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (lastPage) =>
      lastPage.nextCursor != null ? lastPage.nextCursor : undefined,
    retry: false,
  });
  const mastery = useQuery({
    queryKey: ["cloud", "student-mastery", classroomId, student.userId],
    queryFn: () =>
      localAppRequest<{ items: KnowledgeMastery[]; cached: boolean }>("cloud.mastery", {
        classroomId,
        studentUserId: student.userId,
        refreshRemote: true,
      }),
    retry: false,
  });
  return (
    <section className="student-profile" aria-label={`${student.displayName} 的学情画像`}>
      <div className="section-heading">
        <h3>
          学情画像：
          {student.displayName || student.email}
        </h3>
        <Button variant="secondary" onClick={onClose}>
          关闭画像
        </Button>
      </div>
      <p className="muted">
        以下为该学生登录后主动同步的学习记录；未同步的本地练习不会出现在这里。明细读取已记入云端审计。
      </p>
      <div className="settings-grid">
        <label>
          记录类型
          <select value={eventType} onChange={(event) => setEventType(event.target.value)}>
            <option value="">全部类型</option>
            {Object.entries(EVENT_TYPE_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
      </div>
      {events.isPending ? (
        <p className="muted">正在加载学习记录…</p>
      ) : events.isError ? (
        <p className="muted">学习记录暂不可用：学生可能尚未同步，或云端服务版本较旧。</p>
      ) : (
        <>
          <ul className="plain-list">
            {events.data?.pages.flatMap((page) => page.entries).map((entry) => (
              <li key={entry.eventId}>
                <strong>{eventTypeLabel(entry.eventType)}</strong>
                <span>
                  {formatInstant(entry.occurredAt)}
                  {entry.successful ? " · 成功" : " · 未成功"}
                </span>
                {describeEvent(entry).map((line, index) => (
                  <span key={index} className={entry.attributes?.sqlText === line ? "student-sql" : "muted"}>
                    {line}
                  </span>
                ))}
              </li>
            ))}
            {events.data?.pages.flatMap((page) => page.entries).length === 0 && (
              <li>
                <span className="muted">该类型下暂无已同步的记录。</span>
              </li>
            )}
          </ul>
          {events.hasNextPage && (
            <div className="button-row">
              <Button
                variant="secondary"
                busy={events.isFetchingNextPage}
                onClick={() => events.fetchNextPage()}
              >
                加载更早的记录
              </Button>
            </div>
          )}
        </>
      )}
      <p className="muted">知识点掌握度（按任务提交与内容快照聚合）</p>
      {mastery.isPending ? (
        <p className="muted">正在加载掌握度…</p>
      ) : mastery.isError ? (
        <p className="muted">掌握度暂不可用。</p>
      ) : mastery.data?.items.length ? (
        <ul className="plain-list">
          {mastery.data.items.map((item) => (
            <li key={item.knowledgePointId}>
              <strong>{item.knowledgePointName}</strong>
              <span>
                {item.masteryPercent}% · 尝试 {item.attempts} 次 · 通过 {item.passes} 次
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="muted">暂无掌握度数据。</p>
      )}
    </section>
  );
}
