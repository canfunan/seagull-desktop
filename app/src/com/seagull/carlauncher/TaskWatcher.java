package com.seagull.carlauncher;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 任务栈监听（批次 AK，OneStep4 思路自写实现）。
 *
 * 原理：root 进程反射注册系统的 ITaskStackListener
 * （ActivityTaskManager.registerTaskStackListener）—— 任务栈的任何变化
 * （moved-to-front / created / removed / info changed …）都会实时回调到这里，
 * 不用再轮询 `am stack list` / dumpsys。
 *
 * 设计取舍（与 OneStep 不同，刻意简化）：
 *   我们**不解析** RunningTaskInfo 的 parcel（各版本事务号与字段在 ROM 间漂移，
 *   解析错一个字段就可能把"对槽的实例"当成目标任务，踩了 m12 的"误动别人任务"红线）。
 *   事件只作为**触发器**：任何任务栈变化 → 推 EVT BUMP 给桌面 → 桌面跑既有的
 *   ensureOnDisplay 自愈（那里面有全部护栏：只搬不拉、对槽实例绝不动）。
 *   效果等价：任务被拉走后毫秒级收到事件、立即触发搬回，比 2.5s 轮询快得多。
 *
 * 节流：onTaskStackChanged 在动画期间会连发，300ms 内的重复事件只推一次 ——
 *   自愈本身有幂等护栏，推多了只是白跑。
 */
public final class TaskWatcher {

    private static final String TAG = "SeagullWatch";
    private static final String DESCRIPTOR = "android.app.ITaskStackListener";
    private static final long MIN_INTERVAL_MS = 300;

    private static volatile Binder listener;
    private static volatile boolean registered;
    private static volatile long lastPush;

    private TaskWatcher() {}

    public static synchronized boolean start() {
        if (registered) return true;
        try {
            IBinder b = RootMain.service("activity_task");
            Class<?> iatm = Class.forName("android.app.IActivityTaskManager");
            Object svc = iatm.getMethod("asInterface", IBinder.class).invoke(null, b);
            if (listener == null) listener = new WatchBinder();
            Method m = null;
            for (Method cand : iatm.getMethods()) {
                if ("registerTaskStackListener".equals(cand.getName())
                        && cand.getParameterTypes().length == 1
                        && cand.getParameterTypes()[0] == IBinder.class) {
                    m = cand;
                    break;
                }
            }
            if (m == null) {
                RootLog.w(TAG, "registerTaskStackListener 未找到（ROM 裁剪？）", null);
                return false;
            }
            m.invoke(svc, listener);
            registered = true;
            RootLog.i(TAG, "任务栈监听已注册");
            RootMain.pushEvent(PrivCodec.EVT + " " + PrivCodec.EVT_WATCH + " on");
            return true;
        } catch (Throwable t) {
            RootLog.w(TAG, "任务栈监听注册失败", t);
            return false;
        }
    }

    public static synchronized boolean stop() {
        if (!registered) return true;
        try {
            IBinder b = RootMain.service("activity_task");
            Class<?> iatm = Class.forName("android.app.IActivityTaskManager");
            Object svc = iatm.getMethod("asInterface", IBinder.class).invoke(null, b);
            for (Method cand : iatm.getMethods()) {
                if ("unregisterTaskStackListener".equals(cand.getName())
                        && cand.getParameterTypes().length == 1
                        && cand.getParameterTypes()[0] == IBinder.class) {
                    cand.invoke(svc, listener);
                    break;
                }
            }
        } catch (Throwable t) {
            RootLog.w(TAG, "注销监听失败（留在系统里无害：无订阅者事件丢弃）", t);
        }
        registered = false;
        return true;
    }

    public static boolean isRegistered() { return registered; }

    private static final class WatchBinder extends Binder {
        WatchBinder() { attachInterface(null, DESCRIPTOR); }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(DESCRIPTOR);
                return true;
            }
            // 所有事件一视同仁：不解析载荷（取舍见类注释），节流后推 BUMP
            long now = System.currentTimeMillis();
            if (now - lastPush >= MIN_INTERVAL_MS) {
                lastPush = now;
                RootMain.pushEvent(PrivCodec.EVT + " " + PrivCodec.EVT_BUMP);
            }
            // oneway 方法系统不读回复；写 NoException 防御非 oneway 版本
            if (reply != null) reply.writeNoException();
            return true;
        }
    }
}
