import type { DatabaseTable } from "../../shared/types";

/**
 * v3.5.0 SCH-3：从外键元数据生成只读 Mermaid erDiagram 源码。每条外键一条
 * 「子表 ||--o{ 父表」关系线，标签为子表侧列名；不涉及任何数据内容。
 */
export function buildErDiagramSource(tables: DatabaseTable[]): string {
  const lines: string[] = ["erDiagram"];
  for (const table of tables) {
    for (const foreignKey of table.foreignKeys ?? []) {
      const label = foreignKey.columns.join(", ") || table.name;
      lines.push(
        `    ${entityName(table.name)} ||--o{ ${entityName(foreignKey.referencedTable)} : "${label}"`,
      );
    }
  }
  return lines.join("\n");
}

/** 含特殊字符的表名用引号包裹，符合 Mermaid erDiagram 的实体命名规则。 */
function entityName(name: string): string {
  return /^[A-Za-z_][A-Za-z0-9_]*$/.test(name) ? name : `"${name.replace(/"/g, "")}"`;
}

export function hasForeignKeys(tables: DatabaseTable[]): boolean {
  return tables.some((table) => (table.foreignKeys ?? []).length > 0);
}
