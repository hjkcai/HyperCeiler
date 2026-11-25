# 用 Docker 构建 HyperCeiler

这份文档说明如何在一台干净的机器上编译出可安装的 HyperCeiler APK，以及其中一处
绕开官方私有仓库的做法为什么成立、边界在哪里。

读者对象是接手这个仓库、需要在别的机器上复现构建的人。全程不需要 GitHub 凭据。

## 结论先看

```sh
git clone git@github.com:hjkcai/HyperCeiler.git
cd HyperCeiler
./build_in_docker.sh              # 编译 release APK
./build_in_docker.sh --install    # 编译后装到 ADB_SERIAL
```

产物在 `app/build/outputs/apk/release/`。签名沿用 `~/.android/release.keystore`，
第一次运行前需要它存在，格式见下文「签名」。

前提：Docker、`python3`、`curl`，以及能访问 `github.com`、`dl.google.com`、
`docker.hjk.is` 和 Maven 镜像的外网。

**脚本默认直连，不设任何代理。** 能直连外网的机器什么都不用配。只有受限网络才
需要显式设置 `PROXY_URL`，例如：

```sh
PROXY_URL=http://172.17.0.1:7890 ./build_in_docker.sh
```

注意容器内访问宿主代理要用 docker0 网关地址，不能用 `127.0.0.1`。
`PROXY_URL` 会同时驱动容器内的 `HTTP_PROXY` 与 JVM 的 `JAVA_TOOL_OPTIONS`；
私有依赖抓取脚本则读 `HYPERCEILER_PROXY`。两者都留空时全程直连。

这里踩过一个坑：早期版本把 JVM 代理硬编码成 `10.0.1.1:7890`，代理失效后
Gradle 连插件仓库都解析不到（合并上游、AGP 升到 9.5.1 时报
`UnknownPluginException`）。现在代理参数跟随 `PROXY_URL`，空值就完全不设置。

另一个实测过的坑：docker daemon 若全局配置了代理（用 `docker info | grep -i proxy`
查看）而该代理不稳定，拉基础镜像会报 `failed to resolve source metadata ... EOF`。
此时不改系统配置，在单次构建里把所有代理 ARG 传空即可：

```sh
docker build --network=host \
  --build-arg HTTP_PROXY= --build-arg HTTPS_PROXY= \
  --build-arg http_proxy= --build-arg https_proxy= \
  --build-arg JAVA_TOOL_OPTIONS= \
  -f docker/android-builder.Dockerfile -t hyperceiler-android-builder docker
```

Dockerfile 里 `JAVA_TOOL_OPTIONS` 现在是 ARG 驱动的，必须一起传空，否则 Java
工具仍会去连那个失效的代理。容器内的 `curl` / `apt` 不看这个变量，始终直连。

判断依据：`curl -sS -o /dev/null -w '%{http_code}' https://docker.hjk.is/v2/.../manifests/25-jdk-noble`
直连返回 200、走代理报 TLS EOF，就说明是代理的问题。

如果覆盖 ARG 之后 docker 仍去请求 registry（daemon 的代理配置不归 buildkit 的
ARG 管），用 `skopeo` 把基础镜像直接导进来，再打一个与 Dockerfile 同名的本地 tag，
这样构建时命中本地缓存、完全不碰网络：

```sh
skopeo --override-arch arm64 copy \
  docker://docker.hjk.is/library/eclipse-temurin:25-jdk-noble \
  docker-archive:/tmp/temurin.tar:eclipse-temurin:25-jdk-noble
docker load -i /tmp/temurin.tar
docker tag eclipse-temurin:25-jdk-noble \
  docker.hjk.is/library/eclipse-temurin:25-jdk-noble
docker build --pull=false --network=host \
  -f docker/android-builder.Dockerfile -t hyperceiler-android-builder docker
```

`--override-arch` 要按构建机的实际架构选（这台是 aarch64，所以用 arm64）。

另一个实测过的坑：官方 build-tools 里的 `aapt2` / `aidl` 等是 x86_64 二进制，
必须换成 Commit451 维护的 arm64 版本，而这些文件托管在 GitHub Release 上。
部分网络直连 `github.com/.../releases/download/...` 会超时，镜像站又偶尔返回
**200 但内容被截断到几百 KB** 的坏文件。

因此仓库里预置了校验过的 8 个文件，放在 `docker/arm64-build-tools/<version>/`，
Dockerfile 优先 COPY 它们，构建时完全不联网。要重新生成或升级版本时运行：

```sh
python3 tools/fetch_arm64_build_tools.py
```

脚本会重试多个源，逐个校验 ELF 架构（aarch64）与最小文件大小，再落盘。
只有预置目录不存在时，Dockerfile 才会退回网络下载。

## 工具链为什么是这一套

| 组件 | 版本 | 为什么不能用更低的 |
| --- | --- | --- |
| JDK | 25 | AGP 9.4 + Kotlin 2.4 的 toolchain 声明就是 25，21 会直接编译失败 |
| compileSdk | 37 | 项目 `compileSdk = 37`，且 `android.suppressUnsupportedCompileSdk=37.0` 已打开 |
| build-tools | 37.0.0 与 36.0.0 | app 模块指定 37；library 模块没指定，AGP 会回退到默认的 36 |
| NDK | 30.0.16248370 | 镜像内预装；CMake 配置需要它 |
| CMake | 4.1.2 | 镜像内预装 |

镜像定义在 `docker/android-builder.Dockerfile`，用 `docker build` 由脚本按需触发，
已存在时不会重建；要更新加 `--rebuild-image`。

## 私有仓库的包是怎么拿到的

这是整套构建里唯一不显然的地方，值得单独说明。

### 问题

本项目的 `gradle/libs.versions.toml` 依赖两类产物：

- `fan.miuix:*`（21 个包，设置页 UI 全套）
- `com.sevtinge.hyperceiler:hyperceiler-expansion`

它们只发布在 `ReChronoRain/HyperCeiler` 的 GitHub Packages。该 registry 的 Maven
端点是 `https://maven.pkg.github.com/ReChronoRain/HyperCeiler`，**匿名请求一律返回
401**，即使包本身是 public。这意味着纯 CI 之外的机器没有 token 就装不动。

### 绕过方式

GitHub 的公开包页面（`https://github.com/ReChronoRain/HyperCeiler/packages/<id>`）
**匿名可读**。页面上为每个 artifact 给出了带签名的下载直链，形如：

```
https://github-registry-files.githubusercontent.com/<id>/<uuid>?X-Amz-Signature=...
```

这些直链指向 S3，不带任何 GitHub 身份也能下载。签名有有效期（约 5 分钟），
所以不能写死，必须在构建时现场抓。

`tools/fetch_github_packages.py` 做的就是这件事：

1. 逐个打开 30 个包的公开页面
2. 从页面 HTML 里取出 `.pom`、`.module`、`.aar` 的直链
3. 读 pom 得到真实的 `groupId:artifactId:version`（页面标题只有包名，不够用）
4. 按标准 Maven 布局写入 `localrepo/`，例如
   `localrepo/fan/miuix/core/1.0.13.0/core-1.0.13.0.aar`

`settings.gradle.kts` 里加了条件注册：

```kotlin
if (localRepoDir.isDirectory) {
    maven { name = "localOffline"; url = uri(localRepoDir) }
}
```

它排在仓库列表最前，命中后不再访问任何网络仓库。同时 GPR 仓库改成有凭据才注册，
避免空凭据请求拿到 401 后拖慢解析。

### 边界

- `localrepo/` 与 `decomp/` 都在 `.gitignore` 里。它们是可再生成的输入，不入库。
- 脚本只在 `localrepo/fan/miuix` 不存在时才去抓。想强制刷新就删掉 `localrepo/`。
- 有 token 的机器可以直接设 `GIT_ACTOR` / `GIT_TOKEN` 环境变量，走真实 registry，
  这时 `localrepo` 不是必需的。
- 包版本升级后，`PACKAGE_PAGE_IDS` 不需要改（它只认页面），但页面结构若变化，
  解析会失败并在 stderr 报出具体是哪个包。

## 签名

脚本从 `~/.android/release-signing.properties` 读四项：

```properties
storeFile=/home/<user>/.android/release.keystore
storePassword=<password>
keyAlias=release
keyPassword=<password>
```

然后生成仓库根目录的 `signing.properties`（已被 `.gitignore` 忽略）。若文件不存在，
脚本直接报错退出，不会自动生成新密钥 —— 换新密钥会导致已安装的应用无法覆盖升级。

`gpr.user` / `gpr.key` 会被写成空值。这是刻意的：依赖已经由 `localrepo` 提供，
不需要 registry 凭据，而 `settings.gradle.kts` 会正确地把空凭据当作「未配置」。

## 包名

`gradle.properties` 里有一行 `hyperceilerAppId`，当前值 `com.hjkcai.HyperCeiler`。

它同时决定四处运行期身份，改一处即可，不要分别改：

| 位置 | 取值方式 |
| --- | --- |
| `applicationId` | `app/build.gradle.kts` 直接读该属性 |
| `SharedPrefsProvider` authority | `manifestPlaceholders` + libhook 的 `BuildConfig` |
| `SettingsProvider` authority | 同上 |
| 三个自定义权限名 | 同上（`APP_CRASH` / `READ_SETTINGS` / `WRITE_SETTINGS`） |

之所以要一起改：Provider authority 是全局唯一的。只改 `applicationId` 会与官方版
冲突，安装时报 `INSTALL_FAILED_CONFLICTING_PROVIDER`。改成 `com.hjkcai.HyperCeiler`
后可以与其他 HyperCeiler 版本共存。

`library/libhook` 里所有读取该 authority 的地方都引用
`SharedPrefsProvider.AUTHORITY` 或 `ProjectApi.mAppModulePkg`，没有硬编码残留。
新增功能时请沿用这两个常量。

## 原生库：本机为什么复用官方 APK 里的 .so

`app` 模块有 CMake 工程，产出 `libHyperCeilerNative.so` 和
`libHyperCeilerAmbientOS4.so`。官方 NDK **只发布 `linux-x86_64` 工具链**，
没有 aarch64 版本；如果构建机是 ARM 且没有 x86_64 的 QEMU 模拟，CMake 这一步
必然失败（报 `binfmt_dispatcher` 相关错误）。

因此脚本默认走 `-PprebuiltNative=true`：跳过 CMake，直接打包
`app/src/prebuiltNative/arm64-v8a/` 下从官方 APK 提取的 `.so`。

这只适用于**没有改动 `app/src/main/cpp`** 的情况。当前仓库的改动全在 Java/Kotlin 层，
C++ 一字未动，所以复用是安全的。

要重新提取这两个 so：

```sh
adb pull /data/app/<...>/com.sevtinge.hyperceiler-<...>/base.apk /tmp/hc.apk
unzip -o /tmp/hc.apk 'lib/arm64-v8a/libHyperCeiler*.so' -d /tmp/hc
mkdir -p app/src/prebuiltNative/arm64-v8a
cp /tmp/hc/lib/arm64-v8a/*.so app/src/prebuiltNative/arm64-v8a/
rm app/src/prebuiltNative/arm64-v8a/libdexkit.so   # 这个是依赖自带的，不要放进来
```

在 **x86_64 主机**上则不需要这套变通，直接：

```sh
./build_in_docker.sh --native
```

`--native` 会关掉 `prebuiltNative`，走正常的 CMake 构建。做 Dart 层的 native hook
（见 `docs/HANDOFF.md`）必须用这条路径。

## 一台新机器上的完整步骤

1. 装 Docker，确认 `docker run --rm hello-world` 能跑
2. 准备签名：`~/.android/release.keystore` 与 `~/.android/release-signing.properties`
3. `git clone` 本仓库
4. `./build_in_docker.sh`
5. 第一次会拉基础镜像、装 SDK/NDK、抓 30 个私有包，约 10 分钟
6. 后续构建走缓存，约 1 分钟

安装：

```sh
ADB_SERIAL=localhost:5555 ./build_in_docker.sh --install
```

脚本会比较签名，不一致时提示卸载重装。

## 常见问题

**构建到一半 daemon 消失。** 多半是 OOM。`gradle.properties` 里
`org.gradle.jvmargs=-Xmx3g`、`org.gradle.parallel=false`，脚本还传了
`--max-workers=2` 并跳过 lint vital。机器内存小于 8G 时把 `-Xmx` 再降到 `2g`。

**`.gradle/fileHashes.bin is corrupt`。** 上次构建被强杀留下的。删掉仓库根目录的
`.gradle` 再跑。

**`AAPT2 Daemon startup failed`。** 镜像里 build-tools 的可执行文件仍是 x86_64。
检查 `docker/android-builder.Dockerfile` 里 Commit451 替换那一段是否执行成功。

**依赖解析 401。** `localrepo/` 不完整。删掉后重新运行，脚本会重抓。

**`INSTALL_FAILED_CONFLICTING_PROVIDER`。** 有另一个 HyperCeiler 变体占用了同一
authority。把 `hyperceilerAppId` 改成别的值重新构建。

## 相关文件

| 文件 | 作用 |
| --- | --- |
| `build_in_docker.sh` | 入口脚本，构建 / 安装 / 重建镜像 |
| `docker/android-builder.Dockerfile` | JDK 25 + SDK 37 + NDK + arm64 build-tools |
| `tools/fetch_github_packages.py` | 从公开包页面抓私有依赖到 `localrepo/` |
| `settings.gradle.kts` | 本地仓库优先、GPR 凭据可选 |
| `gradle.properties` | `hyperceilerAppId` 与内存参数 |
| `.gitignore` | `/localrepo/`、`/decomp/`、`signing.properties` |
