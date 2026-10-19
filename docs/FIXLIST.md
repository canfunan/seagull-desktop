# 全量审计修复清单（2026-10-19）

来源：全项目代码复盘（root/特权链、虚拟屏/镜像链、服务 UI 链三路深审 + 核心桌面链人工复核）。
约定：每修复一项在本文打勾并附「修复说明」；全部完成后输出总报告 docs/AUDIT-REPORT.md。

状态：**全部 35 项已修复**（javac --release 11 类型检查通过；7 项 JVM 自检 169 断言全绿）。

## 一、高危（阻断性）

- [x] H1 触摸注入主线程阻塞 socket IO（ANR）
  - 修复：TouchForward.feed() 主线程只 `MotionEvent.obtain()` 快照后 `h.post` 到 seagull-touch 线程重放；gestureOnDaemon 改 volatile；长按守护分支同样入队。Socket IO 全部离开主线程。
- [x] H2 system_server 启动路径 pushEvent 持锁阻塞写
  - 修复：RootMain 订阅者改为 Subscriber（写手线程 + 64 条有界队列），pushEvent 快照后逐个 offer（满即丢、连续满 3 次摘除），锁内零 IO；launch 主链不再被慢订阅者拖死。
- [x] H3 QuickBar.runFn 主线程 su 链
  - 修复：runFn 整体挪 Thread("seagull-quickfn")，appContext 预取，toast 经主线程 Handler。
- [x] H4 设置页主线程 root/采样链
  - 修复：monitorLine 行改占位文案 + 点按 bgTaskDialog 后台采样；TaskMover 三行（收回/拉回/排障）bgTaskDialog；forceStop、pm grant 后台线程；RootPanel 亮度滑条占位 + 后台读回填；SettingsSectionActivity.onCreate 后台预热 Caps.hasRoot/level/canVirtualDisplay。
- [x] H5 设置页异步回执 BadTokenException
  - 修复：新增 dead()（isFinishing||isDestroyed）+ msgDialog/confirmDialog 内部兜底；fetchWeather、壁纸图片/视频导入、下载回调、root 通道检测等 8 处异步回执全部守卫；Wallpaper.toast 改主线程 post（后台导入线程直接 Toast 会崩）。
- [x] H6 MirrorSlot 拆除/部署无锁竞争
  - 修复：新增 LIFE_LOCK，deploy/detachSurface/teardownVd/teardown 全部串行化（deploy 内部走 deployLocked 复用同一锁）；生命周期字段不再被跨线程交叉改写。

## 二、中风险

- [x] M1 主线程大图解码/拷贝
  - 修复：Wallpaper.isBright 结果按 path|mtime 缓存（算色管线不再每次 16x 抽样 decode）；loadForScreen 单条缓存（键 path|尺寸|mtime，桌面重建复用同一位图）；壁纸图片导入挪后台线程（M1c，与 H5 守卫配套）。
- [x] M2 LauncherModel save/load 无锁
  - 修复：新增 IO_LOCK（ioLock() 暴露）；save() 序列化 pinned/dock/widgets/folders 全程持锁；load() 同锁；Weather 网络线程改字段+save 也持同一把锁 —— CME 静默丢档路径关闭。
- [x] M3 Lyrics 1Hz 全量 JSON 解析
  - 修复：LauncherModel 新增 CFG_VER 代际（cfgVersion/bumpCfg）；Lyrics.cfg() 按代际缓存模型快照，step/lookup/advance/window 全部走缓存；设置页 6 处歌词写点 + load() 时 bump。每秒一次的全量解析归零。
- [x] M4 DesktopView 歌词组件 1Hz 整条重建
  - 修复：makeWidget(5) 登记 lyricBig/lyricSmall 文本位；lyricSink.onLyric 内容变化才 setText（原位刷新），结构变化仍走 renderWidgets；1Hz 组件条重建不再打断手势/时钟基线。
- [x] M5 每秒 requestLayout 风暴
  - 修复：WallpaperPage.placeClockBox/placeWidget 先比较 margins 后 setLayoutParams；IslandView.bind 尺寸/位置先比较后赋值。每秒全链 requestLayout+measure+layout 归零。
- [x] M6 应用列表重复全量枚举
  - 修复：loadApps 进程级缓存（20s TTL + 拷贝出借）+ PACKAGE_ADDED/REMOVED/REPLACED 广播即时失效（HomeActivity.onCreate 注册）；pinStatic 为低频单次动作（每次固定操作一次模型解析，毫秒级），评估后维持现状。
- [x] M7 PrivClient/RootMain 竞态簇
  - 修复：runWatch finally 只在 watchThread==当前线程时清 listener（旧线程迟到 finally 不再抹掉新订阅回调）；clients 改 AtomicInteger；服务端 serve 30s SoTimeout（SocketTimeoutException 按断开处理）。
- [x] M8 守护进程代际握手
  - 修复：RootMain.GEN（进程启动时间戳）随 PONG/STATE 下发；PrivClient.handleGen 发现 gen 变化回调 onRespawn；MirrorSlot static 块注册回调清 ACTIVE 对账表 —— 守护进程自退出重启后客户端不再拿幽灵 displayId。（DisplayListener 部分评估：现有触摸 TTL 5s + deploy 幂等已覆盖单屏消失场景，未额外引入监听器，详见总报告）
- [x] M9 共享 MediaProjection 归属
  - 修复：MirrorSlot 新增 PROJ_REFS 引用计数（IdentityHashMap）；teardownVd 仅独占时 stop，共享实例的 stop 责任收敛到 PipBoard.stopProjections；deploy 换投影走 setProjection 统一处置旧引用。
- [x] M10 WindowCard resize 重建风暴
  - 修复：attach 优先 vd.resize(w,h,dpi) 原地改尺寸，失败才整建兜底；拖拽改尺寸不再每帧 release/createVirtualDisplay。
- [x] M11 RootOps 包名正则放行大写
  - 修复：launchOnDisplay/moveExistingTask 正则改 [a-zA-Z0-9_.]+（仍挡注入），老应用 Com.Example 形式不再被误拒。
- [x] M12 TouchForward 注入坐标钳制
  - 修复：新增 setVdBounds(vdW,vdH)（MirrorSlot.onTouch 每次 feed 前同步）；replay/pxX/pxY 坐标钳制 [0,vd-1]；clampF 处理 vd 未就绪（hi<lo 直通）。
- [x] M13 MirrorSlot 快照双缓冲
  - 修复：acquireSnapshot 双缓冲轮替复用（snapBuf0/1，尺寸变化才重建）；行对齐 padding 裁剪路径保留；releaseSnapshot 只断引用不 recycle。960x540 快照泵 ~3.3MB/s 分配归零。
- [x] M14 armed 组件快速点按误弹菜单
  - 修复：Drag 新增 longPressed 标记；UP 时 moved→end、longPressed→longTap、其余交给 onSingleTapConfirmed —— armed 快速点按只触发 tap，长按菜单照常。
- [x] M15 root daemon doSurface 输入校验
  - 修复：Surface blob 显式 64KB 上限（解码前后双层防线，行协议天然封顶 + 显式拒绝）；Parcel 解析已有独立 try/catch + recycle。
- [x] M16 Caps.exec waitFor 无超时
  - 修复：正常路径 waitFor(2s) 超限 destroyForcibly；超时/中断路径一律 destroyForcibly 回收，僵尸 su 进程不再积累。

## 三、低风险

- [x] L1 潜在注入点收口
  - 修复：grantAll 通知监听合并前逐条校验 ComponentName 形态；injectTouch 命令白名单正则（tap/swipe/keyevent/draganddrop + 数字参数）；resetCompat 包名校验（M11 同款正则）。
- [x] L2 SysOps.cpuPct 守卫与节流
  - 修复：结果 2s TTL 缓存（顶栏/监控行不再每次付 120ms 采样阻塞）；退化输出仍有 "—" 兜底，total<=0 有守卫。
- [x] L3 Weather.http 响应封顶
  - 修复：响应体 512KB 上限（异常回包不再无限吃内存），超限抛错走既有 err 通道。
- [x] L4 Lyrics 本地歌词路径
  - 修复：Environment.getExternalStoragePublicDirectory(DIRECTORY_MUSIC) 优先 + LinkedHashSet 按路径去重 —— /sdcard 与 /storage/emulated/0 同一文件系统不再扫两遍。
- [x] L5 XposedEntry D 热路径反射
  - 修复：SystemProperties.get 的 Method 对象缓存（volatile + 一次性查找标记），applyOverrideConfiguration 拦截器高频路径不再每回 Class.forName+getMethod。
- [x] L6 keepScreenOn 保存/恢复
  - 修复：开常亮前 settings get 存原值，关闭时还原（无记录回落 60000）—— 用户自定义息屏时长不再被覆盖。
- [x] L7 Caps.resetCapabilities()
  - 修复：新增 synchronized 重置方法；RootPanelActivity.onCreate 后台线程重探（首次 su 被拒绝后有重试入口）；设置页 onCreate 同步预热。
- [x] L8 RootMain 杂项加固
  - 修复：accept 异常 200ms 退避（防日志刷盘风暴）；doCreate 尺寸上限（4096/1000）；selfUid 启动期一次 + dumpsys 子进程 destroy；RootLog 首写后 chmod 600（/data/local/tmp 不再全局可读）。
- [x] L9 自更新完整性校验
  - 修复：apkLooksSane()（ZIP PK 魔数 + 64KB 最小尺寸）接入下载回调，破包不再进安装流程。
- [x] L10 allowBackup=false
  - 修复：AndroidManifest application 标签关闭备份通道（配置含局域网地址等敏感项）。
- [x] L11 FileProvider 强制只读
  - 修复：openFile 统一 MODE_READ_ONLY（openAssetFile 本就只读）—— 共享文件（壁纸库/日志）不可被外部写改。
- [x] L12 selfReport 指纹收敛
  - 修复：不再输出 dataDir/xml 绝对路径（设备指纹），保留存在性/尺寸 + 配置计数诊断价值（保留发布包排障能力，比 debuggable 门控更贴合本应用 root 排障工作流）。
- [x] L13 视图/并发杂项
  - 修复：DesktopView 删除从未使用的 ui Handler（连 import）；scan() 拖拽路径 int[2] 提出循环；MirrorSlot.touch 改 volatile（DCL 读可见性）；PipBoard.RESIZE_CACHE 改 ConcurrentHashMap（免全锁）；PipProjectionService startForeground 失败即 stopSelf（不等系统 5s ANR-crash）。
- [x] L14 兼容杂项
  - 修复：notiListenerOn 改 flattenToString 精确比对（含 MediaListenerService）；GardenActivity.onBackPressed 显式 @Deprecated 注解（API 33 PredictiveBack 语义说明）；dumpsys 窗口 u0 前缀留证注释（单用户假设说明）；LaunchController 解析失败已有 ROM 日志留证，维持。
- [x] L15 常量收敛
  - 修复：新增 Constants（SELF_PACKAGE、LAUNCH_FLAGS/LAUNCH_FLAGS_INT）；XposedEntry/RootMain/PrivClient/RootOps/SelfTestMirror 五处字面量全部收编。
- [x] L16 QuickbarCheck 自检垫片
  - 修复：shim/android/util/LruCache.java（LinkedHashMap 真实现 + LRU 逐出 + 同步），编译后 classpath 前置 —— QuickbarCheck 在 JVM 上 7 项全过，LauncherModel 静态 LruCache 不再炸 Stub!。
