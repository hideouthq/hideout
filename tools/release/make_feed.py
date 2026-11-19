#!/usr/bin/env python3
"""Turns release-notes/releases.json and a folder of packages into the files a GitHub release carries.

Writes OUT/releases.json (the feed Hideout reads) and OUT/notes.md (the release's Markdown).
--check validates the tag, the version and the notes without any packages, so a mistake fails before the build starts.
"""
import argparse
import hashlib
import json
import re
import sys
from datetime import date
from pathlib import Path
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = 1
MAX_HIGHLIGHTS = 2
VERSION = re.compile(r"\d+\.\d+\.\d+")
DATE = re.compile(r"\d{4}-\d{2}-\d{2}")
IMAGE_NAME = re.compile(r"[A-Za-z0-9@_+-]+(\.[A-Za-z0-9@_+-]+)*")
ART_BASE = "https://hideouthq.gg/release-art"
AREAS = {"Discover", "Browse", "Library", "Profiles", "Stats", "Crosshairs", "Autoexec", "Game config", "Settings", "Setup",
         "Install", "Game", "General"}
GROUPS = {"new": "New", "improved": "Improved", "fixed": "Fixed"}
VERSION_FIELDS = {"version", "date", "kind", "highlights", *GROUPS}
FEED_FILES = {"releases.json", "releases.json.sig", "notes.md"}


def package_files(version):
    return {
        "msi": f"Hideout-{version}.msi",
        "appimage": f"Hideout-{version}-x86_64.AppImage",
        "deb": f"hideout_{version}_amd64.deb",
        "rpm": f"hideout-{version}-1.x86_64.rpm",
    }


def module_version(text):
    plugin_indent = None
    for line in text.splitlines():
        indent = len(line) - len(line.lstrip())
        if plugin_indent is None:
            if line.strip() == "native-package:":
                plugin_indent = indent
        elif line.strip() and indent <= plugin_indent:
            return None
        else:
            found = re.fullmatch(r"version:\s*['\"]?([^'\"#\s]+)['\"]?\s*(#.*)?", line.strip())
            if found:
                return found.group(1)
    return None


def text_field(holder, field):
    value = holder.get(field)
    return isinstance(value, str) and bool(value.strip())


def entry_problems(where, group, items):
    if not isinstance(items, list):
        return [f"{where}: {group} is not a list"]
    problems = []
    for item in items:
        if not (isinstance(item, dict) and text_field(item, "area") and text_field(item, "text")):
            problems.append(f"{where}: every {group} item needs an area and a text")
        elif item["area"] not in AREAS:
            problems.append(f"{where}: {group} area {item['area']!r} is not one of {', '.join(sorted(AREAS))}")
    return problems


def image_problems(where, name, art):
    if not isinstance(name, str) or not IMAGE_NAME.fullmatch(name):
        return [f"{where}: image {name!r} must be a bare file name of letters, digits, '@', '_', '+', '-' and single dots"]
    if name in FEED_FILES or name.endswith((".msi", ".exe", ".AppImage", ".deb", ".rpm")):
        return [f"{where}: image {name!r} would collide with a release file"]
    if not (art / name).is_file():
        return [f"{where}: image {name!r} is missing from {art}"]
    return []


def highlight_problems(where, entry, art):
    highlights = entry.get("highlights", [])
    if not isinstance(highlights, list):
        return [f"{where}: highlights is not a list"]
    problems = []
    if len(highlights) > MAX_HIGHLIGHTS:
        problems.append(f"{where}: has {len(highlights)} highlights, the most is {MAX_HIGHLIGHTS}")
    if highlights and entry.get("kind") != "feature":
        problems.append(f"{where}: only a feature update has highlights")
    for highlight in highlights:
        if not (isinstance(highlight, dict) and text_field(highlight, "title") and text_field(highlight, "text")):
            problems.append(f"{where}: every highlight needs a title and a text")
        elif "image" in highlight:
            problems.extend(image_problems(where, highlight["image"], art))
    return problems


def valid_date(text):
    if not (isinstance(text, str) and DATE.fullmatch(text)):
        return False
    try:
        date.fromisoformat(text)
    except ValueError:
        return False
    return True


def version_problems(entry, art):
    where = f"version {entry.get('version', '(no version)')}"
    problems = [f"{where}: {field} is written by make_feed or unknown, remove it" for field in sorted(set(entry) - VERSION_FIELDS)]
    if not (isinstance(entry.get("version"), str) and VERSION.fullmatch(entry["version"])):
        problems.append(f"{where}: the version must look like 1.4.0")
    if not valid_date(entry.get("date")):
        problems.append(f"{where}: date {entry.get('date')!r} is not a real yyyy-mm-dd date")
    if entry.get("kind") not in ("feature", "fixes"):
        problems.append(f"{where}: kind {entry.get('kind')!r} is not feature or fixes")
    problems.extend(highlight_problems(where, entry, art))
    for group in GROUPS:
        problems.extend(entry_problems(where, group, entry.get(group, [])))
    if not any(entry.get(group) for group in GROUPS):
        problems.append(f"{where}: has nothing under new, improved or fixed")
    return problems


def numeric(version):
    return tuple(int(part) for part in version.split("."))


def notes_problems(source, version, art):
    if not isinstance(source, dict):
        return ["the notes file is not a JSON object"]
    problems = [f"{field} is written by make_feed or unknown, remove it from the notes file"
                for field in sorted(set(source) - {"schema", "versions"})]
    if source.get("schema") != SCHEMA:
        problems.append(f"schema {source.get('schema')!r} is not {SCHEMA}")
    versions = source.get("versions")
    if not (isinstance(versions, list) and all(isinstance(entry, dict) for entry in versions)):
        return problems + ["versions is not a list of objects"]
    for entry in versions:
        problems.extend(version_problems(entry, art))
    listed = [entry.get("version") for entry in versions]
    if version not in listed:
        problems.append(f"version {version} has no entry in the notes")
    elif listed[0] != version:
        problems.append(f"the first entry is {listed[0]}, it must be the version being released, {version}")
    if all(isinstance(number, str) and VERSION.fullmatch(number) for number in listed):
        order = [numeric(number) for number in listed]
        if any(newer <= older for newer, older in zip(order, order[1:])):
            problems.append("versions must run newest first, each once")
    return problems


def release_problems(version, tag, module_path):
    problems = []
    if tag != f"v{version}":
        problems.append(f"tag {tag} is not v{version}")
    if not VERSION.fullmatch(version):
        problems.append(f"version {version} must look like 1.4.0")
    try:
        declared = module_version(module_path.read_text())
    except OSError as e:
        return problems + [f"cannot read {module_path}: {e.strerror}"]
    if declared is None:
        problems.append(f"{module_path} has no native-package version")
    elif declared != version:
        problems.append(f"version {version} differs from {declared} in {module_path}")
    return problems


def package_problems(packages, version):
    problems = []
    for name in [*package_files(version).values(), f"Hideout-{version}-Setup.exe"]:
        path = packages / name
        if not path.is_file():
            problems.append(f"package {name} is missing from {packages}")
        elif path.stat().st_size == 0:
            problems.append(f"package {name} is empty")
    return problems


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as file:
        while chunk := file.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def download_block(packages, version, base):
    return {
        key: {"url": f"{base}/{quote(name)}", "size": (packages / name).stat().st_size, "sha256": sha256(packages / name)}
        for key, name in package_files(version).items()
    }


def feed(source, version, repo, download):
    versions = []
    for entry in source["versions"]:
        written = {field: entry[field] for field in ("version", "date", "kind")}
        if entry["version"] == version:
            written["download"] = download
        written.update({field: entry[field] for field in entry if field not in written})
        if "highlights" in entry:
            written["highlights"] = [
                {**highlight, **({"image": f"{ART_BASE}/{highlight['image']}"} if "image" in highlight else {})}
                for highlight in entry["highlights"]
            ]
        versions.append(written)
    return {"schema": SCHEMA, "latest": version, "notesUrl": f"https://github.com/{repo}/releases", "versions": versions}


def notes_markdown(entry):
    parts = []
    for highlight in entry.get("highlights", []):
        parts += [f"### {highlight['title']}", highlight["text"]]
        if "image" in highlight:
            parts.append(f"![{highlight['title']}]({ART_BASE}/{highlight['image']})")
    for group, heading in GROUPS.items():
        if entry.get(group):
            parts += [f"## {heading}", "\n".join(f"- **{item['area']}:** {item['text']}" for item in entry[group])]
    return "\n\n".join(parts) + "\n"


def read_source(notes):
    path = notes / "releases.json"
    try:
        return json.loads(path.read_text(encoding="utf-8")), []
    except OSError as e:
        return None, [f"cannot read {path}: {e.strerror}"]
    except ValueError as e:
        return None, [f"{path} is not valid JSON: {e}"]


def write_release(source, args):
    out = Path(args.out)
    base = (args.download_base or f"https://github.com/{args.repo}/releases/download/{args.tag}").rstrip("/")
    out.mkdir(parents=True, exist_ok=True)
    entry = next(entry for entry in source["versions"] if entry["version"] == args.version)
    (out / "notes.md").write_text(notes_markdown(entry), encoding="utf-8")
    document = feed(source, args.version, args.repo, download_block(Path(args.packages), args.version, base))
    (out / "releases.json").write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True, help="the version being released, such as 1.4.0")
    parser.add_argument("--tag", required=True, help="its tag, such as v1.4.0")
    parser.add_argument("--notes", required=True, help="the folder with releases.json and art/")
    parser.add_argument("--repo", help="owner/name on GitHub")
    parser.add_argument("--packages", help="the folder with the built packages")
    parser.add_argument("--out", help="an empty or new folder for the release's files")
    parser.add_argument("--module", default=str(ROOT / "app/desktopApp/module.yaml"), help="the module.yaml that declares the version")
    parser.add_argument("--download-base", help="where the packages are served, for a local test feed")
    parser.add_argument("--check", action="store_true", help="only validate the tag, the version and the notes")
    args = parser.parse_args(argv)
    if not args.check and not (args.repo and args.packages and args.out):
        parser.error("--repo, --packages and --out are required without --check")
    return args


def main(argv=None):
    args = parse_args(argv)
    problems = release_problems(args.version, args.tag, Path(args.module))
    source, unread = read_source(Path(args.notes))
    problems += unread
    if source is not None:
        problems += notes_problems(source, args.version, Path(args.notes) / "art")
    if not args.check:
        problems += package_problems(Path(args.packages), args.version)
        out = Path(args.out)
        if out.exists() and (not out.is_dir() or any(out.iterdir())):
            problems.append(f"{out} must be an empty or new folder, since all of it goes on the release")
    if problems:
        for problem in problems:
            print(f"make_feed: {problem}", file=sys.stderr)
        return 1
    if not args.check:
        write_release(source, args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
