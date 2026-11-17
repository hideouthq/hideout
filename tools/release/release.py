#!/usr/bin/env python3
"""Cuts a release from main: sets the version in app/desktopApp/module.yaml, commits it with the notes, tags v<version>
and pushes main and the tag, which starts .github/workflows/release.yml.

Without --push it only checks and says what it would do, and changes nothing. It refuses everything the release job's
check would refuse, and a release of anything but origin/main and the notes.
"""
import argparse
import re
import subprocess
import sys
import tempfile
from pathlib import Path

import make_feed

ROOT = make_feed.ROOT
MODULE = Path("app/desktopApp/module.yaml")
NOTES = Path("release-notes")


def git(*args):
    return subprocess.run(["git", "-C", str(ROOT), *args], check=True, capture_output=True, text=True).stdout.rstrip("\n")


def with_module_version(text, version):
    lines = text.splitlines(keepends=True)
    plugin_indent = None
    for i, line in enumerate(lines):
        indent = len(line) - len(line.lstrip())
        if plugin_indent is None:
            if line.strip() == "native-package:":
                plugin_indent = indent
        elif line.strip() and indent <= plugin_indent:
            return None
        else:
            found = re.fullmatch(r"(\s*version:\s*['\"]?)([^'\"#\s]+)(.*)", line, re.DOTALL)
            if found:
                lines[i] = found.group(1) + version + found.group(3)
                return "".join(lines)
    return None


def git_problems(tag):
    if git("branch", "--show-current") != "main":
        return ["releases are cut from main; switch to it first"]
    fetch = subprocess.run(["git", "-C", str(ROOT), "fetch", "--quiet", "origin", "main", "--tags"], capture_output=True, text=True)
    if fetch.returncode != 0:
        return [f"could not fetch origin: {fetch.stderr.strip()}"]
    problems = []
    if git("rev-parse", "HEAD") != git("rev-parse", "origin/main"):
        problems.append("main is not origin/main; push your commits and let the tests pass, or pull, first")
    if git("tag", "--list", tag):
        problems.append(f"the tag {tag} already exists")
    others = [line[3:] for line in git("status", "--porcelain", "--untracked-files=all").splitlines()
              if not line[3:].startswith(f"{NOTES}/")]
    if others:
        problems.append("only the notes may be uncommitted; commit or set aside: " + ", ".join(others))
    return problems


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version", help="the version to release, such as 1.4.0")
    parser.add_argument("--push", action="store_true", help="commit, tag and push; without it nothing changes")
    args = parser.parse_args(argv)
    version, tag = args.version, f"v{args.version}"

    problems = git_problems(tag)
    module_text = (ROOT / MODULE).read_text()
    current = make_feed.module_version(module_text)
    released = with_module_version(module_text, version)
    if released is None:
        problems.append(f"{MODULE} has no native-package version")
    else:
        if current and all(make_feed.VERSION.fullmatch(v) for v in (version, current)) and make_feed.numeric(version) < make_feed.numeric(current):
            problems.append(f"{version} is older than {current}, the version in {MODULE}")
        with tempfile.TemporaryDirectory() as temp:
            module = Path(temp) / "module.yaml"
            module.write_text(released)
            problems += make_feed.release_problems(version, tag, module)
        source, unread = make_feed.read_source(ROOT / NOTES)
        problems += unread
        if source is not None:
            problems += make_feed.notes_problems(source, version, ROOT / NOTES / "art")
    if problems:
        for problem in problems:
            print(f"release: {problem}", file=sys.stderr)
        return 1

    changes = git("status", "--porcelain", "--untracked-files=all")
    steps = [f"set the version in {MODULE} from {current} to {version}"] if current != version else []
    if changes or steps:
        steps.append(f"commit the version and {NOTES}/ as \"Release {version}\"")
    steps += [f"tag {tag}", f"push main and {tag} to origin"]
    if not args.push:
        print("release: ready. With --push it would:\n" + "".join(f"  {step}\n" for step in steps), end="")
        return 0
    before = git("rev-parse", "HEAD")
    (ROOT / MODULE).write_text(released)
    git("add", str(MODULE), str(NOTES))
    if subprocess.run(["git", "-C", str(ROOT), "diff", "--cached", "--quiet"]).returncode != 0:
        git("commit", "--quiet", "-m", f"Release {version}")
    git("tag", tag)
    push = subprocess.run(["git", "-C", str(ROOT), "push", "--atomic", "origin", "main", tag], capture_output=True, text=True)
    if push.returncode != 0:
        git("tag", "--delete", tag)
        git("reset", "--quiet", before)
        (ROOT / MODULE).write_text(module_text)
        print(f"release: the push failed, so the release commit and the tag are undone and the notes left uncommitted:\n"
              f"{push.stderr.strip()}", file=sys.stderr)
        return 1
    print(f"release: pushed {tag}. Approve the release environment once the builds finish:"
          f" https://github.com/hideouthq/hideout/actions/workflows/release.yml")
    return 0


if __name__ == "__main__":
    sys.exit(main())
