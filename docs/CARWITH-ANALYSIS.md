# 车联助手（com.leting 3.0.0）全量反编译对比报告

> 反编译对象：用户提供的 base.apk（8.5MB，签名 CARLINK，versionCode 22100000 / versionName 3.0.0，
> targetSdk **31**，单 dex 4.4MB，xposed_init 三入口）。
> 方法：jadx 1.5.3 全量反编译（2253 个 java 文件），对反编译失败的 2 个方法
> （`c1.h.a`、`MainActivity.onCreate`）用 `--single-class --show-bad-code` 补齐 smali 级还原。
> 结论基于对全部显示/落屏/触摸/root/hook 相关类的逐行阅读；纯 UI 类已扫过确认与本课题无关。
> 本报告只记录，不改代码（遵嘱"先不动手"）。

---

## 一、一句话结论

**车联助手根本没有"守护进程、TRUSTED 屏、跨进程 Surface"这三样海鸥桌面赖以工作的东西。
它的画中画 = 应用进程内一块 flags=0 的私有虚拟屏 + 自家锚点 Activity 在屏上占住 root task +
root `am stack move-task` 把目标应用搬进去 + `input -d` 注触摸。全程只有 6 条 root 命令。**

海鸥桌面黑屏的最可能差距（按嫌疑排序）：
1. **落屏方式**：海鸥用"直启"（daemon setLaunchDisplayId / `am start --display`），
   Android 14 上第三方应用直启非默认 display 可能被**静默重定向回主屏** → 画布黑。
   车联助手用"锚点+move-task"，**绕开 launch 权限检查**，这也是它"没理由不行"的核心。
2. **Surface 跨进程**：海鸥把应用进程的 SurfaceView surface 通过行协议传给 root 守护进程挂到
   daemon 建的屏上 —— 多出的一整条失败链（时序/挂接失败→黑窗）。
   车联助手的 VD 和 SurfaceView **同进程**，`setSurface` 直接调，零失败面。
3. **自愈缺失**：车联助手在 `MainActivity.onResume` **每次都重新执行锚点+搬任务**，
   任务被系统拉回主屏后一回桌面就被搬回；海鸥的 VdPip 路径只在部署时搬一次。

---

## 二、车联助手画中画完整机制（逐层拆解）

### 2.1 显示载体：窗口内嵌，不是悬浮窗

- 主界面 `MainActivity`（[com/leting/MainActivity.java]）是**普通 Activity**，
  `launchMode=singleTask`、`screenOrientation=landscape`、声明 HOME 类目，taskAffinity=`com.leting.CarUI`。
- 布局分 areaA/areaB/areaC 三个区域，设置里把某区域配成 `"pip"` / `"pip2"` 时，
  `new b1.c(...)` / `new b1.e(...)` 把画中画控件**塞进该区域的 FrameLayout**（onCreate 1748 指令已还原）。
- **没有 TYPE_APPLICATION_OVERLAY 承载画布**；overlay（type=2038）只用于"小白点助手"（c1.w TouchAssistant）。

### 2.2 虚拟屏：应用进程内、flags=0、尺寸跟随窗口（核心差异①②）

`b1/c.java`（PipUI，编译自 PipUI.java）+ `b1/b.java`（SurfaceHolder.Callback）：

```java
// b1/b.surfaceCreated —— 首次建屏（应用进程、普通 uid！）
vd = dm.createVirtualDisplay("gdj", win.getWidth(), win.getHeight(), dpi,
        /*surface*/ c.f3502c, /*flags=0 私有屏*/ 0, null, null);
displayId = vd.getDisplay().getDisplayId();
// 建完立刻落屏：
if (!c1.h.d(context, pkg, displayId))   // 锚点+move-task
    c1.h.b(context, pkg, displayId);    // 直启（降级）

// b1/b.surfaceChanged —— 窗口尺寸变了，屏直接跟着变
vd.resize(w, h, dpi);
vd.setSurface(holder.getSurface());

// b1/b.surfaceDestroyed —— 只摘 surface，不拆屏
vd.setSurface(null);
```

关键点：
- **flags=0（私有屏）**。没有 PUBLIC、没有 TRUSTED、没有任何特权位 —— 车联助手证明了
  落屏问题**不靠屏的 flag 解决，靠锚点+move-task 解决**。
- **VD 尺寸 = SurfaceView 视图尺寸**，`surfaceChanged → resize`。因此：
  ① 永无黑边/拉伸；② 触摸坐标就是窗口本地坐标，**不需要任何换算**。
- Surface 在建屏时直接传入，之后每次 changed 重新 setSurface —— 同进程直调。
- `surfaceDestroyed` 只摘 surface 不释放 VD；VD 的释放只在 `MainActivity.onDestroy`。

### 2.3 落屏：锚点 + am stack move-task（核心差异①，"没理由不行"的答案）

`c1/h.java`（FakeStart.java）`h.d(context, pkg, displayId)`：

```java
// 第 1 步：把【自家】PipAnchorActivity 启动到私有屏上 —— 自家居主，必然成功
Intent i = new Intent(ctx, PipAnchorActivity.class);
i.setFlags(411041792);                       // NEW_TASK|CLEAR_TASK 等
ActivityOptions o = ActivityOptions.makeBasic();
o.setLaunchDisplayId(displayId);
ctx.startActivity(i, o.toBundle());          // → 私有屏上出现一个 root task

// 第 2 步：root shell 一行流：找到目标应用 taskId + 锚点所在 root task id，搬进去
su -c "sleep 0.15; OUT=\"$(am stack list)\"; ...
       TASK=$(...含 PKG 且 taskId=... 的第一条)
       ROOT=$(...含 PipAnchorActivity 的 Stack id...)
       am stack move-task $TASK $ROOT true"
// exit 0 = 搬成功；非 0 = 返回 false，调用方降级 h.b
```

- `PipAnchorActivity`（[com/leting/pip/PipAnchorActivity.java]）：
  `taskAffinity="com.leting.pip.anchor"`（manifest）—— **独立亲和性保证它在新 display 上独立成
  root task**；`excludeFromRecents` + empty 主题 + `setFinishOnTouchOutside(false)`，常驻不闪退。
- **为什么这能绕开 Android 14 的限制**：第三方应用"直启"到别的 display 会撞
  ActivityStarter 的 display 许可检查（静默失败/回退主屏）；而 `move-task` 是把任务
  **挪进一个已存在的 root task**，不经过 launch 权限路径。
- **降级链**：`h.d` 失败 → `h.e → h.b`：
  1. 普通 `startActivity` + `setLaunchDisplayId`（部分 ROM 放行）；
  2. root `am start --display <id> -f 0x10104000 -n <comp>`
     （0x10104000 = NEW_TASK | RESET_TASK_IF_NEEDED | TASK_ON_HOME）。
- **不做 force-stop**：搬的是活任务；`h.d` 前不杀目标应用。

### 2.4 自愈：onResume 每次重新部署（核心差异③）

`MainActivity.onResume`：

```java
b1.c.h(this);   // VD 在且 displayId>0 时重跑 h.d → 搬回
b1.e.h(this);
```

任务哪怕被系统拉回主屏，用户一回桌面就被搬回画布。配合 h.d 幂等（搬成功的任务 id 不变）。

### 2.5 触摸与按键

- PIP1 `b1/d.java` / PIP2 `b1/h.java`（PipTouchDispatcher）：SurfaceView onTouch 记下/抬起：
  - 位移 <10px → `su -c "input -d <displayId> tap x y"`
  - 否则 → `input -d <displayId> swipe x1 y1 x2 y2 <dur(50~2000ms)>`
  - **坐标不换算**（VD 尺寸=视图尺寸）。
- `MainActivity.onKeyDown` BACK：`su -c "input -d <displayId> keyevent 4"` + sleep 200ms + 回 HOME。
- 无障碍服务（MyAccessibilityService）只做 performGlobalAction（返回/主页/最近任务），不注入触摸。

### 2.6 LSPosed 模块（xphook）只做两件事

`com/leting/xposed/xphook.java`（483 行）与海鸥此前逆向的 xphook_integrated 完全一致：
1. **SystemUI**：hook `PipBoundsState/PipBoundsAlgorithm`，把**手机版高德**（com.autonavi.minimap）
   的系统 PiP 尺寸缩放到屏短边 60%；
2. **网易云 IoT**（com.netease.cloudmusic.iot）：H1~H6 六个 startActivity/startActivityForResult
   钩子注入 setLaunchDisplayId（用**当前 Activity 所在 display**，应用粘在画布上）。

xposed_scope（arrays.xml）：com.miui.carlink、com.baidu.carlife.xiaomi、com.samsung.android.carlink、
com.android.systemui、com.autonavi.minimap、com.luna.music 等。xposedminversion=102。

**注意：LSPosed 模块与它的 VD 画中画互不依赖** —— 模块失效只影响"手机高德 PiP 缩放"和
"网易云自动粘屏"，VD 画中画照常工作。

### 2.7 全部 root 命令面（仅 6 处）

| # | 位置 | 命令 | 用途 |
|---|------|------|------|
| 1 | c1/h h.d | `am stack list` + `am stack move-task $TASK $ROOT true` | **落屏（主路）** |
| 2 | c1/h h.b | `am start --display <id> -f 0x10104000 -n <comp>` | 落屏（降级） |
| 3 | b1/d、b1/h | `input -d <id> tap/swipe ...` | 画布触摸 |
| 4 | MainActivity | `input -d <id> keyevent 4` | 画布返回键 |
| 5 | RootSettings | `settings put global/secure ...` | policy_control 沉浸条等系统设置 |
| 6 | （杀后台用 KILL_BACKGROUND_PROCESSES API，非 su） | | |

没有 daemon、没有 app_process（`pip/TaskMover.main()` 存在但**全库无调用点，属废弃代码**）、
没有 injectInputEvent 反射、没有跨进程 Surface 协议。

### 2.8 其余外围（与本课题弱相关，已全部看完）

- `notouch/MirrorDisplay`：MediaProjection 镜像备胎（flag 16=AUTO_MIRROR，把主屏镜像到 SurfaceView，
  再把镜像目标应用启动到 `getDisplays()[1]`），配 MediaService（foregroundServiceType=mediaProjection）
  + SlaveActivity/RequestPermissionActivity 走授权。是"免 root 镜像"另一条产品线，**不是** VD 画中画。
- `Alive`：前台通知保活；`BlackScreenActivity`：双击退出的熄屏页；`TouchService`：空壳保活服务。
- `c1.w` TouchAssistant：TYPE_APPLICATION_OVERLAY 小白点（拖动/自动隐藏/最爱应用/一键清理）。
- `c1.f`：`settings put global policy_control=immersive.navigation=...` 藏导航条 hack。
- `DeepLinkService`/`c1.h.a`：按品牌伪装启动（huawei HiCarSplash / ucar UCAR / samsung carlink kit
  intent），把目标应用以"车机模式"拉起。
- 数据面：百度统计（BaiduMobAd_STAT_ID）在后台回传 —— 商业化痕迹，可不管。

---

## 三、海鸥桌面 vs 车联助手 逐项对比

| 维度 | 车联助手 3.0.0 | 海鸥桌面（批次 V，b73fe4d） | 差距影响 |
|------|----------------|------------------------------|----------|
| VD 创建主体 | 应用进程，普通 uid | root 守护进程 uid 0（PrivClient.create） | 海鸥多一条进程间依赖 |
| VD flags | **0（私有）** | TRUSTED+PUBLIC（root），降级 PUBLIC（本地） | 车联证明 flag 不重要，锚点才重要 |
| Surface 挂接 | 同进程 `vd.setSurface()` | **跨进程行协议**（Parcel+Base64 → RootMain SURFACE） | 海鸥多一条黑屏失败链 |
| 画布尺寸 | =窗口视图尺寸，surfaceChanged resize | 固定（副屏分辨率/1280x720），SurfaceView 拉伸 | 车联无黑边无坐标换算 |
| **落屏** | **锚点 Activity 占 root task + `am stack move-task`** | daemon setLaunchDisplayId 直启 / `am start --display` 直启 | **黑屏头号嫌疑：直启被静默重定向回主屏** |
| 落屏时机 | 不杀进程，搬活任务 | 每次部署 force-stop 后冷启 | 海鸥多一步（车机版可接受） |
| 自愈 | onResume 每次重新锚点+搬 | 仅部署一次（VdPip 路径无自愈） | 被拉回主屏后无人搬回 |
| 触摸注入 | `input -d`（窗口本地坐标） | `input -d`（需 VD↔视图换算） | 等价 |
| 显示载体 | 桌面 Activity 布局内嵌 | TYPE_APPLICATION_OVERLAY 悬浮窗 + 前台服务 | 各有利弊，非黑屏根因 |
| LSPosed 依赖 | 无（模块只管手机高德缩放/网易云粘屏） | VdPip 路径无依赖 | 等价 |
| targetSdk | 31 | 33 | 非决定因素 |
| root 命令数 | 6 | daemon 全家桶（create/surface/resize/release/launch/motion…） | 海鸥强但复杂=复杂失败面 |

---

## 四、海鸥黑屏候选根因（按嫌疑排序，供下一批验证）

1. **直启落屏被静默重定向**：`PrivClient.launch`/`am start --display` 在 Android 14 把高德
   送回 display 0（或拒绝）。批次 V 的探针（screencap 量屏）若报"屏黑"即坐实此条。
   → 对应车联助手的锚点+move-task，见第五节 P1。
2. **跨进程 Surface 挂接失败/时序错**：daemon `doSurface` 收到 blob 但挂接晚于首帧，或
   `attachSurface` 异步队列与 surfaceDestroyed 竞态。探针若报"屏有内容但窗口黑"即坐实此条。
3. **本地降级路被选中**：daemon 未起（root 面板没给授权/守护进程死了）→ 降级本地 PUBLIC 屏
   → 直启更不可能成功。状态行"本地屏"字样可辨认。

---

## 五、移植建议（记录备执行，本批不动代码）

- **P1 锚点+move-task（最高优先）**
  新增 `VdPipAnchorActivity`：`taskAffinity` 独立（如 `com.seagull.carlauncher.pipanchor`）、
  `excludeFromRecents`、透明主题、`setFinishOnTouchOutside(false)`。
  落屏顺序改为：`startActivity(锚点, setLaunchDisplayId)` → root
  `am stack list` 找高德 taskId + 锚点 Stack id → `am stack move-task $TASK $ROOT true` →
  失败再退现路（PrivClient.launch / am start --display）。
- **P2 VD 改应用进程内直建（首选路）**
  `createVirtualDisplay(name, viewW, viewH, dpi, holder.getSurface(), 0, null, null)`，
  surfaceChanged → `resize`+`setSurface`，surfaceDestroyed → `setSurface(null)`。
  daemon 路降级为备胎。这同时消灭第 2、3 号黑屏候选。
- **P3 onResume 自愈**：桌面 onResume（或悬浮窗每次可见）重跑锚点+move-task，幂等。
- **P4 尺寸跟随窗口**：采纳 P2 后，VD 尺寸=视图尺寸，触摸换算可整体删除；
  现有 `setFixedSize` 逻辑移除。
- **P5 可选**：BACK 键 `input -d keyevent 4` 保持现状（车联一致）。

---

## 六、反编译文件索引（关键类 → 反编译产物路径）

| 类 | 路径（/tmp/jx/out/sources/ 下） | 作用 |
|----|------|------|
| PipUI | b1/c.java + b1/b.java | PIP1 宿主 + VD 生命周期 |
| PipUI2 | b1/e.java + b1/f.java | PIP2 同构 |
| PipTouchDispatcher | b1/d.java、b1/h.java | input -d 触摸 |
| FakeStart | c1/h.java（a 未反编译已补） | 锚点+move-task / am start / 品牌伪装启动 |
| PipAnchorActivity | com/leting/pip/PipAnchorActivity.java | 锚点（taskAffinity 独立） |
| TaskMover | com/leting/pip/TaskMover.java | app_process 版搬任务（废弃，无调用点） |
| MainActivity | com/leting/MainActivity.java（onCreate 已补） | 桌面宿主、onResume 自愈、BACK |
| MirrorDisplay | com/leting/notouch/MirrorDisplay.java | MediaProjection 镜像备胎 |
| xphook | com/leting/xposed/xphook.java | SystemUI 缩放 + 网易云粘屏 |
| TouchAssistant | c1/w.java | 小白点 overlay |
| RootSettings | com/leting/root/RootSettings.java | settings put |
| Manifest | /tmp/jx/resout/resources/AndroidManifest.xml | 权限/组件/taskAffinity |

## 七、验收口径（移植后）

1. 部署后 `am stack list`：能看见 `PipAnchorActivity` 所在 Stack 与高德 task 同栈/同 display；
2. 高德回主屏后，回桌面一次 → 自动搬回（onResume 自愈生效）；
3. 画布内点/滑坐标与视觉一致（VD=视图尺寸，无换算）；
4. 全程 logcat 只需 `SeagullVdPip`/`CarWithX-PIP` 对照，无需 LSPosed 日志。
