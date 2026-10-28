import { Handle, Position, type NodeProps } from "@xyflow/react";
import { erNodeSize, type ErAttributeFlowNode, type ErEntityFlowNode, type ErIsaFlowNode, type ErRelationshipFlowNode } from "./erModel";

// v3.11.0 ERM-5：陈氏记号（国内教材风格）自定义节点。
// 尺寸一律来自 erNodeSize（与 ELK 布局输入同源），形状用 SVG 绘制；
// 颜色走 App.css 的 .er-diagram 局部 token，深色模式只覆盖 token。
// 隐藏 Handle 仅作 React Flow 边锚点占位：直线端点由 ErEdges.tsx 按
// 形状边界重新计算，因此 Handle 不参与视觉呈现（不参与手工连线）。

function AnchorHandles() {
  return (
    <>
      <Handle type="target" position={Position.Top} className="er-handle" isConnectable={false} />
      <Handle type="source" position={Position.Bottom} className="er-handle" isConnectable={false} />
    </>
  );
}

/** 实体：矩形；弱实体为双线矩形（外框 + 内框）。 */
export function EntityNode({ data }: NodeProps<ErEntityFlowNode>) {
  const { width, height } = erNodeSize("entity", data.label);
  return (
    <div className={data.weak ? "er-node er-entity weak" : "er-node er-entity"} style={{ width, height }}>
      {data.weak && <div className="er-entity-inner" aria-hidden="true" />}
      <span className="er-node-text">{data.label}</span>
      <AnchorHandles />
    </div>
  );
}

/** 属性：椭圆；主码下划线（SVG tspan）、多值双椭圆、派生虚线（记号规范 §3）。 */
export function AttributeNode({ data }: NodeProps<ErAttributeFlowNode>) {
  const { width, height } = erNodeSize("attribute", data.label);
  const rx = width / 2;
  const ry = height / 2;
  return (
    <div className="er-node er-attribute" style={{ width, height }}>
      <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`}>
        <ellipse
          cx={rx}
          cy={ry}
          rx={rx - 1.25}
          ry={ry - 1.25}
          className={data.derived ? "er-attr-ellipse derived" : "er-attr-ellipse"}
        />
        {data.multivalued && (
          <ellipse
            cx={rx}
            cy={ry}
            rx={rx - 6.25}
            ry={ry - 6.25}
            className="er-attr-ellipse er-attr-ellipse-inner"
          />
        )}
        {/* 主码下划线必须用 SVG tspan（计划 §3），HTML <u> 覆盖不到椭圆内排版。 */}
        <text x={rx} y={ry} textAnchor="middle" dominantBaseline="central" className="er-node-text">
          <tspan textDecoration={data.isKey ? "underline" : undefined}>{data.label}</tspan>
        </text>
      </svg>
      <AnchorHandles />
    </div>
  );
}

/** 联系：菱形；依赖联系（identifying）为双菱形。 */
export function RelationshipNode({ data }: NodeProps<ErRelationshipFlowNode>) {
  const { width, height } = erNodeSize("relationship", data.label);
  const diamond = (inset: number) =>
    [
      `${width / 2},${inset}`,
      `${width - inset},${height / 2}`,
      `${width / 2},${height - inset}`,
      `${inset},${height / 2}`,
    ].join(" ");
  return (
    <div className="er-node er-relationship" style={{ width, height }}>
      <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} aria-hidden="true">
        <polygon points={diamond(1.25)} className="er-rel-polygon" />
        {data.identifying && <polygon points={diamond(7)} className="er-rel-polygon er-rel-polygon-inner" />}
      </svg>
      <span className="er-node-text">{data.label}</span>
      <AnchorHandles />
    </div>
  );
}

/** 概括/特化：三角形，标签默认 ISA（可自定义名称）。 */
export function IsaNode({ data }: NodeProps<ErIsaFlowNode>) {
  const { width, height } = erNodeSize("isa", data.label);
  const points = [
    `${width / 2},${3}`,
    `${width - 4},${height - 6}`,
    `${4},${height - 6}`,
  ].join(" ");
  return (
    <div className="er-node er-isa" style={{ width, height }}>
      <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} aria-hidden="true">
        <polygon points={points} className="er-isa-polygon" />
      </svg>
      <span className="er-node-text er-isa-text">{data.label}</span>
      <AnchorHandles />
    </div>
  );
}
