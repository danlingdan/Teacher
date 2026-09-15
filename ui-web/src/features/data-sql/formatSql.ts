/**
 * v3.5.0 WBE-1：轻量本地 SQL 格式化。纯文本重排——只做关键字大小写归一、
 * 子句换行缩进与 SELECT 列表逗号分列；不改变 token 顺序，不经网络，
 * 字符串/注释/标识符原样保留，因此语义不受影响且 Monaco 撤销可恢复。
 */

type Token =
  | { kind: "word"; text: string }
  | { kind: "literal"; text: string } // '字符串'、"标识符"、`标识符`
  | { kind: "comment"; text: string }
  | { kind: "punct"; text: string };

/** 归一为大写的常见关键字；不在表内的词（表名、函数名、别名）保持原样。集合内必须是大写。 */
const KEYWORDS = new Set([
  "SELECT", "FROM", "WHERE", "GROUP", "BY", "HAVING", "ORDER", "LIMIT", "OFFSET",
  "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "ON", "AS",
  "AND", "OR", "NOT", "IN", "LIKE", "BETWEEN", "IS", "NULL", "EXISTS",
  "UNION", "ALL", "DISTINCT", "INSERT", "INTO", "VALUES", "UPDATE", "SET",
  "DELETE", "CREATE", "TABLE", "VIEW", "INDEX", "DROP", "ALTER", "ADD",
  "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "CONSTRAINT", "UNIQUE", "CHECK",
  "DEFAULT", "ASC", "DESC", "CASE", "WHEN", "THEN", "ELSE", "END",
  "BEGIN", "COMMIT", "ROLLBACK", "TRANSACTION", "IF",
]);

/** 子句关键字（长词组在前保证优先匹配；命中即在括号深度 0 处断行）。 */
const CLAUSES: string[][] = [
  ["INSERT", "INTO"],
  ["DELETE", "FROM"],
  ["LEFT", "OUTER", "JOIN"],
  ["RIGHT", "OUTER", "JOIN"],
  ["FULL", "OUTER", "JOIN"],
  ["UNION", "ALL"],
  ["LEFT", "JOIN"],
  ["RIGHT", "JOIN"],
  ["FULL", "JOIN"],
  ["INNER", "JOIN"],
  ["CROSS", "JOIN"],
  ["SELECT"], ["FROM"], ["WHERE"], ["GROUP", "BY"], ["HAVING"],
  ["ORDER", "BY"], ["LIMIT"], ["OFFSET"], ["SET"], ["VALUES"],
  ["UNION"], ["UPDATE"], ["JOIN"],
];

/** 运算符前后补空格；多字符运算符（>=、<>）先合并再补，避免被拆散。 */
const OPERATOR_CHARS = new Set(["=", "<", ">", "!", "+", "-", "*", "/", "%", "|", "&"]);

export function formatSql(input: string): string {
  const tokens = tokenize(input);
  const lines: string[] = [];
  let current = "";
  let parenDepth = 0;
  // SELECT 列表逗号分列只在「顶层 SELECT 之后、下一个子句之前」生效。
  let inTopSelectList = false;
  let pendingJoin = false;
  let lastWasKeyword = false; // 关键字后跟左括号时补一个空格（IN (…)），函数/表名保持紧贴。

  const breakLine = () => {
    if (current.trimEnd()) lines.push(current);
    current = "";
  };
  const isClauseAt = (index: number, clause: string[]): boolean => {
    if (parenDepth > 0) return false;
    for (let k = 0; k < clause.length; k++) {
      const token = tokens[index + k];
      if (!token || token.kind !== "word" || token.text.toUpperCase() !== clause[k]) {
        return false;
      }
    }
    return true;
  };

  for (let index = 0; index < tokens.length; index++) {
    const token = tokens[index];
    if (!token) continue;

    if (token.kind === "comment") {
      current += current && !/\s$/.test(current) ? " " + token.text : token.text;
      breakLine();
      continue;
    }

    if (token.kind === "literal") {
      current += token.text;
      continue;
    }

    if (token.kind === "punct") {
      if (token.text === "(") {
        parenDepth++;
        if (lastWasKeyword && /[A-Za-z]$/.test(current)) current += " ";
        lastWasKeyword = false;
        current += "(";
        continue;
      }
      if (token.text === ")") {
        parenDepth = Math.max(0, parenDepth - 1);
        current += ")";
        continue;
      }
      if (token.text === ",") {
        if (parenDepth === 0 && inTopSelectList) {
          lines.push(current + ",");
          current = "  ";
        } else {
          current += ", ";
        }
        continue;
      }
      if (token.text === ";") {
        current += ";";
        breakLine();
        inTopSelectList = false;
        pendingJoin = false;
        continue;
      }
      if (OPERATOR_CHARS.has(token.text)) {
        const next = tokens[index + 1];
        const nextIsOperator = next?.kind === "punct" && OPERATOR_CHARS.has(next.text);
        const nextIsTight =
          next?.kind === "punct" && [")", ",", ";", "("].includes(next.text);
        if (current !== "" && !/[\s(]$/.test(current)
            && !OPERATOR_CHARS.has(current.slice(-1))) {
          current += " ";
        }
        current += token.text;
        if (!nextIsOperator && !nextIsTight) current += " ";
        continue;
      }
      current += token.text;
      continue;
    }

    // word：先看是否命中子句关键字（括号深度 0）。
    const clause = CLAUSES.find((candidate) => isClauseAt(index, candidate));
    if (clause) {
      breakLine();
      current = clause.join(" ");
      lastWasKeyword = true;
      if (clause[0] === "SELECT") {
        inTopSelectList = true;
      } else {
        inTopSelectList = false;
      }
      pendingJoin = clause[clause.length - 1] === "JOIN";
      index += clause.length - 1;
      continue;
    }

    const upper = token.text.toUpperCase();
    const word = KEYWORDS.has(upper) ? upper : token.text;
    lastWasKeyword = word === upper;
    if (upper === "ON" && pendingJoin) {
      current += "\n  " + word + " ";
      pendingJoin = false;
      continue;
    }
    const needsSpace = current !== "" && !/[(\s]$/.test(current);
    current += (needsSpace ? " " : "") + word;
  }
  breakLine();
  return lines.map((line) => line.trimEnd()).join("\n");
}

function tokenize(sql: string): Token[] {
  const tokens: Token[] = [];
  let i = 0;
  while (i < sql.length) {
    const ch = sql[i] ?? "";
    if (ch === "") break;
    if (ch === "'" || ch === '"' || ch === "`") {
      let j = i + 1;
      while (j < sql.length) {
        if (sql[j] === ch) {
          if (sql[j + 1] === ch) {
            // 引号内的连续两个同种引号是转义，整体并入字面量。
            j += 2;
            continue;
          }
          break;
        }
        j++;
      }
      tokens.push({ kind: "literal", text: sql.slice(i, Math.min(j + 1, sql.length)) });
      i = j + 1;
      continue;
    }
    if (ch === "-" && sql[i + 1] === "-") {
      const end = sql.indexOf("\n", i);
      const stop = end === -1 ? sql.length : end;
      tokens.push({ kind: "comment", text: sql.slice(i, stop) });
      i = stop;
      continue;
    }
    if (ch === "/" && sql[i + 1] === "*") {
      const end = sql.indexOf("*/", i + 2);
      const stop = end === -1 ? sql.length : end + 2;
      tokens.push({ kind: "comment", text: sql.slice(i, stop) });
      i = stop;
      continue;
    }
    if (/\s/.test(ch)) {
      i++;
      continue;
    }
    if (/[A-Za-z0-9_$#\u4e00-\u9fa5]/.test(ch)) {
      let j = i;
      while (j < sql.length && /[A-Za-z0-9_$.#$\u4e00-\u9fa5]/.test(sql[j] ?? "")) j++;
      tokens.push({ kind: "word", text: sql.slice(i, j) });
      i = j;
      continue;
    }
    tokens.push({ kind: "punct", text: ch });
    i++;
  }
  return tokens;
}
