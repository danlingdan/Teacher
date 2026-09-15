/**
 * v3.5.0 SFE-2：Java 侧错误文本可能附带「教学解读」（SqlErrorTeachingAdvisor 以
 * MARKER 拼接）。前端按标记拆分，把解读渲染成独立块；未命中映射时原样展示。
 */
const TEACHING_NOTE_MARKER = "\n教学解读：";

export function splitTeachingNote(text: string | undefined): { main: string; note: string } {
  if (!text) return { main: "", note: "" };
  const marker = text.indexOf(TEACHING_NOTE_MARKER);
  if (marker < 0) return { main: text, note: "" };
  return {
    main: text.slice(0, marker),
    note: text.slice(marker + TEACHING_NOTE_MARKER.length).trim(),
  };
}
