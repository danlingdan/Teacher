package com.sqlteacher.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

/**
 * Writes outbound mail into a local outbox directory ({@code <data>/mails}) that
 * operations can relay to a real SMTP channel. Kept dependency-free and
 * deterministic for local support mode and tests; production relays the outbox.
 */
public final class FileMailSender implements MailSender {
    private static final Logger log = LoggerFactory.getLogger(FileMailSender.class);
    private final Path outbox;

    public FileMailSender(Path dataDirectory) {
        outbox = dataDirectory.toAbsolutePath().normalize().resolve("mails");
    }

    @Override public void send(String to, String subject, String body) {
        String safeTo = to == null ? "" : to.replaceAll("[^A-Za-z0-9@._-]", "_");
        try {
            Files.createDirectories(outbox);
            Path file = outbox.resolve(Instant.now().toString().replace(":", "-") + "-" + UUID.randomUUID().toString().substring(0, 8) + ".mail");
            String content = "To: " + safeTo + "\nSubject: " + subject + "\n\n" + body + "\n";
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (IOException error) {
            log.warn("Mail outbox write failed for recipient {}", safeTo);
        }
    }
}
