package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskLevel;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlRiskRegressionTest {
    private static final String CASES_RESOURCE = "/regression/sql-risk-cases.tsv";

    private final DefaultSqlRiskAnalysisService service = new DefaultSqlRiskAnalysisService();

    @TestFactory
    Stream<DynamicTest> shouldMatchFirstRoundRiskCases() throws IOException {
        return loadCases().stream()
            .map(testCase -> DynamicTest.dynamicTest(testCase.id(), () -> {
                SqlRiskAnalysis result = service.analyze(testCase.sql());

                assertAll(
                    () -> assertEquals(testCase.expectedLevel(), result.level()),
                    () -> assertEquals(testCase.executable(), result.executable()),
                    () -> assertEquals(testCase.confirmationRequired(), result.confirmationRequired()),
                    () -> assertEquals(testCase.multiStatement(), result.multiStatement())
                );
            }));
    }

    private static List<RiskCase> loadCases() throws IOException {
        InputStream input = Objects.requireNonNull(
            SqlRiskRegressionTest.class.getResourceAsStream(CASES_RESOURCE),
            "Missing regression resource: " + CASES_RESOURCE
        );

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(input, StandardCharsets.UTF_8)
        )) {
            return reader.lines()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .map(SqlRiskRegressionTest::parseCase)
                .toList();
        }
    }

    private static RiskCase parseCase(String line) {
        String[] fields = line.split("\t", -1);
        if (fields.length != 6) {
            throw new IllegalArgumentException("Expected 6 tab-separated fields: " + line);
        }

        String sql = fields[1].equals("<blank>") ? " " : fields[1];
        return new RiskCase(
            fields[0],
            sql,
            SqlRiskLevel.valueOf(fields[2]),
            Boolean.parseBoolean(fields[3]),
            Boolean.parseBoolean(fields[4]),
            Boolean.parseBoolean(fields[5])
        );
    }

    private record RiskCase(
        String id,
        String sql,
        SqlRiskLevel expectedLevel,
        boolean executable,
        boolean confirmationRequired,
        boolean multiStatement
    ) {
    }
}
