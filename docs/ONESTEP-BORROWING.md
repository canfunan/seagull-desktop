# OneStep4 借鉴清单（只借思路，不抄代码）

> 对象：github.com/SangLuoCN/OneStep4（锤子 OneStep 复刻，多窗口桌面容器）
> 结论：它与海鸥桌面架构同构（Home + VirtualDisplay + root 转触摸），
> 但 root 层比我们先进一代——本文列出可借鉴的机制、我们的差距、自己实现的落点。
>
> **许可证红线**：OneStep4 自有代码为 AGPL-3.0（锤子原始部分 Apache 2.0）。
> **只研究机制、全部自己重写实现**；不复制源码、不复制注释文案。
> 高德官方服务接口（§H）是高德开放的协议，不属于 OneStep 的版权物。
>
> 本地研究副本：/tmp/OneStep4（只读，不进仓库）。

---

## A. 常驻 root Binder 服务（优先级最高，架构级）

| | |
|---|---|
| 它怎么干 | `su` 启动 `app_process64 CLASSPATH=<apk>` 跑一个 **uid 0 的常驻 Java 进程**，进程内创建 Binder 服务注册到 ServiceManager（服务名 `onestep_display_*`），桌面 App 跨进程 Binder 调用。 |
| 我们现状 | `su` 起 PrivClient/RootMain，**每条 root 命令一次 shell 进程启动**（触摸事件也要过 shell），延迟高、可靠性差、无状态。 |
| 借鉴落点 | 自写 root 守护进程：同样用 app_process 挂常驻进程，服务内部持有 DisplayManager / InputManager / ActivityTaskManager（root 上下文里反射可用）。通信协议自己定义（隐藏 ServiceManager.addService 需要 uid 0，root 进程合法）。崩溃自重启、App 侧 linkToDeath 掉线感知。 |
| 解决 | 触摸延迟与丢事件、任务操作稳定性、为 B/C/E 提供宿主。 |
| 注意 | 需要 SELinux 规则配合（见 §I）；服务名不能与系统服务冲突；版本升级要处理新旧协议（加 PING/version 事务，它就是这么干的）。 |

## B. 触摸注入：root 进程内直调 injectInputEvent

| | |
|---|---|
| 它怎么干 | root 服务进程内反射 `InputManagerGlobal/InputManager.injectInputEvent(InputEvent, mode)`，MotionEvent 用 Binder Parcel 编解码传给服务（有 MotionEventCodec 类似物，我们自己写）。 |
| 我们现状 | 守护进程拼 `input tap/swipe` shell 命令；AI 批次发现 setDisplayId 反射失败会静默注错屏，改成 fail-closed 退回 `input -d`。 |
| 借鉴落点 | root 服务里统一走 `injectInputEvent`（uid 0 天然有 INJECT_EVENTS），事件带 `setDisplayId(displayId)`；反射失败**拒绝注入**（沿用 AI 批次的 fail-closed 原则）。 |
| 解决 | 触摸延迟、精确度、displayId 正确性——一次到位。 |

## C. TRUSTED + PUBLIC 虚拟屏（根治"跳主屏"）

| | |
|---|---|
| 它怎么干 | Android 11+ 在 **root 上下文**创建 VD 时加 `VIRTUAL_DISPLAY_FLAG_TRUSTED (1<<10)` + `PUBLIC` + `OWN_CONTENT_ONLY`，且显式去掉 SECURE。 |
| 我们现状 | 应用进程自建 VD 是**私有屏**——系统规则"别的应用不许往私有屏放东西"，这就是网易云点歌词跳主屏、跨应用启动落主屏的根源；我们靠 LSPosed hook（C0/C 段）+ 2.5s 轮询搬回在硬顶。 |
| 借鉴落点 | TRUSTED 屏必须由 **root 服务**（A 项）创建——应用进程自己永远拿不到 trusted。跳转/新任务就能**原生生在画布**，不需要搬。 |
| 解决 | 跨应用跳主屏（根治）、任务搬回的需求大幅减少、应用"生在画布"天然满尺寸。 |
| 注意 | PUBLIC+TRUSTED 的组合要按它的 flags 归一化思路自己推导（Android 14 上 PUBLIC 与 SHOW_WHEN_LOCKED_INSECURE 冲突等细节要实测）；TRUSTED 屏上的应用窗口语义与私有屏可能不同，H1/H2 是否还需要要实测。 |

## D. IActivityController 全局启动拦截（替代 C0 总闸的思路）

| | |
|---|---|
| 它怎么干 | root 服务里反射 `ActivityTaskManager.setActivityController(IActivityController)`——**system_server 把全系统每次 Activity 启动先问控制器**，可放行/拦截。它的决策：桌面"某应用嵌在某 VD + 该屏 5 秒内有过注入触摸"=武装路由源；武装期间该应用发起的跨应用启动 → 拦截 → 在画布上重开；**豁免**：HOME/SECONDARY_HOME、带 result 回传的启动（分享/登录流）、3 秒过期的 bypass 计数（放行自家启动）、异常一律放行。 |
| 我们现状 | C0 总闸 = LSPosed hook 目标应用的 Instrumentation.execStartActivity——只覆盖勾了作用域的应用，漏一个就跳。 |
| 借鉴落点 | 在 A 项 root 服务里自装同机制。**豁免清单照抄思路不抄码**（HOME 不拦、result 回传不拦、短时 bypass、失败放行）。 |
| 解决 | 不依赖 LSPosed 的全路径跳转拦截；高德/百度/QQ 音乐等未勾作用域的应用一并受益。 |
| 注意 | IActivityController 全系统唯一（会顶掉 Monkey 等），拦截回调在 system_server 关键路径上，**逻辑必须轻**；签名权限 SET_ACTIVITY_CONTROLLER 由 uid 0 满足。 |

## E. ITaskStackListener 实时任务监听（替代轮询）

| | |
|---|---|
| 它怎么干 | root 服务里反射注册系统 `ITaskStackListener`，实时收 onTaskMovedToFront / onTaskRemovalStarted（含 displayId、taskId、topActivity），转发给桌面。 |
| 我们现状 | 2.5s 轮询 + `am stack list` / `dumpsys` 文本解析（AF 批次补了第④通道），又慢又脆。 |
| 借鉴落点 | root 服务注册监听器，事件推给桌面 → 任务被拉走**立刻**搬回/告警，替代轮询自愈。 |
| 解决 | 自愈延迟从秒级降到事件级；删掉两套文本解析。 |

## F. 密度策略思想

| | |
|---|---|
| 它怎么干 | 手机设备：逻辑宽固定 393dp 反算 dpi；大屏：宿主屏比例缩放 + 短边 600dp 下限/800dp 目标的上限夹逼。 |
| 我们现状 | dpiFit 单一 280dp 基准（批次 Q，修好了高德）。 |
| 借鉴落点 | 保留 280dp 现状（已实测有效）；当出现"车机大屏 vs 手机"双场景时再引入按设备类型的策略分支。**现阶段不动**。 |

## G. Zygisk 模块（可选，暂缓）

| | |
|---|---|
| 它怎么干 | 原生 zygisk 模块进 system_server/zygote，修 SECURE 黑屏、状态栏 overlay、手势导航绕过；也支持切到 LSPosed 后端。 |
| 我们现状 | 无。SECURE 黑屏暂未成为痛点。 |
| 借鉴落点 | **暂缓**。等 TRUSTED 屏落地后若出现 FLAG_SECURE 黑屏（银行类 App）再议。 |

## H. 高德导航信息卡片（数据源，直接可用）

| | |
|---|---|
| 数据源 | **bind 高德官方开放的导航状态服务**：action `com.autonavi.minimap.service.NAVIGATION_SERVICE`（AIDL），注册回调收事件流：eventType / maneuverId（转向图标）/ segmentRemainDistance（本路段剩余米）/ speedKph / routeRemainTime + routeRemainDistance（全程剩余）/ currentRoadName + nextRoadName。8 秒无更新判过期清零。 |
| 我们现状 | 旧导航信息区已删（批次 R 前后），高德导航卡片一直没重做。 |
| 借鉴落点 | **这是高德官方接口，不是 OneStep 的私有物**——我们自己 bindService + 自己写 AIDL stub + 自己画卡片。高德车机版（com.autonavi.amapauto）是否开放同一 action 要实测；不支持的手机版再评估 hook/通知监听兜底。 |
| 解决 | 用户此前要求重做的"高德地图导航信息"页面，正路子。 |

## I. 部署与 SELinux（配套）

| | |
|---|---|
| 它怎么干 | Magisk/KSU 模块带 `sepolicy.rule`（root 服务过 SELinux）、service.sh 开机自启（解锁轮询后拉起）、角色授权 `COMPANION_DEVICE_APP_STREAMING`、日志落盘。 |
| 我们现状 | 开机自启用广播接收器 + su 手工拉起守护进程。 |
| 借鉴落点 | A 项落地时配套写我们自己的 sepolicy 规则与自启脚本（内容自己写，思路相同）。 |

## J. 值得吸收的小设计（写代码时对齐）

- **fail-closed 原则**：注入/操作失败宁可不动也不误注（我们 AI 批次已有，双向确认）。
- **owner token 校验**：每个 VD 事务带所有权 token，防跨槽误操作。
- **linkToDeath**：桌面/服务任一方死掉，另一方立刻感知并清理。
- **PING/version 事务**：协议兼容性握手。
- **过期时间戳**：武装路由源、bypass 计数都有 TTL，不靠回调撤销。
- **服务日志落盘**（如它的 service.log 模式）：无 logcat 环境也能排障——正好回应"用户不方便抓日志"的长期痛点。

## K. 保留不动（OneStep 没有或不适用的）

- **LSPosed 模块整段保留**：D 段（网易云竖屏烙印——OneStep 的架构救不了它，§结论见 DEVLOG 批次 AJ）、H1/H2/H3 窗口语义、A 段 SystemUI PiP 缩放。两套体系互补：模块管"应用内的谎报"，root 服务管"窗口与任务"。
- LSPosed 模块自检、画中画体检、检查更新等既有设施照旧。

---

## 分阶段落地建议（待拍板，本文件只记计划）

1. **K1 最小闭环**：常驻 root 服务（A）+ injectInputEvent 触摸（B）+ ITaskStackListener（E）。
   不动 UI、不动模块、不动部署链路——三件事都在 root 层内闭环，收益立现。
2. **K2 TRUSTED 屏**：VD 创建迁入 root 服务（C），部署链路改成"生在画布"优先、"搬任务"兜底；实测高德/网易云/iMusic 各自表现。
3. **K3 启动拦截**：IActivityController（D）上线，C0 hook 降级为备用。
4. **K4 导航卡片**：高德官方服务（H）接入 + 顶栏导航卡片 UI。
5. 每阶段独立可回滚；K1 完成前其他阶段不动工。
