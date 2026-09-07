# SQLTeacherExercisePackage 1

===[DATASET]===
ID: school-core-v2
NAME: 学校核心数据集
VERSION: 1
SQL:
create table student(id integer primary key, name text not null, class_name text not null, score integer not null);
insert into student values
    (1, 'Alice', 'A班', 92), (2, 'Bob', 'A班', 76), (3, 'Carol', 'B班', 88),
    (4, 'David', 'B班', 64), (5, 'Eve', 'C班', 95), (6, 'Frank', 'C班', 76), (7, 'Grace', 'A班', 52);
create table course(id integer primary key, name text not null, teacher text not null);
insert into course values (1, '数据库原理', '王老师'), (2, 'Java 程序设计', '李老师'), (3, '计算机网络', '赵老师'), (4, '软件工程', '钱老师');
create table enrollment(student_id integer not null, course_id integer not null, grade integer not null,
    primary key(student_id, course_id),
    foreign key(student_id) references student(id),
    foreign key(course_id) references course(id));
insert into enrollment values
    (1, 1, 96), (1, 2, 90), (2, 1, 78), (2, 3, 82), (3, 1, 89),
    (3, 2, 91), (4, 2, 68), (5, 1, 97), (5, 3, 94), (6, 3, 75);

===[EXERCISE]===
ID: query-01
TITLE: 查询全部学生
KNOWLEDGE: 基础查询
DIFFICULTY: BEGINNER
DATASET: school-core-v2
DESCRIPTION:
返回 student 表的全部列，按 id 升序。
SQL:
select id, name, class_name, score from student order by id
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
HINTS:
先写 SELECT 和 FROM。
使用 ORDER BY id。
运行前逐项核对题目要求中的返回字段、筛选条件、分组和排序。
VERSION: 3
ENABLED: true
CREATED: 2026-07-21T00:00:00Z
UPDATED: 2026-09-08T00:00:00Z

===[EXERCISE]===
ID: query-02
TITLE: 查询学生姓名
KNOWLEDGE: 选择列
DIFFICULTY: BEGINNER
DATASET: school-core-v2
DESCRIPTION:
返回所有学生的 name，按 id 升序。
SQL:
select name from student order by id
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
HINTS:
只选择 name 列。
排序列不必出现在结果中。
运行前逐项核对题目要求中的返回字段、筛选条件、分组和排序。
VERSION: 3
ENABLED: true
CREATED: 2026-07-21T00:00:00Z
UPDATED: 2026-09-08T00:00:00Z

===[EXERCISE]===
ID: query-03
TITLE: 查询班级名单
KNOWLEDGE: 选择列
DIFFICULTY: BEGINNER
DATASET: school-core-v2
DESCRIPTION:
返回学生姓名和班级，按姓名升序。
SQL:
select name, class_name from student order by name
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
HINTS:
选择 name 与 class_name。
使用 ORDER BY name。
运行前逐项核对题目要求中的返回字段、筛选条件、分组和排序。
VERSION: 3
ENABLED: true
CREATED: 2026-07-21T00:00:00Z
UPDATED: 2026-09-08T00:00:00Z

===[EXERCISE]===
ID: query-04
TITLE: 成绩降序列表
KNOWLEDGE: 排序
DIFFICULTY: BEGINNER
DATASET: school-core-v2
DESCRIPTION:
返回姓名和成绩，成绩从高到低，同分按 id 升序。
SQL:
select name, score from student order by score desc, id
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
HINTS:
DESC 表示降序。
添加第二排序键 id。
运行前逐项核对题目要求中的返回字段、筛选条件、分组和排序。
VERSION: 3
ENABLED: true
CREATED: 2026-07-21T00:00:00Z
UPDATED: 2026-09-08T00:00:00Z

===[EXERCISE]===
ID: filter-01
TITLE: 筛选及格学生
KNOWLEDGE: WHERE 筛选
DIFFICULTY: BEGINNER
DATASET: school-core-v2
DESCRIPTION:
返回成绩不低于 60 的学生姓名，按 id 升序。
SQL:
select name from student where score >= 60 order by id
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
HINTS:
使用 WHERE。
不低于对应 >=。
运行前逐项核对题目要求中的返回字段、筛选条件、分组和排序。
VERSION: 3
ENABLED: true
CREATED: 2026-07-21T00:00:00Z
UPDATED: 2026-09-08T00:00:00Z

===[EXERCISE]===
ID: filter-02
TITLE: 筛选高分学生
KNOWLEDGE: 比较条件
DIFFICULTY: BEGINNER
DATASET: school-core-v2
DESCRIPTION:
返回成绩大于 85 的姓名和成绩，按成绩降序。
SQL:
select name, score from student where score > 85 order by score desc
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
HINTS:
条件是 score > 85。
按 score DESC 排序。
运行前逐项核对题目要求中的返回字段、筛选条件、分组和排序。
VERSION: 3
ENABLED: true
CREATED: 2026-07-21T00:00:00Z
UPDATED: 2026-09-08T00:00:00Z

===[EXERCISE]===
ID: filter-03
TITLE: 查询 B 班学生
KNOWLEDGE: 文本条件
DIFFICULTY: BEGINNER
DATASET: school-core-v2
DESCRIPTION:
返回 B 班学生姓名，按 id 升序。
SQL:
select name from student where class_name = 'B班' order by id
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
HINTS:
文本值需要引号。
筛选 class_name。
运行前逐项核对题目要求中的返回字段、筛选条件、分组和排序。
VERSION: 3
ENABLED: true
CREATED: 2026-07-21T00:00:00Z
UPDATED: 2026-09-08T00:00:00Z

===[EXERCISE]===
ID: filter-04
TITLE: 筛选分数区间
KNOWLEDGE: 区间条件
DIFFICULTY: INTERMEDIATE
DATASET: school-core-v2
DESCRIPTION:
返回成绩在 75 到 90（含边界）的学生姓名，按成绩再按 id 升序。
SQL:
select name from student where score between 75 and 90 order by score, id
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
HINTS:
可使用 BETWEEN。
BETWEEN 包含两端。
运行前逐项核对题目要求中的返回字段、筛选条件、分组和排序。
VERSION: 3
ENABLED: true
CREATED: 2026-07-21T00:00:00Z
UPDATED: 2026-09-08T00:00:00Z

===[EXERCISE]===
ID: aggregate-01
TITLE: 统计学生人数
KNOWLEDGE: COUNT 聚合
DIFFICULTY: BEGINNER
DATASET: school-core-v2
DESCRIPTION:
返回学生总人数，列名为 student_count。
SQL:
select count(*) as student_count from student
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: false
HINTS:
使用 COUNT(*)。
用 AS 指定列名。
运行前逐项核对题目要求中的返回字段、筛选条件、分组和排序。
VERSION: 3
ENABLED: true
CREATED: 2026-07-21T00:00:00Z
UPDATED: 2026-09-08T00:00:00Z

===[EXERCISE]===
ID: aggregate-02
TITLE: 计算平均成绩
KNOWLEDGE: AVG 聚合
DIFFICULTY: BEGINNER
DATASET: school-core-v2
DESCRIPTION:
返回全体学生平均成绩，列名为 average_score。
SQL:
select avg(score) as average_score from student
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: false
HINTS:
使用 AVG(score)。
结果列命名为 average_score。
运行前逐项核对题目要求中的返回字段、筛选条件、分组和排序。
VERSION: 3
ENABLED: true
CREATED: 2026-07-21T00:00:00Z
UPDATED: 2026-09-08T00:00:00Z

===[EXERCISE]===
ID: aggregate-03
TITLE: 统计各班人数
KNOWLEDGE: GROUP BY
DIFFICULTY: INTERMEDIATE
DATASET: school-core-v2
DESCRIPTION:
返回班级和人数 student_count，按班级升序。
SQL:
select class_name, count(*) as student_count from student group by class_name order by class_name
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
HINTS:
按 class_name 分组。
每组使用 COUNT(*)。
运行前逐项核对题目要求中的返回字段、筛选条件、分组和排序。
VERSION: 3
ENABLED: true
CREATED: 2026-07-21T00:00:00Z
UPDATED: 2026-09-08T00:00:00Z

===[EXERCISE]===
ID: aggregate-04
TITLE: 筛选高平均分班级
KNOWLEDGE: HAVING
DIFFICULTY: INTERMEDIATE
DATASET: school-core-v2
DESCRIPTION:
返回平均成绩不低于 75 的班级和 average_score，按班级升序。
SQL:
select class_name, avg(score) as average_score from student group by class_name having avg(score) >= 75 order by class_name
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
HINTS:
分组后筛选使用 HAVING。
HAVING 条件使用 AVG(score)。
运行前逐项核对题目要求中的返回字段、筛选条件、分组和排序。
VERSION: 3
ENABLED: true
CREATED: 2026-07-21T00:00:00Z
UPDATED: 2026-09-08T00:00:00Z

===[EXERCISE]===
ID: join-01
TITLE: 查询选课明细
KNOWLEDGE: 内连接
DIFFICULTY: INTERMEDIATE
DATASET: school-core-v2
DESCRIPTION:
返回学生姓名、课程名（列名为 course_name）和选课成绩，按学生 id、课程 id 升序。
SQL:
select s.name, c.name as course_name, e.grade from enrollment e join student s on s.id = e.student_id join course c on c.id = e.course_id order by s.id, c.id
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
HINTS:
enrollment 连接 student。
再通过 course_id 连接 course。
运行前逐项核对题目要求中的返回字段、筛选条件、分组和排序。
VERSION: 3
ENABLED: true
CREATED: 2026-07-21T00:00:00Z
UPDATED: 2026-09-08T00:00:00Z

===[EXERCISE]===
ID: join-02
TITLE: 数据库课程名单
KNOWLEDGE: 连接与筛选
DIFFICULTY: INTERMEDIATE
DATASET: school-core-v2
DESCRIPTION:
返回选修数据库原理的学生姓名和成绩，按成绩降序。
SQL:
select s.name, e.grade from enrollment e join student s on s.id = e.student_id join course c on c.id = e.course_id where c.name = '数据库原理' order by e.grade desc
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
HINTS:
连接三张表。
按课程名称筛选。
运行前逐项核对题目要求中的返回字段、筛选条件、分组和排序。
VERSION: 3
ENABLED: true
CREATED: 2026-07-21T00:00:00Z
UPDATED: 2026-09-08T00:00:00Z

===[EXERCISE]===
ID: join-03
TITLE: 各课程选课人数
KNOWLEDGE: 左连接
DIFFICULTY: INTERMEDIATE
DATASET: school-core-v2
DESCRIPTION:
返回课程名和 enrollment_count，包含无人选修课程，按课程 id 升序。
SQL:
select c.name, count(e.student_id) as enrollment_count from course c left join enrollment e on e.course_id = c.id group by c.id, c.name order by c.id
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
HINTS:
从 course 开始 LEFT JOIN。
COUNT 选课表的非空列。
运行前逐项核对题目要求中的返回字段、筛选条件、分组和排序。
VERSION: 3
ENABLED: true
CREATED: 2026-07-21T00:00:00Z
UPDATED: 2026-09-08T00:00:00Z

===[EXERCISE]===
ID: join-04
TITLE: 学生选课平均分
KNOWLEDGE: 连接与聚合
DIFFICULTY: ADVANCED
DATASET: school-core-v2
DESCRIPTION:
返回每位有选课记录学生的姓名和 average_grade，按学生 id 升序。
SQL:
select s.name, avg(e.grade) as average_grade from student s join enrollment e on e.student_id = s.id group by s.id, s.name order by s.id
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
HINTS:
先连接 student 和 enrollment。
再按学生分组计算 AVG。
运行前逐项核对题目要求中的返回字段、筛选条件、分组和排序。
VERSION: 3
ENABLED: true
CREATED: 2026-07-21T00:00:00Z
UPDATED: 2026-09-08T00:00:00Z

===[EXERCISE]===
ID: subquery-01
TITLE: 高于平均分的学生
KNOWLEDGE: 标量子查询
DIFFICULTY: INTERMEDIATE
DATASET: school-core-v2
DESCRIPTION:
返回成绩高于全体平均分的学生姓名和成绩，按成绩降序。
SQL:
select name, score from student where score > (select avg(score) from student) order by score desc
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
HINTS:
子查询先计算 AVG(score)。
外层 WHERE 与平均值比较。
运行前逐项核对题目要求中的返回字段、筛选条件、分组和排序。
VERSION: 3
ENABLED: true
CREATED: 2026-07-21T00:00:00Z
UPDATED: 2026-09-08T00:00:00Z

===[EXERCISE]===
ID: subquery-02
TITLE: 选修数据库的学生
KNOWLEDGE: IN 子查询
DIFFICULTY: INTERMEDIATE
DATASET: school-core-v2
DESCRIPTION:
使用子查询返回选修数据库原理的学生姓名，按 id 升序。
SQL:
select name from student where id in (select e.student_id from enrollment e join course c on c.id = e.course_id where c.name = '数据库原理') order by id
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
KEYWORDS: IN
HINTS:
子查询返回 student_id。
外层使用 IN。
运行前逐项核对题目要求中的返回字段、筛选条件、分组和排序。
VERSION: 3
ENABLED: true
CREATED: 2026-07-21T00:00:00Z
UPDATED: 2026-09-08T00:00:00Z

===[EXERCISE]===
ID: subquery-03
TITLE: 未选数据库课程的学生
KNOWLEDGE: NOT EXISTS
DIFFICULTY: ADVANCED
DATASET: school-core-v2
DESCRIPTION:
使用 NOT EXISTS 相关子查询返回未选修数据库原理的学生姓名，按 id 升序。
SQL:
select s.name from student s where not exists (select 1 from enrollment e join course c on c.id = e.course_id where e.student_id = s.id and c.name = '数据库原理') order by s.id
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
KEYWORDS: NOT EXISTS
HINTS:
使用相关子查询。
NOT EXISTS 表示不存在匹配记录。
运行前逐项核对题目要求中的返回字段、筛选条件、分组和排序。
VERSION: 3
ENABLED: true
CREATED: 2026-07-21T00:00:00Z
UPDATED: 2026-09-08T00:00:00Z

===[EXERCISE]===
ID: subquery-04
TITLE: 课程最高分记录
KNOWLEDGE: 相关子查询
DIFFICULTY: ADVANCED
DATASET: school-core-v2
DESCRIPTION:
返回每门有选课记录课程的课程名（列名为 course_name）、学生姓名和该课程最高成绩，按课程 id 升序。
SQL:
select c.name as course_name, s.name, e.grade from enrollment e join course c on c.id = e.course_id join student s on s.id = e.student_id where e.grade = (select max(e2.grade) from enrollment e2 where e2.course_id = e.course_id) order by c.id
COMPARE_COLUMNS: true
COMPARE_ROWS: true
ROW_ORDER: true
HINTS:
子查询按当前 course_id 求 MAX。
外层保留 grade 等于最大值的记录。
运行前逐项核对题目要求中的返回字段、筛选条件、分组和排序。
VERSION: 3
ENABLED: true
CREATED: 2026-07-21T00:00:00Z
UPDATED: 2026-09-08T00:00:00Z
