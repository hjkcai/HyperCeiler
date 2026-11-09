#!/usr/bin/env bash
# 在 Docker 里编译 HyperCeiler 的已签名 APK，可选直接装到真机。
#
# 与 HyperTermuxKeep/build_in_docker.sh 保持同一套用法：镜像按需构建、构建容器用完即删、
# 签名沿用 ~/.android 下那把共享密钥。
#
# 与参考脚本的两处差异，都由本项目决定：
#   1. 本项目依赖 fan.miuix:* 与 hyperceiler-expansion，它们只发布在 GitHub Packages。
#      该 registry 要求 token，构建机没有。脚本改为先用 tools/fetch_github_packages.py
#      从公开发布页抓取产物，落进 localrepo/，再由 Gradle 以本地 maven 仓库消费。
#   2. 工具链是 JDK 25 + SDK 37 + NDK/CMake，不是通用镜像那套 JDK 21 + SDK 36。
#
# 用法:
#   ./build_in_docker.sh
#   ./build_in_docker.sh --install
#   ADB_SERIAL=localhost:5555 ./build_in_docker.sh --install
#   ./build_in_docker.sh --rebuild-image
set -euo pipefail

root=$(cd "$(dirname "$0")" && pwd)
image=hyperceiler-android-builder
container=hyperceiler-android-build
base_image=eclipse-temurin:25-jdk-noble
serial="${ADB_SERIAL:-localhost:5555}"
app_id=$(sed -n 's/^hyperceilerAppId=//p' "${root}/gradle.properties")
build_type=release
proxy_url="http://127.0.0.1:7890"
signing_dir="${HOME}/.android"
keystore="${signing_dir}/release.keystore"
signing_props="${signing_dir}/release-signing.properties"
localrepo="${root}/localrepo"
install=0
rebuild_image=0
native_build=0

usage() {
  cat <<EOF
用法: $0 [--install] [--rebuild-image] [--debug] [--native]

  --install        编译后装到 ADB_SERIAL（默认 ${serial}）
  --rebuild-image  强制重建构建镜像
  --debug          编译 debug 变体，默认 release
  --native         真正编译 C++（默认复用预编译的 .so）

构建镜像 ${image} 不存在时自动构建；构建容器用完即删。
签名密钥: ${keystore}
本地依赖仓库: ${localrepo}

关于 --native：官方 NDK 只发布 linux-x86_64 工具链，没有 x86_64 模拟能力的 aarch64
主机跑不了它。默认做法是跳过 CMake，直接打包 app/src/prebuiltNative 下从官方 APK
提取的 .so；Java/Kotlin 改动不受影响。改过 app/src/main/cpp 时必须加 --native 并在
x86_64 主机或 CI 上编译。
EOF
}

cleanup() {
  docker rm -f "$container" >/dev/null 2>&1 || true
}
trap cleanup EXIT

for arg in "$@"; do
  case "$arg" in
    --install) install=1 ;;
    --rebuild-image) rebuild_image=1 ;;
    --debug) build_type=debug ;;
    --native) native_build=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数: $arg" >&2; usage >&2; exit 2 ;;
  esac
done

# 共享签名密钥。参考项目已经用它签过别的应用，这里只校验存在，不生成新密钥。
if [[ ! -f "$keystore" || ! -f "$signing_props" ]]; then
  echo "缺少共享签名密钥: ${keystore} 或 ${signing_props}" >&2
  exit 1
fi

# 本地 maven 仓库：把 GitHub Packages 上那批产物抓下来，避免依赖 token。
if [[ ! -d "${localrepo}/fan/miuix" ]]; then
  echo "本地依赖仓库不完整，开始抓取 GitHub Packages..."
  python3 "${root}/tools/fetch_github_packages.py" --output "$localrepo"
fi

if [[ "$rebuild_image" -eq 1 ]] || ! docker image inspect "$image" >/dev/null 2>&1; then
  echo "构建镜像 ${image}..."
  docker build \
    --build-arg "HTTP_PROXY=${proxy_url}" \
    --build-arg "HTTPS_PROXY=${proxy_url}" \
    --build-arg "http_proxy=${proxy_url}" \
    --build-arg "https_proxy=${proxy_url}" \
    -f "${root}/docker/android-builder.Dockerfile" \
    -t "$image" \
    "${root}/docker"
fi

# gradle 需要 sdk.dir 才能定位平台；容器内路径是固定的。
printf 'sdk.dir=/opt/android-sdk\n' > "${root}/local.properties"

# signing.properties 指向共享密钥，用绝对路径，容器内同样可见（见下方只读挂载）。
# gpr.* 留空：依赖改由 localrepo 提供，settings.gradle.kts 的 GPR 仓库不会命中。
cat > "${root}/signing.properties" <<EOF
storeFile=${keystore}
storePassword=$(sed -n 's/^storePassword=//p' "$signing_props")
keyAlias=$(sed -n 's/^keyAlias=//p' "$signing_props")
keyPassword=$(sed -n 's/^keyPassword=//p' "$signing_props")
gpr.user=
gpr.key=
EOF
chmod 600 "${root}/signing.properties"

chmod +x "${root}/gradlew"
cleanup

task="assembleRelease"
[[ "$build_type" == "debug" ]] && task="assembleDebug"

gradle_extra=""
if [[ "$native_build" -eq 0 ]]; then
  if [[ -d "${root}/app/src/prebuiltNative/arm64-v8a" ]]; then
    gradle_extra="-PprebuiltNative=true"
  else
    echo "未找到 app/src/prebuiltNative/arm64-v8a，改为编译 C++（需要能运行 NDK 的 x86_64 环境）。" >&2
    echo "若这是 ARM 主机，请先从官方 APK 提取 .so，步骤见 docs/BUILD_IN_DOCKER.md。" >&2
    native_build=1
  fi
fi

docker run --rm \
  --name "$container" \
  -e HOST_UID="$(id -u)" \
  -e HOST_GID="$(id -g)" \
  -e BUILD_TOOLS_VERSION=37.0.0 \
  -e GRADLE_EXTRA="$gradle_extra" \
  -e HTTP_PROXY="${proxy_url}" \
  -e HTTPS_PROXY="${proxy_url}" \
  -e http_proxy="${proxy_url}" \
  -e https_proxy="${proxy_url}" \
  -e NO_PROXY=localhost,127.0.0.1 \
  -e JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=10.0.1.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=10.0.1.1 -Dhttps.proxyPort=7890" \
  -v "${root}":/project \
  -v "${keystore}":"${keystore}":ro \
  -v "${signing_props}":"${signing_props}":ro \
  -v "${localrepo}":/opt/localrepo:ro \
  -v hyperceiler-gradle-cache:/root/.gradle \
  -w /project \
  "$image" \
  bash -lc "set -euo pipefail
status=0
./gradlew ${task} --no-daemon --stacktrace \$GRADLE_EXTRA \
  -Pandroid.aapt2FromMavenOverride=/opt/android-sdk/build-tools/37.0.0/aapt2 \
  -x lintVitalRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease \
  --max-workers=2 || status=\$?
apk=\$(find app/build/outputs/apk -name '*.apk' -print -quit 2>/dev/null || true)
verify_status=0
if [ \"\$status\" -eq 0 ] && [ -n \"\$apk\" ]; then
  /opt/android-sdk/build-tools/37.0.0/apksigner verify --print-certs \"\$apk\" || verify_status=\$?
fi
for dir in .gradle build app/build library; do
  if [ -d \"\$dir\" ]; then chown -R \"\$HOST_UID:\$HOST_GID\" \"\$dir\" || true; fi
done
if [ \"\$status\" -ne 0 ]; then exit \"\$status\"; fi
exit \"\$verify_status\""

apk=$(find "${root}/app/build/outputs/apk" -name '*.apk' -print -quit)
echo "APK: ${apk}"

if [[ "$install" -eq 1 ]]; then
  adb connect "$serial"
  if ! adb -s "$serial" install -r "$apk"; then
    echo "已安装的包签名不一致，卸掉后重装。"
    adb -s "$serial" uninstall "$app_id" || true
    adb -s "$serial" install -r "$apk"
  fi
fi
