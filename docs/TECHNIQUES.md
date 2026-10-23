# 关键技术

本文记录本项目已**真机验证**的核心技术，以及验证过程中推翻了哪些错误结论。
所有结论都在 Redmi K60 / HyperOS Android 14 上实测过。

---

## 一、窗口能力分档（L0~L3）

海鸥桌面把窗口能力分四档，本项目沿用这个模型：

| 档位 | 名称 | 机制 | 需要什么 |
|---|---|---|---|
| L0 | 启动卡片 | 普通 `startActivity` | 无 |
| L1 | 悬浮镜像卡片 | 悬浮窗 + 屏幕捕获镜像 | `SYSTEM_ALERT_WINDOW` + 截屏授权 |
| L2 | 系统画中画 | `PictureInPictureParams` | 无（系统 API） |
| L3 | 虚拟屏窗口 | `MediaProjection.createVirtualDisplay` + 搬应用 | root（本项目当前实现） |

实现见 `app/src/com/seagull/carlauncher/Caps.java`。

---

## 二、L3 虚拟屏窗口（核心）

### 2.1 建虚拟屏：必须用 MediaProjection，不能用 DisplayManager

**错误做法（会失败）**：

```java
DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
dm.createVirtualDisplay("name", w, h, dpi, surface, flags);   // ✗ 抛 SecurityException
```

`DisplayManager.createVirtualDisplay` 需要 `ADD_TRUSTED_DISPLAY` 权限，
声明为 `signature|role` —— 普通 app 拿不到，平台签名也不行（除非是 system/root 角色持有者）。

**正确做法**：

```java
MediaProjection mp = mgr.getMediaProjection(resultCode, data);   // 需先走完授权流程
VirtualDisplay vd = mp.createVirtualDisplay(
        "seagull-l3test",        // 名字
        960, 540, 160,           // 宽 高 dpi
        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
        surface,                 // 可为 null（null = 不显示到任何 Surface，应用照跑）
        null,                    // VirtualDisplay.Callback
        handler);
```

这条路**公开 app 就能走**，只需要用户点一次录屏授权。

实测输出：

```
DisplayDeviceInfo{"seagull-l3test", 960 x 540, modeId 1, defaultModeId 1,
  supportedModes [...], state ON, type VIRTUAL, owner com.seagull.carlauncher (uid 10350), ...}
```

### 2.2 Android 14 授权顺序（硬性要求）

顺序错了会抛 `SecurityException: MediaProjection requires a foreground service of type mediaProjection`。

```
1. createScreenCaptureIntent() → startActivityForResult
2. onActivityResult 收到 RESULT_OK
3. 立刻 startForegroundService(前台服务, type=mediaProjection)
4. 服务里完成 startForeground(id, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
5. 才能 getMediaProjection(resultCode, data)
6. 才能 createVirtualDisplay(...)
```

注意：服务和 Manifest 里的 `foregroundServiceType` 必须是 `mediaProjection`。
如果给一个**不做投屏**的服务错误地声明了这个类型，`startForeground` 会直接抛异常 ——
本项目因此把 `WindowService` 的类型留空，投屏服务单独一个 `PipProjectionService`。

### 2.3 把第三方应用搬到虚拟屏

**公开 API 做不到**（`ActivityOptions.setLaunchDisplayId` 对普通 app 无效）。
用 root 走 `am`：

```bash
# 搬屏前必须先杀掉已在主屏运行的实例，否则 am 会静默投递到旧实例
am force-stop <pkg>
am start --display <displayId> -f 0x18800000 -n <pkg>/<activity>
```

- `--display <id>` 指定目标屏
- `-f 0x18800000` = `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK | FLAG_ACTIVITY_CLEAR_TASK` 组合，
  实测必备（候选值：`0x18800000` / `0x18000000` / `0x10104000`）
- `am force-stop` **一次只能带一个包名**

### 2.4 触摸注入

```bash
input -d <displayId> tap <x> <y>
input -d <displayId> swipe <x1> <y1> <x2> <y2> [duration_ms]
```

`-d` 在 Android 14 的 `input` 上可用（帮助文本：`-d: specify the display ID`）。
实测 `input -d 11 tap 480 270` 返回成功，目标应用响应。

公开通道（无 root）：`AccessibilityService.dispatchGesture()`，需用户手动开启无障碍。

### 2.5 解析窗口现在在哪个屏

**踩过的坑**：`dumpsys activity activities` 里的 `Task{...}` 行**没有** `displayId=` 字段。
曾经因为按 `displayId=N` 正则去抓 Task 行，导致误判「搬屏失败」。

**正确解析**：按 `Display #N (activities from top to bottom):` 段头切分，段内后续的
`* Task{...}` 行都归属当前 N。

```
Display #11 (activities from top to bottom):
  * Task{6f01801 #27654 type=standard A=10344:com.android.deskclock}
    ...
```

---

## 三、L2 系统画中画

```java
PictureInPictureParams p = new PictureInPictureParams.Builder()
        .setAspectRatio(new Rational(16, 9))
        .build();
enterPictureInPictureMode(p);
```

Manifest 里 Activity 需要 `android:supportsPictureInPicture="true"`
和 `android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation"`。

`onPictureInPictureModeChanged(boolean, Configuration)` 里切 UI（隐藏桌面，只留窗口内容）。

---

## 四、读取媒体会话（歌词基础）

两条路，权限要求不同：

| 通道 | API | 权限 | 拿到什么 |
|---|---|---|---|
| 媒体会话 | `MediaSessionManager.getActiveSessions()` | 需 `MEDIA_CONTENT_CONTROL`（平台签名）或通知监听权限 | 播放状态、元数据、播放位置 |
| 通知监听 | `NotificationListenerService.onNotificationPosted()` | 用户手动开启「通知使用权」 | 通知标题/文本，多数播放器把歌词放这 |

**实测**：普通 app 直接调 `MediaSessionManager.getActiveSessions()` 抛
`SecurityException: Access to media sessions requires MEDIA_CONTENT_CONTROL or notification listener`。
所以公开版走**通知监听**通道；`MediaSessionManager` 只在已获得通知使用权时才可用。

---

## 五、其它实测结论

### 5.1 SharedPreferences 落盘
- `apply()` 异步，进程被回收时可能没写完 → 首次初始化用 `commit()`
- **两个文件用不同 prefs 名字 = 数据互相看不见**（本项目踩过）

### 5.2 root shell 的挂载命名空间
从 root shell `ls /data/data` 只能看到极少数目录（本例 2 个），
**不是真实内容**。别用它判断应用数据是否存在。
正确做法：让应用自己打日志上报真实路径与文件状态。

### 5.3 权限拒绝的正确处理
`pm install` / `am start` 从普通 shell 调用会报：

```
SecurityException: Permission Denial: runUninstall from pm command asks to run as user -1
You either need MANAGE_USERS or CREATE_USERS permission to: query users
```

→ 必须经由 root（`su -c`）执行。

### 5.4 屏幕与布局
- 手势导航条占底部约 40px，布局要留 inset
- 1080x2400 / density 560（2.625x）下，状态栏 `y=0..90` 是纯黑挖孔区
- 默认配色：地面 `#0b100c` / 面板 `#131a15` / 卡片 `#1b241d` / 叶绿 `#8cc26a` / 文字 `#e8efe9`

### 5.5 构建链
- R8 8.2.2 的 `d8` 有 NPE，换 R8 9.5.20 正常
- 无 Gradle 构建：`aapt2 link → R.java → javac --release 11 → d8 → zip → zipalign → apksigner`
- `JAVA_TOOL_OPTIONS=-Duser.home=$HOME`（沙箱里 `$HOME` 不可写时必需）
