# 功能待办池（BACKLOG）

记录用户拍板待做的功能与候选想法。拍板的进「已拍板」，其余进「候选池」。拍板后按 feature-design 流程出需求/设计文档，再排批次实施。

## 已拍板待做（2026-10-19）

### 1. 开机自启恢复

- 点火后自动把上次的两块画布布回来（RECEIVE_BOOT_COMPLETED + 延迟 deploy，等系统稳定后带重试）
- 改动集中在 HomeActivity 加 receiver + LauncherModel 已有布屏状态
- 验收：断电重启，进桌面 10s 内画布自动恢复，无需手点

### 2. 场景一键

- QuickBar 场景卡：一个按钮布好预设（画布 A/B 应用 + 权重 + 壁纸）
- 依赖：runFn、deploy、pipWeightA/pipWeightB、Wallpaper 全现成，纯配置层工作
- 验收：一键完成分屏 + 启动；正在运行的目标应用不重复 force-stop

### 3. 桌面手势

- 空白处双指下滑 = 锁屏、上滑 = 进画中画、长按拖拽 = 直接分屏应用
- 依赖：Drag 的 armed 门闩 / longPressed 基建（M14 修复后语义干净）
- 验收：手势与既有单击、长按菜单零冲突

### 4. 主题包

- Skin.c() 取色体系扩成主题包：配色 / 圆角 / 玻璃透明度 / 卡片描边整体切换
- 验收：切换即时生效并持久化进 LauncherModel，主题间零硬编码色残留

### 5. 本地音乐播放器

- 扫描 /sdcard/Music 与 U 盘，自建 MediaPlayer + MediaSession，MiniPlayer / 歌词组件直接联动，本地 .lrc 复用现成解析器
- 改动量最大（播放服务 + 扫描 + 列表 UI），值得独立批次单独出方案
- 验收：播放/暂停/上下曲走 MiniPlayer；歌词同步滚动；重启后播放列表与进度保留

### 6. 组件扩展

- makeWidget 体系补日历（含农历）、倒计时、指南针三个自绘组件，与歌词/时钟同一套摆位缩放
- 验收：三组件可进桌面、可拖摆、可缩放；刷新走原位更新，零 requestLayout 风暴（M5 教训）

## 候选池（未拍板，随时可挑）

- 导航信息卡片：解析高德/百度常驻导航通知（转向/距离/路名）投 IslandView
- 画布比例拖拽：分割线把手调权重，双击回 1:1（pipWeightA/B 已存在）
- 专辑封面：MediaMetadata Bitmap 进 MiniPlayer / 歌词组件背景
- 方向盘媒体键兜底：HomeActivity onKeyDown 转 MediaController.transportControls
- 倒车自动切后视：倒车广播 / root 读 sysfs，需真机确认信号源
- 配置导出/导入：LauncherModel JSON 走 SeagullFileProvider 分享
- 蓝牙来电卡片：BluetoothHeadset 监听 + root TelecomManager 接听挂断
- HUD 夜间条：熄灯后时钟+歌词压成低亮度黑底条（环境光三档基建已在）
- 应用冻结：root pm disable-user，长按冻结/解冻，冻结项从 dock 和列表消失
- 尾号限行提示：天气卡加一行「今日限行 x/x」，本地城市规则
- 一键日志导出：RootLog 拷 /sdcard/Seagull/ 带 commit 号，反馈排障用
- 隐藏应用：dock 候选过滤 + pin 锁显示
- 崩溃自恢复：uncaught handler 记 RootLog + 回落默认画布
