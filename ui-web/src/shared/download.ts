// Blob-based local export helpers (v3.4.0 REF-16). Tauri save-dialog flows stay local
// to their callers; these cover plain browser-style downloads.
export function downloadJson(filename: string, value: unknown): void {
  downloadText(filename, JSON.stringify(value, null, 2), "application/json");
}

export function downloadText(
  filename: string,
  value: string,
  contentType = "application/json;charset=utf-8",
): void {
  const url = URL.createObjectURL(new Blob([value], { type: contentType }));
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}
