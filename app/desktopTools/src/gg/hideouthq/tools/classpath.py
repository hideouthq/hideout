import glob
import json
import os
import sys


def state(build, name):
    found = glob.glob(os.path.join(glob.escape(os.path.join(build, "incremental.state")), glob.escape(name) + "-*"))
    if len(found) != 1:
        sys.exit(f"expected one Toolchain state for {name} in {build}, found {len(found)}; run ./kotlin build -m desktopTools first")
    with open(found[0]) as file:
        return json.load(file)


def main(build):
    entries = []
    for module in ("desktopTools", "desktopApp", "shared", "core"):
        entries += [path for path in state(build, f"_{module}_compileJvm")["outputFiles"] if os.path.isdir(path)]
    entries += json.loads(state(build, "desktopTools_jvm_main")["outputValues"]["serializedOutput"])["runtimeClasspath"]
    print("-cp")
    print(";".join("Z:" + path for path in entries))


if __name__ == "__main__":
    main(os.path.abspath(sys.argv[1]))
