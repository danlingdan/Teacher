#!/usr/bin/env python3
"""Merge the zh bundle with translation parts into the full English bundle."""
import sys
from pathlib import Path

ROOT = Path(".")
ZH = ROOT / "src/main/resources/i18n/messages_zh_CN.properties"
EN = ROOT / "src/main/resources/i18n/messages_en.properties"
PARTS = ["i18n-en-part1", "i18n-en-part2", "i18n-en-part3", "i18n-en-part4"]

translations = {}
for part in PARTS:
    mod = __import__(part)
    translations.update(mod.TRANSLATIONS)

# load zh bundle
zh = {}
for line in ZH.read_text(encoding="utf-8").splitlines():
    line = line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    k, _, v = line.partition("=")
    zh[k.strip()] = v.strip()

# load existing en bundle (preserve existing translations)
existing = {}
if EN.exists():
    for line in EN.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, _, v = line.partition("=")
        existing[k.strip()] = v.strip()

missing = []
en_out = {}
for k, zhv in sorted(zh.items()):
    if k in existing:
        en_out[k] = existing[k]
    elif zhv in translations:
        en_out[k] = translations[zhv]
    else:
        missing.append((k, zhv))
        en_out[k] = zhv  # fallback: zh value (will fail key-set parity tests later if any)

with EN.open("w", encoding="utf-8", newline="\n") as fh:
    fh.write("# SQLTeacher English UI strings (v1.11 full)\n")
    for k in sorted(en_out):
        fh.write(f"{k}={en_out[k]}\n")

print(f"en bundle keys: {len(en_out)}")
print(f"missing translations: {len(missing)}")
for k, v in missing[:40]:
    print(f"  MISSING {k} = {v}")
