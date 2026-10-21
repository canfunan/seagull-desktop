package com.seagull.carlauncher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 按键映射（批次 KM，复刻野菜桌面普通版 KeyMapService）：
 * AccessibilityService 拦截硬件按键 → 按映射表改派动作。
 *
 * 为什么用 AccessibilityService：它是唯一能在**不 root 改框架**的前提下
 * 拿到原始硬件键事件并「吃掉」的官方通道（canRequestFilterKeyEvents）。
 * 野菜同款：serviceInfo.flags |= FLAG_REQUEST_FILTER_KEY_EVENTS(32)。
 *
 * 默认映射（车机桌面最常用，可在 RootPanel 改 persist.seagull.keymap 覆盖）：
 *   音量+ (24) → 下一曲      音量- (25) → 上一曲      耳机键 (79) → 播放/暂停
 * 有音乐 app 挂载画布时打给挂载的那个（挂载哪个控制哪个，同底栏播放器语义）。
 *
 * 授权（root 级，用户要求）：ensureAccess() 用 su settings put 直写
 * enabled_accessibility_services + accessibility_enabled，不弹设置 UI 不重启。
 */
public class KeyMapService extends AccessibilityService {

    private static final String TAG = "SeagullKeyMap";
    /** 映射表 prop：格式 "24:next:25:prev:79:playpause"（键码:动作 成对），空=用默认表。 */
    public static final String PROP_MAP = "persist.seagull.keymap";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
                setServiceInfo(info);
            }
        } catch (Throwable t) {
            Log.w(TAG, "setServiceInfo 失败: " + t);
        }
        Log.i(TAG, "按键映射服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不消费窗口事件，只做按键
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        try {
            if (event == null || event.getAction() != KeyEvent.ACTION_UP) {
                return super.onKeyEvent(event);
            }
            if (event.getRepeatCount() > 0) return true;   // 长按重复吃掉
            int code = event.getKeyCode();
            String action = mappingFor(code);
            if (action == null) return super.onKeyEvent(event);
            dispatch(action);
            return true;   // 吃掉：不再执行原键行为（音量调节等）
        } catch (Throwable t) {
            Log.w(TAG, "处理按键出错: " + t);
            return false;
        }
    }

    /** 键码 → 动作名（prop 优先，空则默认表）。 */
    private String mappingFor(int code) {
        String map = null;
        try {
            map = SysOps.getProp(PROP_MAP);
        } catch (Throwable ignored) {
        }
        if (map != null && !map.trim().isEmpty()) {
            String[] kv = map.trim().split(":");
            for (int i = 0; i + 1 < kv.length; i += 2) {
                try {
                    if (Integer.parseInt(kv[i].trim()) == code) return kv[i + 1].trim();
                } catch (NumberFormatException ignored) {
                }
            }
            return null;
        }
        // 默认表：音量上下曲 + 耳机键播放暂停
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP:   return "next";
            case KeyEvent.KEYCODE_VOLUME_DOWN: return "prev";
            case KeyEvent.KEYCODE_HEADSETHOOK: return "playpause";
            default: return null;
        }
    }

    /** 动作分发：媒体控制打给挂载画布的那个播放器；导航类打桌面。 */
    private void dispatch(String action) {
        String pkg = null;
        try {
            for (int slot : new int[]{1, 2}) {
                String p = PipBoard.loadPkg(this, slot);
                if (p != null && MediaListenerService.isMusicPkg(p)) { pkg = p; break; }
            }
            if (pkg == null) {
                MediaListenerService.Snap s = MediaListenerService.snap();
                pkg = s == null ? null : s.pkg;
            }
        } catch (Throwable ignored) {
        }
        switch (action) {
            case "next":
            case "prev":
            case "playpause":
                MediaListenerService.control(pkg, action);
                break;
            case "home":
                startActivity(new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            default:
                break;
        }
        Log.i(TAG, "按键映射执行: " + action + (pkg != null ? " → " + pkg : ""));
    }

    /* ---------------- root 级自授权（不弹 UI、不重启） ---------------- */

    /** 无障碍服务是否已授权。 */
    public static boolean isEnabled(Context ctx) {
        try {
            String s = Settings.Secure.getString(ctx.getContentResolver(),
                    "enabled_accessibility_services");
            if (s == null || s.isEmpty()) return false;
            ComponentName cn = new ComponentName(ctx, KeyMapService.class);
            for (String part : s.split(":")) {
                ComponentName c = ComponentName.unflattenFromString(part.trim());
                if (c != null && c.equals(cn)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * root 直写无障碍授权：enabled_accessibility_services 追加本服务 +
     * accessibility_enabled=1。用户要求走 root 级授权 —— su settings put 一次到位。
     */
    public static void ensureAccess(Context ctx) {
        if (ctx == null || isEnabled(ctx)) return;
        ComponentName cn = new ComponentName(ctx, KeyMapService.class);
        String flat = cn.flattenToString();
        try {
            String cur = SysOps.getProp("settings_secure_enabled_accessibility_services");
            if (cur == null || cur.trim().isEmpty()) {
                cur = Caps.exec("settings get secure enabled_accessibility_services");
            }
            String nv;
            if (cur == null || cur.trim().isEmpty() || "null".equals(cur.trim())) {
                nv = flat;
            } else {
                String c = cur.trim();
                nv = c.contains(flat) ? c : c + ":" + flat;
            }
            Caps.exec("settings put secure enabled_accessibility_services '" + nv + "'");
            Caps.exec("settings put secure accessibility_enabled 1");
            Log.i(TAG, "按键映射 root 授权完成: " + nv);
        } catch (Throwable t) {
            Log.w(TAG, "root 授权失败: " + t);
        }
    }
}
