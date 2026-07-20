package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.application.ai.AiCompletionRequest;
import com.sqlteacher.application.ai.AiCompletionResult;
import com.sqlteacher.application.ai.AiModelProvider;
import com.sqlteacher.application.config.AiConfiguration;
import java.net.URI;
import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.metadata.DatabaseTable;
import com.sqlteacher.application.nl2sql.Nl2SqlPlan;
import com.sqlteacher.application.nl2sql.Nl2SqlRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class Nl2SqlRegressionTest {
    private Nl2SqlServiceImpl nl2SqlService;

    @BeforeEach
    void setUp() {
        AiModelProvider mockAiProvider = new MockProvider(AiCompletionResult.success(
            "{\"sqlDraft\": \"SELECT * FROM student LIMIT 500\", \"intent\": \"QUERY\", \"explanation\": \"test\"}",
            "qwen3.5:0.8b"
        ));

        AiConfiguration config = new AiConfiguration(
            URI.create("http://localhost:11434"),
            Duration.ofSeconds(30),
            Duration.ofSeconds(5),
            "qwen3.5:0.8b"
        );

        DatabaseMetadataService mockMetadataService = connectionId -> List.of();

        LearningEventService mockEventService = new NoOpLearningEventService();

        nl2SqlService = new Nl2SqlServiceImpl(mockAiProvider, config, mockMetadataService, mockEventService);
    }

    @Test
    void regressionSample1_selectAllStudents() {
        setupMockResponse("SELECT * FROM student LIMIT 500");
        Nl2SqlPlan plan = nl2SqlService.generate(new Nl2SqlRequest("查询所有学生", "demo"));

        assertTrue(plan.sqlDraft().toUpperCase().contains("SELECT"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("student"));
        assertTrue(plan.sqlDraft().contains("LIMIT"));
    }

    @Test
    void regressionSample2_selectNameAndScore() {
        setupMockResponse("SELECT name, score FROM student LIMIT 500");
        Nl2SqlPlan plan = nl2SqlService.generate(new Nl2SqlRequest("查询学生姓名和分数", "demo"));

        assertTrue(plan.sqlDraft().toUpperCase().contains("SELECT"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("name"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("score"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("student"));
    }

    @Test
    void regressionSample3_whereCondition() {
        setupMockResponse("SELECT * FROM student WHERE score > 60 LIMIT 500");
        Nl2SqlPlan plan = nl2SqlService.generate(new Nl2SqlRequest("查询成绩大于60的学生", "demo"));

        assertTrue(plan.sqlDraft().toUpperCase().contains("WHERE"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("score"));
        assertTrue(plan.sqlDraft().contains(">"));
    }

    @Test
    void regressionSample4_countStudents() {
        setupMockResponse("SELECT COUNT(*) FROM student LIMIT 500");
        Nl2SqlPlan plan = nl2SqlService.generate(new Nl2SqlRequest("查询学生总数", "demo"));

        assertTrue(plan.sqlDraft().toUpperCase().contains("COUNT"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("student"));
    }

    @Test
    void regressionSample5_orderBy() {
        setupMockResponse("SELECT * FROM student ORDER BY score DESC LIMIT 500");
        Nl2SqlPlan plan = nl2SqlService.generate(new Nl2SqlRequest("按分数降序排列学生", "demo"));

        assertTrue(plan.sqlDraft().toUpperCase().contains("ORDER BY"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("score"));
        assertTrue(plan.sqlDraft().toUpperCase().contains("DESC"));
    }

    @Test
    void regressionSample6_joinTables() {
        setupMockResponse("SELECT s.name, c.name FROM student s JOIN class c ON s.class_id = c.id LIMIT 500");
        Nl2SqlPlan plan = nl2SqlService.generate(new Nl2SqlRequest("查询学生所属班级", "demo"));

        assertTrue(plan.sqlDraft().toUpperCase().contains("JOIN"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("student"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("class"));
    }

    @Test
    void regressionSample7_groupBy() {
        setupMockResponse("SELECT class_id, COUNT(*) FROM student GROUP BY class_id LIMIT 500");
        Nl2SqlPlan plan = nl2SqlService.generate(new Nl2SqlRequest("统计每个班级的学生人数", "demo"));

        assertTrue(plan.sqlDraft().toUpperCase().contains("GROUP BY"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("class_id"));
        assertTrue(plan.sqlDraft().toUpperCase().contains("COUNT"));
    }

    @Test
    void regressionSample8_avgScore() {
        setupMockResponse("SELECT AVG(score) FROM student LIMIT 500");
        Nl2SqlPlan plan = nl2SqlService.generate(new Nl2SqlRequest("查询平均分", "demo"));

        assertTrue(plan.sqlDraft().toUpperCase().contains("AVG"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("score"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("student"));
    }

    @Test
    void regressionSample9_distinct() {
        setupMockResponse("SELECT DISTINCT name FROM student LIMIT 500");
        Nl2SqlPlan plan = nl2SqlService.generate(new Nl2SqlRequest("查询学生姓名不重复", "demo"));

        assertTrue(plan.sqlDraft().toUpperCase().contains("DISTINCT"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("name"));
    }

    @Test
    void regressionSample10_likeQuery() {
        setupMockResponse("SELECT * FROM student WHERE name LIKE '李%' LIMIT 500");
        Nl2SqlPlan plan = nl2SqlService.generate(new Nl2SqlRequest("查询姓李的学生", "demo"));

        assertTrue(plan.sqlDraft().toUpperCase().contains("LIKE"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("name"));
    }

    @Test
    void regressionSample11_maxScore() {
        setupMockResponse("SELECT MAX(score) FROM student LIMIT 500");
        Nl2SqlPlan plan = nl2SqlService.generate(new Nl2SqlRequest("查询最高分", "demo"));

        assertTrue(plan.sqlDraft().toUpperCase().contains("MAX"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("score"));
    }

    @Test
    void regressionSample12_minScore() {
        setupMockResponse("SELECT MIN(score) FROM student LIMIT 500");
        Nl2SqlPlan plan = nl2SqlService.generate(new Nl2SqlRequest("查询最低分", "demo"));

        assertTrue(plan.sqlDraft().toUpperCase().contains("MIN"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("score"));
    }

    @Test
    void regressionSample13_limit10() {
        setupMockResponse("SELECT * FROM student LIMIT 10");
        Nl2SqlPlan plan = nl2SqlService.generate(new Nl2SqlRequest("查询前10名学生", "demo"));

        assertTrue(plan.sqlDraft().contains("LIMIT"));
        assertTrue(plan.sqlDraft().contains("10"));
    }

    @Test
    void regressionSample14_queryClass() {
        setupMockResponse("SELECT * FROM class LIMIT 500");
        Nl2SqlPlan plan = nl2SqlService.generate(new Nl2SqlRequest("查询班级信息", "demo"));

        assertTrue(plan.sqlDraft().toLowerCase().contains("class"));
    }

    @Test
    void regressionSample15_whereEqual() {
        setupMockResponse("SELECT * FROM student WHERE id = 1 LIMIT 500");
        Nl2SqlPlan plan = nl2SqlService.generate(new Nl2SqlRequest("查询学号为1的学生", "demo"));

        assertTrue(plan.sqlDraft().toUpperCase().contains("WHERE"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("id"));
        assertTrue(plan.sqlDraft().contains("="));
    }

    @Test
    void regressionSample16_betweenCondition() {
        setupMockResponse("SELECT * FROM student WHERE score BETWEEN 60 AND 80 LIMIT 500");
        Nl2SqlPlan plan = nl2SqlService.generate(new Nl2SqlRequest("查询成绩在60到80之间的学生", "demo"));

        assertTrue(plan.sqlDraft().toUpperCase().contains("BETWEEN"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("score"));
    }

    @Test
    void regressionSample17_selectColumns() {
        setupMockResponse("SELECT name, score, class_id FROM student LIMIT 500");
        Nl2SqlPlan plan = nl2SqlService.generate(new Nl2SqlRequest("查询学生姓名分数和班级ID", "demo"));

        assertTrue(plan.sqlDraft().toLowerCase().contains("name"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("score"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("class_id"));
    }

    @Test
    void regressionSample18_teacherQuery() {
        setupMockResponse("SELECT * FROM class WHERE teacher = '王老师' LIMIT 500");
        Nl2SqlPlan plan = nl2SqlService.generate(new Nl2SqlRequest("查询王老师的班级", "demo"));

        assertTrue(plan.sqlDraft().toLowerCase().contains("class"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("teacher"));
    }

    @Test
    void regressionSample19_failedStudents() {
        setupMockResponse("SELECT * FROM student WHERE score < 60 LIMIT 500");
        Nl2SqlPlan plan = nl2SqlService.generate(new Nl2SqlRequest("查询不及格的学生", "demo"));

        assertTrue(plan.sqlDraft().toLowerCase().contains("student"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("score"));
        assertTrue(plan.sqlDraft().contains("<"));
    }

    @Test
    void regressionSample20_joinWithCondition() {
        setupMockResponse("SELECT s.name, c.name FROM student s JOIN class c ON s.class_id = c.id WHERE c.name = '一班' LIMIT 500");
        Nl2SqlPlan plan = nl2SqlService.generate(new Nl2SqlRequest("查询一班的学生姓名和班级名称", "demo"));

        assertTrue(plan.sqlDraft().toUpperCase().contains("JOIN"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("student"));
        assertTrue(plan.sqlDraft().toLowerCase().contains("class"));
        assertTrue(plan.sqlDraft().toUpperCase().contains("WHERE"));
    }

    private void setupMockResponse(String sqlDraft) {
        String jsonResponse = String.format(
            "{\"sqlDraft\": \"%s\", \"intent\": \"QUERY\", \"explanation\": \"test explanation\"}",
            sqlDraft
        );
        AiModelProvider mockProvider = new MockProvider(AiCompletionResult.success(jsonResponse, "qwen3.5:0.8b"));

        AiConfiguration config = new AiConfiguration(
            URI.create("http://localhost:11434"),
            Duration.ofSeconds(30),
            Duration.ofSeconds(5),
            "qwen3.5:0.8b"
        );

        DatabaseMetadataService mockMetadataService = connectionId -> List.of();
        LearningEventService mockEventService = new NoOpLearningEventService();

        nl2SqlService = new Nl2SqlServiceImpl(mockProvider, config, mockMetadataService, mockEventService);
    }

    private static class MockProvider implements AiModelProvider {
        private final AiCompletionResult result;

        MockProvider(AiCompletionResult result) {
            this.result = result;
        }

        @Override
        public AiCompletionResult complete(AiCompletionRequest request) {
            return result;
        }

    }

    private static class NoOpLearningEventService implements LearningEventService {
        @Override
        public void recordSqlExecution(String connectionId, boolean successful, String statementType, Duration duration, int resultCount, String errorCode) {
        }

        @Override
        public void recordSqlRiskBlocked(String connectionId, String statementType, com.sqlteacher.application.risk.SqlRiskLevel riskLevel, boolean multiStatement) {
        }

        @Override
        public void recordAiGeneration(String connectionId, boolean successful, String model, String promptVersion, String errorCode) {
        }
    }
}
