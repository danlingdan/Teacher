package com.sqlteacher.server;

import java.time.Instant;

/** Plaintext view of a freshly minted role grant code; the code is returned exactly once (v3.8.0 ACC-S4). */
record TeacherRoleCodeIssued(String code, String codeHash, String role, Instant expiresAt) { }
