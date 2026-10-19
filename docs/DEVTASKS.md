# 功能开发清单（2026-10-19 开工）

依据 docs/BACKLOG.md 已拍板 16 项 + docs/RESEARCH.md 方案拆解。执行纪律：每个小功能先调研（官方文档/开源实现/全网）再动手；实现一个复盘一个；全部完成后清单对齐 + 三次大复盘修复 + 推送构建持续监控。

## 批次 G 开机链
- [x] G1 开机自启恢复：BootReceiver（BOOT_COMPLETED/QUICKBOOT_POWERON，exported=true）+ 延迟布屏退避重试（15/30/60s）+ 开关项；adb am broadcast 可测
- [x] G2 场景一键：scenes 配置（slotA/slotB/weight/wallpaper）+ QuickBar 场景卡入口 + 一键布屏（deploy 幂等复用）

## 批次 H GPS 管线
- [ ] H1 LocationHelper：权限申请、GPS_PROVIDER 1s 节流、死亡解绑
- [ ] H2 车速组件（km/h，丢星 --）
- [ ] H3 海拔/航向组件（共用 H1）
- [ ] H4 本次行程组件（点火时长+里程，speed>1.5m/s 计入）

## 批次 I 信息组件
- [ ] I1 LunarCalendar 单文件查表（1900-2100）+ selfcheck 抽查 30 日期
- [ ] I2 日历组件（公历+农历行）
- [ ] I3 倒计时组件（目标日配置）
- [ ] I4 指南针组件（加速度计+磁力计融合）
- [ ] I5 系统监控组件（内存/CPU/存储）

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
