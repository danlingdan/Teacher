import ELK from "elkjs/lib/elk.bundled.js";
import type { ErFlowElements, ErFlowNode } from "./erModel";

// v3.11.0 ERM-5：E-R 图确定性自动布局。
//
// 采用“核心分层 + 属性环绕”混合方案：实体/联系/ISA 三角核心图跑 ELK layered
// （≤16 实体 + 24 联系 + 8 层级，规模极小，layered 快且结果稳定），属性若混入
// layered 会自成一列、直线交叉穿梭，教材观感差，故按拥有者极坐标环绕摆放。
// 全程无随机源：ELK 对同一图输入结果确定，环绕次序/角度均由输入数组序决定。

type Box = { x: number; y: number; width: number; height: number };

const MARGIN = 40;
const COLLISION_MARGIN = 14;

function isCore(node: ErFlowNode): boolean {
  return node.type === "entity" || node.type === "relationship" || node.type === "isa";
}

function halfDiagonal(box: { width: number; height: number }): number {
  return Math.hypot(box.width, box.height) / 2;
}

function boxesCollide(a: Box, b: Box): boolean {
  return (
    a.x - COLLISION_MARGIN < b.x + b.width &&
    b.x - COLLISION_MARGIN < a.x + a.width &&
    a.y - COLLISION_MARGIN < b.y + b.height &&
    b.y - COLLISION_MARGIN < a.y + a.height
  );
}

/**
 * 在 ownerCenter 周围为单个属性节点找无碰撞位置。
 * 依次尝试半径环（由近及远）× 角度偏移表（确定性次序），取第一个可行解；
 * 全部冲突时退回最外环的理想角（保证终止，且大半径通常已不冲突）。
 */
function placeAround(
  ownerCenter: { x: number; y: number },
  ownerHalf: number,
  item: { width: number; height: number },
  occupied: Box[],
  idealAngle: number,
): Box {
  const itemHalf = halfDiagonal(item);
  const baseRadius = Math.max(ownerHalf + itemHalf + 26, 104);
  const angleOffsets = [0, 0.26, -0.26, 0.55, -0.55, 0.9, -0.9, Math.PI / 3];
  let fallback: Box | undefined;
  for (let ring = 0; ring < 6; ring += 1) {
    const radius = baseRadius + ring * 30;
    for (const offset of angleOffsets) {
      const angle = idealAngle + offset;
      const box: Box = {
        x: ownerCenter.x + Math.cos(angle) * radius - item.width / 2,
        y: ownerCenter.y + Math.sin(angle) * radius - item.height / 2,
        width: item.width,
        height: item.height,
      };
      if (!occupied.some((other) => boxesCollide(box, other))) return box;
      if (ring === 5 && offset === 0) fallback = box;
    }
  }
  return (
    fallback ?? {
      x: ownerCenter.x + Math.cos(idealAngle) * (baseRadius + 150) - item.width / 2,
      y: ownerCenter.y + Math.sin(idealAngle) * (baseRadius + 150) - item.height / 2,
      width: item.width,
      height: item.height,
    }
  );
}

/** 核心图 fallback：单行排开（ELK 异常时仍可渲染，保持确定性）。 */
function rowLayout(core: ErFlowNode[]): Map<string, { x: number; y: number }> {
  const positions = new Map<string, { x: number; y: number }>();
  let cursor = MARGIN;
  for (const node of core) {
    positions.set(node.id, { x: cursor, y: MARGIN });
    cursor += (node.width ?? 120) + 90;
  }
  return positions;
}

/**
 * 输入 toFlowElements 的元素，输出带确定位置的元素。
 * 同一输入永远产生同一布局（纯函数 + 确定性算法）。
 */
export async function layoutErFlow(elements: ErFlowElements): Promise<ErFlowElements> {
  const core = elements.nodes.filter(isCore);
  if (core.length === 0) return elements;

  const coreIds = new Set(core.map((node) => node.id));
  let positions: Map<string, { x: number; y: number }>;
  try {
    const elk = new ELK();
    const graph = await elk.layout({
      id: "root",
      children: core.map((node) => ({
        id: node.id,
        width: node.width ?? 120,
        height: node.height ?? 48,
      })),
      edges: elements.edges
        .filter((edge) => coreIds.has(edge.source) && coreIds.has(edge.target))
        .map((edge, index) => ({ id: `e${index}`, sources: [edge.source], targets: [edge.target] })),
      layoutOptions: {
        "elk.algorithm": "org.eclipse.elk.layered",
        "elk.direction": "DOWN",
        "elk.spacing.nodeNode": "70",
        "elk.layered.spacing.nodeNodeBetweenLayers": "110",
      },
    });
    const raw = new Map<string, { x: number; y: number }>();
    for (const child of graph.children ?? []) {
      if (typeof child.x === "number" && typeof child.y === "number") {
        raw.set(child.id, { x: child.x, y: child.y });
      }
    }
    if (raw.size < core.length) throw new Error("ELK 未返回完整核心布局");
    const minX = Math.min(...core.map((node) => raw.get(node.id)!.x));
    const minY = Math.min(...core.map((node) => raw.get(node.id)!.y));
    positions = new Map(
      core.map((node) => {
        const point = raw.get(node.id)!;
        return [node.id, { x: point.x - minX + MARGIN, y: point.y - minY + MARGIN }];
      }),
    );
  } catch {
    positions = rowLayout(core);
  }

  // 属性归属：attr→实体/联系 为直属属性；attr→attr 为复合子属性（挂在父属性上）。
  const nodeById = new Map(elements.nodes.map((node) => [node.id, node]));
  const childrenOfAttr = new Map<string, string[]>();
  const attrsOfOwner = new Map<string, string[]>();
  for (const edge of elements.edges) {
    const source = nodeById.get(edge.source);
    const target = nodeById.get(edge.target);
    if (!source || source.type !== "attribute" || !target) continue;
    if (target.type === "attribute") {
      const list = childrenOfAttr.get(target.id) ?? [];
      list.push(source.id);
      childrenOfAttr.set(target.id, list);
    } else {
      const list = attrsOfOwner.get(target.id) ?? [];
      list.push(source.id);
      attrsOfOwner.set(target.id, list);
    }
  }

  const placed = core.map<Box>((node) => {
    const point = positions.get(node.id)!;
    return { x: point.x, y: point.y, width: node.width ?? 120, height: node.height ?? 48 };
  });
  const boxById = new Map(placed.map((box, index) => [core[index]!.id, box]));

  // 按拥有者在 nodes 数组中的出现次序处理，保证同一输入次序确定。
  for (const node of elements.nodes) {
    if (node.type === "attribute") continue;
    const attrIds = attrsOfOwner.get(node.id);
    if (!attrIds?.length) continue;
    const ownerBox = boxById.get(node.id)!;
    const ownerCenter = { x: ownerBox.x + ownerBox.width / 2, y: ownerBox.y + ownerBox.height / 2 };
    attrIds.forEach((attrId, index) => {
      const attr = nodeById.get(attrId)!;
      const box = placeAround(
        ownerCenter,
        halfDiagonal(ownerBox),
        { width: attr.width ?? 90, height: attr.height ?? 46 },
        placed,
        -Math.PI / 2 + (2 * Math.PI * index) / attrIds.length,
      );
      placed.push(box);
      boxById.set(attrId, box);
      positions.set(attrId, { x: box.x, y: box.y });
    });
  }
  // 复合子属性最后绕复合父属性摆放（父属性此时已定位）。
  for (const [parentId, childIds] of childrenOfAttr) {
    const parentBox = boxById.get(parentId);
    if (!parentBox) continue;
    const parentCenter = { x: parentBox.x + parentBox.width / 2, y: parentBox.y + parentBox.height / 2 };
    childIds.forEach((childId, index) => {
      const child = nodeById.get(childId)!;
      const box = placeAround(
        parentCenter,
        halfDiagonal(parentBox),
        { width: child.width ?? 72, height: child.height ?? 46 },
        placed,
        -Math.PI / 2 + (2 * Math.PI * index) / childIds.length,
      );
      placed.push(box);
      positions.set(childId, { x: box.x, y: box.y });
    });
  }

  return {
    ...elements,
    nodes: elements.nodes.map((node) => ({ ...node, position: positions.get(node.id) ?? node.position })),
  };
}
