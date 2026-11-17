#!/usr/bin/env python3
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

build, output, source = map(Path, sys.argv[1:4])
image = sys.argv[4]
for bits, arch, machine in [(32, 'i386', 3), (64, 'x86_64', 62)]:
    bundle = output / ('linux-' + arch)
    bundle.mkdir(parents=True, exist_ok=True)
    imports = {}
    names = ['libhideout_steam_bridge.so', 'bridge_probe', 'launch.sh', 'final-launch.sh', 'client-container.sh', 'THIRD_PARTY_NOTICES.txt', 'BUILD.json']
    for name in names[:2]:
        path = build / str(bits) / name
        h = path.read_bytes()[:20]
        assert h[:4] == b'\x7fELF' and h[4] == (1 if bits == 32 else 2) and int.from_bytes(h[18:20], 'little') == machine
        needed = subprocess.check_output(['readelf', '-d', path], text=True)
        assert not any(x in needed for x in ('libstdc++.so', 'libgcc_s.so', 'libZydis.so'))
        symbols = subprocess.check_output(['objdump', '-T', path], text=True)
        versions = sorted(set(re.findall(r'GLIBC_([0-9.]+)', symbols)))
        assert all(tuple(map(int, v.split('.'))) <= (2, 15) for v in versions), versions
        imports[name] = versions
        shutil.copy2(path, bundle / name)
    for name in names[2:5]:
        shutil.copy2(source / 'launcher' / name, bundle / name)
    shutil.copy2(source / 'THIRD_PARTY_NOTICES.txt', bundle / 'THIRD_PARTY_NOTICES.txt')
    (bundle / 'BUILD.json').write_text(json.dumps({'sdk': image, 'architecture': arch, 'glibcImports': imports}, sort_keys=True) + '\n')
    (bundle / 'SHA256SUMS').write_text(''.join(hashlib.sha256((bundle / n).read_bytes()).hexdigest() + '  ' + n + '\n' for n in names))
