#!/usr/bin/env python3
"""pin_modlock: move hideout-link to the commit of the Modlock fork that the custom games feed's runtime is built from.

The helper reads each mode's mod.json and streams frames to the feed's renderer with Modlock's own packages, so it must
import the same commit. This points tools/link/go.mod's replace at it, lets Go resolve and record it, and rewrites the
licence notices, which a native build checks. The modes repository's check compares the replace with its runtime.json.
"""
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
FORK = "github.com/hideouthq/modlock"


def main(argv):
    if len(argv) != 1 or not re.fullmatch(r"[0-9a-f]{40}", argv[0]):
        print("usage: pin_modlock.py <full commit of the fork>", file=sys.stderr)
        return 2
    subprocess.run(["go", "mod", "edit", f"-replace=github.com/paralin/modlock={FORK}@{argv[0]}"], cwd=HERE, check=True)
    subprocess.run(["go", "mod", "tidy"], cwd=HERE, check=True)
    subprocess.run([sys.executable, str(HERE / "notices.py")], check=True)
    print(next(line for line in (HERE / "go.mod").read_text().splitlines() if line.startswith("replace github.com/paralin/modlock")))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
