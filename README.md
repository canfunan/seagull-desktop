# 海鸥桌面 · Seagull Desktop

> 公开版多窗口车机桌面。对标 **海鸥桌面**（`cn.wayecai.launcher` v1.7.3）的功能结构，
> **去掉账号 / 会员 / 云端体系**，做成一个装上就能用的普通桌面。

- 包名：`com.seagull.carlauncher`
- 签名：`CN=SeagullCarLauncher`（自签名，非平台签名）
- 最低版本：Android 10 (API 29)，targetSdk 33
- **不需要 root 即可安装使用**；root / 无障碍 / 截屏授权是**可选增强层**，缺失时自动隐藏对应入口

---

## 目录结构

```
seagull-desktop/
├── README.md                  # 本文件
├── FEATURE-MATRIX.md          # 功能对齐清单（205 条，逐条状态 + 可行性分类）
├── TODO.md                    # 后续开发清单（P0/P1/P2 + 完成定义）
├── app/                       # 应用源码（Java，无 Gradle，自建构建脚本）
│   ├── src/com/seagull/carlauncher/
│   ├── res/
│   ├── AndroidManifest.xml
│   └── build.sh               # 7 步无 Gradle 构建流水线
├── docs/
│   ├── AI-GUIDE.md            # AI 开发指南（怎么改、怎么验证、怎么排障）
│   ├── ARCHITECTURE.md        # 架构与模块职责（先读这个）
│   ├── DEVLOG.md              # 批次 A~I 的目标 / 改动 / 复盘
│   ├── TECHNIQUES.md          # 关键技术：L0~L3 窗口档位、MediaProjection、触摸注入
│   ├── ORIGINAL-ANALYSIS.md   # 海鸥桌面逆向分析结论
│   ├── REFERENCE-REPOS.md     # 参考仓库索引与可复用点
│   └── DEVICE-NOTES.md        # 真机环境笔记（Redmi K60 / HyperOS / KernelSU）
└── reference/                 # 参考资料（原样留档，便于溯源）
    ├── carlink-desktop/       # 参考仓库 1：关键 Kotlin 源码 + 全部文档
    ├── carplay-re/            # 参考仓库 2：分析报告 + PiP 适配器源码
    └── original-yecai/        # 目标应用：1173 条 UI 文案 + 9 个业务类反编译源码
```

---

## 快速开始

### 构建（本地）

```bash
cd app
bash build.sh
# 产物：out/SeagullLauncher.apk
```

构建链路（无 Gradle）：`aapt2 链接资源 → 生成 R.java → javac → d8 → 组包 → zipalign → apksigner`

依赖（Termux / 类 Unix 环境）：`aapt2`(arm64)、JDK 17+、`d8`(R8)、`apksigner`、`android.jar`(API 33)
路径可用环境变量覆盖：`SEAGULL_SDK` / `SEAGULL_ANDROID_JAR` / `SEAGULL_JAVA_HOME`

### 构建（CI，推 main 自动跑）

`.github/workflows/build-apk.yml`：JDK 17 + cmdline-tools + `platforms;android-33` +
`build-tools;34.0.0`，调同一个 `app/build.sh`，产物作为 artifact `SeagullLauncher-apk` 上传。
推 main 即可触发，也可在 Actions 页手动 `workflow_dispatch`。

> 开发环境里**不出本地包**，用 `javac` 类型检查兜住编译错误，APK 一律由 CI 产出。
> 详见 `docs/AI-GUIDE.md` 第 6 节。

### 安装

```bash
# 先落到 /data/local/tmp 再装（直接装外部存储的路径常被拒）
cp out/SeagullLauncher.apk /data/local/tmp/
pm install -r /data/local/tmp/SeagullLauncher.apk
```

### 设为默认桌面

设置 → 应用 → 默认应用 → 桌面 → 选「海鸥桌面」
（或桌面内「设置 → 设为默认桌面」跳转）

---

## 设计原则

1. **公开优先**：核心功能只用标准公开 API。任何需要 root / 平台签名 / 特殊权限的能力都做成
   「有则增强，无则隐藏」，绝不因为缺权限而崩溃或拒绝启动。
2. **单一事实源**：所有配置（布局 / Dock / 快捷栏 / 菜园 / 外观 / 天气 / 歌词 / 任务）
   收在 `LauncherModel` 一处，落在同一个 SharedPreferences 文件（`HomeActivity.PREFS`，主键 `json_layout`）。
   UI 只读写这一处，禁止各自开 prefs 文件。
3. **无账号**：不做登录、不做会员、不做云端存档、不做分享码。
   备份/恢复只走本地文件导入导出。
4. **可逆**：每个副作用（服务、监听、悬浮窗、定时器）都要能随模块关闭而撤销。

---

## 当前进度

P0（桌面可用）→ P1（特色功能）→ P2（root 增强层）三个阶段的**代码全部落地**，
共 9 个批次，每批的改动与复盘记在 `docs/DEVLOG.md`。

| 指标 | 数值 |
|---|---|
| 源码规模 | 44 个源文件 + 2 个自检 / 约 10100 行 |
| 功能清单条目 | 205 条（5 组 / 17 分区） |
| 已完成 ✅ | 逐条状态见 `FEATURE-MATRIX.md` |
| 平台不可达 ⛔ | 26 条（附证据，见矩阵第六节 D 类） |

### 分批交付

| 批次 | 内容 | 状态 |
|---|---|---|
| A | 文件夹闭环 + 应用搜索（拼音） | ✅ |
| B | Dock 全配置 + 双槽镜像自动部署 | ✅ |
| C | 快捷栏（功能按钮 + 每布局一份） | ✅ |
| D | 主题与壁纸（14 主题 / 日夜 / 字号 / 遮罩） | ✅ |
| E | 屏幕（边距 / 缝 / 方向 / 横竖屏两套 / 信息栏） | ✅ |
| F | 菜园（三种摆法）+ 小白点悬浮球 | ✅ |
| G | 天气（Open-Meteo）+ 歌词（媒体会话三来源） | ✅ |
| H | 自动化任务 + 海鸥岛 + 关于页（查更新 / 自更新） | ✅ |
| I | root 增强层：触摸转发 / 窗口搬运 / 系统监控 | ✅ |

### 已经跑通的

- **L3 虚拟屏窗口**（root 增强层）：`MediaProjection.createVirtualDisplay` 建屏 +
  root `am start --display <id>` 把任意第三方应用搬上去 + root `input -d <id>` 注入触摸。
  真机验证通过（`DisplayDeviceInfo{"seagull-l3test", 960x540, type VIRTUAL, owner com.seagull.carlauncher}`）。
- **L2 系统画中画**：标准 PiP API，无 root 可用。
- **应用网格 / Dock / 文件夹 / 搜索 / 菜园 / 海鸥岛**：公开 API。
- **主题换肤与全局字号**：`Skin` + `BaseActivity`，一处生效。
- **天气与歌词**：Open-Meteo 公开接口 + `MediaSessionManager`（读别的应用的播放会话无需权限）。
- **系统操作层**：亮度、音量、Wi-Fi、蓝牙、飞行模式、自动校时、强停、清理缓存、CPU/温度/内存读数。

### 代码已写、未在真机验收的部分

容器里做不了这些验证：root `am task` 搬运命令在这台 ROM 上是否被接受、悬浮窗与录屏授权流程、
车机上的实际布局与性能。相关条目在矩阵里标 🟡 并写清原因，不标 ✅。

### 关键结论

`ADD_TRUSTED_DISPLAY` 是 `signature|role` 级权限，**普通 app 无法调 `DisplayManager.createVirtualDisplay`**。
但 `MediaProjection.createVirtualDisplay` 走的是另一条路，**公开 app 也能建虚拟屏**——
这是本项目多窗口方案的基础。详见 `docs/TECHNIQUES.md`。

另外两条实测结论（写代码时绕着走）：

- `PlaybackState.getDuration()` 与 `MediaMetadata.getBundle()` 都是 `@hide`，公开 API 读不到，
  播放时长要从 `METADATA_KEY_DURATION` 取，歌词只能扫 `MediaDescription.getExtras()`。
- 各家 ROM 对 `am task` 的搬运子命令裁得不一致，所以 `TaskMover` 按顺序试多条并把真实返回摊在设置页，
  而不是赌一个「通用写法」。

## 相关仓库

| 仓库 | 用途 |
|---|---|
| [caogenfunan123/carlink-desktop](https://github.com/caogenfunan123/carlink-desktop) | 核心窗口/镜像/root 技术来源（Kotlin） |
| [caogenfunan123/carplay-reverse-engineering](https://github.com/caogenfunan123/carplay-reverse-engineering) | 车机 PiP / 触摸转发逆向分析 |

两个仓库的关键源码与分析报告已留档在 `reference/`，便于离线溯源。

---

## 许可

本项目源码用于学习与自有设备使用。`reference/` 下材料版权归各自作者。
目标应用「海鸥桌面」为第三方商业软件，本项目**不包含**其任何代码，
仅依据公开可见的 UI 文案与设置结构做功能对齐。
