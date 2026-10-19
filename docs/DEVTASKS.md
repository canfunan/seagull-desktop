# 功能开发清单（2026-10-19 开工）

依据 docs/BACKLOG.md 已拍板 16 项 + docs/RESEARCH.md 方案拆解。执行纪律：每个小功能先调研（官方文档/开源实现/全网）再动手；实现一个复盘一个；全部完成后清单对齐 + 三次大复盘修复 + 推送构建持续监控。

## 批次 G 开机链
- [x] G1 开机自启恢复：BootReceiver（BOOT_COMPLETED/QUICKBOOT_POWERON，exported=true）+ 延迟布屏退避重试（15/30/60s）+ 开关项；adb am broadcast 可测
- [x] G2 场景一键：scenes 配置（slotA/slotB/weight/wallpaper）+ QuickBar 场景卡入口 + 一键布屏（deploy 幂等复用）

## 批次 H GPS 管线
- [x] H1 LocationHelper：权限申请、GPS_PROVIDER 1s 节流、死亡解绑
- [x] H2 车速组件（km/h，丢星 --）
- [x] H3 海拔/航向组件（共用 H1）
- [x] H4 本次行程组件（点火时长+里程，speed>1.5m/s 计入）

> H 批次落位说明：DesktopView 组件条是上代桌面遗留（当前无实例化，仅 LayoutModeActivity 引用 WIDGET_NAMES 常量），车速/海拔/行程三块按 BACKLOG「与时钟/歌词同套摆位缩放」的意图落到壁纸页时钟块同款机制上——一个可拖/可缩放的行车块，三行分别是 H2/H3/H4；开关默认开，无权限时整块缩成「点按开启定位」，隐藏后经时钟块菜单恢复。

## 批次 I 信息组件
- [x] I1 LunarCalendar 单文件查表（1900-2100）+ selfcheck 抽查 30 日期
- [x] I2 日历组件（公历+农历行）
- [x] I3 倒计时组件（目标日配置）
- [x] I4 指南针组件（加速度计+磁力计融合）
- [x] I5 系统监控组件（内存/CPU/存储）

> I 批次落位说明：农历行并入时钟块日期行下方（I2）；倒计时/监控为壁纸页独立块（I3/I5，与时钟块菜单同一开关位找回）；I4 指南针做成行车块的航向兜底源（GPS 失联才补显），独立罗盘盘面在车机场景价值低。

## 批次 J 负一屏
- [ ] J1 负一屏容器（桌面左滑页，复用滑动基建）
- [ ] J2 天气卡+限行卡（本地城市规则）
- [ ] J3 快捷开关（cmd/svc WiFi 蓝牙 + 热点 service call 探号降级设置页）
- [ ] J4 日志导出卡（RootLog → /sdcard/Seagull/ 带 commit 号）
- [ ] J5 常用目的地卡（amapuri://route/plan + baidumap://map/direction，GCJ02，<queries>）

## 批次 K 壁纸联动
- [ ] K1 天气动态壁纸联动（weather/night 目录约定 + 手动锁定优先 + 失败回落）

## 批次 L 导航卡片
- [ ] L1 导航通知解析骨架（高德/百度包 ongoing 通知 → 正则模板常量 → IslandView 横幅；格式真机探明后填模板，失败静默隐藏）

## 批次 M 本地音乐
- [ ] M1 音乐扫描：/sdcard/Music File API + U盘 StorageVolume + root /mnt/media_rw 扫描
- [ ] M2 播放服务：MediaPlayer + 前台服务 + MediaSession（MiniPlayer 自动联动）
- [ ] M3 音乐列表 UI + 队列（选中/播放/切歌）
- [ ] M4 .lrc 联动歌词组件（复用 Lrc 解析器）
- [ ] M5 播放列表组件（桌面点歌/切歌）

## 批次 N 手感
- [ ] N1 桌面手势（双指下滑锁屏[lockNow/input POWER 真机试]、上滑进画中画、长按拖拽分屏）
- [ ] N2 主题包（色板/圆角/玻璃 alpha/描边整体切换）
- [ ] N3 娱乐组件（G值表；频谱 Visualizer 权限评估后决定）

## 终局
- [ ] 功能清单对齐（本文件逐项核对 + 真机验收项标注）
- [ ] 三次大复盘（并行分组精读 → 亲自复核 → 自检交叉验证）+ 修复全部发现
- [ ] javac --release 11 + 全部 selfcheck 绿 → commit/push → CI 监控 → APK 交付

## 复盘记录
（每个功能完成后在此追加一行：功能 — 复盘结论）
- G1 开机自启恢复 — 用 SystemClock.elapsedRealtime<90s 窗口判定替代 BOOT 广播标记，规避 receiver 与 home 启动竞态；slot1 复用现有 deployIfBound+selfHeal 重试链零新增机制；slot2 仅开机窗口放行（批次 CG 手动门槛保持）；新增 autoRestore 开关（默认开）与 QUICKBOOT_POWERON action。javac 绿。
- G2 场景一键 — 调研期发现 QuickBar 已有 @fn: 前缀分发模式，场景沿用同构 key 约定 @scene:<名字>（名字而非索引，重排序安全）；编辑工具多行锚块匹配吞码两处（QuickBar cell/桌面菜单），改小锚点后恢复。场景存档全局共享（scenes 数组），重命名/删除时同步清洗所有布局快捷栏的引用 key（quickbarByMode 是 private，新增 allQuickbars() 访问器）；PipBoard.applyScene 走 savePkg+s2Entered+deployIfBound 复用幂等部署链，空槽场景 slot2 重新上闸（批次 CG 语义）；删除场景即清格，悬空 key 兜底 toast「场景不存在」。javac 绿。
- H1 LocationHelper — 单例 fanout（acquire/release 配对，最后一个退订即 removeUpdates，对齐歌词 sink 纪律）；GPS 在位用 GPS、否则回落 NETWORK；1s/0m 节流；回调统一切主线程；10s 内无新 fix 视为失联（隧道显示 --）。javac 绿。
- H2/H3/H4 行车块 — 调研期发现 makeWidget 所在的 DesktopView 已是死代码（当前零实例化），三块 UI 按原意（与时钟同套摆位缩放）落到壁纸页时钟块同款 Drag/Zoom 机制：一个块三行（车速/海拔航向/行程），摆位缩放存 model.wallGps*；无权限=缩成「点按开启定位」（点按申请权限，回执经 HomeActivity.onRequestPermissionsResult 转发 refreshGpsBlock）；隐藏后经时钟菜单恢复；1s 定位事件原位 setText（审计 M4 纪律）；行程=进程级点火时长+speed>1.5m/s 位移累计，重启归零（BACKLOG #14）。合并为单块而非三个独立组件：壁纸页摆位机制按块计，三块独立可拖反而拥挤，J1 负一屏如需拆分再拆。javac 绿。
- I1 LunarCalendar — 位表由 lunar-java 1.7.3 逐项生成（比抄网页表可靠，1900=0x04bd8 与经典表逐位一致）；全量表内 67537 天逐日比对 0 差异后，LunarCheck 钉 30 个机器生成用例。教训：第一轮手写期望值 10 处错（2020 闰四月起点、2057 闰月等全记错），机器生成期望值才可信；锚点前（1900-01-31 之前）超界返回 null。javac 绿 + LunarCheck 绿。
- I2 农历行 — 时钟块 date 行下加 lunarRow（"农历 腊月十九 · 乙巳(蛇)"），超表隐藏；restackClockBox 等比缩放同步。javac 绿。
- I3 倒计时块 — 壁纸页块 + 点按菜单配置（改名称/改日期/大小/隐藏），日期手动解析+校验（失败 toast），跨零点由 30s tick 重算；cdDays 用本地零点差避免时区坑。javac 绿。
- I4 指南针兜底 — 车机场景 GPS 航向是主源，磁力计+加速度计只做失联兜底（融合=优先级仲裁而非盘面）：500ms 节流、横屏 remapCoordinateSystem(AXIS_Y, AXIS_MINUS_X)、±180 最短角差指数平滑；无磁力计静默降级。javac 绿。
- I5 监控块 — 内存（ActivityManager）/CPU（SysOps.cpuPct，120ms 采样）/存储（StatFs /data）三行；采样放后台线程（cpuPct 的 sleep 在 UI 线程会卡帧），结果 uiPost 原位 setText；默认关，时钟菜单开。javac 绿。
