package com.sqlteacher.infrastructure.database;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.domain.SqlTeacherException;

final class ExerciseAttemptCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new SqlTeacherException("EXERCISE_ATTEMPT_ENCODING_FAILED", "Failed to encode attempt data", error);
        }
    }
}
