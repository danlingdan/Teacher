import type { Edge, Node } from "@xyflow/react";

// v3.11.0 ERM-5：E-R 建模前端类型与纯渲染映射。
// 契约以 docs/plans/2026-10-27-v3.11.0-er-modeling-plan.md §4 为准（冻结）：
// Java 侧完成解析宽容与确定性校验后经桥接返回，前端只做纯映射渲染，
// 不在本文件引入任何 IPC、React 组件或布局依赖。

/** 联系成员基数白名单（Java 校验后的取值）。 */
export type ErCardinality = "1" | "n" | "m";

export type ErGenerationStatus =
  | "OK"
  | "MODEL_UNAVAILABLE"
  | "PROVIDER_ERROR"
  | "PROMPT_ERROR"
  | "PARSE_ERROR"
  | "VALIDATION_FAILED";

/** 实体/联系共用的属性描述（联系属性由 Java 校验禁止 key=true）。 */
export type ErAttribute = {
  name: string;
  key?: boolean;
  composite?: string[];
  multivalued?: boolean;
  derived?: boolean;
};

export type ErEntity = {
  id: string;
  name: string;
  weak?: boolean;
  attributes: ErAttribute[];
};

export type ErRelationshipMember = {
  entity: string;
  cardinality: ErCardinality;
  total?: boolean;
};

export type ErRelationship = {
  id: string;
  name: string;
  identifying?: boolean;
  members: ErRelationshipMember[];
  attributes: ErAttribute[];
};

export type ErHierarchy = {
  id: string;
  name: string;
  superclass: string;
  subclasses: string[];
};

export type ErModel = {
  title: string;
  explanation: string;
  entities: ErEntity[];
  relationships: ErRelationship[];
  hierarchies: ErHierarchy[];
};

/** `ai.modeling.er.generate` 的 IPC 响应包络；失败态 erModel 为 null。 */
export type ErGenerationResult = {
  status: ErGenerationStatus;
  model: string | null;
  promptVersion: string;
  erModel: ErModel | null;
  errors: string[];
  message: string;
};

// ---------------------------------------------------------------------------
// React Flow 元素映射
// ---------------------------------------------------------------------------

export type ErNodeKind = "entity" | "attribute" | "relationship" | "isa";

export type ErEdgeData = {
  /** 实体端基数标注（1/n/m），仅实体—联系边携带。 */
  cardinality?: ErCardinality;
  /** 全参与约束：该边画双线。 */
  total?: boolean;
  /** 依赖联系中弱实体一侧：该边画双线。 */
  identifying?: boolean;
};

export type ErEntityFlowNode = Node<{ label: string; weak: boolean }, "entity">;
export type ErAttributeFlowNode = Node<
  { label: string; isKey: boolean; multivalued: boolean; derived: boolean },
  "attribute"
>;
export type ErRelationshipFlowNode = Node<{ label: string; identifying: boolean }, "relationship">;
export type ErIsaFlowNode = Node<{ label: string }, "isa">;
export type ErFlowNode =
  | ErEntityFlowNode
  | ErAttributeFlowNode
  | ErRelationshipFlowNode
  | ErIsaFlowNode;
export type ErFlowEdge = Edge<ErEdgeData, "straight">;

export type ErFlowElements = {
  nodes: ErFlowNode[];
  edges: ErFlowEdge[];
};

/** 节点 id 规约（计划 §5 ERM-5，布局与导出都依赖该前缀解析）。 */
export const ER_NODE_KINDS: readonly ErNodeKind[] = ["entity", "attribute", "relationship", "isa"];

/**
 * 节点尺寸估计：中文按整字宽、ASCII 按 0.6 倍字宽估。
 * 与 ErNodes.tsx 的渲染共用同一函数，保证 ELK 布局输入与实际形状一致。
 */
export function erNodeSize(kind: ErNodeKind, label: string): { width: number; height: number } {
  let textWidth = 0;
  for (const ch of label) textWidth += ch.charCodeAt(0) > 0x2e80 ? 13 : 8;
  switch (kind) {
    case "entity":
      return { width: Math.max(96, textWidth + 44), height: 48 };
    case "attribute":
      return { width: Math.max(72, textWidth + 46), height: 46 };
    case "relationship":
      // 菱形内接文字需要更大的留白，避免文字顶到斜边。
      return { width: Math.max(118, textWidth + 84), height: 62 };
    case "isa":
      return { width: 88, height: 50 };
  }
}

function attributeData(attr: ErAttribute): ErAttributeFlowNode["data"] {
  return {
    label: attr.name,
    isKey: attr.key === true,
    multivalued: attr.multivalued === true,
    derived: attr.derived === true,
  };
}

function plainEdge(id: string, source: string, target: string, data: ErEdgeData): ErFlowEdge {
  // 教材风格：一律直线、无箭头；直线裁剪与双线由 ErEdges.tsx 的自定义 straight 边实现。
  return { id, source, target, type: "straight", markerEnd: undefined, data };
}

/**
 * ErModel → React Flow 元素。纯函数：不排位置（全部 {0,0}），位置由 layout.ts 决定。
 *
 * id 规约：
 *   entity:<id>  attr:<ownerKey>:<name>  attr:<ownerKey>:<name>:<sub>（复合子属性）
 *   rel:<id>  relattr:<relId>:<name>  isa:<id>
 * 边规约：实体→联系边携带 {cardinality,total,identifying}；属性边指向拥有者；
 *   概括层级边为 超类→三角 与 三角→子类（方向仅供分层布局使用，渲染无箭头）。
 */
export function toFlowElements(erModel: ErModel): ErFlowElements {
  const nodes: ErFlowNode[] = [];
  const edges: ErFlowEdge[] = [];
  const weakEntities = new Set(
    (erModel.entities ?? []).filter((entity) => entity.weak === true).map((entity) => entity.id),
  );

  for (const entity of erModel.entities ?? []) {
    const size = erNodeSize("entity", entity.name);
    nodes.push({
      id: `entity:${entity.id}`,
      type: "entity",
      position: { x: 0, y: 0 },
      data: { label: entity.name, weak: entity.weak === true },
      width: size.width,
      height: size.height,
    });
    for (const attr of entity.attributes ?? []) {
      const attrSize = erNodeSize("attribute", attr.name);
      const attrId = `attr:${entity.id}:${attr.name}`;
      nodes.push({
        id: attrId,
        type: "attribute",
        position: { x: 0, y: 0 },
        data: attributeData(attr),
        width: attrSize.width,
        height: attrSize.height,
      });
      edges.push(plainEdge(`edge:attr:${attrId}`, attrId, `entity:${entity.id}`, {}));
      for (const sub of attr.composite ?? []) {
        const subSize = erNodeSize("attribute", sub);
        const subId = `attr:${entity.id}:${attr.name}:${sub}`;
        nodes.push({
          id: subId,
          type: "attribute",
          position: { x: 0, y: 0 },
          data: { label: sub, isKey: false, multivalued: false, derived: false },
          width: subSize.width,
          height: subSize.height,
        });
        edges.push(plainEdge(`edge:attr:${subId}`, subId, attrId, {}));
      }
    }
  }

  for (const relationship of erModel.relationships ?? []) {
    const size = erNodeSize("relationship", relationship.name);
    nodes.push({
      id: `rel:${relationship.id}`,
      type: "relationship",
      position: { x: 0, y: 0 },
      data: { label: relationship.name, identifying: relationship.identifying === true },
      width: size.width,
      height: size.height,
    });
    relationship.members.forEach((member, index) => {
      edges.push(
        plainEdge(`edge:member:${relationship.id}:${index}`, `entity:${member.entity}`, `rel:${relationship.id}`, {
          cardinality: member.cardinality,
          total: member.total === true,
          // 双线只画在弱实体一侧（依赖联系的教材画法）。
          identifying: relationship.identifying === true && weakEntities.has(member.entity),
        }),
      );
    });
    for (const attr of relationship.attributes ?? []) {
      const attrSize = erNodeSize("attribute", attr.name);
      const relAttrId = `relattr:${relationship.id}:${attr.name}`;
      nodes.push({
        id: relAttrId,
        type: "attribute",
        position: { x: 0, y: 0 },
        data: attributeData(attr),
        width: attrSize.width,
        height: attrSize.height,
      });
      edges.push(plainEdge(`edge:attr:${relAttrId}`, relAttrId, `rel:${relationship.id}`, {}));
    }
  }

  for (const hierarchy of erModel.hierarchies ?? []) {
    const size = erNodeSize("isa", hierarchy.name);
    nodes.push({
      id: `isa:${hierarchy.id}`,
      type: "isa",
      position: { x: 0, y: 0 },
      data: { label: hierarchy.name || "ISA" },
      width: size.width,
      height: size.height,
    });
    edges.push(
      plainEdge(`edge:isa:super:${hierarchy.id}`, `entity:${hierarchy.superclass}`, `isa:${hierarchy.id}`, {}),
    );
    for (const sub of hierarchy.subclasses ?? []) {
      edges.push(plainEdge(`edge:isa:sub:${hierarchy.id}:${sub}`, `isa:${hierarchy.id}`, `entity:${sub}`, {}));
    }
  }

  return { nodes, edges };
}
