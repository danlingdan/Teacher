/**
 * datetime-local 输入值格式化（本地时区，分钟精度）。
 * 此前 CloudPage 与 useClassroom 各自内联实现同一格式，收敛到一处。
 */

/** 把给定 Date 格式化为 datetime-local 输入值（YYYY-MM-DDTHH:mm）。 */
export function datetimeLocalFromDate(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** ISO 时间 → datetime-local 输入值（本地时区）；空值或无法解析时返回空串。 */
export function datetimeLocalFromIso(iso?: string): string {
  if (!iso) return "";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  return datetimeLocalFromDate(date);
}
