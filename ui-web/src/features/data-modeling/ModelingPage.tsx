import { useMutation, useQuery } from "@tanstack/react-query";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Button, EmptyState, Feedback, FormField, Loading, useToast } from "../../shared/ui";
import { localAppRequest } from "../../shared/ipc";
import { aiEngineStatusQuery } from "../../app/queries";
import { openAiEnginePanel } from "../data-sql/aiPanel";
import ErCanvas, { type ErCanvasApi } from "./ErCanvas";
import {
  toFlowElements,
  type ErFlowElements,
  type ErGenerationResult,
  type ErGenerationStatus,
} from "./erModel";
import { layoutErFlow } from "./layout";
import { erExportFilename, exportPng, exportSvg } from "./exportImage";

// v3.11.0 ERM-6：自然语言生成 E-R 图页面。
// AI 结果三类呈现：成功（建模思路 + 画布 + 工具栏）、未就绪（顶栏 AI 引擎引导，
// 与数据页 NL2SQL 的 HAJ-3 先例一致）、其余失败（中文话术 + 校验错误列表）。
// 页面不伪造任何数据：空态/加载态之外一律以 IPC 实际返回为准。

const REQUIREMENT_MAX = 2000;

const FAILURE_TITLES: Record<Exclude<ErGenerationStatus, "OK" | "MODEL_UNAVAILABLE">, string> = {
  PROVIDER_ERROR: "AI 供应商调用失败",
  PROMPT_ERROR: "提示词准备失败",
  PARSE_ERROR: "模型输出解析失败",
  VALIDATION_FAILED: "生成结果校验未通过",
};

export default function ModelingPage() {
  const toast = useToast();
  const { data: engine } = useQuery(aiEngineStatusQuery);
  const [requirement, setRequirement] = useState("");
  const [result, setResult] = useState<ErGenerationResult>();
  const [laidOut, setLaidOut] = useState<ErFlowElements>();
  const [exporting, setExporting] = useState<"png" | "svg" | null>(null);
  const canvasWrapRef = useRef<HTMLDivElement>(null);
  const canvasApiRef = useRef<ErCanvasApi | undefined>(undefined);

  const generate = useMutation({
    mutationFn: () =>
      localAppRequest<ErGenerationResult>("ai.modeling.er.generate", { requirement }),
    onSuccess: (value) => setResult(value),
  });

  const erModel = result?.status === "OK" ? result.erModel : undefined;
  // 引擎话术跟随 ai.engine.status 的实际通道：网络供应商会把需求上传至对应服务商，
  // 不得宣称"仅本机处理、不上传云端"（HAJ-6 文案失实同类问题，状态未知时只用中性表述）。
  const engineLabel = engine ? (engine.networkActive ? "网络 AI" : "本地 AI") : null;
  const requirementHint =
    engineLabel === null
      ? "描述业务场景、实体与联系。"
      : engineLabel === "网络 AI"
        ? "描述业务场景、实体与联系；需求将上传至所选网络 AI 服务商生成。"
        : "描述业务场景、实体与联系；需求仅经本机 AI 通道处理，不上传云端。";
  const elements = useMemo(() => (erModel ? toFlowElements(erModel) : undefined), [erModel]);
  // elkjs 布局是异步的：布局完成前先渲染“正在排布图元”，取消渲染后丢弃结果。
  useEffect(() => {
    setLaidOut(undefined);
    if (!elements) return;
    let alive = true;
    void layoutErFlow(elements).then((value) => {
      if (alive) setLaidOut(value);
    });
    return () => {
      alive = false;
    };
  }, [elements]);

  const handleCanvasReady = useCallback((api: ErCanvasApi) => {
    canvasApiRef.current = api;
  }, []);

  const runExport = (format: "png" | "svg") => {
    const container = canvasWrapRef.current;
    if (!container || !laidOut || exporting) return;
    setExporting(format);
    const exporter = format === "png" ? exportPng : exportSvg;
    exporter(container, laidOut, erExportFilename(erModel?.title ?? "", format))
      .then(() => toast("success", format === "png" ? "已导出 PNG 图片" : "已导出 SVG 图片"))
      .catch((error: unknown) =>
        toast("error", `导出失败：${error instanceof Error ? error.message : "未知错误"}`),
      )
      .finally(() => setExporting(null));
  };

  return (
    <div className="er-workspace">
      <section className="content-card er-input-card">
        <div className="section-heading">
          <div>
            <p className="eyebrow">AI 概念建模</p>
            <h2>自然语言生成 E-R 图</h2>
          </div>
          <span className="policy-chip">
            {engineLabel ? `陈氏记号 · ${engineLabel}` : "陈氏记号"}
          </span>
        </div>
        <FormField label="建模需求" hint={requirementHint}>
          {(ids) => (
            <textarea
              {...ids}
              className="er-requirement"
              value={requirement}
              onChange={(event) => setRequirement(event.target.value)}
              maxLength={REQUIREMENT_MAX}
              placeholder="例：设计一个学生选课系统，学生可选修多门课程，每门课程可被多名学生选修，记录成绩。"
            />
          )}
        </FormField>
        <div className="button-row er-input-actions">
          <Button
            disabled={!requirement.trim() || generate.isPending}
            busy={generate.isPending}
            onClick={() => generate.mutate()}
          >
            生成
          </Button>
          <span className="er-counter">
            {requirement.length}/{REQUIREMENT_MAX}
          </span>
        </div>
        {generate.isError && (
          <Feedback tone="error" title="生成请求失败">
            <p>{generate.error.message}</p>
          </Feedback>
        )}
        {result?.status === "MODEL_UNAVAILABLE" && (
          <Feedback tone="warning" title="AI 引擎未就绪">
            <p>{result.message}</p>
            <p>可在顶栏「AI 引擎」弹层中选择本地模型或添加网络 AI 供应商；本次未生成模型。</p>
            <Button variant="secondary" onClick={() => openAiEnginePanel()}>
              打开 AI 引擎
            </Button>
          </Feedback>
        )}
        {result && result.status !== "OK" && result.status !== "MODEL_UNAVAILABLE" && (
          <Feedback tone="error" title={FAILURE_TITLES[result.status]}>
            <p>{result.message}</p>
            {result.status === "VALIDATION_FAILED" && result.errors.length > 0 && (
              <ul className="er-error-list">
                {result.errors.map((error) => (
                  <li key={error}>{error}</li>
                ))}
              </ul>
            )}
          </Feedback>
        )}
      </section>
      {erModel ? (
        <section className="content-card er-canvas-card">
          <div className="section-heading">
            <div>
              <p className="eyebrow">E-R 模型</p>
              <h2>{erModel.title || "生成的 E-R 图"}</h2>
            </div>
            <div className="button-row">
              {result?.model && <span className="policy-chip">{result.model}</span>}
              <Button variant="secondary" onClick={() => canvasApiRef.current?.fitView()}>
                适应视图
              </Button>
              <Button
                variant="secondary"
                busy={exporting === "png"}
                disabled={!laidOut || exporting === "svg"}
                onClick={() => runExport("png")}
              >
                导出 PNG
              </Button>
              <Button
                variant="secondary"
                busy={exporting === "svg"}
                disabled={!laidOut || exporting === "png"}
                onClick={() => runExport("svg")}
              >
                导出 SVG
              </Button>
            </div>
          </div>
          {erModel.explanation && <p className="muted er-explanation">{erModel.explanation}</p>}
          {laidOut ? (
            <div className="er-canvas-wrap" ref={canvasWrapRef}>
              <ErCanvas elements={laidOut} onReady={handleCanvasReady} />
            </div>
          ) : (
            <div className="er-canvas-wrap er-canvas-waiting">
              <p className="muted">正在排布图元…</p>
            </div>
          )}
        </section>
      ) : generate.isPending ? (
        <section className="content-card">
          <Loading label="生成中，正在等待 AI 引擎返回…" />
        </section>
      ) : (
        <section className="content-card">
          <EmptyState title="还没有 E-R 模型">
            <p>
              用一段自然语言描述业务场景，AI 将按教材记号生成 E-R 图：矩形实体、椭圆属性（主码下划线）、
              菱形联系与连线上的 1/n/m 基数。
            </p>
          </EmptyState>
        </section>
      )}
    </div>
  );
}
