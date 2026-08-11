package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public final class LocalAppHost {
    private LocalAppHost() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("logback.configurationFile", "logback-sidecar.xml");
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        try (var server = new LocalAppProtocolServer(
            mapper,
            new DefaultLocalAppApi(mapper),
            new InputStreamReader(System.in, StandardCharsets.UTF_8),
            new OutputStreamWriter(System.out, StandardCharsets.UTF_8)
        )) {
            server.run();
        }
    }
}
