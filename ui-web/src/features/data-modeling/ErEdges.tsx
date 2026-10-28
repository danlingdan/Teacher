import { EdgeLabelRenderer, useInternalNode, type EdgeProps, type InternalNode } from "@xyflow/react";
import type { ErFlowEdge, ErNodeKind } from "./erModel";

// v3.11.0 ERM-5：教材风格直线边。以自定义组件注册为 "straight"
// （同名覆盖内建直线边，边 type 语义保持为 'straight' 不变）。
// 端点不锚在固定 Handle 上，而是按两端形状（矩形/椭圆/菱形/三角）与连线方向
// 求交点，拖拽微调后直线仍精确落在形状边界上（floating edge 手法）。
// total=true 的成员边与依赖联系的弱实体侧画双线；基数 1/n/m 标在实体端附近。

type Point = { x: number; y: number };

const FALLBACK_HALF_WIDTH = 50;
const FALLBACK_HALF_HEIGHT = 25;

function shapeOf(node: InternalNode): ErNodeKind {
  return (node.type ?? "entity") as ErNodeKind;
}

function nodeGeometry(node: InternalNode): { center: Point; hw: number; hh: number } {
  const width = node.measured?.width ?? node.width ?? FALLBACK_HALF_WIDTH * 2;
  const height = node.measured?.height ?? node.height ?? FALLBACK_HALF_HEIGHT * 2;
  return {
    center: {
      x: node.internals.positionAbsolute.x + width / 2,
      y: node.internals.positionAbsolute.y + height / 2,
    },
    hw: width / 2,
    hh: height / 2,
  };
}

/** 射线 P(t)=t·d（t>0）与线段 a→b 的交点参数；无交点返回 Infinity。 */
function raySegmentT(d: Point, a: Point, b: Point): number {
  const abx = b.x - a.x;
  const aby = b.y - a.y;
  const det = abx * d.y - aby * d.x;
  if (Math.abs(det) < 1e-9) return Infinity;
  const t = (abx * a.y - aby * a.x) / det;
  const s = (d.x * a.y - d.y * a.x) / det;
  return t > 1e-6 && s >= -0.001 && s <= 1.001 ? t : Infinity;
}

/** 从中心沿 dir 方向与形状边界的交点（相对中心，dir 为非零向量）。 */
function boundaryOffset(kind: ErNodeKind, hw: number, hh: number, dir: Point): Point {
  const length = Math.hypot(dir.x, dir.y);
  if (length < 1e-6) return { x: 0, y: 0 };
  const dx = dir.x / length;
  const dy = dir.y / length;
  let t: number;
  switch (kind) {
    case "attribute":
      // 椭圆：(dx/hw)²t² + (dy/hh)²t² = 1。
      t = 1 / Math.hypot(dx / hw, dy / hh);
      break;
    case "relationship":
      // 菱形：|dx|t/hw + |dy|t/hh = 1。
      t = 1 / (Math.abs(dx) / hw + Math.abs(dy) / hh);
      break;
    case "isa": {
      // 等腰三角形：顶点 (0,-hh)，底角 (±hw, hh)；取射线与三边交点的最小正 t。
      const apex = { x: 0, y: -hh };
      const right = { x: hw, y: hh };
      const left = { x: -hw, y: hh };
      const ray = { x: dx, y: dy };
      t = Math.min(
        raySegmentT(ray, apex, right),
        raySegmentT(ray, right, left),
        raySegmentT(ray, left, apex),
      );
      if (!Number.isFinite(t)) t = hh;
      break;
    }
    default:
      // 矩形：先撞哪条边取哪个 t。
      t = Math.min(hw / Math.abs(dx || 1e-9), hh / Math.abs(dy || 1e-9));
  }
  return { x: dx * t, y: dy * t };
}

export function ErStraightEdge({ id, source, target, data }: EdgeProps<ErFlowEdge>) {
  const sourceNode = useInternalNode(source);
  const targetNode = useInternalNode(target);
  if (!sourceNode || !targetNode) return null;

  const from = nodeGeometry(sourceNode);
  const to = nodeGeometry(targetNode);
  const direction = { x: to.center.x - from.center.x, y: to.center.y - from.center.y };
  const length = Math.hypot(direction.x, direction.y);
  if (length < 1e-6) return null;
  const unit = { x: direction.x / length, y: direction.y / length };
  const normal = { x: -unit.y, y: unit.x };

  const sourceOffset = boundaryOffset(shapeOf(sourceNode), from.hw, from.hh, direction);
  const targetOffset = boundaryOffset(shapeOf(targetNode), to.hw, to.hh, { x: -direction.x, y: -direction.y });
  const p1 = { x: from.center.x + sourceOffset.x, y: from.center.y + sourceOffset.y };
  const p2 = { x: to.center.x + targetOffset.x, y: to.center.y + targetOffset.y };

  // 全参与/弱实体依赖一侧画双线：沿法线偏移 ±gap 的两条平行直线。
  const doubleLine = data?.total === true || data?.identifying === true;
  const gap = 1.7;
  const paths = doubleLine
    ? [gap, -gap].map((offset) => {
        const start = { x: p1.x + normal.x * offset, y: p1.y + normal.y * offset };
        const end = { x: p2.x + normal.x * offset, y: p2.y + normal.y * offset };
        return `M ${start.x} ${start.y} L ${end.x} ${end.y}`;
      })
    : [`M ${p1.x} ${p1.y} L ${p2.x} ${p2.y}`];

  // 基数标签靠近实体端（沿边向内 20px，再向法线方向偏 11px 避让线体）。
  const labelPoint = {
    x: p1.x + unit.x * 20 + normal.x * 11,
    y: p1.y + unit.y * 20 + normal.y * 11,
  };

  return (
    <>
      {paths.map((path, index) => (
        <path key={index} id={index === 0 ? id : undefined} d={path} className="er-edge-line" fill="none" />
      ))}
      {data?.cardinality && (
        <EdgeLabelRenderer>
          <div
            className="er-edge-label"
            style={{ transform: `translate(-50%, -50%) translate(${labelPoint.x}px, ${labelPoint.y}px)` }}
          >
            {data.cardinality}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  );
}
