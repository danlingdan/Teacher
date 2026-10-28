import { describe, expect, it } from "vitest";
import { toFlowElements, type ErAttributeFlowNode, type ErEdgeData, type ErEntityFlowNode, type ErModel, type ErRelationshipFlowNode } from "./erModel";

// v3.11.0 ERM-5：全记号样例（教材“学生选课”扩展版）——主码/复合/多值/派生属性、
// m:n 联系带联系属性、弱实体 + 依赖联系（拥有者基数 1、弱实体全参与）、ISA 概括层级。
const SAMPLE_MODEL: ErModel = {
  title: "学生选课系统",
  explanation: "样例建模思路说明",
  entities: [
    {
      id: "student",
      name: "学生",
      weak: false,
      attributes: [
        { name: "学号", key: true },
        { name: "姓名" },
        { name: "住址", composite: ["省", "市"] },
        { name: "电话", multivalued: true },
        { name: "年龄", derived: true },
      ],
    },
    {
      id: "course",
      name: "课程",
      weak: false,
      attributes: [
        { name: "课程号", key: true },
        { name: "课程名" },
      ],
    },
    {
      id: "course_class",
      name: "开课",
      weak: true,
      attributes: [{ name: "学期" }],
    },
    {
      id: "graduate",
      name: "研究生",
      weak: false,
      attributes: [{ name: "研究方向" }],
    },
  ],
  relationships: [
    {
      id: "elective",
      name: "选修",
      identifying: false,
      members: [
        { entity: "student", cardinality: "n", total: false },
        { entity: "course", cardinality: "m", total: false },
      ],
      attributes: [{ name: "成绩" }],
    },
    {
      id: "offering",
      name: "开设",
      identifying: true,
      members: [
        { entity: "course_class", cardinality: "n", total: true },
        { entity: "course", cardinality: "1", total: false },
      ],
      attributes: [],
    },
  ],
  hierarchies: [{ id: "h_student", name: "ISA", superclass: "student", subclasses: ["graduate"] }],
};

const elements = toFlowElements(SAMPLE_MODEL);

function nodeById(id: string) {
  const node = elements.nodes.find((item) => item.id === id);
  if (!node) throw new Error(`缺少节点 ${id}`);
  return node;
}

function edgeById(id: string) {
  const edge = elements.edges.find((item) => item.id === id);
  if (!edge) throw new Error(`缺少边 ${id}`);
  return edge;
}

// data 在类型上是按 kind 区分的联合，先按 type 收窄再断言（strict + noUncheckedIndexedAccess）。
function entityData(id: string): ErEntityFlowNode["data"] {
  const node = nodeById(id);
  if (node.type !== "entity") throw new Error(`${id} 不是实体节点`);
  return node.data;
}

function attributeData(id: string): ErAttributeFlowNode["data"] {
  const node = nodeById(id);
  if (node.type !== "attribute") throw new Error(`${id} 不是属性节点`);
  return node.data;
}

function relationshipData(id: string): ErRelationshipFlowNode["data"] {
  const node = nodeById(id);
  if (node.type !== "relationship") throw new Error(`${id} 不是联系节点`);
  return node.data;
}

function edgeData(id: string): ErEdgeData {
  const data = edgeById(id).data;
  if (!data) throw new Error(`${id} 缺少边标记`);
  return data;
}

describe("toFlowElements 全记号映射", () => {
  it("生成正确的节点数量与类型分布", () => {
    // 实体 4；属性：学生 5（含复合父属性）+ 复合子属性 2 + 课程 2 + 开课 1 + 研究生 1
    // = 11，加联系属性（成绩，同为 attribute 类型）= 12；联系 2 + ISA 三角 1。
    expect(elements.nodes).toHaveLength(4 + 12 + 2 + 1);
    expect(elements.nodes.filter((node) => node.type === "entity")).toHaveLength(4);
    expect(elements.nodes.filter((node) => node.type === "attribute")).toHaveLength(12);
    expect(elements.nodes.filter((node) => node.type === "relationship")).toHaveLength(2);
    expect(elements.nodes.filter((node) => node.type === "isa")).toHaveLength(1);
    // 边：每个属性节点一条归属边（含复合子属性 2、联系属性 1，共 12）
    // + 联系成员 4 + 层级 2 = 18。
    expect(elements.edges).toHaveLength(12 + 4 + 2);
  });

  it("遵循节点 id 规约与记号标记", () => {
    const student = entityData("entity:student");
    expect(student.label).toBe("学生");
    expect(student.weak).toBe(false);
    expect(entityData("entity:course_class").weak).toBe(true);

    expect(attributeData("attr:student:学号").isKey).toBe(true);
    expect(attributeData("attr:student:电话").multivalued).toBe(true);
    expect(attributeData("attr:student:年龄").derived).toBe(true);
    nodeById("attr:student:住址");
    nodeById("attr:student:住址:省");
    nodeById("attr:student:住址:市");

    const rel = relationshipData("rel:elective");
    expect(rel.identifying).toBe(false);
    expect(relationshipData("rel:offering").identifying).toBe(true);
    expect(nodeById("relattr:elective:成绩").type).toBe("attribute");

    const isa = nodeById("isa:h_student");
    if (isa.type !== "isa") throw new Error("isa:h_student 不是层级三角");
    expect(isa.data.label).toBe("ISA");
  });

  it("属性边指向拥有者，复合子属性挂在复合父属性上", () => {
    const keyEdge = edgeById("edge:attr:attr:student:学号");
    expect(keyEdge.source).toBe("attr:student:学号");
    expect(keyEdge.target).toBe("entity:student");

    const subEdge = edgeById("edge:attr:attr:student:住址:省");
    expect(subEdge.source).toBe("attr:student:住址:省");
    expect(subEdge.target).toBe("attr:student:住址");

    const relAttrEdge = edgeById("edge:attr:relattr:elective:成绩");
    expect(relAttrEdge.target).toBe("rel:elective");
  });

  it("实体—联系边携带基数与双线标记，双线只标弱实体一侧", () => {
    const electiveStudent = edgeData("edge:member:elective:0");
    expect(edgeById("edge:member:elective:0").source).toBe("entity:student");
    expect(edgeById("edge:member:elective:0").target).toBe("rel:elective");
    expect(electiveStudent.cardinality).toBe("n");
    expect(electiveStudent.total).toBe(false);
    expect(electiveStudent.identifying).toBe(false);

    expect(edgeData("edge:member:elective:1").cardinality).toBe("m");
    expect(edgeData("edge:member:offering:1").cardinality).toBe("1");

    // 依赖联系：弱实体（开课）一侧 total + identifying 均为真 → 渲染为双线。
    const weakEdge = edgeData("edge:member:offering:0");
    expect(edgeById("edge:member:offering:0").source).toBe("entity:course_class");
    expect(weakEdge.total).toBe(true);
    expect(weakEdge.identifying).toBe(true);
    // 拥有者（课程，基数 1）一侧不画双线。
    const ownerEdge = edgeData("edge:member:offering:1");
    expect(ownerEdge.identifying).toBe(false);
    expect(ownerEdge.total).toBe(false);
  });

  it("层级边连接超类与子类，直线且无箭头", () => {
    const superEdge = edgeById("edge:isa:super:h_student");
    expect(superEdge.source).toBe("entity:student");
    expect(superEdge.target).toBe("isa:h_student");
    const subEdge = edgeById("edge:isa:sub:h_student:graduate");
    expect(subEdge.source).toBe("isa:h_student");
    expect(subEdge.target).toBe("entity:graduate");

    for (const edge of elements.edges) {
      expect(edge.type).toBe("straight");
      expect(edge.markerEnd).toBeUndefined();
    }
  });

  it("所有节点携带尺寸供布局使用", () => {
    for (const node of elements.nodes) {
      expect(node.width).toBeGreaterThan(0);
      expect(node.height).toBeGreaterThan(0);
    }
  });
});
