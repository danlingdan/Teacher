package com.sqlteacher.server;

import java.time.Instant;

/** Admin listing entry for a role grant code; never contains the plaintext code (v3.8.0 ACC-S4). */
record TeacherRoleCodeView(String codeHash, String role, Instant createdAt, Instant expiresAt,
                           String usedBy, Instant usedAt, Instant revokedAt) { }
