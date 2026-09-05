#!/usr/bin/env python3
"""Run one read-only or maintenance command on the production ECS via SSH.

Usage: python ssh-run.py "command"
Secrets come from .secrets/ecs.env and are never printed.
"""
import os
import sys

import paramiko

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SECRETS = os.path.join(ROOT, ".secrets", "ecs.env")


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


def main():
    command = sys.argv[1]
    env = load_env()
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(env["SQLTEACHER_ECS_HOST"], username=env["SQLTEACHER_ECS_USER"],
                   password=env["SQLTEACHER_ECS_PASSWORD"], timeout=15)
    try:
        _, stdout, stderr = client.exec_command(command, timeout=600)
        out = stdout.read().decode("utf-8", "replace")
        err = stderr.read().decode("utf-8", "replace")
        code = stdout.channel.recv_exit_status()
        if out:
            print(out.rstrip())
        if err:
            print(err.rstrip(), file=sys.stderr)
        sys.exit(code)
    finally:
        client.close()


if __name__ == "__main__":
    main()
