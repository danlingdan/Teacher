import { getNodesBounds, getViewportForBounds } from "@xyflow/react";
import { toPng, toSvg } from "html-to-image";
import type { ErFlowElements } from "./erModel";

// v3.11.0 ERM-5：E-R 图导出（html-to-image 官方配方）。
// 捕获目标是 .react-flow__viewport（节点、直线边与 EdgeLabelRenderer 基数标注都在其中）：
// 捕获前按节点边界计算导出视口并临时替换 viewport 变换，捕获后立即还原，
// 用户当前缩放/平移不受影响。Controls 等容器级部件不在 viewport 内，天然不入图。

const EXPORT_MARGIN = 48;

function exportBackgroundColor(): string {
  // 浅色导出白底（教材/课件），深色主题沿用 token 底色避免浅色线稿看不清。
  if (!document.documentElement.classList.contains("theme-dark")) return "#ffffff";
  return getComputedStyle(document.documentElement).getPropertyValue("--bg").trim() || "#0c1821";
}

async function captureDataUrl(
  container: HTMLElement,
  elements: ErFlowElements,
  format: "png" | "svg",
): Promise<string> {
  const viewport = container.querySelector<HTMLElement>(".react-flow__viewport");
  if (!viewport) throw new Error("E-R 画布尚未就绪");
  if (elements.nodes.length === 0) throw new Error("当前没有可导出的 E-R 图元");
  const bounds = getNodesBounds(elements.nodes);
  const width = Math.max(320, Math.ceil(bounds.width) + EXPORT_MARGIN * 2);
  const height = Math.max(240, Math.ceil(bounds.height) + EXPORT_MARGIN * 2);
  // 宽高已含边距，padding 取 0；min/max zoom 放宽避免极端比例图被钳制。
  const target = getViewportForBounds(bounds, width, height, 0.05, 4, 0);
  const restore = viewport.style.transform;
  viewport.style.transform = `translate(${target.x}px, ${target.y}px) scale(${target.zoom})`;
  try {
    const options = {
      backgroundColor: exportBackgroundColor(),
      width,
      height,
      style: {
        width: `${width}px`,
        height: `${height}px`,
        transform: viewport.style.transform,
      },
    };
    return format === "png" ? await toPng(viewport, { ...options, pixelRatio: 2 }) : await toSvg(viewport, options);
  } finally {
    viewport.style.transform = restore;
  }
}

function downloadDataUrl(dataUrl: string, filename: string): void {
  const anchor = document.createElement("a");
  anchor.href = dataUrl;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
}

/** 文件名只保留安全字符；中文标题直接保留（Windows/Tauri 下载均支持）。 */
export function erExportFilename(title: string, extension: "png" | "svg"): string {
  const safe = title.replace(/[\\/:*?"<>|]/g, "").trim().slice(0, 60);
  return `SQLTeacher-ER-${safe || "model"}.${extension}`;
}

export async function exportPng(container: HTMLElement, elements: ErFlowElements, filename: string): Promise<void> {
  downloadDataUrl(await captureDataUrl(container, elements, "png"), filename);
}

export async function exportSvg(container: HTMLElement, elements: ErFlowElements, filename: string): Promise<void> {
  downloadDataUrl(await captureDataUrl(container, elements, "svg"), filename);
}
