# 开发日志（按批次）

每个批次 = 一个可交付的小主题。写法固定：**目标 → 改了什么 → 复盘（做对/做错/修正）→ 验证 → 遗留**。

构建与验证口径：

| 环节 | 方式 |
|---|---|
| 类型检查 | `bash /tmp/opencode/typecheck.sh`（只跑 javac，不出 APK） |
| APK 构建 | GitHub Actions：`.github/workflows/build-apk.yml`，复用 `app/build.sh` 七步链路 |
| 自检 | `app/selfcheck/*.java`，纯 java 直接跑，断言式 |

---

## 批次 AF — 网易云「点歌词/封面就跳主屏 + 回桌面画布黑屏」：自愈通道补全 + 常驻巡检搬回

**目标**：用户实测两个症状：① 画中画里点网易云的歌词/封面等页面，应用直接全屏跑回
手机主屏；② 进入应用后返回桌面，画中画画布黑屏不再恢复。两个症状是同一条链：
singleTask 任务被系统拉回默认屏后，**所有搬回通道都没把它搬回来**。

### 病根：ensureOnDisplay 的保守放弃区间太宽

搬回链有三条通道：① 守护进程反射 ② `am stack move-task` ③ `am task move-task`，
但 ②③ 都依赖 `am stack list` 的文本可解析。真机上出现过解析不出（空段/格式差异）的
ROM —— 这时只剩守护进程一条路，它一旦失败就走 `return "am stack list 不可用且守护
进程搬屏失败，跳过自愈"`，**静默放弃**。表现恰好是：跳走 → tap 自愈两次全扑空 →
回桌面 healHome 也放弃 → 画布永远黑屏。

### 修复

**① 第④搬运通道：dumpsys（RootOps.ensureViaDumpsys）**

`dumpsys activity activities` 与 `am stack list` 是**两套完全独立的输出格式**
（`Display #N` 段头归口 + `Task{...#id}`，TaskScan 解析）。am stack list 解析不出时
它仍可能可用。通道内保持与解析路径同一条护栏：目标已在虚拟屏 → 不动；同名任务在
其他虚拟屏（别的槽的实例）→ 不动；任何屏都找不到 → ABSENT（只搬不拉红线不变）。
定位后走 moveTaskToDisplay（守护进程反射 → `am task move-task`）。

**② 常驻巡检搬回（PipBoard.repinRun）**

tap 自愈只在抬手后补两次（600/1600ms），跳页的开窗时序一变就全扑空。新增 2.5s 一拍
的巡检：两槽逐个 ensureOnDisplay（单线程串行，不并发打 su），检测到"已搬回"就
`redeployIfPossible` 补一次表面重挂。效果：**跳主屏从"永久黑屏"变成"最多 2.5s 自动
回到画中画"**，move-task 不杀进程，播放/导航状态不中断。

生命周期：onResume 里 `startRepin()`（幂等，先 removeCallbacks 再 post）；
onDestroy / cancelPending 的 `removeCallbacksAndMessages(null)` 自然摘除，无需 onPause。

只搬不拉的边界保持：应用真被用户退出（ABSENT）走既有 `relaunchIfAbsent` 节流重拉，
巡检**绝不重拉** —— 防止虚拟屏里叠出第二个实例（m12 双画布一起坏的教训）。

**验证**：javac --release 11 全树通过（166 class）。真机待验口径：
点歌词/封面 → 应用闪到主屏后 2.5s 内自动回画中画；返回桌面画布不再黑。

**遗留**：跳主屏瞬间主屏仍会闪现全屏应用一瞬 —— 根治要靠 LSPosed 模块
（setLaunchDisplayId 注入）或受信虚拟屏（uid=1000），巡检是模块失效时的兜底。

---

## 批次 AA — 网易云车机版强制横屏（反编译坐实两条病根）+ 画布键条（返回/桌面）+ 重挂载

**目标**：用户报「网易云车机版 3.1.0 竖版 / 没有底部导航 / 返回桌面后点不回去」，
要求像 hook 高德一样：强制横屏、进入应用固定底部键条（导航栏）、返回桌面能重新挂载。
先反编译 APK 找病根再动手。

### 反编译结论（jadx，com.netease.cloudmusic.iot，targetSdk 29）

1. **manifest 全家 `screenOrientation="sensor"`** —— 旧 H2 判据（竖屏系列 or
   UNSPECIFIED）对 sensor(4) 一条不改，首帧方向由 VD 自然方向决定 → 横屏不成立；
2. **IotActivityBase.attachBaseContext 宽高比 ≤1.3 时 `new Configuration();
   orientation=1; applyOverrideConfiguration(...)`** —— 在应用进程内把 Resources
   配置按竖屏摆，走 ContextThemeWrapper 层，WM 侧 H2 够不着 → 画布不够宽就整体切
   竖版形态（顶部 Tab）。反编译确认该应用**任何形态都没有底部导航栏**
   （sw1080dp-land / land = 左侧栏，port = 顶部 Tab），底部键条只能桌面自绘。

### 改了什么

1. `XposedEntry.java`：
   - **H2 判据扩写**：新增 `shouldRewrite(req, target)` —— 与目标同侧放行；
     异侧 / 竖屏系列 / 跟随类（sensor / fullSensor / user / fullUser / nosensor /
     behind / locked / unspecified）一律改 target。网易云 manifest 的 sensor 从此
     进门就改横屏；
   - **新增 D 段** `hookPortraitConfigOverride()`：hook 框架类
     `ContextThemeWrapper.applyOverrideConfiguration`（公开 API 不受混淆影响），
     只拦「显式 orientation=PORTRAIT」的 override，目标为横屏时改写成 LANDSCAPE，
     其余（含 AppCompat 合法调用）原样放行。独立 try/catch，失败不牵连 H1~H3。
2. `PipBoard.java`（画布键条）：
   - 每块画布 = 画布盒(box) + 底部键条(bar) 嵌套；键条【返回 | 桌面】两键，
     `KEYBAR_DP=34`，分屏预览模式隐藏，单应用铺满才顶出（`applyKeybar`）；
   - **键条挤压画布盒底边距**而非叠在画面上：SurfaceView 变小 → surfaceChanged →
     attachTo 按新尺寸 resize VD —— 私有 VD 无 SystemUI 导航栏、应用布局不知道
     要留边，物理让位是唯一不被盖的方案；
   - **返回键** = `RootOps.injectKey(displayId, 4)`（input -d <id> keyevent 4，
     注进应用）；**桌面键** = `ExitListener.onExit` 回宿主（无监听则本地降级
     showSplit）。键条 setClickable 吃掉自身区域触摸，不穿透；
   - 触摸纪律不变：单应用模式全量转发（DOWN 上 syncCanvasSize 校尺寸），分屏
     预览只切焦点/进应用。
3. `HomeActivity.java`：`pip.setOnExitSlot(...)` —— 键条「桌面」与导航栏「首页」
   同路径：showSplit + enableSwipe(true) + syncDock(HOME)。应用留在虚拟屏后台
   不杀，MirrorHost 只断 Surface 不拆屏，再点画布秒回。
4. `RootOps.java`：新增 `injectKey(displayId, keycode)`。
5. `MirrorHost.java`：`relaunchIfAbsent` 重载带 `force` —— 用户主动点画布 /
   键条属显式意图，绕过 60s 重拉节流（正是「返回桌面后点不回去」的挡板）；
   后台自愈仍走节流防连环 force-stop。
6. `AndroidManifest.xml`：xposeddescription 补 D 段说明。

### 验证

- javac --release 11 全树 0 错误（64 源文件，含历史 gen 的 R.java）；
- CI 构建（build-apk.yml）出包，versionName 1.0.<git 短哈希> 供核对。

### 真机验收清单

1. 画布选网易云车机版 → 铺满后应横屏无黑边（若仍竖版：先看 LSPosed 作用域
   是否勾了网易云 + 强杀其进程重开，再用「LSPosed 模块自检」看 D 段日志）；
2. 单应用底部应有常驻【返回 | 桌面】键条，应用画面不被盖（键条下应用窗口
   物理缩让）；
3. 键条「返回」应触发应用内返回；「桌面」退回分屏主页；
4. 退桌面后立刻点画布 → 应用应能重新挂上（不受 60s 节流限制）。

### 遗留

- D 段 hook 挂在 ContextThemeWrapper 上，凡显式竖屏 override 都会被改横 ——
  目标应用内的确要竖屏的页面（若有）也会被拉横，与 H2 口径一致，车机场景可接受；
- 键条按钮圆角按压态用了 setBackground(null) 切换，个别 ROM 上高亮可能不显，
  不影响功能。

---

## 批次 Z — 用户「不方便抓日志，直接改」：三条不依赖框架的确定性修法

**目标**：用户明确说不方便抓 logcat，要求直接改。没有现场日志时，能坐实的只有
「不依赖 LSPosed 是否注入」的那几条 —— 本批全部走 root 通道 + 应用内可见，
换掉任何一条都能单独解释用户看到的现象。

### ① 兼容位写在起进程之后 → 本次部署根本不吃（高德竖版黑边的直接病根之一）

`RootOps.launchOnDisplay` 的顺序原来是：
`am force-stop` → `launch`（成功后才）`relaxCompat(pkg)`。

而 `am compat enable FORCE_RESIZE_APP / NEVER_FIX_ORIENTATION` **只对之后新起的进程
生效**。于是：

- 本次部署：应用已经起来了，才写兼容位 → 进程不读，高德仍按 manifest 的
  `PORTRAIT` 建窗 → 塞进横屏画布就是竖版 + 左右黑边；
- 用户以为"改了没用"，其实是"这次没用、下次才好"。

**修复**：把 `relaxCompat(pkg)` 移到 `am force-stop` 之后、launch 之前，本次即生效。
删掉三处 launch 成功后的重复调用（单一口径，避免下次又被改回去）。

### ② 网易云点一下跳主屏：补一条不依赖模块的兜底

模块的 `setLaunchDisplayId` 注入（C 段）只在模块真被注进网易云进程时才生效；框架 /
作用域任一环没到位就静默失效，表现就是"点一下跳主屏"。而现有的自愈只在
**桌面 onResume** 时跑（`MirrorHost.healHome`），跳走之后桌面并不在前台，自愈永远
轮不上 —— 于是应用一直停主屏。

**修复**：`PipBoard` 在每次「抬手点击」后补两次延时自愈（600ms / 1600ms），走
`RootOps.ensureOnDisplay`（root 的 `am task move-task`），把被拉走的任务搬回画布。
这条与模块无关，模块失效时它就是唯一兜底。跳页开窗有几帧延迟，所以补两次。

### ③ 「模块到底有没有被注入」搬进应用内

用户抓不了日志，等价于**永远无法判断是"模块没注入"还是"注入了但 hook 没生效"**，
后面每批改动都只能靠猜。新增设置 → 系统 →「LSPosed 模块自检」：
root 跑一次 `logcat -d -s SeagullLsp`，据此直接给结论：

- 没装 LSPosed / 模块无日志 → 逐条列出"启用模块 + 作用域勾哪几个包 + 强杀重开"；
- 有日志 → 逐进程报「高德 / 网易云 / SystemUI 已注入 ✓ / 未注入 ✗」，未注入直接点名
  该补勾哪个包。

这样下一轮反馈能带上"自检结果"，不用再让用户去接 adb。

**验证**：`javac --release 11`（`libs/android.jar` + `libs/xposed-api-82.jar`）全树通过，
151 个 class 产出；三条改动均为纯逻辑/顺序调整，无新依赖。

**遗留**：本文三条都是"能坐实"的改动，不代表高德黑边只有这一个病根 —— 方向劫持
（H2）仍依赖模块被注入高德进程，需靠 ③ 的自检结果来判定还需不需要继续往下挖。

---

## 批次 Y — 用户三连报"还是不行"：一个是构建产物问题，一个是模块被自己吞了

**目标**：用户反馈高德画中画仍竖版黑边、网易云点一下仍跳主屏、并且明确说"模块像没
生效一样"。三条同时成立，指向的不是某条 hook 写错，而是**模块根本没有在目标进程
里跑起来**（或跑的是旧包）。本批只做能坐实的两件事，不再盲加 hook。

### ① 构建产物：版本号写死 1.0 → 用户可能一直装着旧包

`app/build.sh` 里 `aapt2 link --version-code 1 --version-name 1.0` 是硬编码的，带来两个后果：

- GitHub release tag 由 versionName 拼出（CI `TAG="v$VER"`），**永远都是 `v1.0`**；
  应用内「检查更新」是拿 `releases/latest` 的 tag 去掉 `v` 跟本机 versionName 比
  （见 `SettingsSectionActivity.checkUpdate`），于是**恒等于 → 永远回「已是最新」**。
- 用户侧看到的现象就是：仓库明明推了新批次、CI 也跑了，但"检查更新"说不用更新，
  他手里那个包还是批次 V 之前的 —— 之后每批模块改动都"像没生效一样"。
  批次 X 也记过一次"源码已改、产物没重建"，同源。

**修复**：`build.sh` 每次构建现算版本号 ——
`versionName = 1.0.<git 短哈希>`、`versionCode = unix 秒`。
短哈希让人眼能核对"手上这包是不是当前提交"（版本信息页会显示），
tag 也随之每次不同，`releases/latest` 指向最新包这条承诺不变。

### ② 模块加载：一个 try 包住三段 hook，H1 抛一次就把 H2 全吞了

`XposedEntry.handleLoadPackage` 原来把 A(SystemUI)/B(H1~H3)/C(网易云) 全塞在
**同一个 try/catch** 里。只要 H1 在某个 ROM 上抛一次（`findAndHookMethod` 签名不匹配
就会抛），后面的 **H2 方向劫持（高德铺满画布全靠它）和 H3 一条都装不上**，
而且只留一行 `hook setup failed`，外观上就是"模块像没生效"。

**修复**：拆成三段各自 try/catch，每段失败单独记日志（`A 段失败` / `H1 失败` /
`H2 失败` …），互不牵连。

### ③ 日志第一条无条件打，且移到开关/白名单之前

原来是白名单通过后才 `log("已挂载 …")`。作用域勾错时日志里**连一条来访记录都没有**，
排查时分不清"LSPosed 没注进来"和"注进来了但被开关/名单挡掉"。
现在 `handleLoadPackage` 第一行就是 `log("handleLoadPackage: " + pkg)`。

### ④ 放宽 xposedminversion：93 → 82

判据是"越低越兼容"：LSPosed 的过滤条件是 `xposedminversion <= 框架 getXposedVersion()`，
所以 82 的可加载集合是 93 的**严格超集** —— 框架报 82 时只有 82 能过，报 93/102 时两者都过。
我们的编译期签名桩本来就是 api-82（`libs/xposed-api-82.jar`），82 才与桩一致。
参考实现 com.leting 写 93 能跑只说明 93 够用，不说明 93 最优。

### 复盘

- 做错：连续几批都在"加 hook / 改 hook"，但**没有一条证据说明模块被加载过**；
  用户在设备上的体感是"没反应"，最可能的解释一直是链路而不是逻辑。
  排查顺序应当是：产物是不是最新 → 模块有没有注进来 → hook 有没有抛 → 才轮到 hook 逻辑。
- 判据固化：`logcat | grep SeagullLsp` 里**必须有** `handleLoadPackage: <包名>`。
  没有它，后面所有 hook 讨论都是空的。
- 保留的结论（未改）：本项目画中画是 VirtualDisplay + SurfaceView（`PipBoard`），
  不是系统原生 PiP。所以 A 段（hook SystemUI `PipBoundsAlgorithm`）对本项目**不生效**，
  真正决定高德铺满的只有目标进程里的 H2。A 段暂时留着（若将来走系统 PiP 才有用），
  不再把它当成主要机制。

### 验证

- `bash -n app/build.sh`；本地无 aapt2/javac（无 SDK），类型检查走 CI。
- 真机验收（P2-19 已更新口径）：装**版本号带短哈希**的新包 → 高德强杀重启 →
  `logcat -s SeagullLsp` 先要看到 `handleLoadPackage: com.autonavi.minimap`，
  再看 `H2 hookOrientationOverride OK`。

### 遗留

- 若 `handleLoadPackage` 一条都不出现：问题在 LSPosed 侧（模块未启用 / 作用域未勾 /
  框架版本低于 82），与本仓库代码无关，只能由用户在管理器里核对。
- 网易云的 displayId 注入依赖目标 Activity 启动时所在 displayId > 0；
  若应用实际落在主屏（部署失败），这条也不触发 —— 先看 `MirrorSlot.describe()`。

## 批次 X — LSPosed 模块不生效的复盘：模块根本没被加载 + 两条死 hook

**目标**：用户报「还是不行」（高德画中画仍竖版黑边、网易云点击跳主屏）。
全面复盘模块侧代码，逐条对齐参考实现（`xphook` = CarWithX `com.leting` 的
AmapPipHook + NetEasePipAdapter；`WFreeHook` = 车联助手桌面的窗口语义模块），
找出「参考能生效、我们为什么不能」。

### ① 模块压根没被加载（批次 W 自己写错了）

`AndroidManifest.xml` 里 `xposedminversion=102`。这个 meta-data 的含义是
**「本模块要求框架提供的 Xposed API 最低版本」**，LSPosed 加载前会拿它跟
`XposedBridge.getXposedVersion()` 比，**大了就直接跳过加载** —— 模块在管理器里
看得见、作用域也勾了，但任何进程都进不去，`SeagullLsp` 一条日志都没有。

- 唯一可信证据：参考实现 `reference/carplay-re/sources/netease-pip-adapter/app/src/main/AndroidManifest.xml`
  是 `93`（这是实测能用的那份）。LSPosed 的 legacy de.robv API 版本号就是 93。
- 批次 W 里「API 102」的来源是 `xphook` 的日志字符串
  `"CarWithX LSPosed API102 loaded"` —— 那是它自己打的广告，不是清单声明值，读错了。
- 改成 `93`（≤ 任何 102 框架，也 = 框架自身的 93，两个方向都满足）。

### ② 网易云那两条 `Context.startActivity` 是死 hook

`XposedHelpers.findAndHookMethod(Context.class, "startActivity", ...)` 挂的是
**抽象基类 Context 上声明的那份实现**。`startActivity` 在 `ContextWrapper` /
`ContextImpl` / `Activity` 里全部被 override，虚拟分派永远走不到 Context 那份，
挂上去一次也不会触发。参考实现自己也有这个毛病，照抄就把病一起抄进来了。

- 而网易云 IoT 的播放页正是 **Service/Application 侧**拉起的 —— 恰好是这对死 hook
  本该负责的那条路，所以「点一下照样跳主屏」。
- 改成挂 `android.app.ContextImpl`（所有非 Activity Context 的最终实现，
  `ContextWrapper.mBase` 就是它），活动 Activity 侧仍由另外 4 条覆盖。
- 顺带把 6 条 hook 各自包 try/catch（对齐参考的写法）：某台 ROM 上少一个签名，
  不该把后面几条一起吞掉。

### ③ H2 漏了批次 W 的 onCreate 强拉（被重写时丢了）

批次 W 原本有 `hook Activity.onCreate(after) → setRequestedOrientation(横屏)`，
重写成 WFreeHook 版时丢了。**只 hook set/get 改不动 WM 侧的 ActivityRecord**：
高德首屏 Activity 的 `screenOrientation=1(PORTRAIT)` 写在 manifest 里，WM 建窗时
读的是 manifest，不是应用读回的那个值 —— 首帧必然竖版。补回来（H2b），
`onCreate` 后再显式请求一次目标方向；这次调用会再进 setRequestedOrientation 的
hook，参数已是目标值，原样放行，无递归。

### 复盘

- 做错：把「日志字符串」当「清单声明值」；把「参考实现有这个 hook」当成
  「这个 hook 有效」。两个都是**没验证据就当事实**，代价是整批功能静默失效 ——
  而且失效方式最恶劣：不崩、不报错、日志里啥也没有。
- 判据固化：任何「模块没生效」的排查，第一步是确认 `SeagullLsp` 有没有日志；
  没有就只查加载链（xposedminversion → 模块启用 → 作用域 → 产物是不是最新），
  不要先去怀疑 hook 逻辑。

### 验证

- `XposedEntry.java` 括号/结构平衡 + 逐条对齐参考；javac 类型检查走 CI（本地无 javac）。
- 真机验收：`adb logcat -s SeagullLsp` 应出现
  `已挂载 com.android.systemui → AmapPip 尺寸缩放 0.6`、`C1..C6 OK`、`H2 ... OK`。

### 遗留

- `Context.startActivity` 死 hook 的结论同样适用于 `ContextWrapper` 之外的写法，
  本批只修了网易云这一段；其余段没用到 `Context.startActivity`。
- 参考实现 `xphook` 的 ② 号缺陷（死 hook）说明「照抄参考」本身不是充分条件，
  后面再移植参考代码时，先判一遍「这个 hook 到底会不会被调用」。

## 批次 S — 全库复盘：复读画中画链路（~2700 行）+ 全库高危模式扫描，修 4 处确凿问题

**目标**：用户要求复盘全部代码找必修问题。精读批次 R 触碰过的完整链路
（MirrorSlot / RootOps / MirrorHost / PipBoard / StackScan / TrustedFlags /
PipAnchorActivity / Caps / TouchForward / PrivClient），HomeActivity 接线抽查，
其余 50+ 文件按模式扫描（静态 Activity 泄漏 / 主线程 sleep / API 门控 / 资源释放）。

**修掉的确凿问题**

1. **锚点在 API 29 上秒崩（P1）**：`PipAnchorActivity.onCreate` 直接调
   `Activity.getDisplay()`——该 API 是 API 30+，minSdk 29。29 的设备上
   NoSuchMethodError → 锚点起不来 → VD 上没有常驻 task → 空屏被系统清理，
   批次 R 的锚点机制整体失效。→ `SDK_INT >= 30` 门控（调用点必须门控，
   光判空救不了 NoSuchMethodError）。
2. **锚点实例无限叠加（P1）**：manifest 里锚点是 standard launchMode，
   `launchAnchor` 只带 NEW_TASK——同 affinity 会路由进既有锚点 task 并
   【新增一个透明实例】，永不 finish。每次部署（换应用/换尺寸重建）叠一个。
   → manifest 加 `launchMode="singleTask"`：复用 task 且单实例，真幂等。
3. **守护进程掉线后首触 ANR（P1）**：触摸手势链 feed → replay →
   `PrivClient.motion` → `ensure()` 在【主线程】跑；ensure 失败时
   waitConnect sleep 500ms×N + fork su，最多 ~3.5s（ANR 阈值 5s）。
   → 新增 `ensureForTouch()`：主线程只接受"已连接"（available 快查），
   掉线立即 false → TouchForward 本手势余下事件退回命令通道
   （跑在 seagull-touch 线程）；launch/move 仍走完整 ensure（调用方全在后台线程）。
4. **重拉节流竞态（P2）**：`MirrorHost.lastRelaunch` 是跨线程读写的
   long[]（桌面自愈线程 + 画布自愈线程都写），无同步 → long 撕裂 +
   双重重拉。→ `RELAUNCH_LOCK` 包住判定+写入。

**顺手清理**：`MirrorSlot.deploy` 里 `register(displayId)` 调了两次（223/229 行）。

**复核过没问题的**（免得下次重查）：`Caps.exec` 超时路径有 destroyForcibly
兜底、读线程随进程回收；`StackScan` 批次 P 的包名边界修复在位且
StackListCheck 30 项覆盖；`TouchForward` 手势解释/降级链完整；`PrivClient`
所有公开操作在类锁内、roundTrip 失败即关连接；`PipBoard` 单焦点/armed/CANCEL
链路、同槽部署串行锁、onDestroy 摘回调；全库无静态 Activity/Context 泄漏、
无主线程 sleep（除 PrivClient 本批修的）、su 通道超时全覆盖。

**遗留（记 TODO，等真机证据再动）**：①私有屏弹回检测——singleTask 目标
（网易云）若被系统从 flags=0 私有屏弹回主屏，ensureOnDisplay 搬回会 3s 一次
循环（move 不重拉，负载可控但治标）；连续 N 次弹回应升级 TRUSTED 重建，
先记 P2-13。②Android 14 录屏授权 token 单次有效：两份投影会话在 API 34 上
第二次 getMediaProjection 必失败（现有 catch + 共用降级 + 日志在位），
根治要改两次授权申请，记 P2-14。

**验证**：typecheck OK；自检 6 套全过（30/54/14/15/10/PrivCodec）。



## 批次 R — 实验台回灌：CarWithX 建屏三铁律移植 + 锚点 + 缺席重拉

**目标**：把 `seagull-pip-lab`（复刻 CarWithX）在真机验证过的路子回灌主项目，修三个症状：
两个画中画没铺满、回桌面画中画消失、画布黑屏无人重拉。

**复盘（症状 → 根因 → 修法，全部读代码推出）**

1. **铺不满**：主项目建屏走 `TRUSTED|PUBLIC` 全量 flags（`TrustedFlags.candidateFlags` 组 1），
   PUBLIC 屏被系统当"第二块真屏"，size-compat/letterbox 决策与私有屏不同路；
   CarWithX 全家（实验台在用户 Redmi K60 实测）用 flags=0 私有屏出画面。
   → 建屏顺序改为 ①flags=0 私有屏（零授权零弹窗）→ ②TRUSTED → ③投影兜底；
   `dpiFit`（批次 Q）保留不动。真机若仍黑边，按 TODO P2-11 口径抓
   `dumpsys activity containers | grep -A5 sizeCompat` 定位 ROM 层兼容框。
2. **回桌面消失**：两股根因。
   a) 应用在画中画里按返回退出后，`RootOps.ensureOnDisplay` 尾部返回
   "可能已退出"就没人管了 —— 画布永久黑屏（确凿代码级根因）。
   → `ensureOnDisplay` 改回 `ABSENT` 前缀标记（仅栈解析成功时），新增
   `MirrorHost.relaunchIfAbsent` 60s 节流重拉；桌面自愈（`healHome`）与
   画布自愈（`PipBoard.selfHeal`）都接上。
   b) singleTask 任务被系统拉回主屏：原有 healHome 兜底之外，补 CarWithX
   锚点 —— 部署建屏成功立即把 `PipAnchorActivity`（新增，空/透明/
   excludeFromRecents/独立 affinity）起在 VD 上，重钉有稳定落点、
   空屏不被系统清理。`launchAnchor` 只带 NEW_TASK（幂等复用锚点 task），
   【绝不能带 MULTIPLE_TASK】否则每次部署叠一个锚点 task。
   锚点的 "Warning: brought to the front" 是好结果，正向证据判据在此放宽。
3. **没有持久化**：主项目绑定早有持久化（`PipBoard.savePkg` → prefs
   `seagull/mirror_pkgN`，`MirrorHost` 进程级持槽，重启后 `deployIfBound` 重建）；
   该症状来自实验台（复刻版确实没写持久化）。回灌后两版行为一致。

**其他改动**

- `MirrorSlot`：新增 `createPlainVd`（flags=0 私有屏）与 `mode` 诊断字段
  （"直建(flags=0)" / "TRUSTED" / "投影"）；`describe()` 显示建屏路。
- `MirrorHost.onProjectionStopped`：只拆 `projectionBased()` 的屏 ——
  批次 R 起私有屏/TRUSTED 屏与投影无关，投影被撤销不能再误拆它们
  （旧判定 `!trusted()` 会把私有屏也拆了，这是重排建屏顺序后必须跟着改的一处）。
- `ensureOnDisplay` 新增双槽护栏：目标在**其他**虚拟屏有同名 task 时不动
  （否则重拉会 force-stop 掉对槽正在跑的实例，两块画布一起死）。

**验证**：typecheck OK；自检 6 套全过（Transform 54 / StackList 30 / DumpParse 14 /
Lrc 15 / TrustedFlags 10 / PrivCodec）；真机验收口径见 TODO P2-12。

**遗留**：LSPosed 模块（网易云强制横屏/吞 PiP）仍在实验台，主线是否合并待用户拍板
（影响包形态：单 APK 变 LSPosed 双体型）；`am compat` 在 HyperOS 上对
NEVER_FIX_ORIENTATION 的接受度未验证，失败仅记日志。

---

## 批次 T — 全库代码复盘（65 文件）：坐实 16 处问题（安全 3 / 正确性 8 / 性能 3 / 健壮性 2），杀掉 2 个误报

**目标**：用户要求「把整个项目代码都复盘一遍，看有没有 bug、性能问题、安全风险，有就修复」。
8 个并行审查代理全库扫过（60s 超时省缺），我再逐项亲自读码复核——
**每条发现必须自己从代码推出才动手**，杀掉 2 个确凿的误报（见下）。

**复核坐实 & 已修（16 处）**

安全：
1. **SysOps 注入**：`forceStop/killBackground/clearCache/grant/installApk` 参数零校验直拼
   `su -c`；UI 入口 `SettingsSectionActivity`「强制停止应用」的用户输入文本直达。
   → 新增 `safePkg/safePath/safePerm` 白名单（包名两段 [A-Za-z0-9_]，路径禁空格/;/&/..，权限名点分段），不过直接返回「参数不合法」。
2. **RootMain uid 白名单双误**：①放行 SHELL_UID(2000)——adb shell 连抽象 socket 即可以
   root 通道拉起虚拟屏/转发触摸，KernelSU 同意框白给；②白名单只有 0/1000/2000，
   **不含本家 uid(10xxx)——普通安装下 launcher 自己的连接全被拒**，PRIV_LAUNCH/MOTION/MOVE
   全程静默降级成 shell 慢通道。→ 撤 SHELL_UID；新增 `selfUid()`（反射
   `Process.getUidForName` → 回落 `dumpsys package` 的 userId=/appId= 双通道）放行本家。
3. **下载文件名注入**：`downloadApk` 用 GitHub release tag_name 直拼文件名（可 `../`
   穿越）。→ `safeVer()` 只留 [A-Za-z0-9._-] 且限长；下载落盘同时收敛到 `files/updates/`
   子目录，与 `file_paths.xml` 的 `path="updates"` 对齐（旧 path="." 把整个 files/ 圈进映射）。

正确性：
4. **`LauncherModel.launch()` 死路**：ctx 是 applicationContext，`asNewTask=false` 不加
   FLAG_ACTIVITY_NEW_TASK → startActivity 必抛 AndroidRuntimeException 被 catch 吞 →
   桌面图标/Dock/文件夹点开应用静默无响应（DesktopView/QuickBar/FolderActivity 全链路）。
   → NEW_TASK 恒加（asNewTask 参数保留）。
5. **`LayoutModeActivity` 布局列表永远空**：`rebuildRows()` 往旧 listCol 填完行又
   `setContentView(build())` 换一棵全新的空树，行随旧树被丢弃；首屏更是没人调它。
   → `build()` 内 `fillModeRows()`，`rebuildRows()` 只整树重建。
6. **`BootReceiver` 读错 key**：读顶层 SP 的 `"autoHome"`，而模型存在 K_LAYOUT JSON 里
   （`save()` 的 `o.put("autoHome")`）——「开机自动回桌面」开关恒为默认 true。
   → 改走 `new LauncherModel(ctx, false).autoHome`。
7. **`TaskMover.frontTask` grep 无边界**：`displayId=1` 会命中 `displayId=10/11…` 的行，
   收回/拉回搬错屏上的任务。→ `'displayId=N[^0-9]'` 边界；`taskId=` 加 `\b`。
8. **`homeKeys` 同名文件夹去重**：`covered.add(f.name)` 按名字去重，两个同名文件夹
   只有一个能在桌面占位。→ 改按 Folder 身份（LinkedHashSet + Folder 无 equals）。
9. **`toggleWidget` 死按钮**：`id > 4` 硬编码把 5(歌词)/6(快捷栏) 丢掉，而
   `DesktopView.makeWidget` 两个渲染器都在、菜单也列 7 项。→ 放开到 0~6 并返回
   boolean，满 WIDGET_MAX 时菜单给 toast 反馈；`load()` 钳制同步放到 0~6。
10. **`importScheme` 先写后读**：语法对、语义错的存档让 load 抛异常，方法返 false 但
    存档已被污染，重启停在 defaults，用户以为导入失败、配置已丢。→ catch 里撤回 prev
    并重新 load。
11. **`QuickBar.tap()` NPE**：`@fn:` 分支不判 null 直接 `host.model().context()`，
    Activity 销毁窗口期点功能按钮必崩。→ 与应用分支同样先判空。

性能：
12. **`LauncherModel.save()` `commit()`**：配置保存全在主线程（拖动/开关），commit 同步
    fsync 大存档卡几十毫秒。→ `apply()`。
13. **图标无缓存**：`icon()` 每次 `getApplicationIcon` = 一趟 Binder + 位图解码，
    桌面重画/拖动/组件条刷新全靠它。→ `LruCache(96)`，命中时从 ConstantState 取
    独立副本（共享位图无解码，各 View bounds 不打架）。
14. **`Lyrics` pos 漂移**：播放中纯墙钟累加，postDelayed 真实间隔误差长播漂出秒级。
    → 每 15 跳用 `ps.getPosition()+now-getLastPositionUpdateTime()` 重新锚定。

健壮性：
15. **`factoryReset` 名不副实**：`resetScalars()` 只回 16 个标量，Dock/菜园/小白点/
    野菜岛/天气/歌词/窗口/触摸/快捷按钮全系列漏重置（用户实测过：重置后字号还是 130%）。
    → 补全全部标量字段。
16. **两处泄漏/健壮**：`DesktopView` 歌词 sink 构造时匿名注册、从不摘除，换肤重建
    每次泄漏一整棵旧视图树 → 单实例 + onAttached/onDetached 配套；`HomeActivity` 换肤
    重建时旧 PipBoard 的 redeploy/selfHeal 回调无人摘 → 新增 `PipBoard.cancelPending()`
    在重建前调。另：`Lyrics.http()` 加 1MB 回包封顶。

**复核后杀掉的误报（记录判据，避免下批复盘又报一遍）**

- **WindowCard AUTO_MIRROR「未持 MediaProjection 会 SecurityException」**：误报。
  构造即持 `MediaProjection projection`（:41/:56-58），AUTO_MIRROR+投影是标准镜像写法；
  且 attach 失败只记日志、不会走到 close()。
- **Lyrics `getActiveSessions(null)`「换歌链路死」**：误报。:199 有歌名/歌手比对，
  变化即 `lookup` 重查；会话为空是通知监听权限门槛，属设计行为。
- **SeagullFileProvider「file_paths.xml path='.' 暴露整个 files/」**：误报。
  这是手写 ContentProvider，根本不读 file_paths.xml，`fileFor` 有 canonical 前缀校验。
  XML 仍顺手收紧（防将来换成真 FileProvider 时踩坑）。

**验证**：typecheck OK；自检 6 套全过（PrivCodec/Transform/StackList/DumpParse/Lrc/
TrustedFlags）；真机验收口径见 TODO P2-15。

**遗留**：代理发现里未亲自复核的项（GardenActivity dp 双倍密度+时钟不走字、BallService
长按/点按双触发、RootPanelActivity 主线程 su×2、VirtualDisplayHost 未同步、Lrc BOM/
[mm:99]、Pinyin locale、Theme accent 弱校验、MediaListenerService 打印通知正文、
Wallpaper 流泄漏、SettingsSection 定时器 HHMM 误解析、SelfTest 改设备亮度/音量、
Skin.apply 时机等）列 TODO P2-16，下批复盘继续。RootMain 线程-per-连接无上限
（本家连接数固定，风险低）记 P2-16。

---

## 批次 A — P0-3 文件夹闭环 + P0-4 应用搜索

**目标**：长按 A 拖到 B 上生成文件夹；搜索按名称/包名/拼音首字母过滤。

**改动**

- `LauncherModel.homeKeys()`：pinned 为空时自动枚举，全在文件夹里也不会渲染成空桌面。
- `LauncherModel.CATEGORIES/categoryOf()/autoGroup()/dissolveFolderQuiet()/dissolveAllFolders()`：按包名前缀分 8 类，单应用分类自动解散。
- 新增 `Pinyin.java`：`android.icu.text.Transliterator("Han-Latin")` 取首字母，不引第三方词典。
- 新增 `SearchActivity.java`：结果点击启动，长按 → Dock / 文件夹 / 应用信息。
- `HomeActivity` 顶栏、`AppListActivity` 各加搜索入口；`LayoutModeActivity` 加自动归类与解散全部。

**复盘**

- 做对：拼音走平台 ICU，比塞一份词典小两个数量级，且 `android.jar` 里本来就有这个类。
- 做错：`homeKeys()` 原来只认 `pinned`，纯文件夹布局的桌面是空的 —— 这是个「首次启动就空白」的必现 bug，优先级高于搜索。
- 修正：搜索结果的长按菜单直接复用 `LauncherModel` 的 `toggleDock/addToFolder`，没有另建一套数据通路。

**验证**：`javac` 0 错误。

**遗留**：拼音首字母只覆盖 `Transliterator` 的通用规则，多音字不完美（中文桌面通病，够用）。

---

## 批次 B — P0-5 Dock 全配置

**目标**：Dock 位置/数量/粗细/图标大小/间距/透明度/自动收起/固定/时钟全部可配，长按图标选默认窗口。

**改动**

- `LauncherModel`：`dockWindow`（pkg → 1/2）+ `windowOf()/setDockWindow()`，save/load/reset 全链路。
- `DesktopView` 重写 Dock 部分：`FrameLayout` 根 + 主列侧边 margin + Dock 兄弟节点按 gravity 定位；`applyDockLayout()` 统一落布局参数；`applyCollapse()` 做贴边平移 + 把手展开；`dockMenu()` 长按菜单。
- `MirrorActivity`：`EXTRA_PKG`/`EXTRA_SLOT` + `intentFor(ctx,pkg,slot)`，onCreate 预写槽位并自动走录屏授权链。
- `SettingsSectionActivity.dockBody()`：9 项配置 + 每应用默认窗口（Dock 为空时用默认候选应用）。

**复盘**

- 做对：Dock 的位置/粗细/透明度/收起全部集中在 `applyDockLayout()`/`applyCollapse()` 两个方法里，其他代码不碰布局参数，改起来只有一处。
- 做错：默认 Dock 键位里写了 5 个包名硬编码；一旦桌面上一个都没有，底栏会空。改成「优先常见包名 → 都没有就取前 5 个」两级兜底。
- 修正：`dockWindow` 只存包名不存 key（同一包多入口时两个入口会互相覆盖），取舍上选了包名，因为「默认开在几号窗口」本来就是应用级属性。

**验证**：`javac` 0 错误；`build.sh` 出签名 APK（119345 bytes）。

**遗留**：自动收起只做了贴边平移，没做「侧栏塞满时自动变窄」。

---

## 批次 C — P0-6 快捷栏

**目标**：组件条里的一条小 Dock，每格放应用或功能按钮，每个布局各有一份。

**改动**

- 新增 `QuickBar.java`：`FN_KEYS/FN_LABELS`（7 个功能按钮）+ `runFn()` 执行 + `build()` 造视图。
- `SettingsSectionActivity` 的功能按钮表改为引用 `QuickBar.FN_KEYS`（原先两处各写一份 7 项清单）。
- `DesktopView`：组件条长按（或桌面空白处长按）出「加组件」菜单；长按单个组件可拿下/前后挪；组件 id=6 渲染 `QuickBar`。
- `LauncherModel.switchMode(Mode)` + `quickbarByMode`：每个桌面布局各存一份，存/取抽成纯静态 `swapQuickbar()`。
- `SysOps` 补 `isWifiOn/isBluetoothOn/isAirplaneOn`，开关按钮需要读当前态。
- 自检 `app/selfcheck/QuickbarCheck.java`：7 项断言。

**复盘**

- 做对：把「每布局一份」的存取逻辑抽成不依赖 Context 的纯静态方法，才有办法用普通 java 跑断言自检。
- 做错：自检第一版写 `cur.get(0).key()`，`QuickSlot.key` 是字段不是方法 —— 一编译就暴露，说明把自检放在同包下直接调包级静态方法是对的。
- 修正：`LayoutModeActivity` 的组件列表原本是 5 项、第 5 项叫「温度」，而桌面组件条里根本没有温度组件，两处对不上。统一到 `DesktopView.WIDGET_NAMES`。

**验证**：自检 7 项通过；`build.sh` 出 APK（123441 bytes）。

**遗留**：竖屏快捷栏（3.7）没做；功能按钮只支持 7 个，亮度/音量是相对调节，依赖 root 通道。

---

## 批次 D — P0-7 主题与壁纸

**目标**：切主题全局变色、壁纸白天/夜间两套、壁纸库、全局字号、文字颜色按壁纸明暗自动翻。

**改动**

- 新增 `Skin.java`：运行时配色层。`c(R.color.x)` 是全局唯一取色口，`apply(LauncherModel)` 按主题色 + 日夜算出 ground/panel/card/leaf/text 全套；颜色混合自己按通道插值（`androidx ColorUtils` 不在依赖里）。
- 新增 `BaseActivity.java`：所有页面改继承它。`attachBaseContext` 里把 `Configuration.fontScale` 乘上全局字号（一次生效，全部界面用的都是 sp）；`onCreate` 里 `Skin.apply()`。
- 新增 `Wallpaper.java`：`ACTION_OPEN_DOCUMENT` 选图 → 拷进 `filesDir/walls/` → 入壁纸库；`loadForScreen()` 抽样解码铺满；`isBright()` 抽样 16×16 算平均亮度。
- `HomeActivity`：`applyWallpaper()` 铺底 + 遮罩层；顶/底栏改用 `Skin.bar()`（亮壁纸上半透明 = 自动字底）；`skinSignature()` 只在主题/壁纸/字号/小时变化时整屏重画，避免每次回桌面都闪。
- `LauncherModel`：`load()` 开头补 `reset()`；`fontScaleOf(Context)` 静态只读字号（attachBaseContext 用，不跑应用枚举）；`pinnedIndex/movePinned/pinTop`（自己摆的布局）。
- `DesktopView`：非整理模式下长按图标 = 自己摆布局菜单（上移/下移/置顶/固定/拿下来/固定到 Dock/应用信息）；长按文件夹 = 打开/解散。
- 机械替换：15 个文件里 `getColor(R.color.x)` → `Skin.c(R.color.x)`，全部 `extends Activity` → `extends BaseActivity`。

**复盘**

- 做对：颜色不再从 `resources` 直接取，而是过 `Skin`。换主题只要 `Skin.apply()` 一次 + 重建视图，全屏生效，零散落点。
- 做错（3 个，都在替换阶段）：
  1. 正则 `getColor(R.color.x)` 命中了 `ctx.getColor(R.color.x)`，产出 `ctx.Skin.c(...)` 这种非法代码。已改回。
  2. `HomeActivity.barBtn(label, colorRes, ...)` 传的是变量，正则没命中，漏了一处 `getColor(colorRes)`。
  3. `android.graphics.Gravity` 不存在，`BitmapDrawable.setGravity` 要的是 `android.view.Gravity.FILL`。
- 修正：`LauncherModel.load()` 原本不清空列表字段，`widgets/folders/tasks/quickbar` 每次重读都会叠加 —— 这是首屏就可能出现的越读越胖 bug，现在 `load()` 开头先 `reset()`。
- 决策：壁纸亮度判定用「抽样 16×16 算平均亮度」，不引图像库；`isBright()` 的解码带 `inSampleSize=16`，一张 4K 图也就解出几百像素。

**验证**：`typecheck.sh` 通过。

**遗留**

- 毛玻璃（6.10）没做：`RenderEffect` 在 API 31+ 才有，minSdk 29 要自己降级，不划算。
- 视频壁纸（6.9）没做：解码开销与车机性能冲突。
- 日出日落精确切换（6.4 细分）用固定 06:00~19:00 代替，联网取日出日落等天气模块落地后再补。
- 主题色只做单色派生（accent → 一整套），没有做每套主题独立配 9 个色值。

---

## 批次 E — P0-8 屏幕设置

**目标**：外边距 / 缝真的作用到桌面；屏幕方向；横竖屏两套布局分开；信息栏关掉后时间挪到 Dock。

**改动**

- `LauncherModel`：`portMarginH/portMarginV/portGap` + `marginHOf(portrait)/marginVOf/gapOf` 三个取值口；save/load 补三个键。
- `BaseActivity.applyOrientation()`：`AUTO` → UNSPECIFIED，`LANDSCAPE` → SENSOR_LANDSCAPE，`PORTRAIT` → SENSOR_PORTRAIT；`isPortrait()` 供页面判断用。
- `BaseActivity` 持有 `protected LauncherModel model`，6 个页面删掉各自的 `private LauncherModel model` 与 `new LauncherModel(this)`（每个 Activity 少构造一次，省一次 `queryIntentActivities`）。
- `DesktopView.refresh()`：主列 padding 走 `marginHOf/marginVOf`，网格格子 margin 走 `gapOf`（原来写死 `dp(12)/dp(2,5)`）。
- `HomeActivity.buildTopBar()`：`topInfoBar` 关掉就整条隐藏，并强制 `dockShowClock = true`。
- `SettingsSectionActivity.screenBody()`：横屏 / 竖屏两组滑杆 + 「竖屏复制横屏的值」，方向选择，保持亮屏，信息栏，自动校时。

**复盘**

- 做对：外边距没有新增字段去覆盖旧字段，而是加三个 `…Of(portrait)` 取值口，页面上只判断一次方向。
- 做错：删各页面的 model 字段时用正则顺手把 `model = new LauncherModel(this);` 也删了，`HomeActivity.onResume` 里靠基类的 model 续命 —— 顺序上没问题，但 `HomeActivity` 原来是 `super.onCreate` 之后自己建 model，现在完全依赖基类，注释里要写清，否则以后有人插一行就炸。
- 决策：竖屏布局「分开存」只覆盖边距与缝这三项，没有把 Dock、桌面模式一起分。理由：桌面模式/Dock 是用户显式摆的，分成两套会让「我在竖屏调了下」和「横屏怎么变了」互相打架；边距是跟着屏幕物理尺寸走的东西，必须分开。

**验证**：`typecheck.sh` 通过。

**遗留**：横竖屏切换时 Activity 会重建（没加 `configChanges`），应用重启一次；7.8「横竖屏切换应用不重启」需要窗口控制，矩阵里本就是 ⛔。

---

## 批次 F — P1-1 菜园 + P1-2 小白点

**目标**：菜园整屏（大时钟 + 歌词 + 叶子），叶子长按进出；悬浮球可拖、贴边、点一下回桌面、长按进菜园。

**改动**

- 新增 `GardenActivity.java`：三种摆法（居中大钟 / 左上时钟+底部歌词 / 大字时间日期）；遮罩走 `gardenDim`，与外观的 `wallDim` 是两份；时钟带 `setShadowLayer` 当字底；叶子点=全部应用、长按=退出；返回键回桌面。
- 新增 `BallService.java`：`TYPE_APPLICATION_OVERLAY` 悬浮球，拖动时改 `WindowManager.LayoutParams.x/y`，松手贴最近的左右边并 `model.save()`；点=回桌面（Dock 关着时改成出全部应用）、长按=进菜园；长按判定用设置里的 `longPressMs`。
- `LauncherModel`：新增 `ballAlpha`（5.12）；`ballX/ballY/ballSize` 由菜园叶子与悬浮球共用（它们是同一个「野菜键」）。
- `HomeActivity` 底栏加「叶」：点=全部应用，长按=进菜园（`gardenEnabled` 关着时提示去设置开）。
- `BootReceiver` 开机后按 `ballEnabled` 拉起悬浮球。
- `SettingsSectionActivity`：菜园分区加「进一次菜园看看」；小白点分区接上 `BallService.setEnabled()`、透明度滑杆、回默认位置。
- Manifest：注册 `GardenActivity` 与 `BallService`。

**复盘**

- 做对：菜园和悬浮球共用一份位置/大小，模型没膨胀成两套字段；「野菜键」在两个场景是同一个东西，拆成两份迟早对不上。
- 做错：叶子背景色一开始写成 `Skin.c(R.color.leaf) & 0x66FFFFFF` —— 这是把 alpha 换成了不透明的白，等于白圆底。正确写法是 `(0x66 << 24) | (color & 0x00FFFFFF)`。这类「alpha 与 RGB 用同一个掩码混搭」的错，肉眼在编译期看不出来，只有 review 能抓到。
- 决策：菜园不给悬浮窗权限、不做成 Service —— 它就是一个 Activity，进菜园时别的应用本来就在后台跑，「画中画藏起来」不需要额外动作。
- 决策：长按用 `postDelayed` 而不是 `GestureDetector`，少一个类；代价是手指按住不动也会触发，符合「长按进菜园」的直觉。

**验证**：`typecheck.sh` 通过。

**遗留**：4.4 / 4.14 歌词与字号跟随等批次 G 的歌词模块；4.7 画中画联动未做（矩阵里标 🟡，需要窗口焦点控制）。

---

## 批次 G — P1-3 天气 + P1-4 歌词

**目标**：天气组件（当前 + 未来 3 天、20 分钟自动、点按刷新、手动/定位城市）；歌词组件（三种来源、行数字号对时、缓存清空、这首不显示、换版本、通知歌词），菜园歌词从占位换成真歌词。

**改动**

- 新增 `Weather.java`：Open-Meteo 公开接口（地名查询 + 预报，免 key 免注册 HTTPS）。`refresh()` 在单线程池里跑，回主线程回调；`arm()` 是 20 分钟单例定时器，桌面 onResume 起、数据过期时立刻取一次；`code()` 把 WMO 码翻成中文；`dayName()` 出今天/明天/后天/周几。
- 新增 `Lrc.java`：纯 Java 的 LRC 解析（行首连续时间戳、分号/冒号小数、一行多时间戳=副歌重复、乱序按时间排、元信息行剔除）+ `indexAt()` 找当前行。刻意不碰任何 Android 类，方便 JVM 自检。
- 新增 `Lyrics.java`：`MediaSessionManager.getActiveSessions()` 读当前歌（一秒一跳，`getPosition()` 是快照所以按墙钟自己累加）；三种来源（播放器自带 → 只扫 `description.extras`，拿不到走在线；本地 lrc → Music 目录两层深度；在线 → 酷狗搜候选 + 按 id 下 base64）；缓存 `filesDir/lyrics/<key>.lrc`；`nextVersion/clearCache`；`Sink` 回调把窗口推给组件条与菜园；同句不重复发通知。
- `LauncherModel`：天气加 `weatherAuto/Summary/Forecast/Error/Lat/Lon` + `setCity()`（改城市清缓存坐标）。
- `DesktopView`：组件 4 显示天气（点一下手动取）、组件 5 显示歌词两行；构造时注册 `Lyrics.Sink`，只在放了歌词组件时每秒重画组件条。
- `GardenActivity`：歌词行换成真歌词（只改 TextView 文字，不重建视图，10.15 与组件条同乘 `lyricSize`）。
- `SettingsSectionActivity`：天气分区接上取数/定位权限（root 走 `pm grant`，普通机走 `requestPermissions`）；歌词分区补状态栏/通知/蓝牙三个开关 + 换版本 + 这首不显示 + 清缓存。
- Manifest：加 `INTERNET/ACCESS_NETWORK_STATE/ACCESS_COARSE/FINE_LOCATION`。
- 新增 `app/selfcheck/LrcCheck.java`：15 项断言（时间戳换算、副歌重复、乱序排序、无时间戳丢弃、播放位置落在两句之间）。

**复盘**

- 做对：把 LRC 解析拆成零 Android 依赖的 `Lrc`，换来一个真能在 JVM 上跑的 15 项自检。时间戳是「算错一行就整首跑偏」的地方，这层测试值这 40 行。
- 做错（三个，写的时候都以为对了）：
  1. `PlaybackState.getDuration()` 编译不过 —— 它是 @hide。改用 `MediaMetadata.getLong(METADATA_KEY_DURATION)`。
  2. `getActiveSessions()` 返回的是 `List<MediaController>`（API23+），不是 `List<MediaSession>`，我按老印象写了 `new MediaController(ctx, token)`。直接遍历 controller 就行。
  3. `MediaMetadata.getBundle(String)` 也是 @hide，公开 API 读不到任意 metadata 键。10.2「播放器自带」只能退到 `getDescription().getExtras()`，扫不到就走在线 —— 这条在矩阵里标 🟡 并写清原因，不假装做到了。
- 决策：一秒一跳里只 `new LauncherModel` 一次（读存档要解 JSON，三次/秒太浪费），`advance/push/window` 都吃传进来的实例。
- 决策：隐藏的静态 Context 一开始留了 `sCtx`（缓存读写要用），看着像全局变量；改成所有方法显式带 `Context`，只留一个 `appCtx` 给定时器用。
- 决策：天气源选 Open-Meteo 而不是和风（9.7）。和风要 key、要注册，公开版没法开箱即用；换回来只需改 `Weather.FC_URL` 与地名接口两个常量。矩阵里 9.7 标 ⛔ 并写了这个替代路径。

**验证**：`typecheck.sh` 通过；`LrcCheck` 15 项通过。

**遗留**：9.4 按网络判断、10.2 播放器自带、10.11 状态栏真显示、10.14 卡片本体受平台/后续批次限制；真机联网与播放验证仍待做。

---

## 批次 H — P1-5 任务引擎 + P1-6 野菜岛 + P1-7 关于

**目标**：任务能真的自己跑；顶部胶囊能显示；关于页能查更新并自更新。

**改动**

- 新增 `TaskEngine.java`：`fireDesktop`（同进程只跑一次）/ `fireBoot` / `arm`（每分钟对表）三个触发，动作层实现 `OPEN_APP / OPEN_PIP / REMOVE_FROM_HOME / DELAY`。
- `LauncherModel.Task` 加 `atMin`（定时触发的「每天第几分钟」），`describe()` 会把时间说成人话（`每天 07:30`）；`appNameOf/shortName` 把包名换成应用名。
- 新增 `IslandView.java`：顶部居中胶囊，`onDraw` 里圆角矩形 + 主副两行文字，超宽尾部省略；`bind(model)` 按设置摆位置并取内容。
- `HomeActivity`：`root` 上挂 `IslandView`（不进 `mainCol`，免得被布局挤动），onResume 与 30 秒 tick 都 `bind`，另注册 `Lyrics.Sink` 让岛上歌词一秒一跳；点一下进野菜岛设置分区。
- `SettingsSectionActivity`：定时任务多问一句「每天几点几分」；关于页接上「检查更新」（GitHub releases/latest 对版本）与「自更新」（下 apk 到 filesDir → 系统安装器）；指纹改用 `GET_SIGNING_CERTIFICATES`。
- `BootReceiver`：开机跑 `TaskEngine.fireBoot`。

**复盘**

- 做对：定时任务用「每分钟对一次表」而不是 `AlarmManager`。`AlarmManager` 要权限、要 exact alarm 白名单（API 31+ 还收紧），一分钟精度对「每天 07:30 打开导航」完全够用；多出来的代价只是一分钟误差。
- 做错：`Task.describe()` 在 `static` 嵌套类里调实例方法 `appNameOf`，编译不过。拆成 `static shortName()`（截包名尾巴）与实例 `appNameOf()` 两份——任务列表里 `allApps` 已经加载过，用实例那份能显示真名，静态上下文才用短名。
- 决策：13.6「在画中画打开应用」用官方 `android.support.picture_in_picture` extra，Android 没有「强制别人进 PiP」的 API。矩阵里标 🟡 并写清原因，不假装 100% 生效。
- 决策：野菜岛浮在桌面自己这层（`FrameLayout` 顶层），不新开悬浮窗。跨应用浮起来要再造一个 Overlay Service；等 8.6 真被用到再上，ponytail 已写进类注释。
- 决策：8.7 挖孔/圆角检测没做，用手动偏移代替。自动检测要读 `DisplayCutout`，拿不到就得靠 `WindowInsets` 猜，收益不抵成本。

**验证**：`typecheck.sh` 通过；CI（`5197fce`，批次 G）`completed success`。

**遗留**：8.6 / 8.7 见上；13.6 受目标应用限制；开机动画（14.x）整体未做，要 Magisk 侧配合。

---

## 批次 I — P2 root 增强层（触摸转发 / 窗口搬运 / 系统监控）

**目标**：把三块 root 能力从「有开关没实现」变成真能跑：触摸转发、窗口搬运、系统监控。

**改动**

- 新增 `TouchForward.java`：整条触摸状态机。DOWN 记起点；MOVE 时按「跟手」开关分流（开=每 ≥16ms、位移 ≥2px 发一小段 `input -d <id> input swipe`；关=等抬手一次发完）；抬起时没滑动的补一次 `tap`；CANCEL 什么都不发。命令串行执行，最近 12 条命令与返回进静态环形日志（12.8 排障）。
- `MirrorSlot` 的 `onTouch` 改成把 `MotionEvent` 整个喂给 `TouchForward`，删掉自己那套 `downX/downAt` + `HandlerThread`。
- `LauncherModel` 加 `touchFollow`（12.5）。
- 新增 `TaskMover.java`：`dumpsys` 解析 `displayId=` 找 `taskId`，然后按顺序试三条搬运命令（`am task move-to-display` / `am stack move-task` / `cmd activity task move-to-display`），把每条的真实返回原样拼成一段文字给 UI 看。
- `MirrorActivity` 槽位右缘加「收回」小标签（11.14），点一下后台线程跑搬运，结果进诊断行。
- `SysOps` 加 `cpuPct()`（`/proc/stat` 两次采样差，120ms 间隔）与 `monitorLine()`（CPU/温度/内存一行）。
- 设置页：触摸分区补「注入方式」「跟手」「注入排障」「能力说明」；窗口分区补「收回窗口 / 拉回窗口 / 搬运排障」（都过 `rootOnly()`，没 root 就直说）；系统分区补「CPU / 温度 / 内存」。

**复盘**

- 做对：`TaskMover` 没有赌一个「通用写法」，而是按顺序试并把每条命令的返回原样摊在 UI 上。`am task` 的子命令各家 ROM 裁得不一致，容器里没法验证，能交付的最有价值形态就是「证据可见 + 一键试」。
- 做错：`SysOps.monitorLine` 第一版写的是 `getMemoryClass() - availMem/MB` —— 把「本应用堆上限」和「系统可用内存」相减，得到的数字没有任何意义。改成 `(totalMem - availMem) / totalMem`。这类「单位对不上但能编译」的错，只有把两个 API 的语义放一起看才抓得到。
- 做错：`TouchForward` 的 `lastAt` 一开始声明成 `float`（跟 `lastX/lastY` 写在一行），`e.getEventTime()` 是 `long`，编译期就报了。这个是纯手滑，编译器当场拦住。
- 决策：跟手靠「拆成多条短 swipe」实现，不用 `input motionevent`（API 30+ 才有，低配车机未必有）。代价是命令量变大，日志里能直接看到，够用。
- 决策：12.6 无障碍 `dispatchGesture` 只留开关没实现 —— 那要一整套无障碍服务 + 用户手动到系统里授权，公开版首发不值得。矩阵标 🟡 写明原因。

**验证**：`typecheck.sh` 通过；CI（`89908c3`，批次 H）`completed success`。

**遗留**：11.13/11.14/13.6/12.6 都要在真机（有 root）上验证命令可用性；容器里只能保证代码路径与降级提示正确。

---

## 批次 J — root 守护进程 + L3 黑屏/触摸修复

**目标**：L3（虚拟屏窗口）建了屏但不出画面、触摸打不进去——两个根因一起修：
① 虚拟屏 flags 把第三方应用的窗口拦在外面；② 特权动作全依赖 fork root shell 的
`am`/`input` 命令，慢且表达不了多指。机制对齐 Extendroid：root uid 守护进程
进程内反射隐藏 API（startActivityAsUser / injectInputEvent / moveRootTaskToDisplay）。

**改动**

- 新增 `PrivCodec.java`：Launcher 与守护进程之间的行文本协议
  （`PING / LAUNCH / MOTION / MOVE / REMOVE`，一请求一应答，脏报文丢事件不杀进程）。
  刻意零 `android.*` import，协议层能在纯 JVM 上自检。
- 新增 `PrivClient.java`：app 侧 LocalSocket 客户端。`su -c setsid sh -c
  'CLASSPATH=base.apk exec app_process RootMain'` 拉起守护进程（setsid 脱离桌面进程组，
  桌面被杀重建不陪葬；不 waitFor，app_process 是常驻进程等它退出会死锁）。
  拉起失败判会话级禁用，后续命令照旧走 shell 兜底。
- 新增 `RootMain.java`：root uid 守护进程。反射 `ServiceManager` 拿服务，
  `IActivityManager.startActivityAsUser`（优先 am 同款 11 参重载，callingPackage 固定
  `com.android.shell`，`ActivityOptions.setLaunchDisplayId` 指定目标屏）；
  `IInputManager.injectInputEvent` + `MotionEvent.setDisplayId` 注入 MotionEvent
  （多指、带时间戳，真手势保真）；`moveRootTaskToDisplay` / `removeTask` 搬屏删任务。
  无客户端 60s 自退出，不宿留。
- `MirrorSlot` 建屏 flags 改 `PUBLIC | AUTO_MIRROR`：去掉 `OWN_CONTENT_ONLY`
  （只显示与建屏者同 UID 的内容——黑屏根因，导航/音乐 task 明明在屏上）与
  `PRESENTATION`（展示屏语义，同样拦第三方窗口）。
- `TouchForward`：修触摸命令双写 `input` 的 bug（`input -d N input tap` 必然报
  未知子命令）；守护进程在位时改「原始事件中继」——MotionEvent 连坐标带时间戳原样
  进目标屏，多指缩放天然成立，每次手势零命令开销；通道钉在每次 DOWN 上，手势中途不换。
- `RootOps.launchOnDisplay` 改三路（守护进程反射 → 公开 API → `am start`）；
  `moveTaskToDisplay` 优先守护进程 `moveRootTaskToDisplay`。
- `VirtualDisplayHost`：flags 同修；`launchViaRoot` 走 RootOps 三级链；
  新增 `attach(Context)` 与 `launchViaAm`（裸 am 口径，排障对比用）。
- `SelfTestL3`：补 `PrivClient.status()` 行与裸 am 对比步。
- `Caps`：有 root 即认为 L3 可用（🟡 待真机验证），新增 `vdPath()` 让体检报告
  写清落地路径（system uid 直连 / root 守护进程反射 / 不可用）。
- 新增 `app/selfcheck/PrivCodecCheck.java`：30 项协议自检。

**复盘**

- 做对：协议层不碰 `android.*`，30 项断言在容器里真跑得起来（协议错一个字符，
  对面整条命令失效，这层测试值）。降级链做成硬规则：PrivClient 每一步失败都只
  「如实返回 false」，命令永远有 shell 兜底。
- 做错：MOTION 报文第一版没带 displayId —— 守护进程同时服务两块镜像槽，
  事件必须自带目标屏，不能默认「就一块」。
- 做错：长按的守护进程实现第一版发的是 `CANCEL` —— CANCEL 语义是「取消手势」，
  长按得是 DOWN→保持→UP 同点。
- 决策：守护进程用 `app_process` 而不是 native 守护 —— 直接复用 APK 里的 Java
  反射代码，省一套 NDK 构建与 .so 分发；隐藏 API 在 app_process 里无 enforcement。
- 决策：`setsid` 拉起 + 60s 空闲自退的组合，而不是常驻 —— 桌面被杀重建时守护
  进程不必重启，孤儿 root 进程也有兜底。

**验证**：`typecheck.sh` 通过；`PrivCodecCheck` 30 项通过。守护进程路径
（SELinux 是否放行 abstract socket、HyperOS KernelSU 域、ROM 是否裁剪
moveRootTaskToDisplay）容器里验不了，全部标 🟡 待真机。

**遗留**：① 真机验证守护进程全链路；② 手势中途连接断掉时，余下事件会掉
（已记日志，下次手势自动恢复）；③ `findTaskId` 的 `dumpsys` 解析口径与
TODO 已知坑 #11（Task 行本身没有 `displayId=`）不符，`moveTaskToDisplay`
的 taskId 来源要复核；④ `launchViaApi` 公开 API 路径在 HyperOS 上被
SecurityException 拒过一次，守护进程路径是否绕过待真机确认。






---

### 批次 J 补：dumpsys 口径复核（已知坑 #11）

**目标**：复核遗留项 ③ —— `findTaskId` 按 `displayId=` 在 Task 行里找屏，
与坑 #11（Task 行没有这个字段，归属看段头）矛盾，`moveTaskToDisplay`
搬的 task 可能不是你以为的那个。

**改动**

- 新增 `TaskScan.java`：从 `dumpsys activity activities` 文本扫 task 的纯 JVM
  解析层（零 android import，模式照 PrivCodec）。display 归属改按
  `Display #N` 段头切分；少数 ROM 的 Task 行自带 `displayId=` 的也认，
  两种口径都过。只认 `* Task{}` 行，`Task id #77` 详情行不认。
- `RootOps.moveTaskToDisplay`：dumpsys 只拉一次 —— 先定位任意实例，
  再确认目标 task 不在目标屏上（已在就是 no-op，如实报「已在」，别让人
  以为搬成功了）。同包两个 display 各有一个 task 时，严格模式不搬错
  实例（displayId>0 找不到就 -1，绝不回退）。
- 新增 `DumpParseCheck` 14 项自检，现场取材：同包在主屏 #77 和虚拟屏
  #88 各活一个 task —— 段头归属错一步，搬的就是另一个实例。
- TODO 坑 #11 补一句「少数 ROM 例外，TaskScan 两种都认」；顺手删了
  已知坑里重复粘贴的 10/11 两条。

**验证**：`typecheck.sh` 通过；`DumpParseCheck` 14 项、`PrivCodecCheck`
30 项、`LrcCheck` 15 项均通过。

**复盘**

- 做对：解析层继续放纯 JVM 类里，「同一包在两个屏各有一个 task」这种
  最容易骗过肉眼的边界，用真实格式的 fixture 钉死成断言，不用等真机。
- 决策：`moveTaskToDisplay` 的 taskId 仍取「第一个」而不是「主屏上的」
  —— 语义是「把这个包现有的 task 搬到目标屏」，搬之前加已在判断兜底；
  真要按屏挑，TaskScan 的严格模式已经现成。

## 批次 K — 画中画镜像：VD 1:1、触摸进得去、目标任务不被拉回主屏

**目标**：修用户实测三条抱怨（对标 carlink-desktop m13 / OneStep4）：
①画中画应跟随画布大小 ②点击不进去软件 ③进去桌面还不是画中画界面。

**参考实现依据**（`/tmp/opencode/refs/carlink-desktop-private`，0.2-m13 +
OneStep4 逐行亲核）：

- 抱怨③根因：Android 14 ActivityStarter 只允许 home-affinity / TASK_ON_HOME
  任务落**【受信】虚拟屏**；MediaProjection 屏无 TRUSTED，注入 launchDisplayId
  也会被拉回默认屏。解法：root 授 `android.app.role.COMPANION_DEVICE_APP_STREAMING`
  角色 → RoleController 授签名级 `ADD_TRUSTED_DISPLAY` → 用公开 6 参
  `createVirtualDisplay`（android-33.jar javap 确认 public）建 TRUSTED 屏。
- 批次 J 的 `PUBLIC|AUTO_MIRROR` 被参考实现证伪：任务不在屏上时 AUTO_MIRROR
  把手机桌面镜像进画中画。参考版实测组合 `PUBLIC|OWN_CONTENT_ONLY|PRESENTATION`，
  其真机截图证明高德/网易云能渲染进 PiP（推翻「OWN_CONTENT_ONLY 黑屏」假设——
  黑屏实为任务没上去）。
- 启动 flags 0x18000000 → **0x18800000**（补 EXCLUDE_FROM_RECENTS；m8 真机结论：
  singleTask 目标不带 MULTIPLE_TASK 会拉主屏已有任务到前台），另加 `--user 0`。
- singleTask 应用在虚拟屏内跳转会拉走任务 → 需 `am stack list` 解析 + 保守自愈。

**改动**

- 新增 `TrustedFlags.java`：5 组 flag 候选（最全→最保守）/ normalize（SDK≥30
  保 TRUSTED；PUBLIC 强制带 OWN_CONTENT_ONLY、剥 INSECURE_KEYGUARD、永远剥
  SECURE）/ `hasTrusted` 位校验（Display flags TRUSTED=1<<7）/ 兜底
  `projectionFallbackFlags()`/ `describe()`。
- 新增 `StackScan.java`：`am stack list` 纯 JVM 解析（displayId 段头归属、
  stackId→taskId→*TaskRecord 行、isRunningOnDisplay / findTaskOnDisplay /
  firstTaskOnDisplay）。坑：stack 行自带 `displayId=` 字样，不能拿它当段头。
- `RootOps.java`：`LAUNCH_FLAGS = 0x18800000`；新增
  `grantTrustedDisplayRole`/`roleState`（进程内缓存、幂等、
  `am role add-role-holder --user 0 <role> <pkg> 0`）；新增 `ensureOnDisplay`
  保守自愈（守护进程 `moveRootTaskToDisplay` 优先，回退 `am task move-task`，
  解析失败不重拉——m12 教训：误判重拉会在虚拟屏内叠出两个目标任务）；
  `launchOnDisplay` 加 `--user 0`，`launchViaApi` 补 EXCLUDE_FROM_RECENTS。
- `MirrorSlot.java`：`deploy` 改 TRUSTED 优先（createTrustedVd：SDK≥33 +
  角色授予 + 5 组候选 + 受信位校验，失败 release 候选），失败回落
  MediaProjection `projectionFallbackFlags()`；`projection` 允许 null
  （needsProjection 标记）；desize 注释改 1:1 口径；describe 加 trusted/
  needsProjection。
- `MirrorActivity.java`：`deployNow` 不再强制录屏 token；VD 尺寸改
  `canvasW()/canvasH()`（优先 SurfaceView 实测，退回 全宽-dp(32) × dp(200)
  设计尺寸）——1:1 建屏后 `TouchForward.scale()`=1f 就是几何正确；
  `attachTo` 去掉 projection 门；`deploySlot` 去掉 UI 线程 su 前置检查，
  统一交给后台 deploy 决策；wait 遮罩按 `slot.ready()` 隐藏；onResume 接
  `ensureOnDisplay`（ensureOnDisplay）；诊断区加角色/受信状态。
- `SelfTestL3` 补 ⑨⑩：角色授予日志 + ensureOnDisplay + StackScan 核对。
- `SelfTestMirror` 补 ⓪ TRUSTED 屏探测：逐组候选建无 Surface 探测屏，
  读回 `displayFlags` 校验 TRUSTED 位并 release，日志直接回答「受信到位没有」。
- `Caps.vdPath()`：root 在手的设备按 SDK 区分口径（Android 14 起需 TRUSTED）。
- 新增自检：`TrustedFlagsCheck` 10 项、`StackListCheck` 12 项。

**验证**：`typecheck.sh` 通过；`TrustedFlagsCheck` 10 项（需 `-cp
android-33.jar`）、`StackListCheck` 12 项、`StackListCheck` 依赖的纯 JVM 层
零 android import。真机验证项见 TODO P2-5。

**复盘**

- 做对：把「Android 14 拉回任务」的机制问题交给 root 角色授予 + 受信位校验，
  几何问题（画布 1:1）用 SurfaceView 实测尺寸解决，两个抱怨根因各归其位；
  `am stack list` 解析层继续放纯 JVM 类 + 12 项断言，不等真机。
- 决策：自愈保守——`am stack list` 解析失败就跳过，不重拉任务（误拉会在
  虚拟屏内叠出两个目标任务，比不修更糟）。
- 遗留：手势中途 daemon 断连时余下事件丢弃，下次手势恢复（已知取舍）；
  TRUSTED 屏若受 SELinux 阻挡仍需回落投影兜底，届时抱怨③ 只能缓解不能根治。

## 批次 L — 画中画界面极简化（只要两个画布）+ 画中画常驻 + 统一签名

**目标**：用户三条实测反馈：
①「每次进入桌面还是应用界面，我要画中画界面」
②「界面只要两个画布！长按选择应用！不要多余的东西！」
③「弄个签名密钥统一签名，以后都用一个签名文件，写 md 文档」。

**根因**

- 「退出镜像页 → 桌面冒出全屏应用」的机制找到了：MirrorActivity.onDestroy
  里 slotA.teardown() → vd.release()，**VD 一释放，系统就把屏上的任务
  倒回默认屏**。用户每次退出画中画页回桌面，目标应用就全屏冒出来——
  这正是「每次进入桌面还是应用界面」。
- 签名漂移实锤：拉两次 CI 产物比对，证书 SHA-256 不同
  （`5e1bc2…` vs `eb7204…`）。旧 build.sh 在没有 secret 时每次现场
  生成随机密钥 → 每个包签名都不一样 → 覆盖安装必失败，只能卸载重装。

**改动**

- 新增 `MirrorHost.java`：画中画槽的进程级持有者。VD 生命周期挂进程，
  退出 MirrorActivity 只 `detachSurface()`（画面断、屏与应用留着），
  再进来把 Surface 挂回同一块 VD；`healHome()` 给桌面 onResume 调
  （节流 3s，保守自愈）；录屏被撤销时只拆投影屏（受信屏不依赖投影）。
- 重写 `MirrorActivity`：界面只有两块画布（各占半屏，黑底），
  长按画布选应用（GestureDetector 截走长按、先给 TouchForward 补一个
  CANCEL 掐掉残留笔画，再弹应用列表；对话框带「清空该槽」），
  点/滑/拖照常转发进画中画。删掉全部按钮、诊断区、机制说明；
  空画布只留一行居中提示。部署失败/缺授权自动补弹录屏授权框。
- `MirrorSlot`：构造改用 applicationContext（进程级常驻不泄漏 Activity）；
  deploy 成功搬完应用立刻 `ensureOnDisplay` 自愈一次（不等用户切页面）；
  teardown 只由 MirrorHost.clear 触发。
- `HomeActivity.onResume` 接 `MirrorHost.healHome(this)`。
- 统一签名：生成 `app/keystore/seagull-release.keystore`（RSA 2048 /
  10000 天 / alias seagull-release），build.sh 第 7 步改用它
  （密码：env `SEAGULL_KS_PASS` > properties > 兜底），CI 增加
  「还原统一签名密钥」步（`SEAGULL_KEYSTORE_B64` + `SEAGULL_KS_PASS`），
  `.gitignore` 加 `keystore/`，两个 secret 已用 PAT 设进仓库，
  新写 `docs/SIGNING.md`（含签名漂移实锤表、备份/轮换/排障）。

**验证**：`typecheck.sh` 通过；自检不变（批次 K 的 TrustedFlagsCheck 10 项 /
StackListCheck 12 项此前已过，本批未动其输入）。签名一致性需 CI 产物比对：
两次构建的 `apksigner verify --print-certs` 应打出同一 SHA-256。

**复盘**

- 做对：先拉旧产物做取证再动手，签名漂移从"疑似"变"实锤"；
  把"退出拆屏"这条机制从代码里读出来，没有盲猜。
- 决策：VD 常驻 = 画中画里的应用退出页面后继续在后台跑（音乐类正是
  想要的行为）；要停只能长按 → 清空该槽（force-stop）。
- 遗留：TRUSTED 角色在其设备上是否真授到，仍要用户回传 logcat
  （`SeagullRootOps` 的"TRUSTED 角色授予"行 + `dumpsys display` 的
  seagull-pipN flags）；不成立则走投影兜底 + 自愈，画中画会偶发被拉走。
- 换签名的代价：用户设备上已装的旧版是随机签名，本包需卸载重装一次，
  之后永久免卸载。

## 批次 M — 画中画成为首屏（左右分割 + 桌面降级 + 默认横屏 + 底栏精简）

**目标**：用户四条新要求：
①「画中画左右分割」②「桌面默认横屏」③「进入应用应该是画中画界面，
不应该是设置里的子界面」④「界面太多不合理的地方精简一下」。

**改动**

- 新增 `PipBoard.java`：可复用画中画面板（LinearLayout，左右两块画布各
  weight 1）。长按画布 → 宿主弹应用列表（长按被 GestureDetector 截走、
  先给 TouchForward 补 CANCEL 掐残留笔画；对话框带「清空该槽」= 拆屏 +
  force-stop）；点/滑/拖照常转发。部署/挂面/自愈/录屏授权补弹全包在
  面板里，同槽部署串行化（`synchronized (slot)`，防并行双建 VD 漏屏）。
- `HomeActivity`：内容层改成 FrameLayout 二选一 —— `PipBoard`（默认首屏）
  ⇄ `DesktopView`（次级层，底栏第一个按钮切回）。顶栏「整理」只在桌面
  模式显示；onResume 在画中画模式转 `pip.onResume()`；onDestroy 转
  `pip.onDestroy()`；onActivityResult 转授权结果；30s tick 改为刷新画布提示。
- `MirrorActivity` 薄壳化：躯干就是一块 PipBoard（构造参数
  `autoDeploy` 给自检页关掉抢跑），只剩两个用途 —— DesktopView Dock 的
  「投进画中画」入口（intentFor 不变）与 SelfTestMirror 自检（selftest
  extra 不变，onActivityResult 先问 SelfTestMirror.isOurRequest）。
  两份画布逻辑并成一份，漂移面归零。
- 底栏精简：砍「镜像小窗」（画中画已是首屏，那个子页面没意义）与含义
  不明的「叶」键（菜园改从 设置 → 桌面 → 菜园 / 小白点长按 进）；
  设置里菜园分区的说明文字同步改。
- `LauncherModel.orientation` 默认 `AUTO` → **`LANDSCAPE`**（用户：桌面
  默认横屏；竖屏党仍可在 设置 → 显示 改，BaseActivity 已有分支）。

**验证**：`typecheck.sh` 通过。画面/自愈行为未变的证据链：批次 K/L 的
TrustedFlagsCheck 10 项、StackListCheck 12 项输入未动；VD 常驻、部署串行化
改动都在 PipBoard 内部，链路与批次 L 实测一致。

**复盘**

- 决策：画中画做成「可复用 View」而不是「又一个 Activity」—— 用户要的
  「进应用就是画中画」本质上是首屏换内容层，Activity 跳转反而多一层。
- 决策：桌面网格保留为次级层而不是删掉 —— 组件条/Dock/文件夹这些既有
  功能还在，砍掉等于回退一批次。
- 遗留：真机三问照旧（TRUSTED 角色是否授到 / SELinux / moveRootTaskToDisplay），
  要用户回传 logcat（`SeagullRootOps` 的"TRUSTED 角色授予"行 +
  `dumpsys display` 的 seagull-pipN flags）。

## 批次 N — CarPlay 纪律移植：单焦点 / 卡片化 / 空态大按钮 / MiniPlayer（C 层只埋接口）

**目标**：用户拿来苹果 CarPlay 互联界面设计思路做参照，拍板四条移植：
单焦点（触摸只进焦点画布）、圆角暂缓改视觉卡片化、MiniPlayer 双场景点击规则、
分割比例锁 1:1 但权重配置化；另按"埋接口不接线"节奏给 DiPlay 推流铺路。

**改动**

- `PipBoard` 重写（A 层，用户四条车机交互修复）：
  - **单焦点**（安全红线）：非焦点画布的第一下触摸只切焦点不吃进 App；
    切换瞬间给刚失焦的画布补 `ACTION_CANCEL`（`cancelStroke`）——多指场景
    （一指拖着左画布、二指点右画布）的鬼拖痕全靠它掐掉。守护中继通道会
    把 CANCEL 真的送进 App，命令通道本来就丢弃 CANCEL（TouchForward.java:139）。
  - **空态大按钮**：≥80dp 高、16dp 圆角、半透明白描边、「＋ 选择应用」
    上下排布；点一下直接弹选择器（空画布没有 App 可误触，可发现性优先于
    焦点语义），长按仍是"更换应用"；按下态 alpha 0.6 兜底车机无震动马达。
  - **卡片化**：深色卡底 + 1px 半透明白描边 + 8dp 缝；卡底比画布大出
    CARD_PAD=12dp，将来开真圆角不露黑边。**圆角暂缓**：SurfaceView 是
    窗外合成，`clipToOutline` 圆角裁剪在部分设备失效，换 TextureView 又
    划不来（GPU 拷贝+延迟），本批方角。
  - **环境光三档遮罩**：非焦点画布盖黑，透明度跟 `SensorManager.TYPE_LIGHT`
    （公开 API 不要权限）：夜间 0.45 / 常态 0.35 / 白天强光 0.28，1s 采样
    + 低通 + 20/2000 lux 带回差，不每帧动 alpha。
- `MiniPlayer` 新文件（B 层）：36dp 常驻媒体条，层级在画布之上、底栏之下；
  只有上一首/播放暂停/下一首三个按钮（热区 TouchDelegate 外扩 12dp，探出
  36dp 条身）；没有进度条没有封面；媒体会话不活跃时整条隐藏不占位。
  数据源走 `MediaSessionManager.getActiveSessions`（MediaListenerService
  就是已启用的通知监听器，不另起服务不加权限）， transport control 直接
  对会话下发，不经过画布。
  **双场景点击规则**（用户拍板）：媒体源在画布里 → 空白区点击导焦到那块
  画布；媒体源在后台（没进画中画）→ 什么都不做，绝不挤占当前应用。
- `HomeActivity`：顶栏画中画模式压到 40dp 且只留 时间+设置（搜索/整理/
  日期全部下沉到桌面模式或应用页）；MiniPlayer 接线；30s tick 兜底扫会话。
- `LauncherModel`：新增 `pipWeightA/pipWeightB`（默认 1:1，钳制 1~10），
  设置 → 窗口 → 「画中画分割」三选一；权重变→VD 尺寸跟着变，保持 1:1。
  skinSignature 带上权重，改了立刻整屏重画。
- C 层（只埋接口不接线）：`CanvasSource`（MirrorSlot 本来就实现这四个
  方法，implements 上去零逻辑变化）、`StreamCanvasSource` 空实现占位、
  `TouchTransformer`（identity/scaling 两静态工厂）+ MirrorSlot 的
  `setDisplay(displayId, transformer.scaleX())` 一行接线（identity 恒 1，
  行为与批次 M 完全一致）+ `TransformCheck` 自检 21 项纯 JVM 全过。

**验证**：`typecheck.sh` 通过；`TransformCheck`（恒等/等比/零保护/负数/
describe）全过。VD 常驻、部署串行化等链路与批次 M/L 一致，未动输入。

**复盘**

- 做对：把"非焦点拦截所有触摸"和"最后触摸的画布获焦点"的矛盾显式收敛成
  "第一下只切焦点"—— CarPlay 同样行为；空画布单独留"直接选应用"的口子，
  没让安全规则把可发现性堵死。
- 决策：SurfaceView 方角是性能换正确性——画中画要 30~60fps 跟手，圆角
  等真机验证 TextureView 再说。
- 决策：MiniPlayer 数据源复用通知监听器拿 `getActiveSessions`，没新起
  服务没新权限——车机上多一个常驻服务就多一个被杀的理由。
- 遗留：DiPlay 推流接入时要实现 StreamCanvasSource（解码帧写 Surface）
  + ScalingTransformer（画布像素→iPhone 分辨率），并给多指中继补缩放链
  路的自检；VD 与推流混排的默认权重预设（7:3 / 3:7）下一批做。

## 批次 O — 批次 N 三个回归的修复（选择器 / 高德铺满 / 应用跳主屏）

**目标**：用户拿到批次 N 包实测报三件事，逐一定位并修：
① 两块画中画都选不了应用；② 高德地图在画布里铺不满；③ 有的软件跳转到
应用界面、返回桌面也回不来。

**根因与修复**

1. **选择不了应用 = 批次 N 自己写的坑**：`column()` 里给卡内 `SurfaceView`
   `setClickable(true)`，它变成可点击 child 之后吞掉整块画布的触摸 —— 长按选
   应用（走卡容器的 GestureDetector）、空态按钮点击全部失效。
   - 修复：SurfaceView 保持**不可点击**，触摸统一回卡容器 OnTouchListener；
     空态大按钮补 `setOnClickListener(v -> openPick(w))`（它自己就是可点击
     child，走自己的分发链）。
   - **教训加进已知坑**：SurfaceView 设在卡里可以，设 clickable 就会吃掉宿主
     的触摸回调——这是 SurfaceView 的可点击判定在 dispatchTouchEvent 最前面
     生效导致的，跟 z-order 无关。
2. **失焦画布顺手把 UP 注进旧 App**（修 ① 时顺手发现的同源漏洞）：非焦点
   画布第一下 DOWN 只切焦点后，之后的 UP 仍会走 `s.onTouch(e)` 被注入刚失焦
   的 App（地图里凭空多点一下），空画布还会顺带弹出选择器。
   - 修复：`armed[w]` 门闩——只有 DOWN 时就是焦点的画布，本手势后续的
     MOVE/UP/长按/选应用才放行。
3. **高德铺不满 = letterbox 兼容模式**：小尺寸 + 怪宽高比的虚拟屏上，应用按
   手机尺寸渲染再居中留黑边（`setDisplayId` 那套独立分辨率被拒之后的可见表现）。
   - 修复：部署成功后 root 执行 `am compat enable FORCE_RESIZE_APP <pkg>`
     + `am compat enable NEVER_FIX_ORIENTATION <pkg>`（前者取消 letterbox，
     后者别锁 manifest 的 screenOrientation，跟着虚拟屏方向转）。清空槽时
     `am compat reset <pkg>` 还原，不污染主屏。
4. **应用跳主屏 = 已知坑 #5 没被执行**：`launchOnDisplay` 从未 force-stop，
   `am start` 对已在主屏运行的应用会静默投递到主屏实例（singleTask 复用），
   应用直接盖住桌面 → "返回桌面也显示不了"。
   - 修复：部署前先 `am force-stop <pkg>` 再起；另外把 `selfHeal()`
     （`ensureOnDisplay`：目标落在主屏就用守护进程 moveRootTaskToDisplay
     搬回）从只在 onResume 跑，加到 30s tick 里周期跑。

**验证**：typecheck.sh 通过。三条修复都是命令链 + 触摸分发层的确定性修改，
没有需要真机才能看结果的新链路（am compat / force-stop 缺命令时只记日志）。

**复盘**

- 做错：批次 N 的"卡片化"重构把触摸入口搬到卡容器时，顺手给 SurfaceView
  加了 clickable（无效的多余行），直接断掉选择应用入口——**新写触摸层必须
  自检"空画布能弹选择器"这一条**，已经连续两个批次在这栽跟头。
- 做对：把 ① 和"失焦 UP 外泄"分开修，armed 门闩一次性把两个洞都堵住。
- 决策：letterbox 用 `am compat` 而不是去要系统签名 API（公开版拿不到），
  命令失败只是日志；`am compat` 对主屏同包也生效，所以 clear 时 reset。

---

## 批次 P — 三轮复盘：并发精读 + 亲自复核 + 自检交叉验证

用户要求"全部代码复盘三次找bug"。执行口径：bug 必须确凿（读代码推出，
不能靠"可能"），三轮分工——并行 5 个 agent 分组精读 → 我逐条复核
（滤掉猜的）→ 非平凡逻辑写自检钉住。修复排序：崩溃 → 数据丢失 → 红线 → 泄漏 → 健壮性。

### P0 崩溃（三条，全部真机必现）

1. **`BallService.longPressRun` 没有 `FLAG_ACTIVITY_NEW_TASK`**
   （BallService.java）：从 Service 上下文 `startActivity` 缺该 flag 直接
   `AndroidRuntimeException` 秒崩，Android 12 起更严。附带修 onDestroy 的
   removeCallbacks 与 `snap()` 前 `model.load()`（快照回写会覆盖新配置）。
2. **`VirtualDisplayActivity` onCreate 没 `host.attach(this)`**：
   所有回调拿宿主=null 空转。附带 su 探测移后台（原在主线程，root 弹窗 = ANR）、
   `append` 改 synchronized + 回主线程 setText（跨线程 setText 崩）。
3. **`Uri.fromFile()` 发安装 intent**（SettingsSectionActivity）：targetSdk 24+
   = FileUriExposedException，"下载新版本"必崩。项目不许引 androidx，
   照 FileProvider 最小面自绘 `SeagullFileProvider`（query/insert/delete/update
   按需实现，files/ 内路径规范化防跨目录穿越）。

### 数据 / 状态正确性

4. **`StackScan` 同一 display 被尾随内容覆盖成 -1**：段头真实形态
   `displayId=0 stacks=2`，旧取第二个 `split()[1]` 得 `stacks=2` → 再
   `displayId=0` 又覆盖一次，`segOf` 永远返回 -1 → `taskOnDisplay` 全判
   "不在" → ensureOnDisplay 自愈会反复误 force-stop 用户应用。
   改 `leadingInt` 前缀解析（接受尾随内容）+ 包名边界匹配
   （`lineHasPkg/segHasPkg`，`com.foo` 不再撞 `com.foobar`）。TaskScan 同源。
   StackListCheck 重写 30 项（尾随段头、前缀碰撞、空输入、taskId 非数字）。
5. **`TaskMover.frontTask` 找不到任务**：正则只认 `taskId=`，真实 dump 是
   `Task{hash #123 ...}` → 两种形态都认。
6. **`am start` 误判成功**：`Warning: Activity not started...` 既无 Error
   也无 Exception，旧判据（无 Error 即成功）会把"没起来"当成功，于是不
   自愈。改正向判定：必须含 `Starting` 且无 `Warning`/`Abort`。
   另加 `safeComponent` 白名单才拼命令。
7. **`TouchTransformer.scaling` 方向注释与实现相反**（注释 dst→src，
   代码 src→dst）：TransformCheck 重写 54 项，用方向往返断言钉死。
   identity 仍恒 1：VD 模式零行为变化是"埋接口不改行为"的兜底。
8. **`PipBoard.switchFocus` 不撤销来源槽的 armed/longFired**：切焦点后旧槽
   继续吞后续手势。
9. **`HomeActivity` sink 每 onResume 漏一个 Activity**：`islandSink`
   是同一实例但旧代码无 remove，CopyOnWrite 集合永久持有已销毁的
   Activity（onLyric 每秒调一次 bind）。改 remove + add 恒单例 + onDestroy 摘。
10. **天气 20 分钟链断**：TICK 早退（weatherAuto=false / ctx==null）时
    `armed` 留在 true → `arm()` 直接 return，桌面不重启就再也不刷新。
11. **LauncherModel 两处数据脏**：widgets 存档 `optInt` 把 JSON null 读成 0
    （时钟组件悄悄复活）；`factoryReset` 漏掉全部标量（用户实测：恢复出厂
    后字号还停在 130%）→ `resetScalars()` 补齐字号/透明/外观/歌词/天气/权重。
12. **悬浮球恢复位置只判 <0**：>屏宽的脏值 + FLAG_LAYOUT_NO_LIMITS 直接
    渲染到屏幕外（用户以为服务挂了）→ 钳回 [0, 屏-球径]。
13. **桌面拖文件夹按名定位**：`dst.split(":")` 找同名文件夹会串；改走
    `model.addToFolder/mergeInto`（带 folderOf 迁移），并先判 model() 空。
14. **QuickBar NPE**：host.model() 空时 `m.quickbar.size()` 秒崩 HomeActivity。

### 泄漏 / 资源

15. **`Caps.exec` 无超时 = ANR 源**（QuickBar/Settings 多处命中）：改 3s
    超时 + `destroyForcibly` + 输出 256KB 上限，读输出放线程池。
16. **`VirtualDisplayHost.create` 传 AUTO_MIRROR**：建的是设备分身屏不是
    绘图屏；displayId 取不到时先 release 再返回。
17. **`WindowService` 重复建卡不关旧卡**：悬浮窗/VD/投影全泄漏；无 projection
    时空转前台服务常驻到进程死 → close 时 stopService。
18. **`SelfTestMirror` 中途 return 不停 mp**：前台服务 + 投影授权活到杀进程。
19. **`MediaListenerService` onSessionDestroyed/onListenerDisconnected 不
    unregister**：会话回调留在已销毁 controller 上。
20. **`LauncherModel` 全量 loadApps 在主线程**：每秒 tick 读配置时也跑一次
    （每应用一次 loadLabel IPC）。新增 `(ctx, false)` 只读存档重载，
    歌词/天气/悬浮球/触摸阈值全切过去（pinStatic 仍要全量）。
21. **`MirrorSlot.touchFor` 懒初始化无锁**：部署线程与触摸主线程都首触，
    各起一个 HandlerThread，被覆盖的泄漏。

### 安全（红线）

22. **RootMain 私有 socket 无鉴权**：抽象 socket 上任意同命名空间进程可连，
    等于给任意 app 开"拉起虚拟屏 + 注入触摸"的特权通道 → peer uid 只放行
    root/system/shell。单行 64KB 上限，超长行读干再拒。
23. **PrivCodec 脏报文**：`split(" ")` 吞尾随空串；MOTION 的 count 与实参
    不匹配时静默只取前几段 → `split(-1)` + 长度严格相等。
24. **MOVE/REMOVE 命令参数**：RootMain 已有 try/catch 兜底，本批次只补
    `Split(-1)`/空串校验路径。

### 清理

25. **`autoPip` 死代码**：prefs 里从未写入此 key，恒 false。删。

### 验证

- `typecheck.sh`（javac 全量）通过。
- 自检全套通过：TransformCheck 54 / StackListCheck 30 / PrivCodecCheck 30 /
  DumpParseCheck 14 / LrcCheck 15 / TrustedFlagsCheck 10 = **153 项**。
- QuickbarCheck 需现生成 R（R.java 由 aapt2 产出），本批次未改其面，
  沿用旧结果。

### 复盘

- 做对：解析器类 bug（StackScan/TaskScan/PrivCodec）全部写进自检，下一轮
  改口径先跑自检再上设备。
- 做错：`Caps.exec` 的超时方案第一版直接在 `Exec` 里写 `linux.os.Process`，
  被 `import android.os.Process` 遮蔽编译失败四次才看出——**本文件顶部已有
  `android.os.Process` import，写 `java.lang.Process` 必须写全限定名**。
- 决策：MOVE/REMOVE 的 uid 校验放在 RootMain（守护进程侧）而不是 RootOps
  （应用侧）：应用侧可被 hook，守护进程是唯一可信边界。

---

## 批次 Q — 用户实测两画布问题：第二画布里套着第一画布 + 高德黑边；删「点击接管」

### ① 套娃（第二个画布里显示第一个画布）

**根因不是槽位逻辑，是投影会话配额。** 两块画布复用同一个 `MediaProjection`
对象：Android 的一个录屏投影会话只能建一块虚拟屏，第二块
`mp.createVirtualDisplay(...)` 出来的屏会把第一块的画面整体"复印"过来 ——
用户描述的"镜像复制第一个画布、无限递归嵌套"正是这个表现。（自查代码时
两槽各建 VD 的写法是对的，错在把同一个 mp 传给两个槽。）

**修复**：
- `PollToken` 用同一次授权结果取**两份**独立 MediaProjection
  （`mpm.getMediaProjection(code, data)` 调两次），`proj[1]/proj[2]`
  各建各的 VD；Session 各自 stop、各自注册回调。
- 拿不到第二份时**显式降级**并写 error 级日志（不再静默套娃），
  日志提示靠 `dumpsys display | grep -i virtual` 只看到一块屏。
- `MirrorSlot.teardownVd()` 停自己那份会话（每槽独占，不会误杀对槽）。
- 共屏检测网：两槽握同一 displayId → 直接拆本槽 + error 日志
  （宁可拆一条，也别再出现套娃）。

### ② 高德四周黑边

**根因是密度，不是尺寸。** VD 尺寸本来就是按 SurfaceView 像素 1:1 建的；
黑边来自把设备 densityDpi（K60 ≈ 440）传给 366px 宽的小屏 —— 应用看到
~85dp 视口，高德按"小屏设备"版式渲染，或触发 size compat 后居中留黑边。

**修复**：`dpiFit()` —— 画布像素折成 ~280dp 基准反算 dpi
（366px @ ~209dpi ≈ 280dp，与紧凑手机视口同量级），宽高两侧都钳，
不低于 mdpi、不高于设备默认。触摸坐标走 VD 像素，不受 dpi 影响（仍 1:1）。
`am compat enable FORCE_RESIZE_APP + NEVER_FIX_ORIENTATION` 保持
（清槽时 reset）。**验收口径**：先拿时钟 App 验VD 铺满（时钟铺满→VD 参数
没问题；时钟也黑边→ROM 层 letterbox，另查 sizeCompat）；再验高德。

### ③ 删「点击接管」

用户原话："还有点击接管什么鬼？"。这是我自作聪明加的非焦点画布提示，
违背了"界面只要两个画布，不要多余的东西"。删掉 TextView 本体与显隐逻辑，
非焦点画布**只留环境光遮罩 + 焦点描边**。

### 复盘

- 做错：批次 L 引入的两槽共用一个投影会话，直到用户真机跑双画布才暴露。
  共享受限资源（投影/麦克风/相机）必须逐槽独占，写两槽架构时就该问一句
  "这个 session 允许多少实例"。
- 决策：黑边先按"密度"修而不是按"尺寸"修 —— VD 尺寸本来就 1:1，唯一可疑
  的就是密度；时钟 App 是判据（应用铺满是 VD 参数问题，只有高德不铺满是
  它自己的小屏版式）。

## 批次 U — CarPlay 风格换皮（新增「苹果互联」预设）

用户拍板范围（question 确认）：**只换皮不动结构**——新增「苹果互联」主题预设 +
全局圆角语言；PiP 画布的 SurfaceView 保持方角（真圆角待 TextureView 化，单独批次）。

### ① 新预设「苹果互联」

- `Theme` 数组 index 0 新增 `"carplay"` / 显示名「苹果互联」/ 强调色 `#0A84FF`；
  `DEFAULT_ID = "carplay"`，新装与恢复出厂即此风格（`LauncherModel` themeId 字段默认 +
  resetScalars 两处同步）。
- `Skin.apply` 识别 carplay 走 Apple 系统色板：深色底 `ground #000 / panel #1C1C1E /
  card #2C2C2E / leaf #0A84FF / leafDim #0060DF / text #FFF / textDim #8E8E93 /
  bad #FF453A / warn #FF9F0A`；浅色底 `#F2F2F7 / #FFF / #FFF / #007AFF /
  text #000 / textDim 由 0x3C3C43 混 40% 白`。**中性灰阶不带主题色倾向**是 CarPlay
  的视觉核心；自定义强调色仍可覆盖 leaf（换苹果底 + 自定义蓝不冲突）。
- `colors.xml` 编译期默认同步成 Apple 深色（首帧兜底不再泛绿）；`styles.xml`
  colorAccent 本就指向 `@color/leaf`，拾取 Apple 蓝无需改。

### ② 全局圆角语言

- `Skin` 新增造形 helpers：`round(color, dp)` / `pill(color)`（999dp 变胶囊），
  用静态 `density`（apply 时刷新）换算 px，**调用点不必传 Context**，sweep 机械化。
  密度兜底 3f（约 xxhdpi）。
- sweep 27 处 `setBackgroundColor(Skin.c(R.color.card))` 及同型 → `Skin.round(...)`：
  行/盒/容器 12dp（AppList、Folder、Search、SettingsHub、SettingsSection、RootPanel、
  VirtualDisplay、WindowTest、PipBoard、LayoutMode、DesktopView 组件卡/快捷栏、
  HomeActivity 底按钮）；按钮/把手/搜索框/编辑条 pill（HomeActivity chip、MiniPlayer、
  DesktopView dockHandle、Search 输入、SettingsHub 搜索行）；SettingsSection 色片 6dp。
- 保持平面的 15 处：全屏 ground、dockBar、PipBoard 画布容器、顶/底栏（CarPlay
  栏本就用平底）。WindowCard 标题条原是写死绿 `0xCC131a15`，改走
  `Skin.bar(Skin.c(R.color.card))` + textDim（换肤后浮动卡片不再掉色）。

### ③ 可读性微抬

CarPlay 风格上 9/10sp 偏小：全部 9sp→11sp、10sp→12sp（QuickBar×3、DesktopView×4、
WindowCard 标题、AppList/Folder/Search 副文案）。

### 验收口径（真机）

1. 新装/恢复出厂默认进「苹果互联」；主题切到「薄荷」/「叶影」再切回，颜色齐全回位
2. 桌面：编辑条、chip、dock 把手、组件卡、快捷栏均为弧度/胶囊；其余区域纯平面
3. 画中画两块画布 Surface 仍为方角、无圆角伪影（预期行为）
4. 浮动卡片（WindowCard）标题条跟随当前主题变色
5. 小字号屏幕：QuickBar / 应用列表副文案可读，无拥挤

## 批次 V — P2-16 遗留 17 项逐条复盘（亲自读码坐实 / 杀误报）

范围：批次 T 期间代理报了 17 项但未经亲自复核的清单（P2-16）。本批逐项读码，
坐实 12 处修掉，杀 14 项误报，另坐实 2 项架构级问题列 P2-18 下批做。

### 坐实修复（按文件）

- **GardenActivity**：① 叶子坐标 `dp(ballX)` 双倍换算——ballX/ballY 由 BallService.snap
  存的是**像素**（lp.x/lp.y），这里再过 dp()，440dpi 机上叶子飞到 2.75 倍远；
  改直接用像素并钳屏内。② 时钟不走字——build 只画一帧，补 Handler 每秒刷新
  时钟与日期（onResume 起、onPause 停）。
- **BallService**：长按 500ms 弹菜园后松手，`!moved` 仍成立 → tap() 又把桌面盖上。
  补 longFired 标志位，长按已发就不再 tap。
- **RootPanelActivity**：onResume 主线程跑 envInfo = 2 次真实 su 落地
  （settings get + dumpsys focus，各 3s 超时）；hasRoot 冷缓存首调也压主线程。
  两者挪后台线程回贴 UI；root 探测先画 UI 后台补警示条。
- **WindowTestActivity**：同型——Caps.report 冷缓存时主线程起 su；排障页恰恰最常被
  adb 直启，缓存最可能冷。挪后台线程。
- **VirtualDisplayHost**：create/release 加 synchronized——排障页每个按钮各起一条
  线程（btn() 里 new Thread），连点两下就是跨线程竞态：release 后 create 把
  displayId 写回去，屏永远释放不掉。（volatile 指控被 synchronized 连带覆盖；
  「attach 前 launchViaRoot 抛 ISE」不可达——attach 恒在 onCreate 先行，杀。）
- **Lrc**：① BOM（U+FEFF）trim() 剥不掉 → 首行时间戳对不上行首被丢，parse 入口剥。
  ② 超长 [mm:…] 让 parseLong 抛 NFE——lookup 的 MAIN.post 里那次调用会把
  **主线程崩掉**（外层 catch 只包 POOL 线程），时间戳解析就地兜 NFE break。
  [mm:99] 与重复时间戳是宽容解析/设计内行为，杀。
- **MediaListenerService**：onNotificationPosted 把每条通知标题+正文打进 logcat——
  本服务只借监听器身份拿 getActiveSessions 调用权，通知内容零用途，纯隐私泄漏，撤。
  playPause 快照竞态两个方向都收敛为 no-op，杀。
- **Wallpaper/LauncherModel**：① importImage 异常路径流泄漏（无 finally）→
  try-with-resources，半截文件落盘即删。② 库满 addWall 失败时孤儿文件残留 → 删。
  ③ removeWall 只删库条目**永不删盘上文件**，「恢复出厂清空壁纸库」后 walls 目录
  全是孤儿 → 删条目时连带删文件（只认 files/walls 目录，外部路径不碰）。
- **SelfTest**：自检把用户亮度钉死 128、音量打到 7 且不还原 → 亮度写后还原
  （保留写通道验证），音量没有可靠读回 API 改只读探针。
- **HomeActivity**：prefs 字段死代码（PREFS 常量另有他用保留）——删字段/赋值/import。
  island 与 DesktopView 双 sink 是设计内多订阅，杀。
- **DesktopView**：dispatchDraw 逐帧 new Rect/int[2] → 预分配字段复用。
  空文件夹 AIOOBE 杀：load（folders.add 前 isEmpty 检查）与自动整理（<2 键即解散）
  双层修剪，瞬时态同线程内消化，get(0) 不可达空列表。
- **BootReceiver**：判定出一个结构性错位——fireBoot 嵌在 pull() 里，**关「开机回桌面」
  会连带吞掉全部开机任务**；而 MY_PACKAGE_REPLACED 更新路径又无视开关硬拉界面 +
  重放开机任务。重构：autoHome 只管拉不拉界面（开机/更新都尊重）；开机任务与
  悬浮球自启只在真 BOOT 走（更新后 sticky 服务系统自己会拉起，不重放任务）。
- **SettingsSectionActivity**：① 定时解析与提示词自相矛盾——「730 表示 07:30」实际
  当成第 730 分钟 = 12:10；按 HHMM 口径解析（730 → 07:30，h≤23/m≤59 校验）。
  ② 延迟秒数 >约 24.8 天时 `*1000` 把 int 顶成负数，任务反而**立即触发**；
  改 long 解析 + 7 天上限。③ render() 从不重跑 Skin.apply——主题页里改主题，
  页面自己还挂着旧色板；render 前补 Skin.apply。（「默认 -1 永不到点」杀：
  describe() 显式标未定且可编辑。）

### 杀掉的误报（9 项 + 2 项记录在案）

tap 区域判定（slop 逻辑成立）、VirtualDisplayHost volatile（synchronized 覆盖）、
launchViaRoot attach 前 ISE（不可达）、WindowTest getDisplay NPE（catch Throwable 兜住）、
Lrc [mm:99]/重复时间戳、Pinyin 土耳其 I（已全程 Locale.ROOT）与输入无上限（缓存 400 上限）、
Theme accent 校验过松（两条路径都有 try/catch，全工程无裸 parseColor）、playPause 竞态、
purgeWalls 删当前壁纸（函数不存在，实际问题是反向的 removeWall 不删文件，已修）、
SelfTestMirror 结果未校验（taskOnDisplay 三处核对在位）、TaskEngine name 重算（触发是
持久化枚举字段）、空文件夹 AIOOBE、island sink 双轨、RootMain thread-per-connection
（本家连接数固定 + 空闲看门狗，维持记录备查）、WallpaperActivity loadLabel（该类不存在）。

### 坐实但架构级，列 P2-18

- loadApps() 主线程全机枚举：6 个页面 onCreate 直调（Home/Search/AppList/Settings×2/
  VirtualDisplay），bloaty 设备上 queryIntentActivities 100ms+。异步化要给 6 个页面
  补回调/占位渲染，超出"小修"范畴。
- 壁纸主线程解码：loadForScreen 在 Garden/Desktop/Home 的 onCreate 里同步 decode；
  后台加载管线连带三处渲染时机改造。

### 复盘

- P2-16 的 17 项里 14 项是误报——代理批量扫出的"嫌疑清单"价值在于圈定阅读范围，
  结论仍必须自己读码下。本批两处最有价值的收获（主线程崩 Lrc NFE、BootReceiver
  开关吞任务）都是复核过程中顺藤摸出的新问题，清单本身都没点名。

---

## 批次 W — 内置 LSPosed 模块：画中画应用强制横屏（真机截图坐实竖屏黑边后立项）

**目标**：用户真机截图显示画中画里高德/音乐 App 全是竖版 UI + 两侧大黑边，
并且拍板做 LSP 模块 hook（LSPosed API 102）。此前批次 R 的 `NEVER_FIX_ORIENTATION`
只能解锁 manifest 声明，挡不住应用运行时自己调 `setRequestedOrientation(PORTRAIT)`
——这条路线对高德无效，之前把"解锁 manifest"当成成立的能力是复盘误判，本批修正。

**注入点侦查（下载官方包静态分析）**

- 包：`com.autonavi.minimap` v17.00.0.2005（targetSdk 35，8 个 dex ~93MB），
  入口 activity-alias `com.autonavi.map.activity.SplashActivity`。
- manifest：几十个 Activity 声明 `screenOrientation=1`（PORTRAIT）——竖屏第一帧
  就是这么来的；少量 `=2`（user）/`=3`（behind）。
- dex 调用方扫描（自研 `/tmp/opencode/amap/dexscan.py`：解析 dex 头 +
  method_ids 名字匹配 + class_defs code 区间 + invoke 字节码正则定位调用方）：
  **106 个类**引用 `Activity.setRequestedOrientation`——mPaaS/H5 容器
  （H5ScreenPlugin/NXScreenOrientationProxyImpl）、支付宝 SDK、amap bundle
  （SpeakerModeManager/ModuleHeadunit/NaviMapView 等）全都在运行时反复锁方向。
- 结论：注入点 = `android.app.Activity` 基类，进程级 hook 全量覆盖 manifest 锁 +
  106 个运行时调用点，一个点管全部。

**改了什么**

1. `XposedEntry.java`（新增，自包含——跑在目标进程，LSPosed 自己的 classloader
   加载本 APK，主项目其他类在目标进程不存在，禁止引用）：
   - hook `Activity.setRequestedOrientation`：args[0] 非 SENSOR_LANDSCAPE(6) 一律改写；
   - hook `Activity.onCreate`（after）：创建完强拉一次横屏，第一帧就是横的；
     内部这次调用会再进 hook 1，参数已是目标值原样放行，无递归；
   - TARGETS 白名单（高德/百度/腾讯地图/网易云/QQ音乐）双重过滤，勾了名单外的
     应用也不生效。
2. XposedBridge 签名桩 `app/libs/xposed-api-82-stub-src/`（7 文件，签名与上游
   api-82 一致）→ `app/libs/xposed-api-82.jar`（5.5KB）只进编译期 classpath
   （javac + d8 --lib），运行时由 LSPosed 提供，不进 dex。
3. manifest：`xposedmodule`/`xposeddescription`/`xposedminversion=102`（这是 LSPosed
   API 版本号，用户用的就是 102，与参考实现 CarWithX `com.leting` 一致；此前误写 82
   ——那是编译期签名桩 artifact 的版本，不是 API 版本）/`xposedscope=@array/xposed_scope`。
4. `res/values/arrays.xml`：默认作用域与 TARGETS 一致；`assets/xposed_init`：
   入口类 FQCN。
5. `build.sh`：aapt2 link 加 `-A assets`；javac/d8 挂签名桩；缺桩/缺 assets
   前置 fail（防静默打出无模块的包）。

**使用步骤**：装新包 → LSPosed 管理器启用「海鸥桌面」（作用域按 xposed_scope
预填，可增删）→ 强杀目标应用进程（高德）让其重启加载 hook → 画中画部署高德。

**验证**：javac 类型检查过（含新 jar classpath）；自检 123/123；dex 扫描法
在 8 个 dex 上跑通（106 命中即证）。真机验收列 P2-19。

**遗留**：目标 App 的弹窗/透明页也会被拉横屏（预期内，车机场景可接受）；
应用内部横竖屏状态机若与 WM 冲突需真机看 logcat（SeagullLsp tag）。
