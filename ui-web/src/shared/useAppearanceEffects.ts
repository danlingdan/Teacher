import { useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { settingsPreferencesQuery } from "../app/queries";
import { installEnglishUi } from "./uiI18n";

/**
 * v3.4.4：把主题/对比度/动效/密度/字体/语言类应用到「当前窗口」的 document。
 * 主窗口与知识助教子窗口共用，保证子窗口与主窗口观感一致。
 */
export function useAppearanceEffects() {
  const appearance = useQuery(settingsPreferencesQuery);
  useEffect(() => {
    const general = appearance.data?.general;
    if (!general) return;
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const apply = () => {
      const dark = general.theme === "dark" || (general.theme === "system" && media.matches);
      document.documentElement.classList.toggle("theme-dark", dark);
      document.documentElement.classList.toggle("high-contrast", general.highContrast);
      document.documentElement.classList.toggle("reduced-motion", general.reducedMotion);
      document.documentElement.classList.toggle("density-compact", general.density === "compact");
      document.documentElement.classList.remove("font-modern", "font-system", "font-classic");
      document.documentElement.classList.add(`font-${general.font}`);
      document.documentElement.lang = general.language === "en" ? "en" : "zh-CN";
      document.documentElement.style.colorScheme = dark ? "dark" : "light";
    };
    apply();
    media.addEventListener("change", apply);
    return () => media.removeEventListener("change", apply);
  }, [appearance.data]);
  useEffect(
    () => (appearance.data?.general.language === "en" ? installEnglishUi() : undefined),
    [appearance.data?.general.language],
  );
  return appearance;
}
