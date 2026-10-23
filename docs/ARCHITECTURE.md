# 架构

## 分层

```
┌─────────────────────────────────────────────────────────┐
│  UI 层（Activity / View）                                │
│  HomeActivity · SettingsHubActivity · 各分区设置页         │
│  DesktopView · FolderActivity · SearchActivity           │
│  GardenActivity · ThemeActivity · LayoutModeActivity ...  │
└────────────────────────┬────────────────────────────────┘
                         │ 只读写 LauncherModel
┌────────────────────────▼────────────────────────────────┐
│  数据层                                                   │
│  LauncherModel —— 全量配置 + 应用清单的单一事实源            │
│  持久化：SharedPreferences(HomeActivity.PREFS, json_layout)│
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│  能力层                                                   │
│  Caps      能力探测与分档（L0~L3）                         │
│  SysOps    系统操作（亮度/音量/网络/时间/强停/CPU/温度）      │
│  RootOps   root 通道（搬屏 / 授予权限 / 安装）              │
│  Skin      运行时配色（全局唯一取色口）                      │
│  Wallpaper 壁纸导入 / 解码 / 亮度判断                      │
│  Theme     内置配色表                                       │
└────────────────────────┬────────────────────────────────┘
                          │
┌────────────────────────▼────────────────────────────────┐
│  数据获取层（联网 + 系统会话，各自带定时器）                   │
│  Weather  天气（Open-Meteo，20 分钟）                      │
│  Lyrics   歌词（媒体会话，一秒一跳）· Lrc 解析（纯 Java）    │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│  增强服务层（可选，缺权限自动降级）                          │
│  MirrorSlot          虚拟屏镜像槽（L3）+ TouchForward 转发  │
│  PipProjectionService 投屏前台服务（mediaProjection 类型）  │
│  BallService         小白点悬浮球（L1）                    │
│  MediaListenerService 通知监听（歌词）                     │
│  TaskEngine          自动化任务引擎                        │
│  TaskMover           窗口搬运（root，按序试 am 子命令）      │
└─────────────────────────────────────────────────────────┘
```

## 模块职责

### 数据层

| 文件 | 职责 |
|---|---|
| `LauncherModel.java` | 应用清单枚举、布局/Dock/快捷栏/菜园/小白点/外观/屏幕/岛屿/天气/歌词/窗口/触摸/任务全部配置的读写与持久化。`load()` 开头先 `reset()`，否则列表字段会越读越多 |
| `Theme.java` | 14 套内置配色 + 自定义主题色解析 |
| `Skin.java` | 运行时取色唯一入口：`Skin.c(R.color.x)` / `bar()` / `apply(model)` / `isLight` / `mix` |
| `Wallpaper.java` | 壁纸选择（系统相册）、图片解码（`inSampleSize=16`）、亮度抽样判断 |
| `Lrc.java` | LRC 解析与「当前该显示哪一行」，零 Android 依赖，可 JVM 自检 |

**约定**：任何 UI 想读或改配置，都必须经 `LauncherModel`。
不要新建 SharedPreferences 文件，不要在自己的类里缓存配置副本。

### 能力层

| 文件 | 职责 |
|---|---|
| `Caps.java` | 探测 root / 悬浮窗 / 虚拟屏 / 通知使用权，输出档位与体检报告 |
| `SysOps.java` | 系统操作统一入口，每项返回 `R{ok,msg}`，失败不抛异常 |
| `RootOps.java` | `su` 通道：`grantAll` / `allowProjectMedia` / `launchOnDisplay` / `injectTouch` / `readDisplays` / `findTaskId` |
| `TouchForward.java` | 触摸转发状态机（点/滑/长按、跟手、防误滑、串行注入、环形日志） |
| `TaskMover.java` | 窗口搬运：`dumpsys` 找 `taskId`，按序试三条 `am` 搬运命令并回传每条真实输出 |
| `Weather.java` | 天气取数 + 20 分钟定时器；`Lyrics.java` 负责歌词取数与一跳一拍 |

`SysOps` 的返回约定：

```java
public static final class R {
    public final boolean ok;
    public final String msg;
}
```

调用方必须检查 `ok`，失败时给用户可读提示，不能静默吞掉。

### 增强服务层

全部遵循「**有则增强，无则隐藏**」：

| 服务 | 缺权限时的行为 |
|---|---|
| `MirrorSlot` | 无 root → 建屏成功但搬不动应用 → 退回悬浮镜像卡片（L1） |
| `PipProjectionService` | 无截屏授权 → 不启动，UI 隐藏相关入口 |
| `BallService` | 无悬浮窗权限 → 不启动，设置页显示「去授权」 |
| `MediaListenerService` | 无通知使用权 → 不启动，歌词格显示提示 |
| `TaskEngine` | 任务动作不可用时，该项在编辑界面置灰并说明原因 |
| `TaskMover` | 无 root → 入口显示「这台机器没有 root」，不执行 |

## 关键流程

### 启动

```
BootReceiver (BOOT_COMPLETED)
  └─ 读 autoHome → startActivity(HomeActivity)

HomeActivity.onCreate
  ├─ prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
  ├─ model = new LauncherModel(this)      ← loadApps() + load()
  ├─ selfReport()                          ← 打 SeagullDiag 日志
  ├─ setContentView(buildUi())             ← 顶栏 + DesktopView + 底栏 + 海鸥岛
  └─ desktop.refresh()

HomeActivity.onResume
  ├─ model.load() + skinSignature() 比对   ← 主题/壁纸/字号变了才整屏重画
  ├─ Weather.arm(this)                     ← 20 分钟一次
  ├─ Lyrics.start(this)                    ← 一秒一跳
  ├─ TaskEngine.fireDesktop(this) / arm(this)
  └─ island.bind(model)
```

### 换肤与字号（为什么所有 Activity 都继承 BaseActivity）

```
attachBaseContext
  └─ 改 Configuration.fontScale = LauncherModel.fontScaleOf(ctx)/100f   ← 只做一次

onCreate
  └─ Skin.apply(model)                    ← 按主题/日夜把 R.color.* 重解析成实际色值
```

新建 Activity 一律继承 `BaseActivity`，不要继承 `Activity`：
字号只在 `attachBaseContext` 里生效一次，晚于它设置的 `sp` 不会跟着变；
配色只在 `Skin.apply()` 之后才有效，所以取色一律走 `Skin.c()`。

### 桌面渲染

```
DesktopView.refresh()
  ├─ renderWidgets(m)    组件条（按 m.widgets 与 m.mode 决定可见性）
  ├─ 重建 grid           m.mode == DENSE ? 6 列 : 5 列
  │   └─ homeKeys() → folderOf(k) → appView / folderView
  └─ 重建 dockBar        按 m.dock 列表
```

### 拖拽压合

```
长按图标（需 editMode）
  └─ startDrag(view, key)
       └─ onTouchEvent(ACTION_MOVE) → hitTest(rawX, rawY) 找落点
            └─ ACTION_UP
                 ├─ 落点是普通图标 → model.mergeInto(dst, src)
                 └─ 落点是文件夹 → 把 src 加进该文件夹
```

### 一秒一跳：歌词 / 天气

```
Lyrics.TICK（1 秒）
  ├─ MediaSessionManager.getActiveSessions()  ← 当前歌 / 播放状态（读别的应用无需权限）
  ├─ 歌变了 → lookup()：本地 lrc / 酷狗在线 / 会话 extras（三选一，命中缓存就不联网）
  ├─ Lrc.indexAt(pos + lyricOffsetMs)        ← 对时偏移只在这里生效一次
  └─ push() → 各 Sink（组件条 5、菜园、野蛮岛）+ 状态栏通知（同句不重复发）

Weather.TICK（20 分钟）
  └─ Open-Meteo：地名查询（手动城市）/ 定位坐标 → 当前天气 + 未来 3 天 → 存 model
```

两条链路的展示方都注册成 `Lyrics.Sink`，只改文字不重建视图。

### L3 镜像（root 增强）

```
MirrorActivity
  ├─ 请求截屏授权（REQ_CONSENT）
  ├─ onActivityResult RESULT_OK
  │    └─ 启动 PipProjectionService（前台服务，mediaProjection 类型）
  ├─ getMediaProjection → MirrorSlot.deploy(mp, pkg, surface, w, h, dpi)
  │    ├─ mp.createVirtualDisplay(...)      ← 建屏
  │    └─ RootOps.launchOnDisplay(displayId, pkgString)
  │         ├─ am force-stop <pkg>          ← 必须先杀，否则投递到主屏旧实例
  │         └─ am start --display <id> -f 0x18800000 -n <comp>
  └─ 触摸：MirrorSlot.onTouch(e) → RootOps.injectTouch(displayId, ...)
```

## 持久化格式

单个 SharedPreferences 文件，主键 `json_layout`，值为 JSON：

```json
{
  "v": 2,
  "mode": "GRID", "labels": true,
  "pinned": ["com.x/.Main"], "dock": ["com.y/.Main"],
  "widgets": [0,1,2,3], "folders": [{"name":"工具","keys":["a","b"]}],
  "layoutPreset": 0, "stripAlpha": 100,
  "dockPos": "BOTTOM", "dockCount": 5, "dockWidth": 76, "dockGap": 8,
  "dockIcon": 48, "dockAlpha": 100, "dockAutoHide": false, "dockFixed": true,
  "dockClock": true, "dockWin": {"0": {"pkg": "", "slot": 1}},
  "quickbars": {"1": [{"k":"@fn:volume_up","l":"音量 +"}], "2": [...]},
  "garden": true, "gardenDim": 60, "gardenStyle": "CENTER_CLOCK",
  "ball": false, "ballX": 40, "ballY": -1, "ballSize": 60, "ballAlpha": 90,
  "theme": "leaf_shadow", "accent": "#8CC26A", "dayNight": "DARK",
  "wallDay": "", "wallNight": "", "wallDim": 40, "fontScale": 100,
  "wallLib": [{"n":"壁纸1","p":"/sdcard/...","u":true}],
  "marginH": 12, "marginV": 12, "gap": 6,
  "portMarginH": 6, "portMarginV": 6, "portGap": 4,
  "orientation": "AUTO", "keepOn": true, "hideBars": false,
  "infoBar": true, "autoTime": false,
  "island": false, "islandY": 0, "islandW": 320, "islandLyric": false,
  "citySource": "MANUAL", "city": "杭州", "cityAt": 0, "cityAuto": true,
  "wSummary": "多云 26°", "wForecast": "明天 多云 20~28°", "wError": "",
  "wLat": 30.29, "wLon": 120.16,
  "lyricSource": "PLAYER", "lyricLines": 0, "lyricSize": 100, "lyricOffset": 0,
  "lyricBar": false, "lyricNoti": false, "lyricBt": true, "mediaLines": 3,
  "lyricHidden": ["周杰伦|晴天"],
  "perf": "MID", "compat": false, "bgKeep": 1,
  "appScale": [{"p":"com.x","s":80}],
  "touchTh": 10, "longPress": 500, "useA11y": true, "useRoot": true,
  "touchFollow": true,
  "tasks": [{"n":"开机开导航","tr":"SYSTEM_BOOT","ac":"OPEN_PIP",
             "p":"com.autonavi.minimap","d":5000,"at":-1,"e":true}],
  "autoHome": true
}
```

```

**兼容策略**：新增字段一律带默认值；枚举用 `optEnum()` 读取，值不认识就回落默认，
旧存档永远不会因为缺字段或脏数据而抛异常。

`quickbars` 按桌面 `Mode` 分组存（`"1"`=GRID、`"2"`=DENSE…），读不到时回落旧的
`quickbar` 单份字段，所以旧存档升级后快捷栏不会空。

## 命名与位置约定

| 概念 | 存在哪 |
|---|---|
| 应用 key | `"pkg/cls"`，纯包名（如通过 intent 建的应用）用 `"pkg"` |
| 文件夹占位 | `"@folder:<名字>:<第一个 key>"` |
| 快捷栏功能格 | `"@fn:<功能名>"`，功能名取 `QuickBar.FN_KEYS` |
| 歌词隐藏表 | `"歌手|歌名"`（小写） |
| 壁纸/歌词缓存 | `filesDir/` 下按名字或 `hashCode()` 命名 |

改字段名的规矩：**加字段随便加，删字段要留回落**。
存档是用户设备上的既有数据，删掉旧 key 而不给默认值，升级后桌面会空白。
