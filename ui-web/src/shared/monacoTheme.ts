import { useCallback, useEffect, useState } from "react";

type MonacoNamespace = {
  editor?: {
    defineTheme?: (name: string, theme: unknown) => void;
  };
};

let themeDefined = false;

/**
 * 注册与界面配色一致的深色编辑器主题。monaco 实例必须经 Editor 的 beforeMount
 * 传入：打包产物中 monaco 模块可能被复制进多个 chunk，模块内自行 import 会拿到
 * 与编辑器不同的实例，导致主题注册无效。
 */
function defineSqlTeacherEditorTheme(monaco: MonacoNamespace): void {
  if (themeDefined) return;
  try {
    // 测试环境的 monaco mock 没有完整 editor 命名空间：跳过注册，主题名回退到内置 vs-dark。
    if (typeof monaco.editor?.defineTheme !== "function") return;
    monaco.editor.defineTheme("sqlteacher-dark", {
      base: "vs-dark",
      inherit: true,
      rules: [],
      colors: {
        "editor.background": "#10222e",
        "editor.foreground": "#dce7ee",
        "editor.lineHighlightBackground": "#162a37",
        "editorLineNumber.foreground": "#5b7484",
        "editorLineNumber.activeForeground": "#8fb2c4",
        "editor.selectionBackground": "#2a4a5c",
        "editorCursor.foreground": "#61d9c8",
        "editorWidget.background": "#132833",
        "editorWidget.border": "#2d4755",
        "scrollbarSlider.background": "#22404e",
        "scrollbarSlider.hoverBackground": "#2d4f5e",
      },
    });
    themeDefined = true;
  } catch {
    return;
  }
}

function currentEditorTheme(): string {
  const root = document.documentElement.classList;
  if (root.contains("theme-dark")) {
    if (root.contains("high-contrast")) return "hc-black";
    return themeDefined ? "sqlteacher-dark" : "vs-dark";
  }
  return root.contains("high-contrast") ? "hc-light" : "vs";
}

/**
 * 跟随 documentElement 上 theme-dark / high-contrast 类切换 Monaco 主题名。
 * 返回 [当前主题名, beforeMount 回调]：回调在编辑器创建前注册主题并刷新主题名，
 * 保证创建时的 setTheme 已经能命中自定义主题。
 */
export function useMonacoEditorTheme(): [
  string,
  (monaco: MonacoNamespace) => void,
] {
  const [theme, setTheme] = useState(currentEditorTheme);
  useEffect(() => {
    const observer = new MutationObserver(() =>
      setTheme(currentEditorTheme()),
    );
    observer.observe(document.documentElement, { attributeFilter: ["class"] });
    return () => observer.disconnect();
  }, []);
  const syncAfterDefine = useCallback((monaco: MonacoNamespace) => {
    defineSqlTeacherEditorTheme(monaco);
    setTheme(currentEditorTheme());
  }, []);
  return [theme, syncAfterDefine];
}
