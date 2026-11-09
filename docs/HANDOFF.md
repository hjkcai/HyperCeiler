# 交接：桌面耗电修复与抽屉标题移除

这份文档把一次完整的工作交接给别人。它记录做了什么、为什么这么做、哪些已
验证、哪些还没做完，以及下一步该从哪里接手。

接手前请先读 `docs/BUILD_IN_DOCKER.md`，那是构建方式的说明。

## 一句话背景

HyperCeiler 在后台反复冷启动模块主进程，每 5 秒一轮，持续耗电。根因是宿主进程
（system_server、SystemUI、桌面）里的 hook 周期性跨进程查询模块自己暴露的
ContentProvider，而那个 Provider 所在的进程一直被系统回收。

修复方向是：把「定时重读」换成「变更驱动」，把 Binder 事务里的同步查询换成读缓存。

## 已完成并验证的部分

### 耗电循环已消除

真机实测（`localhost:5555`，25098PN5AC / HyperOS V816）：

| 指标 | 修复前 | 修复后 |
| --- | --- | --- |
| 2 分钟内进程启停 | 约 24 轮（每 5 秒一次 `am_proc_start` + `am_proc_died`） | 0 次 |
| 进程 PID | 每 5 秒变化 | 稳定不变 |
| 30 秒内 CPU 时间 | 持续尖峰 | 完全不变（utime/stime 三次采样一致） |

日志证据：修复前 `am_proc_start` 的类型固定是 `content provider`，目标是
`com.sevtinge.hyperceiler.libhook.provider.SharedPrefsProvider`；死亡原因固定是
`reason=unstable content provider`。

### 具体改动

| 文件 | 改动 |
| --- | --- |
| `HomeLayoutNativeEndpointOS4.java` | 成功读取后不再每 1.5 秒重查，改为通知驱动；新增 `IDLE_WAIT_MS = 3_600_000` 作为兜底 |
| `HomeRecentsHapticEndpointOS4.java` | 整体重写：WMS Binder 事务里只读进程内缓存，读取由 ContentObserver 完成 |
| `HomeDockWindow.kt` | sweep 里的几何查询加 `geometryDirty` 门控；`SWEEP_BACKOFF_MAX_MS` 从 8s 放宽到 30s；`init()` 里调用 `prime()` 预热触感缓存 |
| `HomeQuickSwitchSystemUIOS4.java` | ContentObserver 从 `ROOT` 收窄到 `home_gestures` 子树 |
| `HomeExtraGesturesOS4.java` | 同上；前台轮询从 500ms 放宽到 1000ms |

### 包名改为可共存

`gradle.properties` 的 `hyperceilerAppId` 一处控制 `applicationId`、两个 Provider
authority、三个自定义权限名。当前值 `com.hjkcai.HyperCeiler`，可与官方版共存。

涉及 20 余个文件，把硬编码的 `com.sevtinge.hyperceiler.provider.sharedprefs` 全部
换成 `SharedPrefsProvider.AUTHORITY` 或 `ProjectApi.mAppModulePkg`。

### 构建链

见 `docs/BUILD_IN_DOCKER.md`。要点：JDK 25 + SDK 37，私有依赖从 GitHub 包页面的
匿名直链抓取到 `localrepo/`，不需要 token。

## 未完成的部分

### 1. 抽屉「全部应用」标题移除（主任务）

**需求**：删掉桌面抽屉顶部那一栏「全部应用」标题。

**已查清的事实**：

OS4 桌面是 **Flutter 应用**，不是原生 Android View。

```
MiuiHome.apk (56MB, /product/priv-app/MiuiHome/)
├── assets/flutter_assets/     ← Dart 资源
├── lib/arm64-v8a/libapp.so    ← 29MB，Dart AOT 编译产物
└── 无 classes.dex             ← 没有 Java 代码
```

真机 `uiautomator dump` 的结果（抽屉打开时）：

```
content-desc="全部应用"  class=android.view.View  bounds=[509,266][711,344]
content-desc="进入抽屉设置"
```

整个界面只有 67 个 `android.view.View` 节点，没有一个原生控件。这是 Flutter 的
语义树，不是真实 View 层级。**Java hook 改不到这个标题，因为没有 View 对象可改。**

桌面进程加载的关键 so：`/system_ext/lib64/libhyper_os_flutter.so`（系统级引擎）。

**为什么这个项目能做**：`app/src/main/cpp/targets/home/` 下已有成熟的 Dart AOT
hook 体系，共约 22710 行：

| 文件 | 行数 | 作用 |
| --- | --- | --- |
| `home_layout_dart_arm64.S` | 2888 | ARM64 汇编跳板（Dart AOT 不遵循 AAPCS） |
| `home_layout_hooks.cpp` | 7690 | 注入与分发 |
| `home_layout_resolver.h` | 1200+ | 从 ELF 符号表定位函数 |
| `home_layout_dart_targets.h` | 40859 字节 | Dart 函数签名表 |
| `recents_guard.generated.h` | 284995 字节 | 生成的守卫表 |

已有的抽屉相关实现可直接参考：

- `home_drawer_config.h`：`kDrawerMagic=0x48434452`（ASCII "HCDR"）+ 一个 `hidden` 布尔
- `home_drawer_render.h`：`apply()` 把最终尺寸包装器的可空子节点置空；`finder()`
  在 Finder 持有者入口直接分支到系统自带的空尺寸分配器
- `home_drawer_feature_resolver.h`：`resolve()` 用连续十几条 ARM64 指令的固定编码
  唯一确定目标函数（不写死 RVA），例如 `b[i+1]!=0xa9460740u`、`b[i+2]!=0x91004000u`

**建议的下一步**：

1. 在 x86_64 主机上用 `./build_in_docker.sh --native` 确认能正常编 C++
2. 从 `libapp.so` 定位绘制抽屉标题的 Dart 函数。项目里有
   `home_layout_elf_targets.h` 和 `tweaks/symtab.cpp`，符号表可用，但 Dart 函数
   不一定有可读名字
3. 仿照 `home_drawer_render.h` 的 `finder()`，让标题栏的尺寸包装器走空尺寸分配器，
   或者把高度置 0
4. 在 `home_drawer_feature_resolver.h` 的 `Plan` 里加一组定位字段

**难度评估**：这是数天量级的逆向工作，且每次桌面大版本升级都可能失效。诚实地说，
性价比不高。如果只是不想要那个标题，让它在视觉上消失（高度归零）比真正移除控件
更容易实现。

**已知的坑**：我在 `libapp.so` 里搜「全部应用」「应用」「设置」等中文全部为 0，
说明 Dart AOT 的字符串不是明文存放。定位函数不能靠搜字符串。

### 2. 触感回归（我改坏了，已修复但待验证）

**现象**：用户报告「最近任务列表里划动卡片震动没了」。

**原因**：我把 `HomeRecentsHapticEndpointOS4.receive` 改成读缓存，但首次调用时
缓存为空，直接返回 `unavailable`，导致第一次（乃至前几次）滑动没有震动。

**已做的修复**：新增 `prime()`，在 `HomeDockWindow.init()` 时预热缓存：

```kotlin
runCatching {
    HomeRecentsHapticEndpointOS4
        .prime(service?.getObjectFieldAs<android.content.Context>("mContext"))
}.onFailure { glassClient.record("haptic prime failed=${it.javaClass.simpleName}") }
```

Binder 路径仍然只读缓存，不做同步查询 —— 这是刻意保持的，否则又会引入冷启动。

**待验证**：修复已编译进 `HyperCeiler-2.10.166-20261109-release.apk` 并安装，但
桌面进程是安装前启动的，hook 未重新注入。需要执行：

```sh
adb shell am force-stop com.miui.home
```

桌面会自动重启。然后打开最近任务滑卡片确认震动恢复。

**若仍未恢复**，检查 `HomeRecentsHapticEndpointOS4` 里 `installObserverIfNeeded` 的
`context.getContentResolver().registerContentObserver(...)` 是否在 system_server
里成功。这段代码运行在宿主进程，context 来自
`ContextUtils.getContextNoError(ContextUtils.FlAG_ONLY_ANDROID)`。可以临时加日志确认。

### 3. 待用户确认的功能面

用户说 Dock 设置他不用，不清楚是否正常。桌面文件夹布局确认仍正常。

建议验证清单：

- 改一个 Dock 相关设置（如高度），看是否立即生效
- 桌面手势（快速切换、额外手势）是否正常
- 最近任务卡片滑动震动（见上一节）

## 重要背景与约束

### 关于「配置稳定」的前提

用户明确说过：「我自己机器配置已经稳定，不需要一直变化，改一遍」。

这是所有改动的前提。原来那套高频轮询的设计意图是「改了设置立刻生效」，代价是
模块进程反复冷启动。在当前假设下（设置很少改），把它降为变更驱动是正确的取舍。

如果将来用户需要频繁调参，可能要把 `IDLE_WAIT_MS` 调小，但要知道那会重新引入
周期性唤醒。

### 关于 worktree 与并发

这次工作全程在主工作区 `/home/hjkcai/Projects/HyperCeiler` 进行，没有用 worktree。
用户环境中可能有其他 agent 并行运行，不要执行 `git stash`。

### 工作区里有一处非本次改动

`library/libhook/.../ChangeBrowserForAIEngine.kt` 有 131 行改动，是用户此前就留在
工作区的，与本次任务无关。它已被一起提交，因为用户要求「提交当前的代码修改」。

### 设备信息

- 序列号：`localhost:5555`（无线调试，偶尔会掉线，重连即可）
- 型号：25098PN5AC
- 系统：HyperOS V816 / OS4.0.5.0.XBLCNXM
- 桌面版本：需要通过 `dumpsys package com.miui.home` 查
- 已安装两个包：官方 `com.sevtinge.hyperceiler`、自编译 `com.hjkcai.HyperCeiler`

## 验证手法备忘

### 确认耗电循环是否还在

```sh
adb -s localhost:5555 logcat -b events -c
# 等 60 秒
adb -s localhost:5555 logcat -b events -d -t 3000 \
  | grep -E "am_proc_start|am_proc_died" | grep -i hyperceiler
```

### 确认进程是否真的空闲

```sh
P=$(adb shell pidof com.hjkcai.HyperCeiler)
# 每隔 10 秒读一次，utime/stime 不变才算空闲
adb shell "cat /proc/$P/stat | awk '{print \$14, \$15}'"
```

### 查看模块是否被 LSPosed 激活

```sh
adb logcat -d | grep -E "HyperCeiler\.(HomeLayout|DockNative|QuickSystemUI)"
```

有输出说明 hook 在跑。

### 抓 Flutter 界面的语义树

```sh
adb shell uiautomator dump /sdcard/ui.xml
adb pull /sdcard/ui.xml
```

Flutter 界面会表现为扁平的 `android.view.View` 树，文本在 `content-desc` 里。

## 关键文件索引

| 路径 | 说明 |
| --- | --- |
| `docs/BUILD_IN_DOCKER.md` | 构建方式、私有依赖原理、签名 |
| `build_in_docker.sh` | 构建入口 |
| `docker/android-builder.Dockerfile` | 构建镜像定义 |
| `tools/fetch_github_packages.py` | 私有依赖抓取 |
| `app/src/main/cpp/targets/home/` | Dart AOT hook 体系（抽屉标题任务在这里） |
| `library/libhook/.../provider/SharedPrefsProvider.java` | Provider 实现 |
| `library/libhook/.../rules/home/dock/HomeLayoutNativeEndpointOS4.java` | 轮询改通知驱动 |
| `library/libhook/.../rules/home/recent/HomeRecentsHapticEndpointOS4.java` | 触感缓存与预热 |
| `library/libhook/.../rules/home/dock/HomeDockWindow.kt` | sweep 与几何门控 |

## 提交历史对照

本次工作基于上游 `a41e309e6`（`fix(libhook): gate launcher recovery hook on prefs
retry state`）。远端 `origin/main` 在 `293cbc08d`（`docs: add Japanese README`），
是纯文档提交，可以直接快进。
