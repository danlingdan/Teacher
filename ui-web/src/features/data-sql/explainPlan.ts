/**
 * v3.5.0 SFE-3：SQLite EXPLAIN QUERY PLAN 行的白话解读。纯文本模式映射，
 * 不改变计划原文的展示；未命中的行原样保留在原文表格中，不强行解释。
 */

export interface ExplainInterpretation {
  detail: string;
  reading: string;
  hint: string;
}

/** 解读一段执行计划行；返回与输入等长的解读数组（未命中给通用说明）。 */
export function interpretExplainRows(details: string[]): ExplainInterpretation[] {
  return details.map((detail) => {
    const upper = detail.toUpperCase();
    if (/SEARCH\s+(TABLE\s+)?\S+\s+USING\s+INTEGER\s+PRIMARY\s+KEY/.test(upper)) {
      return {
        detail,
        reading: "按主键（rowid）直接定位行",
        hint: "这是最快的查找方式，保持现状即可。",
      };
    }
    const indexSearch = detail.match(/SEARCH\s+(?:TABLE\s+)?(\S+)\s+USING\s+INDEX\s+(\S+)/i);
    if (indexSearch) {
      return {
        detail,
        reading: `通过索引 ${indexSearch[2]} 命中 ${indexSearch[1]}`,
        hint: "索引查找效率较高；若过滤列仍有缺失，可再补建复合索引。",
      };
    }
    const scan = detail.match(/SCAN\s+(?:TABLE\s+)?(\S+)/i);
    if (scan) {
      return {
        detail,
        reading: `对 ${scan[1]} 全表扫描`,
        hint: "小表全表扫描没问题；数据量大时可在 WHERE/JOIN 的过滤列上建索引。",
      };
    }
    if (/TEMP\s+B-TREE\s+FOR\s+ORDER\s+BY/i.test(detail)) {
      return {
        detail,
        reading: "ORDER BY 需要临时排序结构",
        hint: "为排序列建索引（可配合 WHERE 列做复合索引）可避免临时排序。",
      };
    }
    if (/TEMP\s+B-TREE\s+FOR\s+GROUP\s+BY/i.test(detail)) {
      return {
        detail,
        reading: "GROUP BY 需要临时分组结构",
        hint: "按分组列建索引可让分组直接按序完成。",
      };
    }
    if (/TEMP\s+B-TREE\s+FOR\s+DISTINCT/i.test(detail)) {
      return {
        detail,
        reading: "DISTINCT 需要临时去重结构",
        hint: "若去重列上有索引，可以减少临时结构的开销。",
      };
    }
    if (/SUBQUERY|CO-?ROUTINE|MATERIALIZE/i.test(upper)) {
      return {
        detail,
        reading: "包含子查询/物化步骤",
        hint: "子查询结果会先物化；可对比把子查询改写为 JOIN 的计划差异。",
      };
    }
    return {
      detail,
      reading: "其他计划步骤",
      hint: "结合原文判断；教学场景下通常无需优化。",
    };
  });
}

/** 从 SqlPage 行中提取 detail 列（EXPLAIN QUERY PLAN 的输出列名固定为 detail）。 */
export function extractExplainDetails(rows: Array<Record<string, unknown>>): string[] {
  return rows
    .map((row) => (row["detail"] == null ? "" : String(row["detail"])))
    .filter((detail) => detail !== "");
}
