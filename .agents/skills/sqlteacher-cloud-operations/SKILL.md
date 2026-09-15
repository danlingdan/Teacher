---
name: sqlteacher-cloud-operations
description: Diagnose, deploy, back up, restore, or verify SQLTeacher production cloud services, Nginx HTTPS, systemd units, SQLite Cloud data, Qdrant, embeddings, certificates, or operational timers. Use for production operations and cloud incidents; do not use for local-only server code changes with no external operation.
---

# Operate SQLTeacher Cloud

## Protect the production boundary

- Begin with read-only inspection. Do not change a service, symlink, certificate, database, firewall, or timer until the current state and exact target are known.
- Production traffic uses `https://api.sqlteacher.tech` through Nginx. The Java API must remain bound to loopback `127.0.0.1:18080` unless the user explicitly approves an architecture change.
- Do not infer hostname health from ping, TCP success, or direct-IP responses. Verify DNS/TLS/HTTP at the public hostname and health locally through loopback.
- Leave unrelated services and ports untouched. Never print or persist secrets, tokens, SMTP credentials, private student data, or full production configuration.

## Diagnose in layers

Check, in order:

1. public DNS, TLS certificate, HTTPS redirect, and `/health`;
2. Nginx configuration and upstream reachability;
3. systemd service state, bind address, and bounded journal output;
4. application health, schema compatibility, dependency capabilities, and backlog;
5. backup freshness and `pragma integrity_check`;
6. Qdrant, embedding service, certificate timer, backup timers, and operations-check timer when relevant.

Use `packaging/cloud/check-cloud-operations.sh` and the current guide under `docs/guide/` as evidence sources. Treat dated files in `docs/operations/` as historical execution records, not live configuration.

## Change with rollback

1. Take and verify an online backup before database or release changes.
2. State the intended change, rollback target, and health checks.
3. Make the minimum change. For a release, upload to a versioned directory, verify it, switch the `current` symlink atomically, and restart only the scoped units.
4. Verify loopback health, public HTTPS health, expected API/capability version, schema, timers, and logs.
5. Roll back immediately if required health checks fail; verify service recovery after rollback.
6. Record commands, pass/fail results, environment notes, backup identifier, release target, and known limitations in `docs/operations/` without recording secrets.

Restores are destructive. Require an explicitly selected backup, preserve a pre-restore copy, verify integrity before and after, and do not proceed from a guessed path.

## Non-interactive server access (v3.4.4)

- ECS credentials live in gitignored `.secrets/ecs.env` (`SQLTEACHER_ECS_HOST` / `SQLTEACHER_ECS_USER` /
  `SQLTEACHER_ECS_PASSWORD`, plus cloud admin console credentials). The file must stay out of Git.
- For non-interactive deployment use Python `paramiko` (installed locally): parse `.secrets/ecs.env`
  at runtime, connect to the ECS host, upload artifacts via SFTP, and run commands over SSH.
  Never print, inline, or commit the password; print only command output that contains no secrets.
- Content-file updates (e.g. knowledge bundle zip + manifest in `/opt/sqlteacher/shared/`) are read
  per request: upload to temp names, verify SHA-256, `install` atomically with `root:sqlteacher 0640`,
  keep the previous version for rollback, and restart `sqlteacher-cloud` only when an env value
  (`/etc/sqlteacher/cloud.env`) changes.
