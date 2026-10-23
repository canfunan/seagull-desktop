# 参考仓库索引

本项目多窗口 / 镜像 / 触摸技术的来源。两个仓库都是本人的，留档在 `reference/` 便于离线溯源。

---

## 1. carlink-desktop（Kotlin）

- 仓库：https://github.com/caogenfunan123/carlink-desktop
- 留档：`reference/carlink-desktop/`
- 定位：真机验证过的车机桌面原型，含完整 root 通道与镜像窗口实现

### 已留档的关键源文件

| 文件 | 为什么重要 |
|---|---|
| `src/MirrorSlot.kt` | **最有价值的一个文件**。完整的 `MediaProjection.createVirtualDisplay` + 触摸转发实现，本项目的 `MirrorSlot.java` 就是它移植过来的 |
| `src/RootAuth.kt` | root 通道：`launchOnDisplay` 双路径（API 优先、root `am` 兜底）、`allowProjectMedia`、`grantAll` |
| `src/PipProjectionService.kt` | 投屏前台服务的正确写法（`foregroundServiceType=mediaProjection` + 运行标志轮询） |
| `src/DesktopService.kt` | 常驻服务与生命周期管理 |
| `src/CarMediaListenerService.kt` | 通知监听取媒体信息 |
| `src/WFreeHook.kt` | 窗口免限制 hook（LSPosed） |
| `src/AppRepository.kt` | 应用清单枚举与缓存 |
| `src/CarLinkState.kt` | 状态中心 |
| `src/CarLinkSettings.kt` | 设置持久化 |
| `src/MainActivity.kt` | 主界面装配 |

### 已留档的文档

| 文件 | 内容 |
|---|---|
| `docs/ARCHITECTURE.md` | 原项目架构 |
| `docs/PIP-MIRROR.md` | **镜像窗口专项设计**，最有参考价值 |
| `docs/REVERSE-AND-PLAN.md` | 逆向与方案推演 |
| `docs/RETRO.md` | 五轮真机反馈复盘（B1~B11 修复清单） |

### 提取的核心技术

```
1. 用 MediaProjection.createVirtualDisplay 而非 DisplayManager.createVirtualDisplay
2. root am start --display <id> -f 0x18800000 -n <pkg>/<act> 搬应用
3. input -d <id> tap/swipe 注入触摸
4. am force-stop 必须先执行，否则投递到主屏旧实例
5. 双路径：先试 API，失败退 root
```

**本项目对应实现**：`app/src/com/seagull/carlauncher/MirrorSlot.java`、
`RootOps.java`、`PipProjectionService.java`（代码注释里标注了移植来源）

---

## 2. carplay-reverse-engineering

- 仓库：https://github.com/caogenfunan123/carplay-reverse-engineering
- 留档：`reference/carplay-re/`
- 定位：车机 PiP / 触摸转发的逆向分析集（17 个版本 APK + 反编译 + 分析报告）
- 原始体积 355 MB（主要是 APK），留档只保留报告与源码，229 KB

### 已留档

| 文件 | 内容 |
|---|---|
| `reports/main-report.txt` | 主分析报告（72819 字符），含 PiP 三层机制 |
| `reports/pip-touch.txt` | PiP 触摸专项（15505 字符） |
| `sources/xphook_integrated.java` | Xposed hook 集成实现 |
| `sources/wangyihook_HookEntry_integrated.java` | 网易云 hook 入口 |
| `sources/netease-pip-adapter/` | 完整 Android 工程：网易云 PiP 适配器（Xposed 模块） |

### 提取的核心技术

**PiP 三层机制**：

| 层 | 机制 |
|---|---|
| 1 | `AmapPipHook` —— hook `com.android.wm.shell.pip`，改 `TARGET_SIZE_PERCENT = 0.6f` 控制 PiP 窗口尺寸 |
| 2 | 网易云 `setLaunchDisplayId` 注入 —— 用 `ActivityOptions.makeBasic()` 设目标 display |
| 3 | `TaskMover` —— `ActivityTaskManager.getService().getTasks(200,false,false,-1)` → `getAllRootTaskInfosOnDisplay(displayId)` → `moveTaskToRootTask(taskId, rootId, true)` |

**锚点 Activity 技巧**：
```
PipAnchorActivity: excludeFromRecents=true, taskAffinity="com.leting.pip.anchor"
```

**已知失败模式**（报告里明确记录）：
> 音乐类应用点击后调 startActivity 跳回主屏

**这些 hook 方案需要 LSPosed，本项目不用** —— 但 `TaskMover` 的
`getAllRootTaskInfosOnDisplay` / `moveTaskToRootTask` 思路对理解系统任务栈归属有帮助。

---

## 3. SmartDock（axel358/smartdock，Kotlin，GPL-3.0-only）

> 无 root 桌面模式/任务栏 Dock。对双画布主线（VD + su）**无直接可搬代码**，
> 但验证了几条无 root 路线的真实形态，留档备用。
> 浅克隆留档：`/tmp/opencode/refs/smartdock`（minSdk 30，DockService.kt 2587 行是核心）。

**架构要点（一手确认，标注 file:line）**

1. **一个 AccessibilityService 身兼三职**（manifest BIND_ACCESSIBILITY_SERVICE）：
   `TYPE_WINDOWS_CHANGED`（WINDOWS_CHANGE_ADDED/REMOVED）触发刷新运行任务列表
   （`services/DockService.kt:513`）+ SYSTEM_ALERT_WINDOW 画 Dock + 自定义 toast。
   任务栈数据本身来自 Shizuku，无障碍只提供"何时刷新"的事件。
2. **Shizuku 直连 IActivityManager**（`wrappers/ActivityManagerWrapper.kt`）：
   `ShizukuBinderWrapper(SystemServiceHelper.getSystemService("activity"))`
   → `IActivityManager.Stub.asInterface` → `getTasks / resizeTask / removeTask`。
   失败回落框架 `ActivityManager.getRunningTasks`（现代系统只返回自己的 task，形同虚设）。
3. **moveTaskToFront 是公开 API**（`services/DockService.kt:456`）：
   框架 `ActivityManager.moveTaskToFront(taskId, 0)` + `REORDER_TASKS` 权限即可切前台，无 root。
4. **Freeform 启动**（`utils/AppUtils.kt:360`）：
   `ActivityOptions.makeBasic()` + 反射 `setLaunchWindowingMode(FREEFORM)` +
   `launchBounds` + `launchDisplayId`。代码里没有任何 `enable_freeform_support`
   开关写入 —— 默认系统已开 freeform，HyperOS 阉割场景无解。
5. **窗口吸附 = 纯矩形数学**（`AppUtils.kt:320` makeLaunchBounds）：
   tiled-left/tiled-bottom 等 mode + dockHeight + statusBarHeight 算 Rect；
   Dock 避让靠 auto_pin/auto_unpin（全屏应用启动自动收起 Dock，`DockService.kt:486`）。
6. **第二屏 metrics 的坑他们也没绕过**：`DeviceUtils.kt:233` 自己标了
   FIXME "This always returns the metrics of the default display"。
   印证本项目 `dpiFit` 读真实 display metrics 的做法。

**对本项目的判断**

| 候选 | 结论 |
|---|---|
| freeform 窗口方案 | 弃。改窗口形态但捕获不了任意应用画面；HyperOS 阉割。与 VD 双画布路线正交 |
| TYPE_WINDOWS_CHANGED 事件驱动 StackScan 刷新 | **候选优化**。现靠 5s su dumpsys 轮询，事件可降延迟省调用。⚠️ VD 内窗口是否触发该事件需真机验证，成本低值得试 |
| Shizuku IActivityManager 替代 su | 备用方案记档。用户真机 KernelSU 已有 root，且 Shizuku 要额外装 app + adb 激活，运维更重。未来遇无 root 车机再启用 |
| moveTaskToFront 公开 API | 备用。relaunchIfAbsent 现走重拉；"task 活着但不在前台"场景可先试 moveTaskToFront 再重拉，边际收益小 |
| 任务过滤黑名单（systemui/installer/launcher 自身） | 已有同类逻辑，不动 |

---

## 4. 其它参考

| 项目 | 说明 |
|---|---|
| [dw2lam/openlauncher](https://github.com/dw2lam/openlauncher) | Kotlin / MIT，164★。窗口管理功能为零，只有组件 + 媒体 + 传感器，可参考其组件系统与设置页组织方式 |

---

## 5. 本项目与参考仓库的关系

```
carlink-desktop          →   L3 虚拟屏 + root 通道 + 触摸转发（技术来源）
carplay-reverse-eng      →   PiP 机制与任务栈搬运（理解系统行为）
smartdock                →   无 root 任务栈路线（Shizuku/无障碍事件）留档，主线不采用
openlauncher             →   组件系统与设置页组织（思路参考）
海鸥桌面（目标）          →   功能结构与 UI 文案（对齐基准，不含其代码）
```

本项目**不包含**上述任何仓库的代码副本作为依赖；
移植的片段（如 `MirrorSlot`）已在源文件注释中标注来源。
`reference/` 下是留档材料，仅供阅读溯源，不参与构建。
