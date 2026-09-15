/**
 * v3.5.0 WBE-2：教学常用语句模板。内容为前端常量，表/列命名与演示库
 * （Student / Course / SC / SPJ）对齐；点击后整体替换或插入编辑器光标处。
 */
export interface SqlTemplate {
  label: string;
  hint: string;
  template: string;
}

export const SQL_TEMPLATES: SqlTemplate[] = [
  {
    label: "三表 JOIN",
    hint: "SC 连 Student、Course",
    template: `SELECT s.Sname, c.Cname, sc.Grade
FROM SC sc
JOIN Student s ON sc.Sno = s.Sno
JOIN Course c ON sc.Cno = c.Cno
ORDER BY s.Sno;`,
  },
  {
    label: "分组统计",
    hint: "GROUP BY + HAVING",
    template: `SELECT c.Cname, COUNT(*) AS 选课人数, AVG(sc.Grade) AS 平均成绩
FROM SC sc
JOIN Course c ON sc.Cno = c.Cno
GROUP BY c.Cname
HAVING COUNT(*) >= 2
ORDER BY 选课人数 DESC;`,
  },
  {
    label: "子查询",
    hint: "IN 嵌套",
    template: `SELECT Sname
FROM Student
WHERE Sno IN (
  SELECT Sno
  FROM SC
  WHERE Cno IN (
    SELECT Cno
    FROM Course
    WHERE Cname = '数据结构'
  )
);`,
  },
  {
    label: "INSERT … SELECT",
    hint: "从查询结果批量插入",
    template: `INSERT INTO SC (Sno, Cno, Grade)
SELECT Sno, 81001, 0
FROM Student
WHERE Smajor = '计算机科学与技术';`,
  },
  {
    label: "条件更新",
    hint: "UPDATE + WHERE",
    template: `UPDATE SC
SET Grade = Grade + 5
WHERE Cno = 81001 AND Grade < 60;`,
  },
];
