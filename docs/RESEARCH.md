# 功能实现调研报告（2026-10-19）

调研方式：高德官方文档 + Android 官方文档 + 全网检索（CSDN/技术栈/火山引擎实践帖）。覆盖 BACKLOG 已拍板 16 项，外部依赖项附来源。

## A. 外部依赖类

### A1 常用目的地一键导航（#8）

- 高德官方 URI（客户端 V4.1.3+）：
  - 路线规划页：`amapuri://route/plan/?sourceApplication=seagull&slat=&slon=&sname=我的位置&dlat=&dlon=&dname=家&dev=0&t=0`（t=0 驾车；dev=0 表示坐标已按国测加密；追加 `navigate=yes` 直接进导航）
  - 官方 navi 式：`androidamap://navi?sourceApplication=..&poiname=..&lat=..&lon=..&dev=1&style=2`
- 百度：`baidumap://map/direction?destination=lat,lng&mode=driving&coord_type=gcj02&src=seagull`
- 包名：高德 com.autonavi.minimap、百度 com.baidu.BaiduMap；startActivity 捕 ActivityNotFoundException 提示未安装
- **坐标系大坑**：GPS 原始 WGS84 与高德 GCJ02 混用会漂移（检索实践帖：80% 调起失败源于坐标系混用）。目的地坐标必须存 GCJ02（dev=0）
- Android 11+ 包可见性：targetSdk>=30 时 manifest 需 `<queries>` 声明上述 scheme/包名
- MVP 数据方案：设置里存「名称 + GCJ02 经纬度」文本对（高德坐标拾取器复制），免地理编码 key
- 来源：lbs.amap.com/api/amap-mobile/guide/android/route-plan、CSDN《Intent调起主流地图导航全攻略》
- 量级：小（半天）

### A2 导航信息卡片（#11）

- 官方无第三方「通知解析」文档；高德导航常驻通知正文含「500米后右转进入XX路」类文案
- 方案：MediaListenerService（通知监听链路已通）扩展捕获 com.autonavi.minimap / com.baidu.BaiduMap 的 ongoing 通知 → 解析 contentTitle+bigText 正则 → 投 IslandView
- **真机确认点**：`adb shell dumpsys notification --noredact | grep -A30 autonavi` 抓实际字段（extras/extraText），高德/百度文案模板分开建；正则失败时静默隐藏横幅
- 风险：导航通知文案随 app 版本变化 → 解析模板做成可配置常量
- 量级：小-中（解析是真机迭代活）

### A3 本地音乐播放器（#5）

- 内置存储：/sdcard/Music 用 File API + READ_EXTERNAL_STORAGE（targetSdk 29 + requestLegacyExternalStorage 兼容 A10）
- U 盘三层：UsbManager（插拔，动态注册 receiver）→ StorageManager.getStorageVolumes()（isRemovable/state/getUuid/getDirectory）→ 访问层两条路：
  - **推荐（贴合 root 架构）**：root `ls -R /mnt/media_rw/<uuid>` 经 Caps 扫描入库
  - 兜底：SAF ACTION_OPEN_DOCUMENT_TREE 持久化授权走 DocumentFile（Scoped Storage 下 getDirectory 直接 File 读会被 SELinux/media_rw 组拦）
- 播放：MediaPlayer + 前台服务 + MediaSession（PlaybackState/Metadata）；自家 session 自动进 MediaListenerService 的 activeSessions → MiniPlayer 零改造联动
- .lrc 复用现有解析器（15 项自检钉住）
- 真机确认点：车机 U 盘挂载点与 FS 类型（`dumpsys mount`）、OTG 供电
- 量级：大（独立批次：服务 + 扫描 + 列表 UI + 队列）
- 来源：developer.android.com/training/data-storage（scoped storage 官方）、CSDN U盘读写实战系列

### A4 农历（#6 子项）

- 决策：自研单文件查表法（1900-2100 农历位表 + 闰月/大小月位运算），进 selfcheck（对照万年历抽查 30 日期）
- 项目零依赖纪律；交叉验证可用 lunar-java（cn.6tail:lunar，无三方依赖，java 1.5+，Apache-2.0）作为数据比对源，仅测试期用
- 来源：codeKK lunar-java 主页、PingCode 农历转换实践
- 量级：小

### A5 快捷开关 root 命令（#7 负一屏子项）

- WiFi：`cmd wifi set-wifi-enabled enabled|disabled`（A9+）→ 降级 `svc wifi enable/disable`（root）；状态 `settings get global wifi_on`
- 蓝牙：`svc bluetooth enable/disable`；状态 `settings get global bluetooth_on`
- 热点：`service call connectivity <txn> i32 1` 的 transaction 号随 ROM 漂移（AOSP 8.1 为 34）→ **真机探号后固化**，探不到就降级打开系统热点页 `am start -n com.android.settings/.TetherSettings`
- 状态轮询：负一屏可见时 2s tick 查 settings（轻量）
- 来源：CSDN《ADB高效管理蓝牙热点WiFi》、火山引擎 AOSP 8.1 热点帖
- 量级：中

### A6 车速/海拔/航向/行程（#10/#12/#14）

- `LocationManager.requestLocationUpdates(GPS_PROVIDER, 1000, 0, listener)`；getSpeed()（m/s→km/h）、getAltitude()、getBearing()
- 行程累计：speed > 1.5 m/s 才计入 distanceTo（过滤漂移）；点火时长用开机时间戳
- 权限：ACCESS_FINE_LOCATION 运行时申请；组件在桌面=前台场景，无后台定位问题
- 真机确认点：车机 GPS 天线在位（`dumpsys location`）；隧道丢星时 UI 显示 "--"
- 量级：小（共享管线 + 三块 UI）

## B. 内部架构类（零外部依赖）

### B1 开机自启恢复（#1）

- launcher 即 home 应用，系统开机必然拉起我们；BOOT_COMPLETED receiver 只做延迟布屏：15s/30s/60s 退避重试读存档 deploy（deploy 幂等）
- 关键坑（检索确认）：receiver exported=true 必须显式声明；应用被强停后收不到广播；`am broadcast -a android.intent.action.BOOT_COMPLETED -n 包名/.Receiver` 可免重启测试
- 量级：小

### B2 场景一键（#2）

- 配置 `scenes:[{name, slotA:{pkg}, slotB, weightA, wallpaper}]` + QuickBar 场景卡；deploy 幂等已保证运行中应用不重复 force-stop
- 量级：小

### B3 桌面手势（#3）

- Drag 的 armed 门闩扩展双指判定；锁屏方案二选一（真机试）：DevicePolicyManager.lockNow（引导激活设备管理员一次）或 root `input keyevent KEYCODE_POWER`
- 量级：小-中

### B4 主题包（#4）

- Skin.c() 加 theme 维度：色板 JSON + 圆角 dp + 玻璃 alpha + 描边，LauncherModel 持久化；全量颜色走查是主要工作量
- 量级：中

### B5 负一屏（#7）

- 桌面横向滑页挂聚合卡容器（复用 WallpaperPage 滑动基建）；天气/限行/快捷开关/日志导出/目的地卡按块装配
- 量级：中

### B6 天气动态壁纸联动（#9）

- Weather 现有 cond/day-night 字段 → WallpaperVideoView 壁纸包目录约定 `wallpapers/{sunny|rain|snow|night}/`；匹配失败回落当前壁纸；手动锁定优先级最高
- 量级：中

### B7 系统监控组件（#13）

- /proc/meminfo（root 读）+ cpuPct（2s TTL 现成）+ StatFs 存储
- 量级：小

### B8 播放列表组件（#15）与娱乐组件（#16）

- 播放列表组件跟 #5 同批；G值表 = SensorManager ACCELEROMETER 低通（零权限）；频谱 Visualizer 需 RECORD_AUDIO，保留评估
- 量级：小-中

## C. 批次建议

| 批次 | 内容 | 量级 |
|---|---|---|
| G 开机链 | #1 开机自启 + #2 场景一键 | 小+小 |
| H GPS 管线 | #10 车速 + #12 海拔航向 + #14 行程 | 小 |
| I 信息组件 | #6 组件扩展（含农历）+ #13 监控组件 | 中 |
| J 负一屏 | #7 负一屏 + #8 目的地导航（URI）+ 日志导出并入 | 中 |
| K 壁纸联动 | #9 天气动态壁纸联动 | 中 |
| L 导航卡片 | #11 导航信息卡片（先真机抓通知格式） | 小-中 |
| M 音乐 | #5 本地音乐播放器 + #15 播放列表组件 | 大 |
| N 手感 | #3 手势 + #4 主题包 + #16 娱乐组件 | 中 |

真机待探清单（一次 adb session 可全探）：导航通知字段（A2）、热点 service call 交易号（A5）、GPS 在位（A6）、U 盘挂载点（A3）、锁屏方案（B3）。
