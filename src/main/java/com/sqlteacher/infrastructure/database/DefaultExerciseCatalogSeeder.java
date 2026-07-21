package com.sqlteacher.infrastructure.database;

import com.sqlteacher.domain.exercise.ExerciseDefinition;
import com.sqlteacher.domain.exercise.ExerciseDifficulty;
import com.sqlteacher.domain.exercise.ExerciseEvaluationRule;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

final class DefaultExerciseCatalogSeeder {
    static final String DATASET_ID = "school-core-v1";
    private static final Instant SEED_TIME = Instant.parse("2026-07-21T00:00:00Z");
    private static final String SETUP_SQL = """
        create table student(id integer primary key, name text not null, class_name text not null, score integer not null);
        insert into student values
            (1, 'Alice', '一班', 92), (2, 'Bob', '一班', 76), (3, 'Carol', '二班', 88),
            (4, 'David', '二班', 64), (5, 'Eve', '三班', 95), (6, 'Frank', '三班', 76);
        create table course(id integer primary key, name text not null, teacher text not null);
        insert into course values (1, '数据库原理', '王老师'), (2, 'Java 程序设计', '李老师'), (3, '计算机网络', '赵老师');
        create table enrollment(student_id integer not null, course_id integer not null, grade integer not null,
            primary key(student_id, course_id));
        insert into enrollment values
            (1, 1, 96), (1, 2, 90), (2, 1, 78), (2, 3, 82), (3, 1, 89),
            (3, 2, 91), (4, 2, 68), (5, 1, 97), (5, 3, 94), (6, 3, 75);
        """;

    private final ExercisePackageCodec codec = new ExercisePackageCodec();

    int seed(Connection connection) throws SQLException {
        insertDataset(connection);
        int inserted = 0;
        for (ExerciseDefinition exercise : exercises()) {
            inserted += insertExercise(connection, exercise);
        }
        return inserted;
    }

    private static void insertDataset(Connection connection) throws SQLException {
        String sql = """
            insert or ignore into exercise_datasets(id, name, setup_sql, version, created_at, updated_at)
            values (?, ?, ?, 1, ?, ?)
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, DATASET_ID);
            statement.setString(2, "学校核心数据集");
            statement.setString(3, SETUP_SQL);
            statement.setString(4, SEED_TIME.toString());
            statement.setString(5, SEED_TIME.toString());
            statement.executeUpdate();
        }
    }

    private int insertExercise(Connection connection, ExerciseDefinition exercise) throws SQLException {
        String sql = """
            insert or ignore into exercises(
                id, title, description, knowledge_point, difficulty, dataset_id, reference_sql,
                evaluation_rule_json, hints_json, version, enabled, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, exercise.id());
            statement.setString(2, exercise.title());
            statement.setString(3, exercise.description());
            statement.setString(4, exercise.knowledgePoint());
            statement.setString(5, exercise.difficulty().name());
            statement.setString(6, exercise.datasetId());
            statement.setString(7, exercise.referenceSql());
            statement.setString(8, codec.encodeRule(exercise.evaluationRule()));
            statement.setString(9, codec.encodeHints(exercise.hints()));
            statement.setInt(10, exercise.version());
            statement.setBoolean(11, exercise.enabled());
            statement.setString(12, exercise.createdAt().toString());
            statement.setString(13, exercise.updatedAt().toString());
            return statement.executeUpdate();
        }
    }

    private static List<ExerciseDefinition> exercises() {
        return List.of(
            exercise("query-01", "查询全部学生", "返回 student 表的全部列，按 id 升序。", "基础查询", ExerciseDifficulty.BEGINNER,
                "select id, name, class_name, score from student order by id", true, "先写 SELECT 和 FROM。", "使用 ORDER BY id。"),
            exercise("query-02", "查询学生姓名", "返回所有学生的 name，按 id 升序。", "选择列", ExerciseDifficulty.BEGINNER,
                "select name from student order by id", true, "只选择 name 列。", "排序列不必出现在结果中。"),
            exercise("query-03", "查询班级名单", "返回学生姓名和班级，按姓名升序。", "选择列", ExerciseDifficulty.BEGINNER,
                "select name, class_name from student order by name", true, "选择 name 与 class_name。", "使用 ORDER BY name。"),
            exercise("query-04", "成绩降序列表", "返回姓名和成绩，成绩从高到低，同分按 id 升序。", "排序", ExerciseDifficulty.BEGINNER,
                "select name, score from student order by score desc, id", true, "DESC 表示降序。", "添加第二排序键 id。"),

            exercise("filter-01", "筛选及格学生", "返回成绩不低于 60 的学生姓名，按 id 升序。", "WHERE 筛选", ExerciseDifficulty.BEGINNER,
                "select name from student where score >= 60 order by id", true, "使用 WHERE。", "不低于对应 >=。"),
            exercise("filter-02", "筛选高分学生", "返回成绩大于 85 的姓名和成绩，按成绩降序。", "比较条件", ExerciseDifficulty.BEGINNER,
                "select name, score from student where score > 85 order by score desc", true, "条件是 score > 85。", "按 score DESC 排序。"),
            exercise("filter-03", "查询二班学生", "返回二班学生姓名，按 id 升序。", "文本条件", ExerciseDifficulty.BEGINNER,
                "select name from student where class_name = '二班' order by id", true, "文本值需要引号。", "筛选 class_name。"),
            exercise("filter-04", "筛选分数区间", "返回成绩在 75 到 90（含边界）的学生姓名，按成绩再按 id 升序。", "区间条件", ExerciseDifficulty.INTERMEDIATE,
                "select name from student where score between 75 and 90 order by score, id", true, "可使用 BETWEEN。", "BETWEEN 包含两端。"),

            exercise("aggregate-01", "统计学生人数", "返回学生总人数，列名为 student_count。", "COUNT 聚合", ExerciseDifficulty.BEGINNER,
                "select count(*) as student_count from student", false, "使用 COUNT(*)。", "用 AS 指定列名。"),
            exercise("aggregate-02", "计算平均成绩", "返回全体学生平均成绩，列名为 average_score。", "AVG 聚合", ExerciseDifficulty.BEGINNER,
                "select avg(score) as average_score from student", false, "使用 AVG(score)。", "结果列命名为 average_score。"),
            exercise("aggregate-03", "统计各班人数", "返回班级和人数 student_count，按班级升序。", "GROUP BY", ExerciseDifficulty.INTERMEDIATE,
                "select class_name, count(*) as student_count from student group by class_name order by class_name", true, "按 class_name 分组。", "每组使用 COUNT(*)。"),
            exercise("aggregate-04", "筛选高平均分班级", "返回平均成绩不低于 80 的班级和 average_score，按班级升序。", "HAVING", ExerciseDifficulty.INTERMEDIATE,
                "select class_name, avg(score) as average_score from student group by class_name having avg(score) >= 80 order by class_name", true, "分组后筛选使用 HAVING。", "HAVING 条件使用 AVG(score)。"),

            exercise("join-01", "查询选课明细", "返回学生姓名、课程名和选课成绩，按学生 id、课程 id 升序。", "内连接", ExerciseDifficulty.INTERMEDIATE,
                "select s.name, c.name as course_name, e.grade from enrollment e join student s on s.id = e.student_id join course c on c.id = e.course_id order by s.id, c.id", true, "enrollment 连接 student。", "再通过 course_id 连接 course。"),
            exercise("join-02", "数据库课程名单", "返回选修数据库原理的学生姓名和成绩，按成绩降序。", "连接与筛选", ExerciseDifficulty.INTERMEDIATE,
                "select s.name, e.grade from enrollment e join student s on s.id = e.student_id join course c on c.id = e.course_id where c.name = '数据库原理' order by e.grade desc", true, "连接三张表。", "按课程名称筛选。"),
            exercise("join-03", "各课程选课人数", "返回课程名和 enrollment_count，包含无人选修课程，按课程 id 升序。", "左连接", ExerciseDifficulty.INTERMEDIATE,
                "select c.name, count(e.student_id) as enrollment_count from course c left join enrollment e on e.course_id = c.id group by c.id, c.name order by c.id", true, "从 course 开始 LEFT JOIN。", "COUNT 选课表的非空列。"),
            exercise("join-04", "学生选课平均分", "返回每位有选课记录学生的姓名和 average_grade，按学生 id 升序。", "连接与聚合", ExerciseDifficulty.ADVANCED,
                "select s.name, avg(e.grade) as average_grade from student s join enrollment e on e.student_id = s.id group by s.id, s.name order by s.id", true, "先连接 student 和 enrollment。", "再按学生分组计算 AVG。"),

            exercise("subquery-01", "高于平均分的学生", "返回成绩高于全体平均分的学生姓名和成绩，按成绩降序。", "标量子查询", ExerciseDifficulty.INTERMEDIATE,
                "select name, score from student where score > (select avg(score) from student) order by score desc", true, "子查询先计算 AVG(score)。", "外层 WHERE 与平均值比较。"),
            exercise("subquery-02", "选修数据库的学生", "使用子查询返回选修数据库原理的学生姓名，按 id 升序。", "IN 子查询", ExerciseDifficulty.INTERMEDIATE,
                "select name from student where id in (select e.student_id from enrollment e join course c on c.id = e.course_id where c.name = '数据库原理') order by id", true, "子查询返回 student_id。", "外层使用 IN。"),
            exercise("subquery-03", "未选数据库课程的学生", "返回未选修数据库原理的学生姓名，按 id 升序。", "NOT EXISTS", ExerciseDifficulty.ADVANCED,
                "select s.name from student s where not exists (select 1 from enrollment e join course c on c.id = e.course_id where e.student_id = s.id and c.name = '数据库原理') order by s.id", true, "使用相关子查询。", "NOT EXISTS 表示不存在匹配记录。"),
            exercise("subquery-04", "课程最高分记录", "返回每门课程的课程名、学生姓名和该课程最高成绩，按课程 id 升序。", "相关子查询", ExerciseDifficulty.ADVANCED,
                "select c.name as course_name, s.name, e.grade from enrollment e join course c on c.id = e.course_id join student s on s.id = e.student_id where e.grade = (select max(e2.grade) from enrollment e2 where e2.course_id = e.course_id) order by c.id", true, "子查询按当前 course_id 求 MAX。", "外层保留 grade 等于最大值的记录。")
        );
    }

    private static ExerciseDefinition exercise(
        String id, String title, String description, String knowledgePoint, ExerciseDifficulty difficulty,
        String referenceSql, boolean ordered, String... hints
    ) {
        return new ExerciseDefinition(
            id, title, description, knowledgePoint, difficulty, DATASET_ID, referenceSql,
            ExerciseEvaluationRule.exactResult(ordered), List.of(hints), 1, true, SEED_TIME, SEED_TIME
        );
    }
}
