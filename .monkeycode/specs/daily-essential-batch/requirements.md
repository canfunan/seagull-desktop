# Requirements Document — 日常可用度批次（O）

Feature Name: daily-essential-batch
Updated: 2026-10-19
批次范围：方向盘媒体键兜底（F1）、专辑封面（F2）、配置导出/导入（F3）、崩溃自恢复（F4）

## Introduction

四个低改动高日常价值的小件打包一个批次，目标是把桌面的「日常可用度」闭环：方控按键能控音乐、音乐块有封面、配置可带走、崩溃有记录可恢复。

## Glossary

- **活跃媒体会话**：MediaSessionManager.getActiveSessions 返回的队列里最近有播放活动的 MediaController
- **本地会话**：MusicService 持有的 MediaSession（包名为本项目包名）
- **方控键**：方向盘物理按键发出的 KeyEvent（KEYCODE_MEDIA_* 系列）
- **配置存档**：SharedPreferences 文件 HomeActivity.PREFS 中主键 json_layout 的全量配置 JSON
- **崩溃签名**：异常类名 + 堆栈首帧（Thread.getStackTrace()[0]）的拼接串

## F1 方向盘媒体键兜底

**User Story:** AS 车主，I want 方向盘的播放/暂停/上一曲/下一曲按键能控制当前音乐，so that 行车中免伸手触屏。

#### Acceptance Criteria

1. WHEN HomeActivity 收到 KEYCODE_MEDIA_PLAY、PAUSE、PLAY_PAUSE、NEXT、PREVIOUS、STOP 之一，HomeActivity SHALL 将按键翻译为活跃媒体会话 transportControls 的对应调用（play/pause/toggle/skipToNext/skipToPrevious/stop）。
2. WHEN 存在本地会话且播放状态为 playing 或 paused，HomeActivity SHALL 优先选择本地会话作为转发目标。
3. WHEN 本地会话无活动，HomeActivity SHALL 选择最近活跃的远端会话作为转发目标。
4. WHEN 无任何活跃会话，HomeActivity SHALL 忽略该按键并保留默认处理（return super，给系统媒体路由留路）。
5. WHEN 转发目标在 300ms 内无响应（播放状态未变化），HomeActivity SHALL 仅在 RootLog 记录转发事件，界面零打扰。
6. IF 前台窗口属于画布内第三方应用（按键被路由到虚拟屏焦点窗口），本兜底 SHALL 接收不到该事件（non-goal：系统层按键路由归系统管）。

## F2 专辑封面

**User Story:** AS 车主，I want 音乐块和迷你播放器显示当前歌曲封面，so that 音乐区有内容感且一眼认出在放哪张专辑。

#### Acceptance Criteria

1. WHEN 活跃媒体会话的 MediaMetadata 含 METADATA_KEY_ALBUM_ART 位图或 METADATA_KEY_ALBUM_ART_URI，封面解码 SHALL 在后台线程完成并缓存。
2. WHEN 缓存命中同一键（title+artist 拼接），封面解码 SHALL 跳过重复解码。
3. WHEN 封面位图边长超过 256px，解码流程 SHALL 采样缩放到 256px 以内再入缓存（OOM 防护）。
4. WHILE 封面可用，音乐块背景 SHALL 以压暗遮罩叠加封面，迷你播放器封面位 SHALL 显示封面。
5. IF 封面缺失或解码失败，音乐块与迷你播放器 SHALL 回落现有默认样式（纯色块），界面零报错。
6. WHEN 本地音乐文件的 ID3 内嵌封面存在，MusicService 构造 MediaMetadata SHALL 将内嵌封面写入 METADATA_KEY_ALBUM_ART。

## F3 配置导出/导入

**User Story:** AS 车主，I want 把全部桌面配置导成一个文件带走、在新机器上一键还原，so that 重装和换机不用重配。

#### Acceptance Criteria

1. WHEN 用户在「系统」设置页点「导出配置」，系统 SHALL 把配置存档包成带 meta（导出时间、版本号、commit 标识）的 JSON 写入 files/export/，文件名含日期，并弹出系统分享面板。
2. WHEN 用户在「系统」设置页点「导入配置」并通过 SAF 选择 JSON 文件，系统 SHALL 校验根对象可解析且含 v 字段，校验通过后反解写入配置存档、bumpCfg、提示「重启桌面生效」。
3. IF 校验失败（解析异常、缺 v 字段、尺寸超 5MB），系统 SHALL 拒绝导入并弹出可读原因，现有配置保持原样。
4. WHEN 导出完成，导出文件 SHALL 仅含配置数据（SharedPreferences 全键 JSON），不含账号、密钥或缓存文件。
5. WHEN 导入的 JSON 缺少新增字段（低版本导出），系统 SHALL 按现有 LauncherModel 默认值补齐（load 自愈语义）。
6. WHEN 导出文件通过 SeagullFileProvider 分享，URI SHALL 为 content:// 且授权给目标应用读取。

## F4 崩溃自恢复

**User Story:** AS 车主，I want 桌面崩溃时有记录、重启后自动回到可用状态，so that 一次崩溃不会让车机停在黑屏或循环崩溃。

#### Acceptance Criteria

1. WHEN 主进程发生未捕获异常，崩溃处理器 SHALL 把崩溃时间、线程、堆栈、commit 标识经 RootLog 落档，并把同一份内容写入 files/crash/crash-<timestamp>.txt，随后委托回原默认处理器（保留系统终止语义）。
2. WHEN 崩溃签名在 24 小时内累计达 3 次，崩溃处理器 SHALL 置「连续崩溃」标记位后照常委托原处理器。
3. WHEN 下次启动 HomeActivity 检测到「连续崩溃」标记位，HomeActivity SHALL 清除画中画布屏状态回落默认布局、清除标记位，并弹一次性提示「检测到连续崩溃，画布已恢复默认布局」。
4. WHILE 负一屏日志卡展示导出入口，日志导出 SHALL 包含 files/crash/ 下的崩溃记录文件。
5. IF 崩溃发生在崩溃处理器自身内，处理流程 SHALL 静默放弃并委托原处理器（兜底不二次崩溃）。

## 备注

- 频率约束：F1 转发路径全程无分配热点（KeyEvent 高频），F2 解码节流，F3/F4 均为用户触发或低频事件。
- 真机验收项并入 DEVTASKS 终局验收表。
