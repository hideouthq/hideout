#!/usr/bin/env bash
set -euo pipefail
bridge_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)
cd -- "$bridge_root"
cmake -S native/steam-bridge -B build/steam-bridge-i386 -DCMAKE_BUILD_TYPE=Debug -DCMAKE_C_FLAGS=-m32 -DCMAKE_CXX_FLAGS=-m32
cmake --build build/steam-bridge-i386
ctest --test-dir build/steam-bridge-i386 --output-on-failure
cmake -S native/steam-bridge -B build/steam-bridge-x86_64 -DCMAKE_BUILD_TYPE=Debug
cmake --build build/steam-bridge-x86_64
ctest --test-dir build/steam-bridge-x86_64 --output-on-failure
python3 -m unittest discover -s native/steam-bridge/tests -p 'test_*.py' -v
./kotlin test -m shared -m desktopApp \
  --include-classes='*SteamTracking*Test' --include-classes='*SteamBridge*Test' \
  --include-classes='*SteamProcess*Test' --include-classes='*SteamRuntime*Test' --include-classes='*SteamLaunch*Test' --include-classes='*SteamGameLocatorTest' \
  --include-classes='*Stats*Test' \
  --include-classes='*Settings*Test' --include-classes='*Preferences*Test' --include-classes='*OnboardingViewModelTest' \
  --jvm-args="-Dhideout.steam.fixture=$bridge_root/build/steam-bridge-i386/bridge_fixture -Dhideout.steam.bits=32"
./kotlin test -m desktopApp --include-classes='*SteamBridgeTest' --include-classes='*SteamBridgeRepositoryTest' \
  --jvm-args="-Dhideout.steam.fixture=$bridge_root/build/steam-bridge-x86_64/bridge_fixture -Dhideout.steam.bits=64"
