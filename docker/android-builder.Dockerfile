# HyperCeiler 的 Android 构建镜像。
#
# 与通用 Android 镜像的区别，全部由本项目的工具链决定：
#   - AGP 9.4 + Kotlin 2.4 要求 JDK 25，通用镜像停在 21，无法编译；
#   - compileSdk 37 与 buildTools 37.0.0，通用镜像只有 36；
#   - app 模块带 CMake 原生工程（libHyperCeilerNative.so），需要 NDK 与 CMake。
#   - 36.0.0 也要装：AGP 在未显式声明 buildToolsVersion 的 library 模块上会回退到它。
#
# 基础镜像来自 docker.hjk.is 镜像站，便于在受限网络下拉取。代理地址按宿主机
# docker0 网关写死为 172.17.0.1；换构建机时若代理不在这个地址，改上面的 ARG。
FROM docker.hjk.is/library/eclipse-temurin:25-jdk-noble

ARG HTTP_PROXY=http://172.17.0.1:7890
ARG HTTPS_PROXY=http://172.17.0.1:7890
ARG http_proxy=http://172.17.0.1:7890
ARG https_proxy=http://172.17.0.1:7890
# Java 工具（sdkmanager / Gradle）只认 JVM 代理参数，不认 HTTP_PROXY。直连外网的
# 机器把这五个 ARG 全部传空即可；受限网络下只传 JAVA_TOOL_OPTIONS 就够，容器内的
# curl / apt 始终直连，不会被空字符串或半失效的代理干扰。
ARG JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=10.0.1.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=10.0.1.1 -Dhttps.proxyPort=7890"

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    BUILD_TOOLS_VERSION=37.0.0 \
    NO_PROXY=localhost,127.0.0.1 \
    JAVA_TOOL_OPTIONS=${JAVA_TOOL_OPTIONS}

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl unzip git \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && curl -fsSL -o /tmp/cmdtools.zip \
        https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
    && unzip -q /tmp/cmdtools.zip -d "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && mv "${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools" "${ANDROID_SDK_ROOT}/cmdline-tools/latest" \
    && rm /tmp/cmdtools.zip

ENV PATH="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${PATH}"

RUN mkdir -p "${ANDROID_SDK_ROOT}/licenses" \
    && printf '%s\n' "24333f8a63b6825ea9c5514f83c2829b004d1fee" > "${ANDROID_SDK_ROOT}/licenses/android-sdk-license"

# HTTP_PROXY="" 这类空值会被 sdkmanager 当成无效 URL 而报错，所以这里显式
# unset 所有代理变量；真正需要代理时由 JAVA_TOOL_OPTIONS 提供 JVM 级代理设置。
RUN yes | env -u HTTP_PROXY -u HTTPS_PROXY -u http_proxy -u https_proxy sdkmanager --install \
        "platforms;android-37.0" \
        "build-tools;${BUILD_TOOLS_VERSION}" \
        "build-tools;36.0.0" \
        "ndk;30.0.16248370" \
        "cmake;4.1.2" \
        "platform-tools" \
    && yes | env -u HTTP_PROXY -u HTTPS_PROXY -u http_proxy -u https_proxy sdkmanager --licenses > /dev/null

# 官方 build-tools 里 aapt2 / aidl / zipalign / split-select 是 x86_64 二进制。
# 本机与 CI 都是 aarch64，直接执行会走 binfmt 的 QEMU 翻译，慢到无法接受甚至直接失败。
# 用 Commit451 维护的同版本 arm64 构建替换掉这四个可执行文件。
#
# 36.0.0 也要装：AGP 在未显式声明 buildToolsVersion 的 library 模块上会回退到它的
# 默认版本，而不是 app 模块指定的 37.0.0。
# 默认使用仓库里预置的 arm64 二进制，避免依赖不稳定的 GitHub Release 镜像站。
# 文件由 tools/fetch_arm64_build_tools.py 生成并校验（aarch64 ELF + aapt2 可执行）。
# 若目录不存在，退回网络下载：直连优先，失败再用 gh-proxy.com。
COPY arm64-build-tools /opt/arm64-build-tools
RUN for v in "${BUILD_TOOLS_VERSION}" 36.0.0; do \
      tools="${ANDROID_SDK_ROOT}/build-tools/${v}"; \
      preset="/opt/arm64-build-tools/${v}"; \
      base="https://github.com/Commit451/android-arm-build-tools/releases/download/platform-tools-${v}"; \
      mirror="https://gh-proxy.com/https://github.com/Commit451/android-arm-build-tools/releases/download/platform-tools-${v}"; \
      for bin in aapt2 aidl zipalign split-select; do \
        if [ -f "${preset}/${bin}" ]; then \
          cp "${preset}/${bin}" "${tools}/${bin}"; \
        elif ! curl -fsSL --max-time 60 -o "${tools}/${bin}" "${base}/${bin}"; then \
          curl -fsSL --max-time 120 -o "${tools}/${bin}" "${mirror}/${bin}"; \
        fi; \
        chmod 755 "${tools}/${bin}"; \
      done; \
      "${tools}/aapt2" version; \
      test -x "${tools}/aidl"; \
      test -x "${tools}/zipalign"; \
      test -x "${tools}/split-select"; \
    done

WORKDIR /project
