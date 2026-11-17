#!/usr/bin/env python3
"""notices: write a licence notice for every Go module hideout-link's Linux and Windows builds include.

Each module gets its own file under app/shared/composeResources/files/licenses and a line in index.tsv, below a marker
line this tool owns. Licence texts come from the pinned module cache, so run it after any change to go.mod. It stops when
a module has no licence file or one it cannot name. The native-package plugin fails a build whose binary holds a module
with no line here.
"""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "tools/link"
LICENCES = ROOT / "app/shared/composeResources/files/licenses"
INDEX = LICENCES / "index.tsv"
MARKER = "# hideout-link's Go modules, written by tools/link/notices.py"
PREFIX = "go_"
NOTICE_FILE = re.compile(r"(LICEN[SC]E|COPYING|NOTICE|PATENTS)([._-].*)?$", re.IGNORECASE)
SOURCE_FILE = re.compile(r"\.(go|s|c|h|sh|py)$", re.IGNORECASE)


def go(*args, goos=None):
    env = dict(os.environ, CGO_ENABLED="0")
    if goos:
        env["GOOS"] = goos
    return subprocess.run(["go", "-C", str(MODULE), *args], env=env, check=True, capture_output=True, text=True).stdout


def modules():
    found = {}
    for goos in ("linux", "windows"):
        # A replaced module is listed as its replacement, the code that goes into the binary, as `go version -m` and the
        # native-package plugin name it.
        listed = go("list", "-deps", "-f", "{{with .Module}}{{if not .Main}}{{with .Replace}}{{.Path}} {{.Version}} {{.Dir}}"
                    "{{else}}{{.Path}} {{.Version}} {{.Dir}}{{end}}{{end}}{{end}}", ".", goos=goos)
        for line in filter(None, listed.splitlines()):
            path, version, folder = line.split(" ", 2)
            found[path] = (version, Path(folder))
    return found


def licence_of(text):
    names = []
    if "Apache License" in text and "Version 2.0" in text:
        names.append("Apache License 2.0")
    if "Mozilla Public License" in text:
        names.append("Mozilla Public License 2.0")
    if "Permission is hereby granted, free of charge" in text:
        names.append("MIT License")
    if re.search(r"Permission to use, copy, modify,? and(/or)? distribute this software", text):
        names.append("ISC License")
    if "Redistribution and use in source and binary forms" in text:
        names.append("BSD 3-Clause License" if "Neither the name" in text or "may not be used to endorse" in text else "BSD 2-Clause License")
    return names


def notice(name, folder):
    files = sorted(f for f in folder.iterdir() if f.is_file() and NOTICE_FILE.match(f.name) and not SOURCE_FILE.search(f.name))
    text = "\n\n".join(f.read_text(errors="replace").strip() for f in files)
    if not text:
        sys.exit(f"{name} has no licence file in {folder}")
    licences = licence_of(text)
    if not licences:
        sys.exit(f"{name}'s licence is not one this tool can name: {[f.name for f in files]}")
    return " and ".join(licences), text + "\n"


def main():
    goversion = go("env", "GOVERSION").strip()
    rows = [("Go standard library", "std", goversion, Path(go("env", "GOROOT").strip()))]
    rows += [(path, path, version, folder) for path, (version, folder) in sorted(modules().items())]
    for old in LICENCES.glob(f"{PREFIX}*.txt"):
        old.unlink()
    lines = [MARKER]
    for component, path, version, folder in rows:
        licence, text = notice(component, folder)
        file = PREFIX + re.sub(r"[^a-z0-9]+", "_", path.lower()) + ".txt"
        (LICENCES / file).write_text(text)
        lines.append("\t".join([component, licence, "yes", file, f"go:{path}:{version}"]))
    kept = INDEX.read_text().split(MARKER)[0].rstrip("\n")
    INDEX.write_text(kept + "\n" + "\n".join(lines) + "\n")
    print(f"{len(rows)} notices")


if __name__ == "__main__":
    main()
