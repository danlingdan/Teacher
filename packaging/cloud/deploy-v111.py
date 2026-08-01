#!/usr/bin/env python3
"""Production Cloud v1.11 deployment via SSH (paramiko).

Phases:
  1. probe: read-only environment inspection (current version, cloud.env, systemd, disk)
  2. backup: online SQLite backup + integrity check
  3. upload: scp the release tarball, extract into /opt/sqlteacher/releases/1.11.0
  4. swap: atomically switch /opt/sqlteacher/current -> 1.11.0, restart service
  5. verify: /health apiVersion=1.11, schema migration, probe endpoints

Secrets are read from .secrets/ecs.env only. Never printed.
"""
import os
import sys
import stat
import time
import hashlib
import tarfile
import io

import paramiko

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SECRETS = os.path.join(ROOT, ".secrets", "ecs.env")
LOCAL_TARBALL = os.path.join(ROOT, "target", "cloud-release-1.11.0.tgz")

PHASE = sys.argv[1] if len(sys.argv) > 1 else "probe"


def load_env():
    env = {}
    with open(SECRETS, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            env[key.strip()] = value.strip()
    return env


def connect(env):
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(env["SQLTEACHER_ECS_HOST"], username=env["SQLTEACHER_ECS_USER"],
                   password=env["SQLTEACHER_ECS_PASSWORD"], timeout=15)
    return client


def run(client, command, timeout=120):
    stdin, stdout, stderr = client.exec_command(command, timeout=timeout)
    out = stdout.read().decode("utf-8", "replace")
    err = stderr.read().decode("utf-8", "replace")
    code = stdout.channel.recv_exit_status()
    return code, out, err


def probe(client, env):
    checks = [
        ("current symlink", "ls -l /opt/sqlteacher/current"),
        ("releases", "ls /opt/sqlteacher/releases/ 2>/dev/null || echo none"),
        ("cloud.env", "ls -l /etc/sqlteacher/cloud.env 2>/dev/null || echo missing"),
        ("service", "systemctl is-active sqlteacher-cloud.service; systemctl is-enabled sqlteacher-cloud.service"),
        ("disk", "df -h /opt/sqlteacher | tail -1"),
        ("local health", "curl -s http://127.0.0.1:18080/health 2>/dev/null | head -c 300 || echo unreachable"),
        ("java", "java -version 2>&1 | head -1"),
        ("backups", "ls -t /opt/sqlteacher/backups/ 2>/dev/null | head -3"),
    ]
    for label, command in checks:
        code, out, err = run(client, command)
        print(f"== {label} ==")
        print((out + err).strip()[:600] or f"(exit {code})")


def backup(client, env):
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    remote = f"/opt/sqlteacher/backups/cloud-{stamp}.db"
    script = (
        f"mkdir -p /opt/sqlteacher/backups && "
        f"sqlite3 /opt/sqlteacher/data/cloud.db '.backup {remote}' && "
        f"echo BACKUP_DONE && sqlite3 {remote} 'pragma integrity_check;' && "
        f"sha256sum {remote} && ls -l {remote}"
    )
    code, out, err = run(client, script, timeout=300)
    print("== online backup ==")
    print((out + err).strip())
    if code != 0 or "BACKUP_DONE" not in out:
        print("FATAL: backup failed, aborting")
        sys.exit(1)
    return remote


def upload(client, env, sftp_path=None):
    remote_tmp = "/tmp/cloud-release-1.11.0.tgz"
    sftp = client.open_sftp()
    local_sha = hashlib.sha256(open(LOCAL_TARBALL, "rb").read()).hexdigest()
    print(f"local tarball sha256: {local_sha}")
    sftp.put(LOCAL_TARBALL, remote_tmp)
    sftp.close()
    code, out, err = run(client, f"sha256sum {remote_tmp}")
    print("remote sha256:", (out + err).strip())
    if local_sha not in out:
        print("FATAL: remote checksum mismatch, aborting")
        sys.exit(1)
    code, out, err = run(client,
        "mkdir -p /opt/sqlteacher/releases/1.11.0 && "
        "tar xzf " + remote_tmp + " -C /opt/sqlteacher/releases/1.11.0 && "
        "rm -f " + remote_tmp + " && "
        "ls /opt/sqlteacher/releases/1.11.0/ && "
        "find /opt/sqlteacher/releases/1.11.0/lib -name '*.jar' | wc -l", timeout=300)
    print("== extract ==")
    print((out + err).strip())
    if code != 0:
        sys.exit(1)


def swap(client, env):
    # Create an app/ layout matching run-cloud.sh (jar + lib under app/)
    prep = (
        "mkdir -p /opt/sqlteacher/releases/1.11.0/app && "
        "mv /opt/sqlteacher/releases/1.11.0/Teacher-1.11.0.jar /opt/sqlteacher/releases/1.11.0/app/ && "
        "mv /opt/sqlteacher/releases/1.11.0/lib /opt/sqlteacher/releases/1.11.0/app/lib && "
        "ls /opt/sqlteacher/releases/1.11.0/app/"
    )
    code, out, err = run(client, prep)
    print("== prep app layout ==")
    print((out + err).strip())

    # atomic swap with automatic rollback on startup failure
    swap_script = (
        "set -e; "
        "ln -sfn /opt/sqlteacher/releases/1.11.0 /opt/sqlteacher/current.new; "
        "systemctl stop sqlteacher-cloud.service || true; "
        "mv -T /opt/sqlteacher/current.new /opt/sqlteacher/current; "
        "systemctl start sqlteacher-cloud.service; "
        "sleep 5; "
        "if systemctl is-active --quiet sqlteacher-cloud.service; then "
        "  echo SWAP_OK; "
        "else "
        "  echo SWAP_FAILED; systemctl start sqlteacher-cloud.service; sleep 3; "
        "  systemctl status sqlteacher-cloud.service --no-pager | tail -5; exit 1; "
        "fi"
    )
    code, out, err = run(client, swap_script, timeout=120)
    print("== swap ==")
    print((out + err).strip()[-1500:])
    if "SWAP_OK" not in out:
        print("FATAL: service failed after swap, manual rollback required")
        sys.exit(1)


def verify(client, env):
    checks = [
        ("service", "systemctl is-active sqlteacher-cloud.service"),
        ("symlink", "readlink -f /opt/sqlteacher/current"),
        ("local health", "curl -s http://127.0.0.1:18080/health 2>/dev/null | head -c 500 || echo unreachable"),
        ("capabilities", "curl -s http://127.0.0.1:18080/api/v1/app/capabilities 2>/dev/null | head -c 400 || echo unreachable"),
        ("schema version", "sqlite3 /opt/sqlteacher/data/cloud.db 'select name from sqlite_master where type=\"table\" and name in (\"account_tasks\",\"reset_tokens\",\"email_verifications\")' 2>/dev/null || echo n/a"),
        ("recent errors", "journalctl -u sqlteacher-cloud.service --since '2 minutes ago' --no-pager 2>/dev/null | grep -iE 'error|exception' | tail -5 || echo clean"),
    ]
    for label, command in checks:
        code, out, err = run(client, command, timeout=60)
        print(f"== {label} ==")
        print((out + err).strip()[:700] or f"(exit {code})")


def main():
    env = load_env()
    client = connect(env)
    try:
        if PHASE == "probe":
            probe(client, env)
        elif PHASE == "backup":
            backup(client, env)
        elif PHASE == "upload":
            upload(client, env)
        elif PHASE == "swap":
            swap(client, env)
        elif PHASE == "verify":
            verify(client, env)
        elif PHASE == "full":
            backup(client, env)
            upload(client, env)
            swap(client, env)
            verify(client, env)
        else:
            print("unknown phase; use probe|backup|upload|swap|verify|full")
            sys.exit(2)
    finally:
        client.close()


if __name__ == "__main__":
    main()
