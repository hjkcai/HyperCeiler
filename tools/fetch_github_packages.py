#!/usr/bin/env python3
"""抓取 HyperCeiler 的 GitHub Packages 依赖，生成可供 Gradle 使用的本地 Maven 仓库。

为什么需要它：HyperCeiler 依赖 fan.miuix:*、hyperceiler-expansion 等产物，它们只发布在
ReChronoRain/HyperCeiler 的 GitHub Packages 私有 registry 上。该 registry 的 Maven 端点要求
任意有效 token（匿名一律 401），但公开发布页会为每个 artifact 生成带签名的直链，直链本身
可以匿名下载。本脚本只读那张页面，因此构建机不需要任何 GitHub 凭据。

产物写入 localrepo/，目录结构即标准 Maven 布局，由 Gradle 以 maven { url } 方式消费。
下载结果按 groupId/artifactId/version 落盘，重复执行会覆盖同名文件。

用法:
  python3 tools/fetch_github_packages.py [--output DIR]
"""

import argparse
import html
import re
import subprocess
import sys
import urllib.parse
from pathlib import Path

# 发布页 ID 到坐标的映射由页面标题给出，这里只列本仓库实际依赖的包。
PACKAGE_PAGE_IDS = [
    "2384793", "2384795", "2384796", "2384797", "2384798", "2384799",
    "2384801", "2384804", "2384805", "2384806", "2384807", "2384808",
    "2384809", "2384810", "2384811", "2384812", "2384813", "2384814",
    "2384827", "2384832", "2385627", "2385629", "2386220", "2470771",
    "2834175", "2834213", "2835319", "2862276", "2869689", "2895620",
]

PACKAGE_PAGE = "https://github.com/ReChronoRain/HyperCeiler/packages/{id}"
PROXY = "http://127.0.0.1:7890"


def fetch(url: str) -> bytes:
    command = ["curl", "-fsSL", "--max-time", "60"]
    if PROXY:
        command += ["-x", PROXY]
    command.append(url)
    result = subprocess.run(command, capture_output=True)
    if result.returncode != 0:
        raise RuntimeError(f"{url}: {result.stderr.decode('utf-8', 'replace')[:200]}")
    return result.stdout


def artifact_links(package_id: str) -> dict[str, str]:
    """从发布页取出 {文件名: 带签名直链}，只保留 Gradle 需要的三类文件。"""
    page = fetch(PACKAGE_PAGE.format(id=package_id)).decode("utf-8", "replace")
    links: dict[str, str] = {}
    for raw in re.findall(r'href="(https://github-registry-files[^"]+)"', page):
        url = html.unescape(raw)
        filename = urllib.parse.unquote(re.search(r"filename%3D([^&]+)", url).group(1))
        if filename.endswith((".pom", ".module", ".aar")):
            links.setdefault(filename, url)
    return links


def coordinates(pom: bytes) -> tuple[str, str, str]:
    text = pom.decode("utf-8", "replace")
    return (
        re.search(r"<groupId>([^<]+)", text).group(1),
        re.search(r"<artifactId>([^<]+)", text).group(1),
        re.search(r"<version>([^<]+)", text).group(1),
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "localrepo",
    )
    args = parser.parse_args()
    repo: Path = args.output

    for package_id in PACKAGE_PAGE_IDS:
        links = artifact_links(package_id)
        pom_name = next((name for name in links if name.endswith(".pom")), None)
        if pom_name is None:
            print(f"跳过 {package_id}: 页面没有 pom 直链", file=sys.stderr)
            continue

        group, artifact, version = coordinates(fetch(links[pom_name]))
        target = repo / group.replace(".", "/") / artifact / version
        target.mkdir(parents=True, exist_ok=True)

        written = 0
        for filename, url in links.items():
            if not filename.startswith(f"{artifact}-{version}."):
                continue
            (target / filename).write_bytes(fetch(url))
            written += 1
        print(f"{group}:{artifact}:{version} ({written} files)")

    print(f"\n本地仓库: {repo}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
