import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
process.env.SQLTEACHER_E2E_DATA_DIR = path.resolve(here, "..", "target", "e2e-app-data");
export const config = {
  runner: "local",
  specs: ["./e2e/**/*.e2e.js"],
  maxInstances: 1,
  capabilities: [{ browserName: "tauri", "tauri:options": { application: path.join(here, "src-tauri", "target", "release", "sqlteacher-desktop.exe") } }],
  framework: "jasmine",
  reporters: ["spec"],
  jasmineOpts: { defaultTimeoutInterval: 30_000 },
  services: [["tauri", { appBinaryPath: path.join(here, "src-tauri", "target", "release", "sqlteacher-desktop.exe"), driverProvider: "embedded", captureFrontendLogs: true, logLevel: "warn" }]],
};
