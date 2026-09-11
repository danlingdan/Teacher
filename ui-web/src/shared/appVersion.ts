import { useEffect, useState } from "react";
import { getVersion } from "@tauri-apps/api/app";

let cachedVersion: string | undefined;

async function readVersion(): Promise<string> {
  if (cachedVersion) return cachedVersion;
  try {
    const version = await getVersion();
    cachedVersion = version && version.trim() ? version.trim() : "";
  } catch {
    // 在 Tauri WebView 之外（单元测试/浏览器开发）拿不到版本号。
    cachedVersion = "";
  }
  return cachedVersion;
}

/** 应用显示版本；在 Tauri 外运行时回退为主版本号 "3"。 */
export function useAppVersion(): string {
  const [version, setVersion] = useState(() => cachedVersion ?? "3");
  useEffect(() => {
    let active = true;
    void readVersion().then((value) => {
      if (active && value) setVersion(value);
    });
    return () => {
      active = false;
    };
  }, []);
  return version;
}
