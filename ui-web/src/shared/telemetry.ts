type Fields = Record<string, string | number | boolean | undefined>;

export function log(level: "info" | "warn" | "error", event: string, fields: Fields = {}) {
  const record = { timestamp: new Date().toISOString(), level, event, ...fields };
  const writer = level === "error" ? console.error : level === "warn" ? console.warn : console.info;
  writer(JSON.stringify(record));
}

export function measure(name: string, started: number, fields: Fields = {}) {
  const durationMs = Math.round((performance.now() - started) * 100) / 100;
  performance.measure(name, { start: started, end: performance.now() });
  log("info", "performance.measure", { name, durationMs, ...fields });
}
