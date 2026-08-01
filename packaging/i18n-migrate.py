#!/usr/bin/env python3
"""v1.11 full-English migration helper.

Strategy (mechanical, reviewable):
  1. FXML: every `text="<Chinese>"`-style attribute becomes `text="%<key>"`.
     Key = "<filebase>.<seq>" by occurrence order.
  2. Java (desktop controllers): every plain string literal that contains CJK
     and no '%' placeholder becomes `AppI18n.get("<key>")`; the file gets an
     AppI18n import if not present.
  3. Writes the new zh bundle (merging the existing keys) and a JSON report of
     every migrated string for the manual English pass.

Usage: python3 packaging/i18n-migrate.py [--apply]
Without --apply it only prints what would change.
"""
import glob
import json
import re
import sys
from pathlib import Path

ROOT = Path(".")
FXML_GLOB = sorted((ROOT / "src/main/resources/fxml").glob("*.fxml"))
JAVA_GLOB = sorted((ROOT / "src/main/java/com/sqlteacher/desktop/controller").glob("*.java"))
ZH_BUNDLE = ROOT / "src/main/resources/i18n/messages_zh_CN.properties"
EN_BUNDLE = ROOT / "src/main/resources/i18n/messages_en.properties"

CJK = re.compile('[\u4e00-\u9fff\u3000-\u303f\uff00-\uffef]')
FXML_ATTR = re.compile(r'([A-Za-z][A-Za-z0-9_-]*)\s*=\s*"([^"]*)"')
TEXT_ATTRS = {"text", "promptText", "title", "content", "headerText", "tooltip", "accessibleText"}
JAVA_STR = re.compile(r'"((?:[^"\\]|\\.)*)"')
SKIP_JAVA = re.compile(r'^\s*(import|package)\b')
APPLY = "--apply" in sys.argv

report = {"fxml": [], "java": []}
new_keys = {}


def key_for(base, seq):
    return f"{base}.{seq}"


def migrate_fxml(path, base):
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    changed = False
    seq = 0
    for li, line in enumerate(lines):
        def repl(m):
            nonlocal seq
            attr, value = m.group(1), m.group(2)
            if attr in TEXT_ATTRS and CJK.search(value):
                seq += 1
                k = key_for(base, seq)
                new_keys[k] = value
                report["fxml"].append({"file": path.name, "key": k, "value": value})
                return f'{attr}="%{k}"'
            return m.group(0)
        new_line = FXML_ATTR.sub(repl, line)
        if new_line != line:
            lines[li] = new_line
            changed = True
    if changed:
        if APPLY:
            path.write_text("".join(lines), encoding="utf-8")
        print(f"[fxml] {path.name}: {seq} keys")


def migrate_java(path, base):
    text = path.read_text(encoding="utf-8")
    seq = 0
    changed = False
    out_lines = []

    def repl(m):
        nonlocal seq
        literal = m.group(1)
        if CJK.search(literal) and "%" not in literal and "{" not in literal and "}" not in literal:
            seq += 1
            k = key_for(base, seq)
            new_keys[k] = literal
            report["java"].append({"file": path.name, "key": k, "value": literal})
            return f'AppI18n.get("{k}")'
        return m.group(0)

    for line in text.splitlines(keepends=True):
        if SKIP_JAVA.match(line):
            out_lines.append(line)
            continue
        new_line = JAVA_STR.sub(repl, line)
        if new_line != line:
            changed = True
        out_lines.append(new_line)
    if changed:
        joined = "".join(out_lines)
        if "import com.sqlteacher.desktop.AppI18n;" not in joined:
            # insert after the package line
            lines = joined.splitlines(keepends=True)
            inserted = False
            for li, ln in enumerate(lines):
                if ln.startswith("package "):
                    lines.insert(li + 1, "import com.sqlteacher.desktop.AppI18n;\n")
                    inserted = True
                    break
            joined = "".join(lines) if inserted else joined
        if APPLY:
            path.write_text(joined, encoding="utf-8")
        print(f"[java] {path.name}: {seq} keys, changed")


def merge_bundle(path, extra):
    props = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, _, v = line.partition("=")
            props[k.strip()] = v.strip()
    props.update(extra)
    return props


def main():
    for path in FXML_GLOB:
        migrate_fxml(path, path.stem)
    for path in JAVA_GLOB:
        migrate_java(path, path.stem)
    zh = merge_bundle(ZH_BUNDLE, new_keys)
    if APPLY:
        with ZH_BUNDLE.open("w", encoding="utf-8", newline="\n") as fh:
            fh.write("# SQLTeacher 简体中文界面文案（v1.11 全量迁移）\n")
            for k in sorted(zh):
                fh.write(f"{k}={zh[k]}\n")
        report_path = ROOT / "target" / "i18n-migration-report.json"
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"\nWROTE {len(zh)} zh keys -> {ZH_BUNDLE.name}")
        print(f"report -> {report_path}")
    else:
        print(f"\nDRY RUN: would migrate fxml={len(report['fxml'])} java={len(report['java'])} keys={len(new_keys)}")
        print("run with --apply to write changes")


if __name__ == "__main__":
    main()
