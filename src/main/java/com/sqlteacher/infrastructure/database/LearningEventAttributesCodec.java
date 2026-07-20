package com.sqlteacher.infrastructure.database;

import java.util.LinkedHashMap;
import java.util.Map;

final class LearningEventAttributesCodec {
    private LearningEventAttributesCodec() {
    }

    static String serialize(Map<String, String> attributes) {
        if (attributes.isEmpty()) {
            return null;
        }

        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (!encoded.isEmpty()) {
                encoded.append(',');
            }
            appendEscaped(encoded, entry.getKey());
            encoded.append('=');
            appendEscaped(encoded, entry.getValue());
        }
        return encoded.toString();
    }

    static Map<String, String> deserialize(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return Map.of();
        }

        Map<String, String> attributes = new LinkedHashMap<>();
        StringBuilder key = new StringBuilder();
        StringBuilder value = new StringBuilder();
        boolean readingValue = false;
        boolean escaped = false;

        for (int index = 0; index < encoded.length(); index++) {
            char current = encoded.charAt(index);
            StringBuilder target = readingValue ? value : key;

            if (escaped) {
                target.append(current);
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (!readingValue && current == '=') {
                readingValue = true;
            } else if (readingValue && current == ',') {
                attributes.put(key.toString(), value.toString());
                key.setLength(0);
                value.setLength(0);
                readingValue = false;
            } else {
                target.append(current);
            }
        }

        if (escaped) {
            (readingValue ? value : key).append('\\');
        }
        if (readingValue) {
            attributes.put(key.toString(), value.toString());
        }

        return Map.copyOf(attributes);
    }

    private static void appendEscaped(StringBuilder target, String value) {
        if (value == null) {
            return;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\\' || current == '=' || current == ',') {
                target.append('\\');
            }
            target.append(current);
        }
    }
}
