package com.seagull.carlauncher;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * root 侧落盘日志（批次 AK）。
 *
 * 为什么存在：用户长期反馈「不方便抓 logcat」，而守护进程的故障（建屏失败、
 * 注入被拒、拦截决策）恰恰只发生在 root 进程里。这里把关键事件写进
 * /data/local/tmp/seagull-root.log —— adb shell / 文件管理器（root）都能直接看，
 * 设置页的自检报告也能引用。
 *
 * 容量红线：单文件上限 {@link #MAX_BYTES}，超限重写只保留尾部一半 ——
 * 车机长期开机不清理，绝不能让这个文件无限长。
 * 只在 root 进程使用（app 进程有 SeagullDiag/SeagullCar 自己的标签）。
 */
public final class RootLog {

    public static final String PATH = "/data/local/tmp/seagull-root.log";
    private static final long MAX_BYTES = 256 * 1024;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private RootLog() {}

    public static synchronized void i(String tag, String msg) {
        write(tag, msg, null);
    }

    public static synchronized void w(String tag, String msg, Throwable t) {
        write(tag, msg, t);
    }

    private static void write(String tag, String msg, Throwable t) {
        // logcat 同步一份：现场能连 adb 时照常用标准工具看
        if (t != null) android.util.Log.w(tag, msg, t);
        else android.util.Log.i(tag, msg);
        File f = new File(PATH);
        try {
            if (f.length() > MAX_BYTES) rotate(f);
            try (BufferedWriter out = new BufferedWriter(new FileWriter(f, true))) {
                out.append(FMT.format(new Date())).append(' ')
                        .append(tag).append(": ").append(msg);
                if (t != null) out.append(" | ").append(t.toString());
                out.append('\n');
            }
        } catch (Throwable ignore) {
            // 落盘失败（SELinux/无写权）不能影响守护进程本体
        }
    }

    /** 超限：整读进来只保留尾部一半重写。文件上限 256KB，整读开销一次性可接受。 */
    private static void rotate(File f) {
        try {
            File tmp = new File(PATH + ".tmp");
            char[] buf = new char[8192];
            StringBuilder tail = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new FileReader(f))) {
                int n;
                long total = 0;
                StringBuilder all = new StringBuilder((int) MAX_BYTES + 1024);
                while ((n = in.read(buf)) > 0) {
                    all.append(buf, 0, n);
                    total += n;
                }
                if (total > MAX_BYTES / 2) {
                    tail.append(all, (int) (total - MAX_BYTES / 2), all.length());
                } else {
                    tail.append(all);
                }
            }
            try (BufferedWriter out = new BufferedWriter(new FileWriter(tmp))) {
                out.append("---- 日志轮转，保留尾部 ----\n");
                out.write(tail.toString());
            }
            if (!f.delete() || !tmp.renameTo(f)) {
                // rename 失败：删 tmp 保平安，下次再轮转
                tmp.delete();
            }
        } catch (Throwable t) {
            try { f.delete(); } catch (Throwable ignore) {}
        }
    }
}
