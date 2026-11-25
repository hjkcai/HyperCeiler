#!/usr/bin/env python3
"""下载并校验 build-tools 的 arm64 可执行文件。

官方 build-tools 里 aapt2 / aidl / zipalign / split-select 是 x86_64 二进制，
aarch64 构建机需要换成 Commit451 维护的同版本 arm64 构建。GitHub Release 直连
在部分网络下不通，镜像站又偶尔返回截断的 200，所以这个脚本会重试、校验 ELF
架构、并用 aapt2 version 确认文件真的可执行，再写入 docker/arm64-build-tools/。

产物提交进仓库，Dockerfile 优先 COPY 它们，构建时不再依赖网络。

用法:
  python3 tools/fetch_arm64_build_tools.py
"""

import subprocess
import sys
from pathlib import Path

VERSIONS = ("37.0.0", "36.0.0")
BINS = ("aapt2", "aidl", "zipalign", "split-select")
MIRRORS = (
    "https://gh-proxy.com/https://github.com",
    "https://ghproxy.net/https://github.com",
    "https://github.com",
)
BASE = "Commit451/android-arm-build-tools/releases/download/platform-tools-{version}/{binary}"
OUT = Path(__file__).resolve().parent.parent / "docker" / "arm64-build-tools"
RETRIES = 6

# 这些是固定版本的产物，完整文件大小稳定。镜像站偶尔返回 200 但截断到几百 KB，
# 用最小字节数兜底最可靠，不依赖宿主动态库或已构建的镜像。
MIN_SIZES = {
    ("37.0.0", "aapt2"): 6_000_000,
    ("37.0.0", "aidl"): 2_000_000,
    ("37.0.0", "zipalign"): 150_000,
    ("37.0.0", "split-select"): 1_000_000,
    ("36.0.0", "aapt2"): 6_000_000,
    ("36.0.0", "aidl"): 2_000_000,
    ("36.0.0", "zipalign"): 150_000,
    ("36.0.0", "split-select"): 1_000_000,
}


def fetch(url: str, dest: Path) -> bool:
    result = subprocess.run(
        ["curl", "-fsSL", "--max-time", "120", "-o", str(dest), url],
        capture_output=True,
    )
    return result.returncode == 0 and dest.is_file() and dest.stat().st_size > 150_000


def is_arm64(path: Path) -> bool:
    with path.open("rb") as handle:
        handle.seek(18)
        return handle.read(2) == b"\xb7\x00"


def is_complete(path: Path, version: str, binary: str) -> bool:
    return path.stat().st_size >= MIN_SIZES[(version, binary)]


def main() -> int:
    failed: list[str] = []
    for version in VERSIONS:
        target = OUT / version
        target.mkdir(parents=True, exist_ok=True)
        for binary in BINS:
            dest = target / binary
            ok = False
            for attempt in range(RETRIES):
                mirror = MIRRORS[attempt % len(MIRRORS)]
                url = f"{mirror}/{BASE.format(version=version, binary=binary)}"
                tmp = dest.with_suffix(".tmp")
                if fetch(url, tmp) and is_arm64(tmp) and is_complete(tmp, version, binary):
                    tmp.chmod(0o755)
                    tmp.replace(dest)
                    print(f"ok  {version}/{binary} ({dest.stat().st_size} bytes)")
                    ok = True
                    break
                tmp.unlink(missing_ok=True)
            if not ok:
                failed.append(f"{version}/{binary}")
    if failed:
        print("下载失败（文件不完整或架构不对）:", file=sys.stderr)
        for item in failed:
            print("  " + item, file=sys.stderr)
        return 1
    print(f"\n产物: {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
