package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end bridge check: execute SQL, then export the cached result to CSV. */
class SqlResultExportFlowTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @TempDir
    Path tempDirectory;

    @Test
    @ResourceLock("sqlteacher.data.dir")
    void shouldExportExecutedResultToCsvThroughTheBridge() throws Exception {
        System.setProperty("sqlteacher.data.dir", tempDirectory.resolve("export-data").toString());
        try (var api = new DefaultLocalAppApi(mapper)) {
            JsonNode executed = api.invoke("sql.execute", mapper.createObjectNode()
                .put("connectionId", "demo")
                .put("sql", "select sno, sname from Student order by sno")
                .put("maxRows", 500)
                .put("pageSize", 50), () -> false, ignored -> { });
            String resultId = executed.path("resultId").asText();
            assertTrue(!resultId.isBlank());

            Path target = tempDirectory.resolve("export").resolve("students.csv");
            Files.createDirectories(target.getParent());
            JsonNode exported = api.invoke("sql.result.export", mapper.createObjectNode()
                .put("resultId", resultId)
                .put("path", target.toString()), () -> false, ignored -> { });

            assertEquals(7, exported.path("rows").asInt());
            assertEquals(2, exported.path("columns").asInt());
            String csv = Files.readString(target, StandardCharsets.UTF_8);
            assertTrue(csv.startsWith("\uFEFFSno,Sname\r\n"));
            assertTrue(csv.contains("20180001,李勇\r\n20180002,刘晨"));
        }
    }
}
