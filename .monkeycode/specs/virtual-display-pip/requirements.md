# 需求文档：海鸥虚拟屏画中画（Virtual Display PiP）

## 引言

海鸥桌面在不依赖 LSPosed/Xposed 注入的前提下，通过 App 自建虚拟屏（VirtualDisplay）承载高德，以悬浮小窗形式显示在车机屏幕上。窗口位置、尺寸、拉伸比例由海鸥完全控制；触摸与按键通过 root input 命令转发进虚拟屏。该方案与现有 LSPosed hook 功能并存、互不依赖。

## 术语表

- **虚拟屏（VD）**：海鸥通过 DisplayManager 创建的 VirtualDisplay，高德在其中渲染
- **PiP 悬浮窗**：承载 VD 画面的 SYSTEM_ALERT_WINDOW 悬浮窗，内含 SurfaceView
- **车机高德**：com.autonavi.amapauto
- **公版高德**：com.autonavi.minimap
- **预设**：PiP 悬浮窗的尺寸方案（小窗/全屏拉伸/自定义比例）

## 需求

### R1 虚拟屏创建

**User Story:** AS 车机用户，我 want 高德以可调尺寸的悬浮窗运行，so that 主屏其余区域留给桌面和其他应用。

#### Acceptance Criteria

1. WHEN 用户在海鸥桌面点击「高德画中画」，海鸥 SHALL 创建一块虚拟屏，分辨率取副屏物理分辨率，取不到时回退 1280x720。
2. WHEN 虚拟屏创建成功，海鸥 SHALL 记录其 displayId 供后续启动与输入命令使用。
3. IF 虚拟屏创建失败，海鸥 SHALL 在悬浮日志区显示失败原因。
4. WHILE 一块虚拟屏已存活，海鸥 SHALL 复用该虚拟屏，禁止重复创建。

### R2 高德拉入虚拟屏

**User Story:** AS 车机用户，我 want 一键把高德装进画中画，so that 无需手动操作多任务。

#### Acceptance Criteria

1. WHEN 虚拟屏就绪，海鸥 SHALL 通过 root 执行 `am start --display <vdId> -n <高德launchable Activity>` 把高德拉入虚拟屏。
2. WHEN 高德进程已在运行且位于其他屏，海鸥 SHALL 先 `am force-stop` 再拉入。
3. IF 8 秒内虚拟屏无窗口内容，海鸥 SHALL 提示「高德未落入虚拟屏」并提供重试按钮。
4. IF root 通道不可用，海鸥 SHALL 直接提示失败，禁止进入半功能状态。

### R3 PiP 悬浮窗呈现

**User Story:** AS 车机用户，我 want 悬浮窗尺寸位置可调可拉伸，so that 高德画面适配不同布局。

#### Acceptance Criteria

1. WHEN 虚拟屏出现画面，海鸥 SHALL 以悬浮窗承载 SurfaceView 显示画面。
2. 悬浮窗 SHALL 支持拖动改变位置。
3. 悬浮窗 SHALL 提供三种预设：60% 短边小窗（车联规格）、全屏拉伸铺满、自定义宽高比（默认 16:9）。
4. WHEN 用户切换预设，海鸥 SHALL 立即调整 SurfaceView 布局；虚拟屏分辨率 SHALL 保持不变，拉伸由视图层完成。
5. 悬浮窗 SHALL 提供「返回键」按钮与「关闭」按钮。

### R4 输入转发

**User Story:** AS 车机用户，我 want 在悬浮窗里直接操作高德，so that 导航交互不被打断。

#### Acceptance Criteria

1. WHEN 用户在悬浮窗上触摸，海鸥 SHALL 把触摸坐标映射到虚拟屏坐标系，并通过 root 执行 `input -d <vdId> tap` 或 `input -d <vdId> swipe`。
2. WHEN 用户点击「返回键」按钮，海鸥 SHALL 向虚拟屏注入 KEYCODE_BACK。
3. 坐标映射误差 SHALL 不超过 1 个虚拟屏像素。
4. IF root input 命令连续 3 次失败，海鸥 SHALL 提示 root 通道异常并停止转发。

### R5 生命周期

**User Story:** AS 车机用户，我 want 画中画用完即走不留垃圾，so that 系统资源干净。

#### Acceptance Criteria

1. WHILE 悬浮窗存活，海鸥 SHALL 以前台服务常驻通知栏，防止进程被杀。
2. WHEN 用户点「关闭」，海鸥 SHALL 释放虚拟屏并移除悬浮窗。
3. WHEN 用户在设置中开启「关闭时杀高德」，海鸥 SHALL 在释放虚拟屏后 force-stop 高德。

### R6 兼容与降级

**User Story:** AS 车机用户，我 want 画中画方案与现有功能互不干扰，so that 已有功能稳定。

#### Acceptance Criteria

1. IF 检测不到车机高德的本机进程（pidof 为空），海鸥 SHALL 提示「高德疑似远端投屏，虚拟屏方案不可用」。
2. 系统 SHALL 保留现有 LSPosed hook 功能，虚拟屏方案与其并存。
3. 虚拟屏方案 SHALL 对高德版本升级零依赖（不 hook 高德任何内部接口）。

## 明确限制（写入用户预期）

- 虚拟屏无法接收系统输入法：高德内搜索打字不可用，语音、收藏点、历史记录可用
- 滑动手势通过离散 swipe 段模拟，连续性弱于真机触摸
