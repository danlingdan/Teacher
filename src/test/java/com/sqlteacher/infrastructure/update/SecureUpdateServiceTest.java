package com.sqlteacher.infrastructure.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class SecureUpdateServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void acceptsOnlyPayloadsSignedByATrustedEd25519Key() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Properties keys = new Properties();
        keys.setProperty("test-key", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        byte[] payload = payload();
        byte[] envelope = envelope("test-key", payload, sign(pair.getPrivate(), payload));
        assertEquals("1.10.0", SecureUpdateService.verifyAndParse(envelope, keys).version().toString());

        payload[payload.length - 2] ^= 1;
        assertThrows(IllegalArgumentException.class,
            () -> SecureUpdateService.verifyAndParse(envelope("test-key", payload, sign(pair.getPrivate(), payload())), keys));
        assertThrows(IllegalArgumentException.class,
            () -> SecureUpdateService.verifyAndParse(envelope("unknown", payload(), sign(pair.getPrivate(), payload())), keys));
    }

    private static byte[] payload() throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 1); value.put("product", "SQLTeacher"); value.put("channel", "stable"); value.put("version", "1.10.0");
        value.put("platform", "windows"); value.put("architecture", "x64"); value.put("publishedAt", "2026-08-02T00:00:00Z");
        value.put("releaseNotesUrl", "https://github.com/danlingdan/Teacher/releases/tag/v1.10.0");
        value.put("installerUrl", "https://github.com/danlingdan/Teacher/releases/download/v1.10.0/SQLTeacher-1.10.0.exe");
        value.put("installerSize", 10); value.put("installerSha256", "a".repeat(64));
        value.put("portableUrl", "https://github.com/danlingdan/Teacher/releases/download/v1.10.0/SQLTeacher-1.10.0-windows-x64.zip");
        value.put("portableSize", 10); value.put("portableSha256", "b".repeat(64)); value.put("minimumSupportedVersion", "1.9.0");
        return JSON.writeValueAsBytes(value);
    }
    private static byte[] sign(java.security.PrivateKey key, byte[] payload) throws Exception {
        Signature signature = Signature.getInstance("Ed25519"); signature.initSign(key); signature.update(payload); return signature.sign();
    }
    private static byte[] envelope(String keyId, byte[] payload, byte[] signature) throws Exception {
        return JSON.writeValueAsBytes(Map.of("keyId", keyId, "payload", Base64.getEncoder().encodeToString(payload),
            "signature", Base64.getEncoder().encodeToString(signature)));
    }
}
