/** 解析后端时间字段；无法解析时返回 null。 */
export function parseInstant(value: string | number): Date | null {
  const text = String(value).trim();
  if (/^-?\d+(\.\d+)?$/.test(text)) {
    const numeric = Number(text);
    if (!Number.isFinite(numeric)) return null;
    // Java Instant 经 Jackson 默认序列化为秒级数值；接近 2001-09 的秒值约为 1e9，
    // 而毫秒值为 1e12 起。以 1e12 为界区分秒与毫秒。
    const millis = Math.abs(numeric) < 1e12 ? numeric * 1000 : numeric;
    const date = new Date(millis);
    return Number.isNaN(date.getTime()) ? null : date;
  }
  const parsed = new Date(text);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

/** 以 zh-CN 无 12 小时制格式化；无法解析时返回占位文案。 */
export function formatInstant(value: string | number): string {
  const date = parseInstant(value);
  return date ? date.toLocaleString("zh-CN", { hour12: false }) : "时间未知";
}
