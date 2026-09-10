# SQLTeacherExercisePackage 1

===[DATASET]===
ID: spj-demo-v1
NAME: 供应商-零件-工程数据集
VERSION: 1
SQL:
create table s(sno text primary key, sname text not null, status integer, city text);
insert into s values
    ('S1', '精益', 20, '天津'), ('S2', '盛锡', 10, '北京'), ('S3', '东方红', 30, '北京'),
    ('S4', '丰泰盛', 20, '天津'), ('S5', '为民', 30, '上海');
create table p(pno text primary key, pname text not null, color text, weight integer);
insert into p values
    ('P1', '螺母', '红', 12), ('P2', '螺栓', '绿', 17), ('P3', '螺丝刀', '蓝', 14),
    ('P4', '螺丝刀', '红', 14), ('P5', '凸轮', '蓝', 40), ('P6', '齿轮', '红', 30);
create table j(jno text primary key, jname text not null, city text);
insert into j values
    ('J1', '三建', '北京'), ('J2', '一汽', '长春'), ('J3', '弹簧厂', '天津'),
    ('J4', '造船厂', '天津'), ('J5', '机车厂', '唐山'), ('J6', '无线电厂', '常州'),
    ('J7', '半导体厂', '南京');
create table spj(sno text not null, pno text not null, jno text not null, qty integer,
    primary key(sno, pno, jno));
insert into spj values
    ('S1', 'P1', 'J1', 200), ('S1', 'P1', 'J3', 100), ('S1', 'P1', 'J4', 700), ('S1', 'P2', 'J2', 100),
    ('S2', 'P3', 'J1', 400), ('S2', 'P3', 'J2', 200), ('S2', 'P3', 'J4', 500), ('S2', 'P3', 'J5', 400),
    ('S2', 'P5', 'J1', 400), ('S2', 'P5', 'J2', 100), ('S3', 'P1', 'J1', 200), ('S3', 'P3', 'J1', 200),
    ('S4', 'P5', 'J1', 100), ('S4', 'P6', 'J3', 300), ('S4', 'P6', 'J4', 200), ('S5', 'P2', 'J4', 100),
    ('S5', 'P3', 'J1', 200), ('S5', 'P6', 'J2', 200), ('S5', 'P6', 'J4', 500);

===[EXERCISE]===
ID: spj-01
TITLE: 查询全部零件
KNOWLEDGE: 基础查询
DIFFICULTY: BEGINNER
DATASET: spj-demo-v1
DESCRIPTION:
返回 p 表的全部列（pno、pname、color、weight），按 pno 升序。
SQL:
select pno, pname, color, weight from p order by pno
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
HINTS:
先写 SELECT 和 FROM。
使用 ORDER BY pno 升序。
核对列名与顺序后再提交。
VERSION: 1
ENABLED: true
CREATED: 2026-09-10T00:00:00Z
UPDATED: 2026-09-10T00:00:00Z

===[EXERCISE]===
ID: spj-02
TITLE: 查询较轻的零件
KNOWLEDGE: 条件筛选
DIFFICULTY: BEGINNER
DATASET: spj-demo-v1
DESCRIPTION:
返回重量小于 30 的零件名（pname）与重量（weight），按重量升序、名称升序排列。
SQL:
select pname, weight from p where weight < 30 order by weight, pname
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
EXPECT_COLUMNS: pname, weight
HINTS:
用 WHERE 过滤重量。
ORDER BY 可以写多个排序键。
确认列名是 pname、weight。
VERSION: 1
ENABLED: true
CREATED: 2026-09-10T00:00:00Z
UPDATED: 2026-09-10T00:00:00Z

===[EXERCISE]===
ID: spj-03
TITLE: 有供应记录的供应商
KNOWLEDGE: 去重与排序
DIFFICULTY: BEGINNER
DATASET: spj-demo-v1
DESCRIPTION:
查询在 spj 表中出现过供应记录的供应商号（sno），去除重复行，按供应商号升序。
SQL:
select distinct sno from spj order by sno
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
KEYWORDS: DISTINCT
HINTS:
DISTINCT 用于去除重复行。
只输出 sno 一列。
结果应为 5 行。
VERSION: 1
ENABLED: true
CREATED: 2026-09-10T00:00:00Z
UPDATED: 2026-09-10T00:00:00Z

===[EXERCISE]===
ID: spj-04
TITLE: 各供应商供应总量
KNOWLEDGE: 分组聚合
DIFFICULTY: INTERMEDIATE
DATASET: spj-demo-v1
DESCRIPTION:
统计每个供应商的供应总数量，输出供应商号（sno）与总量（列名为 total_qty），按供应商号升序。
SQL:
select sno, sum(qty) as total_qty from spj group by sno order by sno
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
EXPECT_COLUMNS: sno, total_qty
HINTS:
用 GROUP BY sno 分组。
SUM(qty) 求和，别名为 total_qty。
列名必须与题目要求一致。
VERSION: 1
ENABLED: true
CREATED: 2026-09-10T00:00:00Z
UPDATED: 2026-09-10T00:00:00Z

===[EXERCISE]===
ID: spj-05
TITLE: 红色零件的供应商
KNOWLEDGE: 连接查询
DIFFICULTY: INTERMEDIATE
DATASET: spj-demo-v1
DESCRIPTION:
查询供应了红色零件的供应商号（sno）与零件号（pno），去除重复行，按供应商号、零件号升序。
SQL:
select distinct s.sno, p.pno from s join spj on s.sno = spj.sno join p on p.pno = spj.pno where p.color = '红' order by s.sno, p.pno
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
KEYWORDS: JOIN
HINTS:
需要连接 s、spj、p 三张表。
红色零件的 color 是"红"。
DISTINCT 避免同一供应商与零件的多条供应记录重复。
VERSION: 1
ENABLED: true
CREATED: 2026-09-10T00:00:00Z
UPDATED: 2026-09-10T00:00:00Z

===[EXERCISE]===
ID: spj-06
TITLE: 北京工程采购天津供应商的明细
KNOWLEDGE: 连接与筛选
DIFFICULTY: INTERMEDIATE
DATASET: spj-demo-v1
DESCRIPTION:
查询位于"北京"的工程从位于"天津"的供应商处采购的供应明细：供应商号（sno）、零件号（pno）、工程号（jno），按 sno、pno、jno 升序。
SQL:
select spj.sno, spj.pno, spj.jno from spj join s on s.sno = spj.sno join j on j.jno = spj.jno where s.city = '天津' and j.city = '北京' order by spj.sno, spj.pno, spj.jno
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
HINTS:
工程与供应商的城市在 j、s 两张表里。
连接条件用城市值"天津"与"北京"。
结果应只有 2 行。
VERSION: 1
ENABLED: true
CREATED: 2026-09-10T00:00:00Z
UPDATED: 2026-09-10T00:00:00Z

===[EXERCISE]===
ID: spj-07
TITLE: 供应总量超过 500 的供应商
KNOWLEDGE: 分组与过滤
DIFFICULTY: INTERMEDIATE
DATASET: spj-demo-v1
DESCRIPTION:
查询供应总量大于 500 的供应商号（sno）与总量（列名 total_qty），按总量降序排列；总量相同时按供应商号升序。
SQL:
select sno, sum(qty) as total_qty from spj group by sno having sum(qty) > 500 order by total_qty desc, sno
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
KEYWORDS: HAVING
HINTS:
对分组结果的过滤要用 HAVING。
总量列别名为 total_qty。
结果应为 4 行。
VERSION: 1
ENABLED: true
CREATED: 2026-09-10T00:00:00Z
UPDATED: 2026-09-10T00:00:00Z

===[EXERCISE]===
ID: spj-08
TITLE: 没有被使用的零件
KNOWLEDGE: 子查询
DIFFICULTY: ADVANCED
DATASET: spj-demo-v1
DESCRIPTION:
查询没有被任何工程使用过的零件号（pno）与零件名（pname）。要求使用 NOT EXISTS 子查询。
SQL:
select pno, pname from p where not exists (select 1 from spj where spj.pno = p.pno)
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: false
EXPECTED_ROWS: 1
KEYWORDS: NOT EXISTS
HINTS:
NOT EXISTS 判断子查询无结果。
子查询里把外层的 p.pno 与 spj 关联起来。
结果只有 1 行。
VERSION: 1
ENABLED: true
CREATED: 2026-09-10T00:00:00Z
UPDATED: 2026-09-10T00:00:00Z

===[EXERCISE]===
ID: spj-09
TITLE: 供应多种零件的供应商
KNOWLEDGE: 分组统计
DIFFICULTY: ADVANCED
DATASET: spj-demo-v1
DESCRIPTION:
查询至少供应了两种不同零件的供应商号（sno）与供应零件种数（列名 kinds），按种数降序、供应商号升序排列。
SQL:
select sno, count(distinct pno) as kinds from spj group by sno having count(distinct pno) >= 2 order by kinds desc, sno
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
EXPECT_COLUMNS: sno, kinds
HINTS:
COUNT(DISTINCT pno) 统计不同零件种数。
HAVING 过滤分组结果。
结果应为 5 行。
VERSION: 1
ENABLED: true
CREATED: 2026-09-10T00:00:00Z
UPDATED: 2026-09-10T00:00:00Z

===[EXERCISE]===
ID: spj-10
TITLE: 供应大户工程
KNOWLEDGE: 综合聚合
DIFFICULTY: ADVANCED
DATASET: spj-demo-v1
DESCRIPTION:
查询供应总数量大于 800 的工程号（jno）与总量（列名 total_qty），按总量降序、工程号升序排列。
SQL:
select jno, sum(qty) as total_qty from spj group by jno having sum(qty) > 800 order by total_qty desc, jno
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
EXPECT_COLUMNS: jno, total_qty
HINTS:
按 jno 分组并对 qty 求和。
HAVING 里写总量条件。
结果应为 2 行。
VERSION: 1
ENABLED: true
CREATED: 2026-09-10T00:00:00Z
UPDATED: 2026-09-10T00:00:00Z
