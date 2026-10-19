# 海鸥桌面 · 更新清单（OneStep 借鉴落地）

> 制定时间：批次 AJ 之后（v1.0.6051e28）。
> 依据：`docs/ONESTEP-BORROWING.md`（只借思路，全部自写实现）。
> 用法：**动手前**本文是计划；**做完后**逐项打勾对齐（§K5）；对齐完**全面复盘**所有代码，
> 复盘结论写进 `docs/DEVLOG.md`，并同步 `TODO.md` / `docs/AI-GUIDE.md`。
>
> 批次号接续 DEVLOG：AK = K1，AL = K2，AM = K3，AN = K4。
> 铁律：每阶段独立可回滚、独立可发布；K1 未验收不动 K2，K2 未验收不动 K3。

---

## 通用要求（每个批次都要满足）

- [ ] 空气开关：`persist.seagull.rootbridge`（1=新链路，0/缺省=回退旧链路），关掉后行为与 AJ 完全一致
- [ ] 失败降级：root 服务不可用/版本不符时，自动退回旧 `su` 链路并在日志说明，绝不卡死 UI
- [ ] 日志：桌面侧 `SeagullCar` / root 侧 `SeagullRoot` 双标签；root 服务**日志落盘**
      `/data/local/tmp/seagull-root.log`（限 256KB，超限截断保留尾部）——治"不方便抓 logcat"
- [ ] 协议握手：连接时先 PING/version，版本不符走降级
- [ ] linkToDeath：桌面或 root 服务任一方死亡，另一方感知并清理（不残留 VD/监听器）
- [ ] 文档：每批次 DEVLOG 记录"为什么这么改"；AI-GUIDE 增补排障路径
- [ ] 构建：GitHub Actions 出包，tag 跟随 versionName（`1.0.<短哈希>`），下载地址在会话里给出

---

## K1（批次 AK）—— 常驻 root Binder 服务 + 直注触摸 + 实时任务监听

**范围红线：不动 VD 创建方式、不动部署链路、不动 LSPosed 模块、不动 UI。**
K1 的 VD 仍由应用进程自建（现状），root 服务只接管"注入类"操作。

### 工作项
- [ ] R1 root 进程入口：`app_process64 CLASSPATH=<本 APK> /system/bin com.seagull.carlauncher.rootserver.RootMain`
      —— root 入口类打进主 APK（su 启动时 CLASSPATH 指向 APK 即可，无需独立 dex）
- [ ] R2 Binder 服务 `SeagullRootBridge`：uid 0 校验 + `ServiceManager.addService`
      （服务名不得与系统冲突，若 ROM 拒绝则日志说明并放弃，桌面侧走降级）
- [ ] R3 事务集（自定协议，参考思路自写）：
      `PING(version)` / `INJECT_MOTION(motionEvent, displayId)` / `REGISTER_TASK_LISTENER(callback)` /
      `UNREGISTER_TASK_LISTENER`；事务号常量独立成类，客户端服务端共用
- [ ] R4 MotionEvent 编解码：桌面 → root 进程（Parcel 手写编解码，不序列化对象）
- [ ] R5 注入实现：root 进程内反射 `InputManager.injectInputEvent(InputEvent, mode)`，
      事件带 `setDisplayId(displayId)`；**反射失败拒绝注入并记日志（fail-closed）**
- [ ] R6 实时任务监听：root 进程反射注册 `ITaskStackListener`
      （onTaskMovedToFront / onTaskRemovalStarted / onTaskStackChanged，含 displayId/taskId/topActivity），
      事件实时推给桌面注册的 callback Binder
- [ ] R7 桌面侧客户端 `RootBridgeClient`：连接管理、PING 握手、重连、linkToDeath、
      对上层暴露 `inject(event, displayId)` 与任务事件监听
- [ ] R8 触摸链路切换：PipBoard/MirrorSlot 的触摸路径在"root 服务可用"时走 R5，
      否则原 `su input -d` 兜底（开关 R1 通用要求）
- [ ] R9 自愈兜底保留：PipBoard 2.5s 轮询搬回**不删**，仅在 root 服务监听可用时降低频次
      （如 8s），防止监听通道失联后无人兜底
- [ ] R10 su 启动与保活：root 服务由桌面经 su 拉起（沿用现守护进程的拉起时机）；
      崩溃后桌面感知（linkToDeath）→ 重新拉起一次，二次失败退回旧链路
- [ ] R11 自检页新增「root 服务」状态行：已连接 + 版本 / 未连接 + 原因（设置 → 系统）

### 验收（真机）
- [ ] `logcat -s SeagullRoot`：`bridge published uid=0 version=1`；`/data/local/tmp/seagull-root.log` 有内容
- [ ] 画布触摸：延迟明显低于旧链路（主观 + 日志时间戳对比），且事件只出现在目标 displayId
- [ ] `persist.seagull.rootbridge=0` → 行为与 AJ 完全一致（回滚验证）
- [ ] 任务监听：把网易云拉到主屏，桌面 1s 内收到 MOVED_TO_FRONT 事件（日志），随后自愈搬回
- [ ] 杀 root 服务进程 → 桌面 linkToDeath 感知 → 自动重拉成功（或明确降级）
- [ ] 高德画中画部署/画满/触摸全部照旧（无回归）

---

## K2（批次 AL）—— TRUSTED + PUBLIC 虚拟屏（跳主屏根治）

**前置：K1 全部验收通过。**

### 工作项
- [ ] R1 root 服务新增事务：`CREATE_DISPLAY(w,h,dpi,flags)` / `RESIZE` / `SET_SURFACE` / `RELEASE`，
      flags 归一化自写（Android 11+ 加 TRUSTED(1<<10)；PUBLIC+OWN_CONTENT_ONLY；
      显式去掉 SECURE；Android 14 的 PUBLIC×SHOW_WHEN_LOCKED_INSECURE 冲突规避）
- [ ] R2 桌面 SurfaceView surface 经 Binder 传给 root 服务（`SET_SURFACE` 携带 Surface）
- [ ] R3 部署链路增加"root 服务建屏"优先级：**root TRUSTED 屏 → 现有私有屏直建 → MediaProjection 兜底**，
      每级失败自动降下一级并记日志
- [ ] R4 应用部署到 TRUSTED 屏：优先 `startActivityAsUser` 带 `setLaunchDisplayId`
      （root 服务内执行，"生在画布"）→ 失败退回现有 move-task
- [ ] R5 密度沿用 dpiFit 280dp 基准（F 项策略暂不引入）
- [ ] R6 体检/巡检/触摸适配：displayId 与帧数据来源改为 root 服务；私有屏路径保持可用（回滚）
- [ ] R7 输入法实测：TRUSTED+PUBLIC 屏上画布内点击输入框，IME 能否弹出（预期能）；
      弹不出记 DEVLOG，不阻塞本批
- [ ] R8 网易云竖屏烙印回归验证：部署后 D 段 hook + 密度抖动照常工作（模块不动），
      "生在画布"的应用首次 attach 仍读 display 0 → 烙印仍在 → 抖动重建修复链路必须依然成立

### 验收（真机）
- [ ] `dumpsys display`：画布为 trusted 且 public（flags 与设计一致）
- [ ] 网易云部署 → 8 秒内铺满（不劣化于 AJ）
- [ ] **点歌词/封面：不再跳主屏**（生在画布的任务新页面跟随任务）——本批核心验收
- [ ] 高德部署/触摸/铺满无回归
- [ ] 关闭 root 服务 → 降级到私有屏链路，一切照旧

---

## K3（批次 AM）—— IActivityController 全局启动拦截

**前置：K2 验收通过（拦截后需要 TRUSTED 屏承接改道启动）。**

### 工作项
- [ ] R1 root 服务事务：`SET_ACTIVITY_CONTROLLER(on/off)`；卸载时恢复系统原状（可置回 null）
- [ ] R2 武装路由源（自写）：桌面在应用部署/触摸注入时更新"displayId ↔ 嵌入应用 ↔ 最近触摸时间"，
      TTL 5s；bypass 计数（放行自家/改道后的启动）TTL 3s
- [ ] R3 豁免规则（自写实现，思路对齐）：
      HOME / SECONDARY_HOME 不拦；带 result 回传语义的启动不拦（防打断分享/登录流）；
      目标 = 源应用自身不拦；root 服务自身与桌面不拦；**一切异常放行**
- [ ] R4 拦截动作：改道 = 在源 displayId 上重新启动该 Intent（先发 bypass 计数再启动，避免自锁）
- [ ] R5 性能红线：controller 回调内不做磁盘 IO / 不做 binder 重试；意图判定只用内存态
- [ ] R6 C0 hook（LSPosed）降级为备用：加 `persist.seagull.c0`（默认开），root 拦截生效时
      模块侧日志注明"备用模式"（不改模块逻辑本身）
- [ ] R7 自检页新增「启动拦截」状态：armed 包列表 + 最近一次拦截记录

### 验收（真机）
- [ ] 画布内点网易云歌词/封面：**零跳主屏闪现**（对比 K2 可能存在的先跳后搬回）
- [ ] 主屏正常使用：桌面/浏览器/相册启动完全不受影响；HOME 键行为正常
- [ ] 分享/登录等 result 流程实测一次（如相册分享到微信）不被打碎
- [ ] QQ 音乐/百度地图（未勾 LSPosed 作用域的）画布内跨应用启动也被正确改道
- [ ] 关闭开关或 root 服务下线 → 拦截解除，系统行为恢复

---

## K4（批次 AN）—— 高德导航信息卡片（官方服务接入）

**无前置依赖，可与 K1~K3 并行评审，但实施排在 K3 后。**

### 工作项
- [ ] R1 bind 高德官方导航服务：action `com.autonavi.minimap.service.NAVIGATION_SERVICE`
      （包名 `com.autonavi.minimap`；车机版 `com.autonavi.amapauto` 是否开放同一 action 真机实测，
      不开放则记录 DEVLOG 并评估通知监听兜底，本批不强做）
- [ ] R2 AIDL stub 自写（只取：eventType / maneuverId / segmentRemainDistance / speedKph /
      routeRemainTime / routeRemainDistance / currentRoadName / nextRoadName）
- [ ] R3 数据保鲜：8 秒无更新判过期 → 状态清空；断连/绑死清理（onServiceDisconnected /
      onBindingDied / onNullBinding 全覆盖）
- [ ] R4 顶栏导航卡片 UI：沿用原"导航信息"区的位置与交互（转向图标 + 距离 + 路名 + 全程剩余），
      无导航时隐藏/置灰；绑定应用可选（高德手机版/车机版）
- [ ] R5 转向图标映射自维护（maneuverId → 本地资源），缺图给通用箭头，不引用 OneStep 资源

### 验收（真机）
- [ ] 手机版高德开导航 → 卡片实时更新（路名/距离/转向随播报变化）
- [ ] 结束导航 → 卡片 8 秒内消失；强杀高德 → 断连清理无残留
- [ ] 不影响画中画（导航卡片只读数据，不触碰高德进程）

---

## K5（收尾批次）—— 全面对齐清单 + 全面复盘所有代码

**前置：K1~K4（或用户裁剪后的范围）全部完成。**

### 对齐（逐项打勾）
- [ ] 本清单 K1~K4 每个工作项、每个验收项逐条核对，未勾项逐条给出"完成/降级/放弃 + 原因"
- [ ] 通用要求（开关/降级/日志/握手/死亡感知/文档/构建）逐条核对
- [ ] 三次回归基线全部复测：AJ（模块链路）、AK（root 服务链路）、AL/AM/AM 后各一次

### 全面复盘（输出物）
- [ ] `docs/DEVLOG.md`：新增「复盘」章——本系列每批"预期 vs 实测"对照、踩坑记录、协议与开关的最终形态
- [ ] `docs/AI-GUIDE.md`：架构图更新（root 服务拓扑、事务表、降级树）、新排障路径
      （root 服务连不上怎么查、trusted 屏异常怎么查、拦截误伤怎么关）
- [ ] `TODO.md`：P2 分区重写（完成项打勾、新增遗留项）
- [ ] 风险清单：IActivityController 与 ROM 兼容性、TRUSTED 屏在不同 ROM 的 flags 差异、
      root 服务被杀场景矩阵——每项给"现状/触发条件/缓解"
- [ ] 代码复盘（全仓库）：死代码清理（旧 su 触摸链路若确认稳定降级后标记）、
      命名与注释口径统一、`app/selfcheck/` 增补 root 服务与拦截的自检用例
- [ ] 发布：终版 tag + release，下载地址与验收要点在会话里交付用户

---

## 明确不做（本系列内）

- Zygisk 模块（SECURE 黑屏等）——出现痛点再立项
- 密度策略分设备分支（F 项）——280dp 基准已实测有效
- 抄任何 OneStep 源码/注释/文案——只对齐机制思想，实现全部自写
