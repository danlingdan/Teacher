import { useQuery } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { aiEngineStatusQuery } from "../../app/queries";
import AiEnginePanel from "./AiEnginePanel";
import { subscribeAiEnginePanel } from "./aiPanel";

// v3.10.0 HAJ-1 修订：AI 引擎从「数据库连接」弹层中拆出，独立为顶栏按钮
// （渲染在连接 chip 左侧，见 App.tsx），各自开合互不影响。
// chip 样式类（connection-anchor/connection-trigger）与连接 chip 共用是有意为之：
// 两个入口保持同一视觉与弹层定位，未就绪时同样走 missing 高亮引导。
export default function TopbarAiEngine() {
  const [open, setOpen] = useState(false);
  const engine = useQuery(aiEngineStatusQuery);
  // chip 状态词：网络供应商生效 → 网络；否则看本地 Ollama 可达性；数据未到时先留空。
  const statusText = engine.data
    ? engine.data.networkActive
      ? "网络"
      : engine.data.ollamaAvailable
        ? "本地"
        : "未就绪"
    : "";
  const ready = Boolean(engine.data && (engine.data.networkActive || engine.data.ollamaAvailable));

  // 数据页/练习页的失败引导按钮通过这个通道唤起本弹层。
  useEffect(() => subscribeAiEnginePanel(() => setOpen(true)), []);

  // 与 TopbarConnection 一致：点击外部即关闭；Dialog 固定背板内的按下不算"点击外部"。
  const anchorRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: MouseEvent) => {
      const target = event.target as HTMLElement;
      if (anchorRef.current?.contains(target)) return;
      if (target.closest(".ui-dialog-backdrop")) return;
      setOpen(false);
    };
    document.addEventListener("mousedown", onPointerDown);
    return () => document.removeEventListener("mousedown", onPointerDown);
  }, [open]);

  // 弹层头部的当前引擎描述，与 AiEnginePanel 内的 engineLabel 同口径。
  const engineLabel = engine.data
    ? engine.data.networkActive
      ? `网络 AI · ${engine.data.displayName}`
      : engine.data.ollamaAvailable
        ? `本地 Ollama${engine.data.selectedModel ? ` · ${engine.data.selectedModel}` : ""}`
        : "本地 Ollama（不可达）"
    : "";

  return (
    <div className="connection-anchor" ref={anchorRef}>
      <button
        type="button"
        className={`connection-trigger${ready ? "" : " missing"}`}
        aria-label={`AI 引擎${statusText ? `：${statusText}` : ""}`}
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        <span aria-hidden="true">✦</span>
        <span className="connection-chip-label">AI 引擎</span>
        {statusText && <small>{statusText}</small>}
      </button>
      {open && (
        <section className="connection-popover" role="dialog" aria-label="AI 引擎">
          <header>
            <div>
              <strong>AI 引擎</strong>
              {engine.data && <small>当前引擎：{engineLabel}</small>}
              {engine.isError && <small className="engine-state-bad">引擎状态读取失败</small>}
            </div>
          </header>
          <AiEnginePanel />
        </section>
      )}
    </div>
  );
}
