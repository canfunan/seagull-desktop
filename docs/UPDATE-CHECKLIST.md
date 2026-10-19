# 海鸥桌面 · 更新清单（OneStep 借鉴落地）

> 制定时间：批次 AJ 之后（v1.0.6051e28）。
> 依据：`docs/ONESTEP-BORROWING.md`（只借思路，全部自写实现）。
> 状态：**批次 AK~AN 代码全部落地（2026-10-19），javac 编译通过，自检 39 项全过**。
> 真机验收项标 🟡 —— 容器里做不了 root / 车机验证，逐项待真机过。
> 用法：真机验收时逐项打勾；偏差与原因都写在各条目下，复盘结论在 `docs/DEVLOG.md` 批次 AK~AN。
>
> 批次号：AK = K1，AL = K2，AM = K3，AN = K4。
> 原铁律"每阶段独立可回滚"按用户指令改为**一次落地**：每批的独立回滚仍成立
> （空气开关 `persist.seagull.rootbridge` 秒级回退 AJ 行为），只是验收合并进行。

---

## 通用要求（每个批次都要满足）

- [x] 空气开关：`persist.seagull.rootbridge`（1=新链路，0/缺省=回退旧链路）——
      `Caps.rootBridgeOn()`（10s 缓存）/`setRootBridge()`（设置页开关）/`rootBridgeLabel()`（只读缓存展示）。
      关掉后：建屏走旧三路、不武装不拦截、不订阅事件，行为与 AJ 一致
- [x] 失败降级：守护进程不可用（disabled 会话禁用）时旧 `su` 链路照走；root 建屏失败
      自动落回本地三路（私有屏→TRUSTED→投影）；绝不卡死 UI（主线程纪律见批次 AK 复盘）
- [x] 日志：桌面侧 `SeagullCar`/`SeagullPriv`/`SeagullWatch`/`SeagullNav` 等双标签；
      root 侧 `RootLog` 落盘 `/data/local/tmp/seagull-root.log`（256KB 上限，超限保留尾部）
- [x] 协议握手：连接建立即 `PING`（`ensure()` 里 `waitConnect` 要求 ping 通才算连上）；
      守护进程无客户端 60s 自退出，不宿留
- [x] 死亡感知：LocalSocket 断连/EOF 即感知（`roundTrip` 失败关连接、订阅 EOF 自动摘），
      等效 linkToDeath；守护进程退出 = root 屏全拆 + system_server 侧控制器/监听由 binder 清理
- [x] 文档：DEVLOG 批次 AK~AN 记录"为什么这么改"；AI-GUIDE 增补排障路径
- [x] 构建：GitHub Actions 出包，tag 跟随 versionName（`1.0.<短哈希>`）

---

## K1（批次 AK）—— 常驻 root 守护服务 + 直注触摸 + 实时任务监听

**范围红线：不动 VD 创建方式、不动部署链路、不动 LSPosed 模块、不动 UI。**
（AL 起才动建屏；本批只接管"注入类"操作）

### 工作项
- [x] R1 root 进程入口：`app_process` 起 `RootMain`（打进主 APK，su 拉起 CLASSPATH 指 APK）——
      **沿用既有守护进程骨架**，本批扩展而非另起
- [x] R2 服务通道：**实现偏差**——计划是 ServiceManager.addService 注册 Binder 服务，
      实际沿用 LocalSocket（abstract namespace）+ 行协议（PrivCodec）。理由：现有
      PrivClient/RootMain 就是常驻服务的雏形且已在真机验证过拉起链路；uid 校验
      （root/system/自身）+ 私有协议达到同等安全边界，风险更小。详见 DEVLOG AK
- [x] R3 事务集：PrivCodec 新增 `CREATE/RESIZE/SURFACE/RELEASE/ARM/CTRL/WATCH/STATE`
      （事务号=字符串 op，常量独立成类，两端共用）；自检 `PrivCodecCheck` 覆盖往返
- [x] R4 MotionEvent 编解码：行协议文本编码（`encodeMotionRequest`/`f2s`，
      多触点 + 原始 action），不序列化对象
- [x] R5 注入实现：`doMotion` 反射 `IInputManager.injectInputEvent`，
      事件带 `setDisplayId(displayId)`；反射失败**拒绝注入**（fail-closed，批次 AI 原则沿用）
- [x] R6 实时任务监听：`TaskWatcher` 反射注册 `ITaskStackListener`——
      **实现偏差（刻意）**：不解析 RunningTaskInfo 载荷（ROM 字段漂移会误动对槽任务），
      一切事件 300ms 节流后推 `EVT BUMP`，自愈决策全在桌面既有护栏里
- [x] R7 桌面侧客户端：**实现偏差**——计划新建 `RootBridgeClient`，实际扩展 `PrivClient`
      （主连接请求-应答 + 独立订阅连接 `subscribe/unsubscribe`，watch 线程收 EVT 行）
- [x] R8 触摸链路切换：`PrivClient.motion` 走守护进程直注；`ensureForTouch` 保证主线程
      不 fork su（掉线立即返回 false，手势余量退回 `input -d` 命令通道）
- [x] R9 自愈兜底保留：PipBoard 轮询不删，订阅在线降为 8s 兜底拍，离线保持 2.5s
- [x] R10 su 启动与保活：setsid/裸两路拉起，断连自动重连（`ensure()` 幂等）；
      会话内拉起失败一次即禁用，免得每次手势都 fork su
- [x] R11 自检页状态行：设置 → 系统 →「root 桥接快照」（`PrivClient.state()`：
      watch/held/subs/ctrl/armed 一行），子线程调用

### 验收（真机）🟡
- [ ] `logcat -s SeagullPrivd` + `/data/local/tmp/seagull-root.log`：daemon start uid=0
- [ ] 画布触摸延迟明显低于旧链路，事件只出现在目标 displayId
- [ ] `persist.seagull.rootbridge=0` → 行为与 AJ 完全一致（回滚验证）
- [ ] 把网易云拉到主屏，桌面毫秒级收到 BUMP 并自愈搬回（对比 2.5s 轮询）
- [ ] 杀守护进程 → 下一次操作自动重拉成功（或明确降级 shell 兜底）
- [ ] 高德画中画部署/铺满/触摸全部照旧（无回归）

---

## K2（批次 AL）—— root 上下文 TRUSTED+PUBLIC 虚拟屏（跳主屏根治）

### 工作项
- [x] R1 守护进程新事务 `CREATE(w,h,dpi)/RESIZE/SURFACE/RELEASE`：root 进程用
      `ActivityThread.getSystemContext()` 拿 DisplayManager 建屏；flags 候选复用
      `TrustedFlags.candidateFlags()+normalize`（TRUSTED(1<<10)+PUBLIC+OWN_CONTENT_ONLY，
      显式无 SECURE），建后校验 Display flags 的 TRUSTED 位
- [x] R2 surface 跨进程：`Surface.writeToParcel + Base64(NO_WRAP)` 过行协议，
      守护进程 `Surface.CREATOR.createFromParcel` 挂载；空 blob "-" = 摘面（画布离场屏不拆）
- [x] R3 部署链路优先级：**root TRUSTED 屏 → 私有屏直建 → TRUSTED 角色 → 投影兜底**，
      每级失败自动降级并记日志；已有 root 屏时只 resize+挂面不重建
- [x] R4 应用部署：finishDeploy 公共段（relaxCompat→搬任务→冷启动→自愈→武装）root 屏同款；
      "生在画布"由守护进程 launchOptions.setLaunchDisplayId 承接
- [x] R5 密度沿用 dpiFit 280dp 基准（F 项策略不引入，符合预期）
- [x] R6 体检/巡检/触摸适配：displayId/syncSize/nudgeLayout/detachSurface/teardownVd
      全加 root 分支（nudge 两拍抖动整段走守护进程 resize，专用线程 sleep 不占路）
- [ ] R7 输入法实测：🟡 TRUSTED+PUBLIC 屏画布内点输入框看 IME 是否弹出（预期弹）
- [ ] R8 网易云竖屏烙印回归：🟡 D 段 hook + 密度抖动在 root 屏上必须依然成立
      （新 activity 仍读 display 0 → 烙印仍在 → 抖动重建修复链路不变）

### 验收（真机）🟡
- [ ] `dumpsys display`：画布 trusted 且 public
- [ ] 网易云部署 → 8 秒内铺满（不劣化 AJ）
- [ ] **点歌词/封面不再跳主屏**（本批核心验收）
- [ ] 高德部署/触摸/铺满无回归
- [ ] 关闭 rootbridge → 降级私有屏链路一切照旧

---

## K3（批次 AM）—— IActivityController 全局启动拦截

### 工作项
- [x] R1 守护进程事务 `CTRL on/off`：反射 `setActivityController(controller, false)`，
      优先 2 参重载回落 1 参；卸载置 null 恢复系统原状
- [x] R2 武装路由源：`ARM displayId pkg` 登记画布↔嵌入应用；`doMotion` 内
      `LaunchController.touchAt()` 自动刷新触摸 TTL（5s）；bypass 窗口 3s
- [x] R3 豁免规则（宁放行不误拦）：HOME/SECONDARY_HOME 不拦；FLAG_FORWARD_RESULT
      不拦（分享/登录流）；目标=嵌入应用自身不拦；bypass 窗口内不拦（防自锁）；
      **解析/决策任何异常一律放行**
- [x] R4 拦截动作：先记 bypass 再改道（`startActivityAsUser` 原意图原 flags 换落点屏，
      独立线程跑不堵 system_server 关键路径），推 `EVT ROUTED pkg displayId`
- [x] R5 性能红线：回调内零磁盘 IO、零 binder 重试，意图判定全内存态；
      回复字节纪律显式分支（activityStarting 0/1、activityResuming 恒 1、其余恒 0）
- [x] R6 C0 hook 降级备用：**实现偏差**——未加 `persist.seagull.c0` 开关。理由：root
      拦截与 LSPosed C0 是叠加而非互斥（C0 拦勾过的应用，控制器拦全部且先到），
      两者并存无冲突；改动留待真机验收后按需补
- [x] R7 拦截状态可见：`STATE` 应答含 `ctrl=on/off armed=pkg@d routed=pkg@d`
      （设置页「root 桥接快照」展示）

### 验收（真机）🟡
- [ ] 画布内点网易云歌词/封面：零跳主屏闪现
- [ ] 主屏正常使用不受影响；HOME 键行为正常
- [ ] 分享/登录等 result 流程实测一次不被打碎
- [ ] QQ 音乐/百度地图（未勾 LSPosed 作用域）画布内跨应用启动被正确改道
- [ ] 关闭开关或守护进程下线 → 拦截解除，系统行为恢复

---

## K4（批次 AN）—— 高德导航信息卡片（官方服务接入）

### 工作项
- [x] R1 bind 高德官方导航服务：action `com.autonavi.minimap.service.NAVIGATION_SERVICE`，
      包名可配（`LauncherModel.navPkg`，默认手机版 `com.autonavi.minimap`；车机版
      `com.autonavi.amapauto` 是否开放同一 action 🟡 真机实测，本批不强做）
- [x] R2 协议实现：**AIDL stub 自写为手写 transact**（构建链无 aidl 工具）——
      控制端 ILinkSdkControl（1=authenticate / 3=registerListener / 4=unregisterListener），
      回调端 ILinkSdkCallback（1=onEvent / 2=onEvent+fd）；只取 8 个字段
      （eventType/maneuverId/segmentRemainDist/speed/routeRemainTime/routeRemainDist/
      curRouteName/nextRouteName）；载荷明文 + AES-GCM 信封双解（自写 PayloadCodec）
- [x] R3 数据保鲜：8s 无更新判过期清空；onServiceDisconnected/onBindingDied/
      onNullBinding/强杀高德 四路全清
- [x] R4 顶栏卡片 UI：`NavCard` 半透明黑胶囊（转向 + 本段剩余 + 路名 + 全程剩余 + 速度），
      无导航整条隐藏；总开关 `navEnabled`（设置页"导航与增强"组）；
      系统 PiP 模式 `setForcedHidden` 强制隐藏
- [x] R5 转向图标自维护：maneuverId → 文本箭头映射（右 2/3/4/12/14、左 6/7/8/13、
      掉头 5/9、并入 10、靠左 11），未知 id 给直行箭头并记日志，零外部资源引用

### 验收（真机）🟡
- [ ] 手机版高德开导航 → 卡片实时更新（路名/距离/转向随播报变化）
- [ ] 结束导航 → 卡片 8 秒内消失；强杀高德 → 断连清理无残留
- [ ] 不影响画中画（导航卡片只读数据，不触碰高德进程）

---

## K5（收尾批次）—— 全面对齐清单 + 全面复盘所有代码

### 对齐（逐项打勾）
- [x] K1~K4 每个工作项逐条核对：完成如上；3 项偏差（R2 服务通道、R7 客户端、
      AM-R6 c0 开关）均在条目下给出原因；验收项全部标 🟡 待真机
- [x] 通用要求逐条核对（见顶部）
- [ ] 三次回归基线复测：🟡 AJ（模块链路）、AK（守护链路）、AL/AM/AN 后各一次——合并进真机验收

### 全面复盘（输出物）
- [x] `docs/DEVLOG.md`：批次 AK/AL/AM/AN 四章 + 复盘章（预期 vs 实现、踩坑、协议与开关最终形态、风险清单）
- [x] `docs/AI-GUIDE.md`：架构图更新（守护进程拓扑、事务表、降级树）+ 排障路径
      （连不上怎么查 / trusted 屏异常怎么查 / 拦截误伤怎么关 / 导航卡片不出怎么查）
- [x] `TODO.md`：K 系列章节重写（完成项归档、真机验收清单、遗留项）
- [x] 风险清单：DEVLOG 复盘章（IActivityController ROM 兼容、TRUSTED flags ROM 差异、
      守护进程被杀场景矩阵、高德协议版本漂移——各给现状/触发条件/缓解）
- [x] 代码复盘（全仓库）：旧 su 触摸链路**保留**（是降级兜底不是死代码）；
      命名与注释口径统一（批次号 AK~AM 口径已对齐）；`PrivCodecCheck` 增补新事务用例（39 项全过）
- [ ] 发布：🟡 终版 tag + release 待真机验收后打出，下载地址与验收要点在会话交付

---

## 明确不做（本系列内）

- Zygisk 模块（SECURE 黑屏等）——出现痛点再立项
- 密度策略分设备分支（F 项）——280dp 基准已实测有效
- 抄任何 OneStep 源码/注释/文案——只对齐机制思想，实现全部自写
