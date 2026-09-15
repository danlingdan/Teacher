import { describe, expect, it } from "vitest";
import { formatSql } from "./formatSql";

describe("formatSql", () => {
  it("SELECT/JOIN/GROUP BY 逐字重排且语义 token 顺序不变", () => {
    const input =
      "select s.sname, c.cname from sc sc join student s on sc.sno=s.sno where sc.grade>=60 group by s.sname order by s.sname limit 10";
    expect(formatSql(input)).toBe(
      [
        "SELECT s.sname,",
        "  c.cname",
        "FROM sc sc",
        "JOIN student s",
        "  ON sc.sno = s.sno",
        "WHERE sc.grade >= 60",
        "GROUP BY s.sname",
        "ORDER BY s.sname",
        "LIMIT 10",
      ].join("\n"),
    );
  });

  it("字符串与注释原样保留（含大小写）", () => {
    const input = "select * from t where name = 'mixed Case' -- keep me";
    expect(formatSql(input)).toBe(
      ["SELECT *", "FROM t", "WHERE name = 'mixed Case' -- keep me"].join("\n"),
    );
  });

  it("括号内不断行：函数参数与 IN 列表保持内联", () => {
    const input = "select count(id) from t where c in (1,2,3)";
    expect(formatSql(input)).toBe(["SELECT count(id)", "FROM t", "WHERE c IN (1, 2, 3)"].join("\n"));
  });

  it("空输入与超长输入不崩", () => {
    expect(formatSql("")).toBe("");
    expect(formatSql("   \n  ")).toBe("");
    const huge = "select " + Array.from({ length: 5_000 }, (_, i) => `c${i}`).join(", ") + " from t";
    expect(formatSql(huge).startsWith("SELECT c0,")).toBe(true);
  });

  it("INSERT INTO / VALUES / UPDATE SET 断行正确（关键字后括号留空格）", () => {
    expect(formatSql("insert into t(a,b) values(1,2)")).toBe(
      ["INSERT INTO t(a, b)", "VALUES (1, 2)"].join("\n"),
    );
    expect(formatSql("update t set a=1 where id=2")).toBe(
      ["UPDATE t", "SET a = 1", "WHERE id = 2"].join("\n"),
    );
  });
});
