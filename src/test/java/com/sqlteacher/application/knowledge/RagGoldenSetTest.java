package com.sqlteacher.application.knowledge;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RagGoldenSetTest {
    @Test
    void shipsTheInitialFortyCaseEvaluationBaseline() throws Exception {
        var input = getClass().getResourceAsStream("/knowledge/v185-rag-golden-set.jsonl");
        assertNotNull(input);
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            assertEquals(40, reader.lines().filter(line -> !line.isBlank()).count());
        }
    }
}
