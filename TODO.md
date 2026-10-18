# 后续开发清单

> 基线：`FEATURE-MATRIX.md`（205 条，5 组 / 17 分区）
> 顺序原则：先让「桌面」真的能用（纯公开 API），再加特色功能，最后做 root 增强层。
>
> **状态**：P0~P2 的代码全部落地（批次 A~I，见 `docs/DEVLOG.md`）。
> 标 🟡 的是「代码已写、未在真机验收」——容器里做不了 root 命令、悬浮窗授权、录屏授权与车机布局验证。
> 接手前先读 `docs/AI-GUIDE.md`（怎么改、怎么验证、怎么排障）。

---

## P0 —— 让桌面可用（全部公开 API，无权限依赖）

### P0-1 持久化与数据层 ✅ 已完成
- [x] 统一 `PREFS`：`LauncherModel` 曾用 `"seagull_launcher"`，与 `HomeActivity.PREFS = "seagull"` 不一致，
      导致写入与读取是两个文件 → 已改为引用 `HomeActivity.PREFS`
- [x] `apply()` → `commit()`，保证首次启动立即落盘
- [x] `LauncherModel` 扩展为全量配置单一事实源（布局/Dock/快捷栏/菜园/小白点/外观/屏幕/岛屿/天气/歌词/窗口/触摸/任务）
- [x] 存档加版本号 `v`，枚举用 `optEnum` 容错（旧存档不认识的值回落默认，不抛异常）
- **验收**：`logcat -s SeagullDiag` 显示 `xml=.../seagull.xml exists=true`

### P0-2 设置骨架 🟡 代码已写，未验收
- [x] `SettingsHubActivity`：5 组 / 17 分区入口，逐字对齐原文案
      - 桌面 → 布局 | Dock 栏 | 快捷栏 | 菜园 | 小白点
      - 外观 → 主题与壁纸 | 屏幕 | 野菜岛
      - 天气与歌词 → 天气 | 歌词
      - 高级功能 → 窗口 | 触摸 | 自动化任务 | 开机动画
      - 系统与工具 → 系统 | 车机工具 | 关于
- [x] 设置项搜索（全局搜设置项名 + 应用名，结果可直接启动应用）
- [x] `SettingsSectionActivity`：17 个分区页，接上 `LauncherModel` 全部已有配置字段的
      UI 与持久化（选择 / 开关 / 滑杆 / 输入对话框 / 应用选择器）
- [x] `Theme`：14 套内置配色表 + 自定义主题色解析（选择与预览已落，全局换肤在 P0-7）
- [x] 已接的最小生效项：Dock 位置=不显示时隐藏、Dock 数量截断、组件条透明度、保持亮屏（公开 API 路径）
- [x] 旧 `SettingsActivity` 改为兼容跳转；桌面「设置」按钮指向 Hub
- [ ] 真机验收：从桌面进设置，能点进每个分区并返回，返回后配置不丢
- **验收**：从桌面进设置，能点进每个分区并返回，返回后配置不丢

### P0-3 文件夹闭环 🟡 代码已写，未验收
- [x] `LauncherModel.mergeInto / renameFolder / dissolveFolder / removeFromFolder / addToFolder`
- [x] `FolderActivity`（打开 / 改名 / 解散 / 移出）
- [x] `DesktopView` 拖拽压合（`onInterceptTouchEvent` + `hitTest`）
- [x] 拖到文件夹图标上 → 并入（`@folder:` tag 分支已写）
- [x] 自动归类：`LauncherModel.CATEGORIES/categoryOf/autoGroup`
- [x] 长按图标 → 上移/下移/置顶/固定/拿下/固定到 Dock/应用信息（批次 D）
- [ ] 真机拖拽验收
- **验收**：长按 A 拖到 B 上松手 → 生成文件夹；点进去能改名/移出/解散；杀进程重进仍在

### P0-4 应用搜索 ✅
- [x] `SearchActivity`：输入即时过滤（应用名 + 包名）
- [x] 拼音首字母匹配（`Pinyin.java`，ICU `Transliterator "Han-Latin"`）
- [x] 搜索结果可直接启动 / 固定到 Dock
- **验收**：输入 2 个字符出结果，点结果能启动

### P0-5 Dock 全配置 ✅
- [x] 位置（底部/左侧/右侧/不显示）
- [x] 数量（1~8）
- [x] 宽度（底部时=高度）
- [x] 图标大小 / 间距
- [x] 透明度（实时预览）
- [x] 自动收起（把手）/ 固定
- [x] Dock 时钟开关；长按 Dock 应用 → 默认开在几号窗口（`MirrorActivity` 双槽）
- [x] 数量改小时保留多出的应用
- **验收**：改任一项立即生效且重启后保持

### P0-6 快捷栏 ✅
- [x] `QuickBar` 组件：组件条里的一条小 Dock
- [x] 每格放应用或功能按钮（7 个功能：亮度/音量 ±、静音、Wi-Fi、蓝牙、飞行模式）
- [x] 每个桌面布局各有一份（按 `LauncherModel.Mode` 分组存 `quickbars`）
- [x] 长按组件条 →「+」→ 选组件 / 长按组件 → 管理
- [x] 自检 `app/selfcheck/QuickbarCheck.java`
- **验收**：快捷栏能放 3 个应用 + 2 个功能按钮并点击生效

### P0-7 主题与壁纸 ✅（毛玻璃 6.10 暂缓）
- [x] `Theme` + `Skin`：14 套内置配色 + 自定义十六进制主题色
- [x] 日夜模式（跟随系统 / 深色 / 浅色）
- [x] 壁纸：白天 / 夜间两套，从系统相册选
- [x] 壁纸库（上限 24，满了给提示）
- [x] 背景遮罩 `wallDim`
- [x] 文字颜色自动（壁纸亮度抽样 16×16）+ 自动字底
- [x] 全局字号缩放（`BaseActivity.attachBaseContext` 改 `fontScale`，全用 sp）
- [ ] 毛玻璃（6.10）：要 RenderEffect，暂缓
- **验收**：切主题全局变色，换壁纸立即生效，重启保持

### P0-8 屏幕设置 ✅
- [x] 屏幕外边距（左右 / 上下）
- [x] 窗口之间的缝
- [x] 屏幕方向（跟随系统 / 固定横屏 / 固定竖屏）
- [x] 横屏 / 竖屏两套边距与缝（`portMarginH/V`、`portGap`）
- [ ] 保持亮屏（`SysOps.keepScreenOn` 已实现，接 UI）
- [ ] 顶部信息栏（日期 / Wi-Fi / 电量）开关
- [ ] 自动同步网络时间（接 UI）

---

## P1 —— 特色功能

### P1-1 菜园 🟡 代码已写，未验收
- [x] 长按叶子进菜园，再长按退出
- [x] 大时钟 + 歌词（歌词接批次 G 的 `Lyrics`）
- [x] 壁纸压暗 `gardenDim`（浅色主题下遮罩换黑色）
- [x] 返回键回桌面
- [x] 菜园里点叶子出应用列表
- [x] 3 种摆法（居中大钟 / 左上时钟+底部歌词 / 大字时间日期）
- [ ] 进菜园时画中画窗口隐藏（4.7，需要窗口焦点控制）

### P1-2 小白点 🟡 代码已写，未验收
- [x] `BallService` 悬浮球（`TYPE_APPLICATION_OVERLAY`）
- [x] 可拖到任意位置，松手贴最近左右边
- [x] 位置记在本机
- [x] 点一下回桌面，长按进出菜园
- [x] Dock 关着时点悬浮球改为出全部应用
- [x] 大小 / 透明度；开机自启

### P1-3 天气组件 ✅
- [x] `Weather.java`：Open-Meteo 公开接口（免 key；和风要 key，见矩阵 9.7）
- [x] 城市来源（自动定位 ✅ / 手动指定 ✅ / 按网络判断 🟡 退化为沿用缓存城市）
- [x] 当前天气 + 未来 3 天
- [x] 每 20 分钟刷新 + 点按手动取
- [x] 组件条天气格

### P1-4 歌词组件 ✅
- [x] 媒体会话（`MediaSessionManager`，一秒一跳）
- [x] 来源：播放器自带 🟡（公开 API 读不到任意 metadata 键）/ 本地 .lrc ✅ / 在线酷狗 ✅
- [x] 行数（自动 横屏 5 / 竖屏 3）、字号
- [x] 对时偏移（±0.5s / 1s）
- [x] 歌词缓存 + 清空（`app/selfcheck/LrcCheck.java` 15 项自检）
- [x] 这首不显示歌词 / 这首歌词不对（换版本）
- [x] 状态栏 / 通知文字歌词
- [x] 媒体卡片行数（设置项已接，卡片本体未做）

### P1-5 自动化任务 ✅
- [x] `TaskEngine`：触发器 + 动作
- [x] 触发：桌面启动 / 系统启动 / 定时（每分钟对表）
- [x] 动作：延迟 / 在画中画打开应用 🟡 / 移除主屏任务 / 打开应用
- [x] 新建 / 删除 / 启停
- [x] 上限 20 条
- [x] 随桌面配置一起导出

### P1-6 野菜岛 🟡 代码已写，未验收
- [x] 顶部胶囊浮层（`IslandView`，浮在桌面这层）
- [x] 距顶部偏移（挖孔 / 圆角）
- [x] 宽度 + 歌词省略
- [ ] 状态栏隐藏时浮在窗口上（8.6，要再开一个悬浮窗）
- [ ] 挖孔/圆角自动检测（8.7，用手动偏移代替）

### P1-7 关于页 ✅
- [x] 版本信息
- [x] 检查更新（GitHub `releases/latest`）
- [x] 自更新（下载 apk 交系统安装器）
- [x] 体检报告（能力探测汇总）
- [x] 安装包指纹（签名证书 SHA-256）

---

## P2 —— root 增强层（自动隐藏，无 root 不影响安装）

### P2-1 窗口镜像 🟡 代码已写，未在车机复验
- [x] `MirrorSlot`：`MediaProjection.createVirtualDisplay` + root 搬应用 + 触摸注入
- [x] 正式 UI（`MirrorActivity`）：授权 → 选应用 → 建屏 → 搬应用 → 渲染到 `SurfaceView`
- [x] 双槽位 A/B
- [ ] 亮度/缩放/裁剪

### P2-2 窗口搬运与保活 🟡 代码已写，root 命令待真机验证
- [x] `dumpsys activity activities` 解析 `displayId=` 找 `taskId`
- [x] 收回窗口 / 从边缘小标签拉回（`TaskMover`，按序试三条 `am` 命令并回传真实输出）
- [ ] 窗口丢失自动重建（需 L3）
- [ ] 重启后恢复窗口（需 L3）

### P2-3 触摸转发调优 ✅（无障碍通道只留开关）
- [x] 防误滑阈值、长按判定（300/500ms）
- [x] 跟手（`touchFollow`，每 ≥16ms 一小段）
- [x] 触摸方式选择（root input / 无障碍）、注入排障日志
- [x] 修 `input -d N input tap` 双写 input（批次 J）
- [ ] 无障碍 `dispatchGesture` 通道：整套无障碍服务授权成本高，暂缓

### P2-4 系统监控增强 ✅
- [x] CPU（`/proc/stat` 两次采样差）/ 温度 / 内存一行读数
- [x] 一键清理后台（`SysOps.killBackground`）
- [x] 息屏暂停 + 亮屏恢复（开关已接）

### P2-5 root 守护进程与 L3 修复 🟡 代码已写，真机待验（批次 J + 批次 K）
- [x] `PrivCodec` 线协议 + `PrivCodecCheck` 30 项自检（纯 JVM）
- [x] `PrivClient`：`su -c setsid app_process` 拉起守护进程、断线重连、会话级禁用
- [x] `RootMain`：反射 `startActivityAsUser`（setLaunchDisplayId）/ `injectInputEvent`（多指）/ `moveRootTaskToDisplay` / `removeTask`
- [x] 守护进程在位时触摸改原始事件中继（多指、零命令开销）
- [x] 复核 `findTaskId` 的 dumpsys 口径（已知坑 #11）—— 段头归属，`TaskScan` + `DumpParseCheck` 14 项
- [x] **批次 K：画中画三条实测抱怨根治**
  - [x] 抱怨③「进去桌面还不是画中画界面」：TRUSTED 屏优先（`RootOps.grantTrustedDisplayRole` 授 COMPANION_DEVICE_APP_STREAMING → `TrustedFlags` 5 组候选 + 受信位 1<<7 校验），MediaProjection 屏回落 `projectionFallbackFlags()`
  - [x] VD flags 从 `PUBLIC|AUTO_MIRROR` 改为 `PUBLIC|OWN_CONTENT_ONLY|PRESENTATION`（AUTO_MIRROR 会把手机桌面镜像进画中画，参考实现已证伪）
  - [x] 启动 flags 0x18000000 → **0x18800000**（补 EXCLUDE_FROM_RECENTS，singleTask 目标不带 MULTIPLE_TASK 会拉主屏已有任务到前台）+ `--user 0`
  - [x] 抱怨①「画中画应跟随画布大小」：VD 按 SurfaceView 实际像素 1:1 建（`MirrorActivity.canvasW/H`），触摸 scale=1f 零换算
  - [x] 抱怨②「点击不进去软件」：1:1 建屏修坐标偏移；另加 `RootOps.ensureOnDisplay` 保守自愈（singleTask 应用虚拟屏内跳转拉走任务时搬回，解析失败不重拉）
  - [x] `TrustedFlagsCheck` 10 项 / `StackListCheck` 12 项自检（前者需 `-cp android-33.jar`）
- [ ] 真机验证（用户操作）：① `am role get-role-holder` 是否真授到 ADD_TRUSTED_DISPLAY、dumpsys display 看 seagull-pipN flags 是否带 TRUSTED ② SELinux 放行角色授予/abstract socket ③ ROM 是否裁剪 `moveRootTaskToDisplay`

### P2-6 画中画界面极简化 + 常驻 + 统一签名 ✅ 代码已写，CI 待验（批次 L）
- [x] **抱怨「每次进入桌面还是应用界面」根因修复**：VD 生命周期改挂进程级 `MirrorHost`，退出画中画页只断 Surface 不拆屏（旧版 onDestroy 拆屏 → 屏上任务倒回默认屏 → 桌面冒全屏应用）
- [x] **界面只剩两块画布**：删掉全部按钮/诊断区/机制说明/小标签；两画布各占半屏，长按选应用（GestureDetector 截长按 + 补 CANCEL 掐 TouchForward 残留笔画），对话框带「清空该槽」
- [x] 部署后立即 `ensureOnDisplay` + 桌面 resume 自愈（`MirrorHost.healHome`，3s 节流）
- [x] **统一签名**：`keystore/seagull-release.keystore`（唯一签名文件）+ build.sh/CI 改造 + secrets（SEAGULL_KEYSTORE_B64 / SEAGULL_KS_PASS）+ `docs/SIGNING.md`
- [ ] 用户验收：装本包需先卸载旧版一次（旧包是随机签名，此后永久免卸载）；两次 CI 产物 `apksigner verify --print-certs` 指纹应一致

### P2-7 画中画成为首屏（左右分割 + 默认横屏 + 底栏精简）✅ 代码已写，CI 待验（批次 M）
- [x] **进应用即画中画**：新增 `PipBoard`（可复用面板），HomeActivity 内容层改成 PipBoard ⇄ DesktopView 二选一，默认画中画；桌面网格降为次级层（底栏「桌面/画中画」按钮切换）
- [x] **左右分割**：两块画布各 weight 1 左右排（旧版上下半屏废弃）；VD 仍按画布实测像素 1:1
- [x] **桌面默认横屏**：`LauncherModel.orientation` 默认 AUTO → LANDSCAPE
- [x] **界面精简**：底栏砍「镜像小窗」（子页面已无意义）与「叶」键（菜园走 设置 → 桌面 → 菜园 / 小白点长按）；顶栏「整理」只在桌面模式显示
- [x] `MirrorActivity` 薄壳化（躯干=PipBoard）：Dock 投应用入口 + SelfTestMirror 自检保留；画布逻辑两份并一份
- [x] 同槽部署/挂面串行化（`synchronized (slot)`），防并行双建 VD
- [ ] 用户验收：进应用首屏是不是画中画；切「桌面」网格正常；横屏下车机/手机都正常

### P2-8 CarPlay 纪律移植：单焦点 / 卡片化 / 空态 / MiniPlayer（批次 N）
- [x] **单焦点**（车机交互安全红线）：非焦点画布第一下触摸只切焦点不吃进 App；切换瞬间给失焦画布补 `ACTION_CANCEL`（多指鬼拖痕）；input 子命令通道不发 CANCEL，守护中继通道才会 —— 中继优先再添一实据
- [x] **空态大按钮**：≥80dp 高 + 16dp 圆角 + 半透明白描边，点一下直接弹选择器（空画布无 App 可误触）；长按仍是更换应用
- [x] **卡片化**（圆角暂缓）：深色卡底 + 1px 半透明白描边 + 8dp 缝；SurfaceView 窗外致 `clipToOutline` 圆角裁剪部分设备失效，换 TextureView 顺滑度不划算，方角+描边，卡 padding 12dp 预留真圆角
- [x] **环境光三档遮罩**：TYPE_LIGHT 采样（公开 API 免权限）+ 低通 + 20/2000 lux 回差，非焦点画布黑遮罩 0.45/0.35/0.28
- [x] **顶栏精简**：画中画模式压 40dp 只留 时间+设置；搜索/日期/整理下沉桌面模式
- [x] **MiniPlayer**：36dp 媒体条（只有上一首/播放/下一首，热区外扩 12dp），媒体会话不活跃则整条隐藏；数据源复用通知监听器 `getActiveSessions`，不新起服务不加权限；空白区点击双场景规则 —— 媒体源在画布内→导焦，在后台→什么都不做
- [x] **画布权重配置化**：`LauncherModel.pipWeightA/pipWeightB` 默认 1:1（钳 1~10），设置 → 窗口 →「画中画分割」三选一；权重变 → VD 尺寸跟着变仍 1:1
- [x] C 层只埋接口不接线：`CanvasSource` 接口 + `StreamCanvasSource` 占位 + `MirrorSlot implements CanvasSource` + `TouchTransformer`（`setDisplay(displayId, scaleX)`，identity 恒 1 与批次 M 行为一致）+ `TransformCheck` 21 项纯 JVM 自检全过
- [ ] 用户验收：单画布焦点切换跟手、空态按钮弹选择器、MiniPlayer 三键控音乐/双场景点击；切分割比例后 VD 尺寸变化
- [ ] **下一批**：DiPlay 推流接入（StreamCanvasSource 写解码帧 + ScalingTransformer 画布像素→iPhone 分辨率）、多指中继补缩放链自检、默认权重预设 7:3/3:7

### P2-9 批次 N 三个回归修复（选择器 / 高德铺满 / 应用跳主屏）✅ 代码已写，CI 待验（批次 O）
- [x] **选择不了应用**：卡内 SurfaceView `setClickable(true)` 吞掉整块画布触摸（长按/空态按钮全失效）→ SurfaceView 保持不可点击，触摸统一回卡容器；空态大按钮补 `setOnClickListener`（已知坑 §16）
- [x] **失焦 UP 外泄**：非焦点画布第一下只切焦点后，UP 仍被注进刚失焦的 App（地图里多点一下）/ 空画布顺带弹选择器 → `armed[w]` 门闩：仅 DOWN 时即焦点的画布才放行后续事件
- [x] **高德铺不满**：小尺寸怪宽高比 VD 上应用走 letterbox 兼容模式，按手机尺寸渲染居中留黑边 → 部署成功后 `am compat enable FORCE_RESIZE_APP + NEVER_FIX_ORIENTATION <pkg>`，清空槽 `am compat reset`
- [x] **应用跳主屏**：`launchOnDisplay` 从未 force-stop（已知坑 #5），`am start` 对已在运行应用静默投递主屏实例 → 部署前先 `am force-stop`；`selfHeal()`（目标在主屏用守护进程 moveRootTaskToDisplay 搬回）加进 30s tick 周期跑
- [ ] 用户验收：空画布点大按钮弹选择器、长按已选画布换应用；高德铺满画布（无黑边）；应用不再跳到手机主屏；若有残留回传 logcat（SeagullPipBoard 焦点切换 / SeagullRootOps ensureOnDisplay / am compat 日志）

### P2-10 批次 P 三轮复盘修复 🟡 代码已写，typecheck + 153 项自检通过，推送待发
用户原话："帮我全部代码复盘三次找bug"。三轮：并行分组精读 → 亲自复核 → 自检/交叉验证。确凿 bug 分级修（崩溃 → 数据丢失 → 红线 → 泄漏 → 健壮性）：
- [x] **P0 崩溃 ①** `BallService.longPressRun` 无 `FLAG_ACTIVITY_NEW_TASK`：从 Service 上下文起 Activity 秒崩（Android 12+ 更严），onDestroy 补 removeCallbacks；`snap()` 前先 `model.load()` 修快照回写覆盖新配置
- [x] **P0 崩溃 ②** `VirtualDisplayActivity` onCreate 没 `host.attach(this)`：回调里拿宿主=null；su 探测移后台线程；`append` 改 synchronized + 回主线程 setText
- [x] **P0 崩溃 ③** `Uri.fromFile()` 发安装 intent（targetSdk 24+ = FileUriExposedException）：纯 SDK 自绘 `SeagullFileProvider`（manifest + res/xml/file_paths.xml，零依赖不引 androidx）
- [x] **VD 故障态** `VirtualDisplayHost.create` 传 AUTO_MIRROR（设备分身屏）→ 去掉；displayId 取不到时 release 再返回
- [x] **焦点状态错乱** `PipBoard.switchFocus` 未撤销来源槽的 armed/longFired（切换后旧槽继续吞手势）；`requestConsent` 去重 + su 探测移后台；`pollToken` 覆盖前 stop 旧 projection；onDestroy `ui.removeCallbacksAndMessages(null)`
- [x] **am start 误判成功** `RootOps.launchOnDisplay` 只判 "无 Error"：`Warning: Activity not started...` 既无 Error 也无 Exception 会把"没起来"当成功 → 要求正向 `Starting` 且无 Warning/Abort；包名/组件名经 `safeComponent` 白名单才进命令
- [x] **触摸变换方向反了** `TouchTransformer.scaling` 注释说 dst→src，代码算的 src→dst（VD 模式会放大而非裁切）→ 纠正为 src/dst 并更新文档；`TransformCheck` 重写为 54 项（方向往返断言钉死）
- [x] **解析器漏任务 = 自愈失效** `StackScan` 同一 display 被 `displayId=0 stacks=2` 尾随内容覆盖成 -1（taskOnDisplay 全判"不在"→ 反复 force-stop 停掉用户应用）→ `leadingInt` 段头容错 + `lineHasPkg/segHasPkg` 包名边界匹配（com.foo 不再撞 com.foobar）；`TaskScan` 同源改边界匹配；`StackListCheck` 重写 30 项
- [x] **TaskMover.frontTask 找不到任务**：正则只认 `taskId=`，真实 dump 是 `Task{#42}` → 两种都认
- [x] **热区互抢** MiniPlayer 三按钮共用 TouchDelegate：第三个 expandHotZone 把前两个的有效区顶掉 → `MultiDelegate` 合派 + `installHotZone()` 钳回行边界（行外 1px 属于画布/底栏，不抢）
- [x] **会话泄漏** `MediaListenerService`：onSessionDestroyed/onListenerDisconnected 先 `unregisterCurrent()`；`HomeActivity` sink 恒单例 + onDestroy `removeSink`（旧实现每次 onResume push 一整个 Activity）
- [x] **主线程枚举应用** 每秒 tick 读配置时 `new LauncherModel(ctx)` 跑全量 `loadApps()`（每应用一次 loadLabel IPC）：新增 `LauncherModel(ctx, false)` 只读存档重载，歌词/天气/悬浮球/触摸阈值/pinStatic 之外全部切过去
- [x] **天气链断** Weather TICK 在 weatherAuto=false 或 ctx==null 早退时 armed 留在 true → 20 分钟链断掉，桌面不重启再也不刷新
- [x] **窗口卡片泄漏** WindowService 重复 START_CARD 不关旧卡（悬浮窗/VD/投影全泄漏）；无 projection 时空转前台服务常驻 → close 时 stopService；WindowCard.close 顺手停服务
- [x] **LauncherModel 数据脏** widgets 存档 `optInt` 把 JSON null 读成 0（时钟悄悄复活）+ toggleWidget 不校验范围；factoryReset 漏掉全部标量（字号/透明/外观/歌词/天气/分割权重，实测"恢复出厂"后字号还停 130%）→ `resetScalars()` 补齐
- [x] **悬浮球跑出屏幕** ballX/ballY 恢复时只判 <0，>屏宽的脏值 + FLAG_LAYOUT_NO_LIMITS 直接渲染到屏幕外（用户以为服务挂了）→ 钳回 [0, 屏-球径]
- [x] **桌面拖文件夹按名定位** DesktopView `dst.split(":")` 找名字相同文件夹会串，且 model() 可能为 null → 走 model.addToFolder/mergeInto，先判空
- [x] **su 悬挂 = ANR 源** Caps.exec 无超时，KernelSU 弹窗没人点时主线程被钉死 → 3s 超时 + destroyForcibly + 输出 256KB 上限
- [x] **私有 socket 无鉴权** RootMain 任意 uid 可发指令（拉起虚拟屏/注入触摸的特权通道）→ peer uid 只放行 root/system/shell；单行 64KB 上限，超长行读干再拒
- [x] **协议脏报文** PrivCodec.split(" ") 吞尾随空串；MOTION 的 count 与实参不匹配时静默只取前几段 → split(-1) + 长度必须严格相等
- [x] **QuickBar NPE** host.model() 返回 null 时 `m.quickbar.size()` 秒崩 HomeActivity → 返回空 View
- [x] **自检泄漏** SelfTestMirror 中途 return 不停 mp（前台服务+投影授权活到杀进程）→ safeStop + 正常出口补 mp.stop()；`autoPip` 死代码（prefs 里从未写入此 key）清理
- [ ] typecheck + 全部自检（153 项）已过；commit/push/CI 轮询、证书核对待做

---

## 明确不做

| 项目 | 原因 |
|---|---|
| 账号 / 登录 / 注册 | 需求明确砍掉 |
| 会员 / PRO / 兑换码 / 支付 | 同上 |
| 云端存档 / 分享码 | 同上 |
| 开机动画云端生成 | 依赖账号体系，可保留纯本地版 |
| 平台签名才能做的系统改写 | 公开版不可达（见 FEATURE-MATRIX 第六节 D 类） |
| 破解 / 绕过原版激活 | 不做 |

---

## 已知坑（写代码时注意）

1. **`PREFS` 必须只有一处定义** —— 曾经两个文件各用各的名字，数据互相看不见。
2. **`commit()` 优先于 `apply()`** —— 首次启动立即需要落盘时，`apply()` 可能还没写完进程就被回收。
3. **root shell 看到的 `/data/data` 是过滤后的挂载命名空间** —— 从外部 `ls` 判断应用数据是否存在会误判。
   要判断持久化状态，让应用自己打日志（`SeagullDiag`）。
4. **`am force-stop` 一次只能带一个包名**。
5. **`am start` 对已在运行的应用会静默投递到主屏实例** —— 搬屏前必须先 `am force-stop`。
6. **`DesktopView` 只能有 `(Context, Host)` 一个构造函数** —— 多个重载会导致 `reference to DesktopView is ambiguous`。
7. **lambda 捕获的局部变量必须 effectively final** —— 需要重赋值时先算好再赋给 `final` 变量。
8. **取色一律用 `Skin.c(R.color.x)`** —— `getColor()` 拿到的是资源固定值，主题切换后不变；
   自定义 View 里也不能用 Activity 的 `getColor`。
9. **`LauncherModel.load()` 开头必须有 `reset()`** —— 少了它 `widgets/folders/tasks/pinned`
   每次重读都追加一遍，越用越胖。
10. **手势导航条占屏幕底部约 40px** —— 布局要留底部 inset。
11. **`dumpsys activity activities` 的 display 归属**：按 `Display #N` 段头切分，
    后续 `* Task{...}` 行归当前 N；Task 行本身**没有** `displayId=` 字段（少数 ROM 例外，
    `TaskScan` 两种都认）。
12. **`am stack list` 不能按 `displayId=` 分段** —— 它逐行描述 stack 属性，stack 行里就带
    `displayId=` 字样，看到它就切段会把同一个 stack 后面的 task 行切丢。
    正确口径：`displayId=N` 的 stack 自身成段头，`stackId=` → `taskId=` → `*TaskRecord{}` /
    `topActivity=` 依次归属当前段（见 `StackScan` + `StackListCheck` 12 项）。
13. **SurfaceView 塞进 View 卡里绝对不能 `setClickable(true)`** —— 一旦可点击，
    它会在 dispatchTouchEvent 最前面判定可点击并吞掉整块区域的触摸，宿主的
    长按/空态按钮/触摸转发全死（批次 N 选择器失效就是这个）。SurfaceView 保持
    不可点击，所有触摸收在卡容器的 OnTouchListener。
14. **失焦画布的第一下触摸只切焦点，后续事件也要拦** —— 只拦 DOWN 是不够的：
    同一手势的 UP 会顺着 `slot.onTouch(e)` 被注入刚失焦的 App（凭空多点一下），
    空画布还会顺带弹选择器。用 `armed[w]` 门闩：DOWN 时即焦点才放行 MOVE/UP。
15. **`am start` 搬屏前必须先 `am force-stop <pkg>`**（已知坑 #5 的执行）——
    `launchOnDisplay` 若不做，singleTask 应用会复用主屏老栈，画中画黑屏、
    手机主屏反被应用盖住（"返回桌面也显示不了"）。见批次 O。
16. **小尺寸 / 怪宽高比虚拟屏上应用会 letterbox** —— 按手机尺寸渲染居中留黑边
    （"高德铺不满"）。root `am compat enable FORCE_RESIZE_APP <pkg>` +
    `am compat enable NEVER_FIX_ORIENTATION <pkg>` 解掉；`am compat reset <pkg>`
    在清空槽时还原，别污染主屏。

---

## 环境依赖清单

| 依赖 | 用途 | 备注 |
|---|---|---|
| `aapt2` (arm64) | 资源链接 + 生成 R.java | Termux 需手动放 |
| JDK 17+ | javac | `JAVA_TOOL_OPTIONS=-Duser.home=$HOME` |
| `d8` (R8 9.5.20) | dex 转换 | 旧版 R8 8.2.2 有 NPE |
| `apksigner` | 签名 | 自动生成 debug keystore |
| `android.jar` (API 33) | 编译引导类路径 | 放 `libs/` |
| `zipalign` | 对齐（可选） | 缺失时跳过，apksigner 输出本身已对齐 |

### P2-11 批次 Q 两个画布问题修复（VD 嵌套 / 高德黑边）🟡 代码已写，typecheck 过，真机待验
- [ ] 用户验收 ①第二画布里套着第一画布：根因是两块画布共用同一个 MediaProjection 会话 —— 一个投影会话只能建一块虚拟屏，第二块 `createVirtualDisplay` 会把第一块的画面"复印"过来。修复见 PipBoard.pollToken（同一次授权结果取两份独立 MediaProjection，proj[1]/proj[2] 各建各 VD）+ 共屏检测网（两槽握同一 displayId 直接拆本槽并留 error 日志）
  - 验证：双画布各选一个应用，`dumpsys display | grep -i virtual` 应出现 **2 条** seagull-pip1 / seagull-pip2；logcat 抓 `SeagullPipBoard` 两份投影会话就绪
- [ ] 用户验收 ②高德四周黑边：根因不是 VD 尺寸，是密度（densityDpi≈440 传给 366px 宽小屏 = ~85dp 视口，应用按小屏布局/触发 size compat 居中留黑边）。修复见 PipBoard.dpiFit()：画布像素折成 ~280dp 基准反算 dpi（366px @ ~209dpi），宽高两侧都钳，≥mdpi ≤设备默认；am compat FORCE_RESIZE_APP + NEVER_FIX_ORIENTATION 保持
  - 验证：两块画布分别跑时钟看是否各自铺满（时钟 = App  laying out normally）；再跑高德，看黑边是否消失。若时钟仍黑边 → 是 ROM 层 letterbox，加 `am compat enable FORCE_RESIZE_APP` 未生效，抓 `dumpsys activity containers | grep -A5 sizeCompat`
- [ ] 已删「点击接管」TextView 覆盖层（用户：界面只要两个画布，别加多余东西）；非焦点画布只剩环境光遮罩 + 焦点描边
- [ ] 注意：VD 依赖 TRUSTED 直建（DisplayManager 6 参公开重载，API 33+，角色授予需真机验证）；TRUSTED 失败时走投影路径，两槽各自一份会话

### P2-12 批次 R 实验台回灌（CarWithX 建屏三铁律 + 锚点 + 缺席重拉）🟡 代码已写，typecheck/自检过，真机待验
- [ ] 用户验收 ①铺满：建屏首选已改为 flags=0 私有屏直建（日志 `私有屏直建成功 flags=0`）；dpiFit 保持。验证：两块画布分别跑时钟看铺满；仍黑边则抓 `dumpsys activity containers | grep -A5 sizeCompat` + `logcat -s SeagullMirror`
- [ ] 用户验收 ②回桌面不再消失：锚点常驻（`logcat -s SeagullAnchor` 应见 `锚点就位 task=N displayId=M`）；`dumpsys display | grep -i virtual` 仍 2 条；回桌面 30s 内被拉回主屏的应用自动搬回（`桌面自愈 槽 N: 目标已在虚拟屏`）
- [ ] 用户验收 ③画布黑屏自愈：在画中画里按返回退出应用，60s 内画布应自动重拉该应用（`logcat -s SeagullMirrorHost` 见 `应用缺席 → 重拉`）；重拉 60s 节流，连续退出只补拉一次
- [ ] 双槽护栏：两个槽绑同一个应用时，自愈绝不动对槽实例（日志 `目标任务在其他虚拟屏 d=N，不动`）
- [ ] 投影撤销不误拆：录屏授权撤销后，私有屏/TRUSTED 屏的画中画应继续活着（只有投影屏才拆）
- [ ] 授权链退化确认：flags=0 直建成功时全程不应弹录屏授权框（这是本批次的 UX 核心收益）

### P2-13 批次 S 遗留：私有屏弹回检测 ⚪ 等真机证据
- [ ] 风险：singleTask 目标（网易云）若被系统从 flags=0 私有屏弹回主屏，ensureOnDisplay 搬回会 3s 一次循环（move 非重拉，负载可控但治标）。判据：logcat 见 `桌面自愈/自愈` 连续出现 `目标任务不在虚拟屏` + 主屏冒全屏应用
- [ ] 修法（有证据后做）：ensureOnDisplay 对同一槽连续 N 次（3 次）搬回后仍不在目标屏 → 判定该私有屏不收留此任务 → 自动拆屏按三路重排升级到 TRUSTED 重建
### P2-14 批次 S 遗留：Android 14 录屏 token 单次有效 ⚪ 低优先（兜底路径）
- [ ] API 34 上 resultData 一次性：pollToken 第二次 getMediaProjection 必抛 SecurityException，现有 catch+共用降级+日志在位（嵌套画面风险仅存在于私有屏+TRUSTED 都失败且 Android 14 的组合）
- [ ] 根治：needProj 且 API>=34 时连续弹两次授权（每槽各一次），token 各自消费

### P2-15 批次 T 全库复盘修复验收（安全 3 / 正确性 8 / 性能 3 / 健壮性 2）🟡 代码已写，typecheck/自检过，真机待验
- [ ] SysOps 白名单：设置→「强制停止应用」输 `com.foo; rm -rf /` / 空串 / 非法包名，应 toast「参数不合法」且不执行；合法包名 force-stop 仍正常（`FAIL/OK` 文案不变）
- [ ] RootMain 双 uid 修复（重点）：普通安装下 daemon 现在应接受本家连接——`adb shell` 直接 `logcat -s SeagullPrivd` 重启 app 后应无「拒绝非特权 peer uid=10xxx」；同时确认 adb shell 连接仍被拒（uid=2000 不再放行）
- [ ] 点图标启动：桌面图标 / Dock / 文件夹内图标点开应用应真的启动（旧实为静默失败）；`logcat -s SeagullModel` 不应再出现「启动失败 … AndroidRuntimeException」
- [ ] 布局页列表：设置→桌面→布局，「桌面布局」应立即列出 7 个模式（旧实现永远空白）；点切换 + 组件开关仍正常
- [ ] 「开机自动回桌面」开关关掉后重启，桌面不应被拉起（旧实现恒 true）；开启仍拉起
- [ ] 组件条长按加组件：应能加「歌词」「快捷栏」（旧实现点了没反应）；加满 4 个继续加应 toast「组件都放满了」
- [ ] 任务搬屏：双槽下收回/拉回，目标应是正确屏上的任务（displayId 边界修复）
- [ ] 同名文件夹：建两个同名文件夹，桌面应各占一个图标（旧实现只显示一个）
- [ ] 配置导入：喂一份语法对、语义错的 JSON（缺必填字段的壳），导入应失败且重启后配置保持导入前的值（旧实现会污染存档）
- [ ] 恢复出厂：改字号 130% / Dock 全配置 / 菜园 / 小白点 / 野菜岛 / 天气城市 / 歌词 / 触摸阈值后恢复出厂，全部应回默认值（旧实现只回 16 个标量）
- [ ] 性能体感：连点多个设置开关、长按拖动图标，主线程不应再有 commit() 同步卡顿；桌面反复重画图标不重新解码（省电流量无回归）
- [ ] 歌词长播 10 分钟以上，歌词行应仍跟上播放进度（15 跳锚定修复漂移）
- [ ] 换肤/换壁纸来回切多次，`dumpsys` 看 DesktopView 实例不累积（sink 泄漏修复）；画中画 Surface 无重复（cancelPending 修复）
- [ ] 检查更新→下载安装包：文件名形如 `seagull-1.1.apk`（tag_name 消毒），落盘 `files/updates/`，安装 intent 正常弹出

### P2-16 批次 T 遗留：代理发现但未亲自复核的项 ⚪→✅ 批次 V 已清账（坐实 12 处修复 / 杀 14 项误报 / 2 项架构级转 P2-18，详见 DEVLOG 批次 V）
- [x] GardenActivity：dp(ballX) 双倍换算坐实已修（ballX 是像素）；时钟不走字坐实已修（秒级 ticker）
- [x] BallService：长按后松手 tap() 双触发坐实已修（longFired）；tap 区域判定杀（slop 逻辑成立）
- [x] RootPanelActivity：envInfo 2×su + hasRoot 冷调压主线程坐实已修（全挪后台）；「reload su×2」不存在（杀）
- [x] VirtualDisplayHost：create/release 跨线程竞态坐实已修（synchronized）；volatile/attach 前 ISE 杀（不可达）
- [x] WindowTestActivity：refresh 主线程 su 坐实已修；getDisplay NPE 杀（catch Throwable 兜住）
- [x] Lrc：BOM 坐实已修；超长 mm NFE 崩主线程坐实已修（复核中新发现）；[mm:99]/重复时间戳杀（宽容解析/设计内）
- [x] Pinyin：土耳其 I 杀（已全程 Locale.ROOT）；输入上限杀（缓存 400 上限）
- [x] Theme：accent 校验杀（两路径有 try/catch，无裸 parseColor）
- [x] MediaListenerService：通知正文进 logcat 坐实已修（隐私）；playPause 竞态杀（两向 no-op）
- [x] Wallpaper：流泄漏 + 孤儿文件坐实已修（try-with-resources / removeWall 连带删文件）；主线程解码坐实转 P2-18；purgeWalls 杀（函数不存在）
- [x] SettingsSection：HHMM 730 误解析坐实已修（按 HHMM 口径）；delayMs int 溢出坐实已修（long + 7 天上限）；-1 永不到点杀（describe 可见可编辑）；Skin.apply 时机坐实已修（render 前补）
- [x] SelfTest：亮度/音量副作用坐实已修（亮度还原 / 音量改只读）；mirror 校验杀（taskOnDisplay 在位）
- [x] TaskEngine：name 重算杀（触发是持久化枚举字段）
- [x] HomeActivity：死 prefs 已删；island sink 双轨杀（设计内多订阅）
- [x] DesktopView：dispatchDraw 每帧分配坐实已修（预分配）；空文件夹 AIOOBE 杀（双层修剪）
- [x] RootMain：thread-per-connection 维持记录备查（连接数固定 + 空闲看门狗）
- [x] BootReceiver：更新路径无视开关 + 开机任务被 autoHome 连带吞坐实已修（开关只管拉界面，任务只在真 BOOT）
- [x] LauncherModel：loadApps 主线程坐实转 P2-18；WallpaperActivity loadLabel 杀（类不存在）

### P2-18 批次 V 坐实但架构级的项（下批专项）⚪ 未动
- [ ] loadApps() 异步化：6 个页面 onCreate 主线程全机枚举（Home/Search/AppList/SettingsHub/SettingsSection/VirtualDisplay），bloaty 设备 queryIntentActivities 100ms+；需给各页补占位渲染 + 回调刷新
- [ ] 壁纸后台解码管线：loadForScreen 在 Garden/Desktop/Home onCreate 同步 decode（已降采样但仍是主线程解码）；连带三处渲染时机改造
- [ ] 附带口径：上方两项做完后，RootPanel/WindowTest/VirtualDisplay 的后台线程模式（isFinishing 检查 + 回贴 UI）可作为统一范式推广

### P2-17 批次 U CarPlay 换皮验收（skin-only：预设 + 圆角 + 字号）🟡 代码已写，typecheck 过 + 123 项自检过，真机待验
- [ ] 新装/恢复出厂默认「苹果互联」；主题页可切到其他预设并切回，色值齐全
- [ ] 外观页强调色：自定义颜色覆盖 leaf 时，底仍是中性灰（不串色）
- [ ] 桌面圆角：编辑条/chip/dock 把手/组件卡/快捷栏为圆角或胶囊；应用页行、设置页行、搜索输入同
- [ ] 画中画两块画布 Surface 保持方角（预期），标题条与浮动卡片颜色跟随主题
- [ ] 小字号可读：QuickBar、应用列表、应用详情副文案在新字号下不拥挤
- [ ] 版本升级覆盖安装（旧随机签名包→新包需先卸载一次，装后确认默认主题=苹果互联）

### P2-19 LSPosed 模块验收（画中画应用强制横屏 / 跨屏注入）🟡 代码已写，真机待验（批次 AF 更新口径）
- [ ] 装新包（**版本信息页应显示 `1.0.<短哈希>`**；若还是 `1.0` 说明装的是旧包，先解决这个）
- [ ] **设置 → 系统 →「LSPosed 模块自检」**（批次 Z 新增，免 logcat）：先用一次高德 /
      网易云，再点它。报告会逐进程给「已注入 ✓ / 未注入 ✗」，未注入会点名该补勾哪个包 ——
      以后反馈带上这一段即可，不必接 adb
- [ ] LSPosed 管理器出现「海鸥桌面」模块 → 启用 + 作用域勾上高德 / 网易云 / SystemUI
      （**重点**：作用域是启用时快照的，批次 W~X 期间新增过的包名可能没进已保存的名单，需手动补勾）
- [ ] 自检里出现 `handleLoadPackage: com.autonavi.minimap`。一条都没有 → 模块没被注入，
      与本仓库代码无关，只查管理器
- [ ] 再看到 `已挂载 … → H1/H2/H3 窗口语义` + `H2 hookOrientationOverride OK`，
      且没有 `H1 失败` / `H2 失败`（批次 Y 起三段各自 try/catch，失败会单独点名）
- [ ] 强杀高德（`am force-stop com.autonavi.minimap`）→ 重开 → 画中画部署：横屏铺满、两侧黑边消失
      （批次 Z 起 `relaxCompat` 提前到起进程之前，本次部署就吃到 FORCE_RESIZE_APP +
      NEVER_FIX_ORIENTATION；以前要部署第二次才生效）
- [ ] **（批次 AF）网易云画中画里点歌词/封面等页面**：应用闪到主屏后 **2.5s 内自动回画中画**
      （tap 自愈扑空时由常驻巡检兜住；`logcat -s SeagullCar` 应有 `巡检搬回 槽 n: 已搬回…`）
- [ ] **（批次 AF）跳走后返回桌面**：画布不再永久黑屏（第④搬运通道 dumpsys 兜住
      "am stack list 解析不出 + 守护进程失败"的设备）
- [ ] 高德内部横竖屏状态机与 WM 无死循环冲突（观察 10 分钟导航）
- [ ] 名单外应用（勾选作用域但不在 TARGETS）不受影响
- [ ] 弹窗/透明页被拉横屏的实际体验确认（车机场景预期可接受）
