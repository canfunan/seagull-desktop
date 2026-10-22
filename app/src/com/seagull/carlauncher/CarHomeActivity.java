package com.seagull.carlauncher;

/**
 * 车机投屏专用入口（vivo互联投屏·修掉线）：HomeActivity 的克隆。
 *
 * 背景：海鸥桌面 root 直推上 vivo 车机屏后，vivo 车机框架会主动 CLOSE 外来
 * 任务、恢复自己的 CarLauncherActivity（实测日志坐实：Task{海鸥} m=CLOSE +
 * Task{vivo.carlauncher:main} m=TO_FRONT）—— 车机屏的 home 角色被框架锁定
 * 给 com.vivo.carlauncher，海鸥自己的任务必被踢。
 *
 * 解法：本类 taskAffinity="com.vivo.carlauncher"（清单里声明）——vivo 的
 * CarLauncherActivity（被 LSPosed hook 后）onCreate 里拉起本 Activity 时，
 * affinity 匹配 → **直接进 vivo 自己的车机任务**。框架眼里任务 base 仍是
 * vivo 的启动器（home 角色不动、不踢），内容已经是海鸥桌面 —— 不再掉线。
 *
 * 声明为普通 Activity（不带 CATEGORY_HOME intent-filter）：不影响手机桌面
 * 的 home 解析，只在 vivo 车机框架的拉起路径上生效。
 */
public class CarHomeActivity extends HomeActivity {
}
