#!/usr/bin/env python3
"""Package the source tree as a build-ready archive (handoff/LoopJet-1.0.0-source.zip).

What goes in: the Gradle project, the wrapper, build-apk.sh, the docs and the design sources.
What never goes in: .git, build output, dist/ and ci/ artefacts, and every credential
(*.p12, *.jks, *.keystore, keys/, local.properties, credentials files).

The archive is deterministic for a given tree: entries are sorted, timestamps are fixed, and the
executable bit is preserved for gradlew and build-apk.sh, so unzipping produces a tree that behaves
exactly like a git checkout.

Usage:
  python3 tools/make_source_zip.py [--out handoff/LoopJet-1.0.0-source.zip]
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import os
import stat
import zipfile
from pathlib import Path

VERSION = "1.0.0"
PREFIX = f"LoopJet-{VERSION}"
EXCLUDE_DIRS = {".git", "dist", "ci", "build", ".gradle", "handoff", "keys", ".idea", "out", "node_modules"}
EXCLUDE_FILES = {"local.properties", "keystore.properties"}
EXCLUDE_SUFFIX = (".jks", ".keystore", ".p12", ".b64")
STAMP = (2026, 10, 28, 0, 0, 0)


def collect(root: Path) -> list[str]:
    files: list[str] = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in EXCLUDE_DIRS)
        for name in sorted(filenames):
            rel = Path(dirpath, name).relative_to(root).as_posix()
            if name in EXCLUDE_FILES or name.endswith(EXCLUDE_SUFFIX):
                continue
            if "credentials" in name:
                continue
            files.append(rel)
    return sorted(files)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=f"handoff/LoopJet-{VERSION}-source.zip")
    args = parser.parse_args()

    root = Path(__file__).resolve().parent.parent
    out = root / args.out
    out.parent.mkdir(parents=True, exist_ok=True)
    files = collect(root)
    for required in ("gradlew", "build-apk.sh", "HANDOFF.md", "app/build.gradle.kts",
                     "gradle/wrapper/gradle-wrapper.jar", "gradle/libs.versions.toml"):
        if required not in files:
            print(f"missing from the tree: {required}")
            return 1

    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for rel in files:
            path = root / rel
            info = zipfile.ZipInfo(f"{PREFIX}/{rel}", date_time=STAMP)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            executable = bool(path.stat().st_mode & stat.S_IXUSR)
            info.external_attr = (0o755 if executable else 0o644) << 16
            archive.writestr(info, path.read_bytes())

    digest = hashlib.sha256(out.read_bytes()).hexdigest()
    (out.parent / "SHA256SUMS.txt").write_text(f"{digest}  {out.name}\n", encoding="utf-8")
    print(f"{out} — {len(files)} files, {out.stat().st_size} bytes")
    print(f"sha256 {digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
