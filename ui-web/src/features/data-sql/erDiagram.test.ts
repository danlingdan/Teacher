import { describe, expect, it } from "vitest";
import { buildErDiagramSource, hasForeignKeys } from "./erDiagram";
import type { DatabaseTable } from "../../shared/types";

const demoTables: DatabaseTable[] = [
  {
    name: "Student",
    columns: [],
    foreignKeys: [],
  },
  {
    name: "Course",
    columns: [],
    foreignKeys: [{ columns: ["Cpno"], referencedTable: "Course", referencedColumns: ["Cno"] }],
  },
  {
    name: "SC",
    columns: [],
    foreignKeys: [
      { columns: ["Sno"], referencedTable: "Student", referencedColumns: ["Sno"] },
      { columns: ["Cno"], referencedTable: "Course", referencedColumns: ["Cno"] },
    ],
  },
];

describe("buildErDiagramSource", () => {
  it("演示库形态：自引用 + 双外键全部生成关系线", () => {
    expect(buildErDiagramSource(demoTables)).toBe(
      [
        "erDiagram",
        '    Course ||--o{ Course : "Cpno"',
        '    SC ||--o{ Student : "Sno"',
        '    SC ||--o{ Course : "Cno"',
      ].join("\n"),
    );
  });

  it("复合外键标签用逗号连接列名", () => {
    const tables: DatabaseTable[] = [
      {
        name: "ENROLL",
        columns: [],
        foreignKeys: [
          { columns: ["Sno", "Cno"], referencedTable: "Progress", referencedColumns: ["Sno", "Cno"] },
        ],
      },
    ];
    expect(buildErDiagramSource(tables)).toContain('ENROLL ||--o{ Progress : "Sno, Cno"');
  });

  it("无外键表只产生 erDiagram 头", () => {
    expect(buildErDiagramSource([demoTables[0]!])).toBe("erDiagram");
  });
});

describe("hasForeignKeys", () => {
  it("有外键为真，无外键为假", () => {
    expect(hasForeignKeys(demoTables)).toBe(true);
    expect(hasForeignKeys([demoTables[0]!])).toBe(false);
  });
});
