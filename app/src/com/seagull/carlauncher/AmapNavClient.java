package com.seagull.carlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 高德导航信息客户端（批次 AN，K4）。
 *
 * 数据来源是高德官方开放的车联服务（com.autonavi.minimap.service.NAVIGATION_SERVICE，
 * 高德地图开放平台"车机/车联 Link SDK"定义的接口）：bind 上去、注册一个回调 Binder，
 * 导航状态以 JSON 推过来。只读数据，不碰高德进程 —— 画中画/桌面完全不受影响。
 *
 * 协议要点（互操作所必需的接口事实）：
 *   · 控制端 ILinkSdkControl：1=authenticate（返回 JSON，status=success 视为通过），
 *     3=registerListener、4=unregisterListener（传回调 Binder，返回 boolean）；
 *   · 回调端 ILinkSdkCallback：1=onEvent(payload)，2=onEvent(payload, fd)（大包走 fd）；
 *   · payload 可选加密：{encrypted:true, content:base64}，content 解出
 *     [aadLen|aad|keyLen|key|12B nonce|密文]，AES/GCM/NoPadding tag 128；
 *   · 明文 JSON：datas[].type==4 是导航状态，data 里的字段见 {@link NavState}。
 *
 * 数据保鲜（清单 R3）：8s 无更新判过期清空；断连/绑死/空绑定三路全清。
 */
public final class AmapNavClient {

    private static final String TAG = "SeagullNav";
    /** 导航状态在 datas[] 里的 type 值（高德 LinkSDK 协议）。 */
    private static final int TYPE_NAV_STATE = 4;
    /** 8s 无更新 = 导航已结束或播报停了，状态作废。 */
    private static final long STALE_MS = 8_000L;

    /** 一次导航快照（清单 R2 只取这 8 个字段）。state==null 表示"当前没有导航"。 */
    public static final class NavState {
        public final int eventType;            // 播报事件类型（高德枚举，原样透传）
        public final int maneuverId;           // 转向动作 id
        public final int segmentRemainM;       // 本路段剩余距离（米）
        public final int speedKph;             // 当前速度
        public final int routeRemainS;         // 全程剩余时间（秒）
        public final int routeRemainM;         // 全程剩余距离（米）
        public final String roadName;          // 当前路名
        public final String nextRoadName;      // 下一条路名

        NavState(int eventType, int maneuverId, int segmentRemainM, int speedKph,
                 int routeRemainS, int routeRemainM, String roadName, String nextRoadName) {
            this.eventType = eventType;
            this.maneuverId = maneuverId;
            this.segmentRemainM = segmentRemainM;
            this.speedKph = speedKph;
            this.routeRemainS = routeRemainS;
            this.routeRemainM = routeRemainM;
            this.roadName = roadName;
            this.nextRoadName = nextRoadName;
        }
    }

    public interface Listener { void onNavState(NavState state); }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CallbackBinder cb = new CallbackBinder();
    /** 8s 保鲜定时器：到点说明高德不再推状态（导航结束），清空。 */
    private final Runnable stale = () -> post(null);
    private Control control;
    private boolean bound;
    private boolean closed;

    /** 单例：桌面全进程一份（HomeActivity 起，设置页读状态）。 */
    private static volatile AmapNavClient sInstance;

    public static AmapNavClient get(Context c) {
        if (sInstance == null) {
            synchronized (AmapNavClient.class) {
                if (sInstance == null) sInstance = new AmapNavClient(c);
            }
        }
        return sInstance;
    }

    private AmapNavClient(Context c) {
        this.ctx = c.getApplicationContext();
    }

    /* ---------------- 订阅者（NavCard 等多个 UI 可同时听） ---------------- */

    private final java.util.concurrent.CopyOnWriteArrayList<Listener> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile NavState last;

    public void addListener(Listener l) { if (l != null) listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }
    /** 最近一次状态（可能为 null）：卡片恢复显隐用。 */
    public NavState last() { return last; }
    /** 服务是否绑上了（设置页状态行用）。 */
    public boolean connected() { return control != null; }

    private void deliver(NavState s) {
        last = s;
        for (Listener l : listeners) {
            try { l.onNavState(s); } catch (Throwable t) { Log.w(TAG, "listener 异常", t); }
        }
    }

    /* ---------------- 绑定生命周期 ---------------- */

    /** 绑定（幂等）。包名由调用方给：手机版 com.autonavi.minimap / 车机版实测后可换。 */
    public synchronized void start(String pkg) {
        if (closed || bound || pkg == null || pkg.isEmpty()) return;
        Intent i = new Intent("com.autonavi.minimap.service.NAVIGATION_SERVICE");
        i.setPackage(pkg);
        try {
            bound = ctx.bindService(i, conn, Context.BIND_AUTO_CREATE);
            if (!bound) Log.i(TAG, pkg + " 未提供导航服务（未装或版本不开放）");
        } catch (Throwable t) {
            bound = false;
            Log.w(TAG, "bind 失败: " + t.getClass().getSimpleName());
        }
    }

    public synchronized void stop() {
        closed = false;
        main.removeCallbacks(stale);
        if (control != null) {
            try { control.unregister(cb); } catch (Throwable ignore) {}
            control = null;
        }
        if (bound) {
            try { ctx.unbindService(conn); } catch (Throwable ignore) {}
            bound = false;
        }
        deliver(null);
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            if (closed) return;
            control = new Control(service);
            try {
                String auth = control.authenticate();
                boolean ok = control.register(cb);
                Log.i(TAG, "导航服务已连接 registered=" + ok + " authOk=" + authOk(auth));
                if (!ok) post(null);
            } catch (Throwable t) {
                Log.w(TAG, "注册回调失败: " + t.getClass().getSimpleName());
                post(null);
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            control = null;
            post(null);   // 强杀高德：清干净，不残留旧状态（验收 R3）
        }

        @Override public void onBindingDied(ComponentName name) {
            control = null;
            post(null);
        }

        @Override public void onNullBinding(ComponentName name) {
            control = null;
            post(null);
        }
    };

    private static boolean authOk(String auth) {
        if (auth == null || auth.isEmpty()) return false;
        try { return "success".equals(new JSONObject(auth).optString("status")); }
        catch (Throwable t) { return false; }
    }

    /* ---------------- 载荷：解包 → 找 type=4 → 交订阅者 ---------------- */

    private void onPayload(String payload) {
        if (closed || payload == null || payload.isEmpty()) return;
        try {
            JSONObject root = new PayloadCodec(payload).decode();
            JSONArray datas = root.optJSONArray("datas");
            if (datas == null) return;   // 非 datas 包（心跳/鉴权回执）不处理
            for (int i = 0; i < datas.length(); i++) {
                JSONObject entry = datas.optJSONObject(i);
                if (entry == null || entry.optInt("type", -1) != TYPE_NAV_STATE) continue;
                JSONObject d = entry.optJSONObject("data");
                if (d == null) continue;
                post(new NavState(
                        d.optInt("eventType", 0),
                        d.optInt("curManeuverID", 0),
                        Math.max(0, d.optInt("segmentRemainDist", 0)),
                        Math.max(0, (int) Math.round(d.optDouble("speed", 0d))),
                        Math.max(0, d.optInt("routeRemainTime", 0)),
                        Math.max(0, d.optInt("routeRemainDist", 0)),
                        clean(d.optString("curRouteName", "")),
                        clean(d.optString("nextRouteName", ""))));
            }
        } catch (Throwable t) {
            Log.w(TAG, "载荷解析失败: " + t.getClass().getSimpleName());
        }
    }

    /** 送到主线程 + 重挂保鲜定时器。null 状态（过期/断连）不挂定时器。 */
    private void post(NavState s) {
        main.removeCallbacks(stale);
        main.post(() -> {
            if (closed) return;
            deliver(s);
            if (s != null) main.postDelayed(stale, STALE_MS);
        });
    }

    private static String clean(String v) {
        String s = v == null ? "" : v.trim();
        return "null".equalsIgnoreCase(s) ? "" : s;
    }

    /* ---------------- 回调 Binder（高德把事件 transact 进来） ---------------- */

    private final class CallbackBinder extends Binder implements IInterface {
        private static final String DESCRIPTOR = "com.amap.linksdk.aidldefine.ILinkSdkCallback";

        CallbackBinder() { attachInterface(this, DESCRIPTOR); }

        @Override public IBinder asBinder() { return this; }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(DESCRIPTOR);
                return true;
            }
            // 1=纯字符串载荷；2=字符串 + 大包 fd（读干 fd 里的 JSON 再解析一次）
            if (code != 1 && code != 2) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(DESCRIPTOR);
            String payload = data.readString();
            ParcelFileDescriptor fd = null;
            if (code == 2 && data.readInt() != 0) {
                fd = ParcelFileDescriptor.CREATOR.createFromParcel(data);
            }
            onPayload(payload);
            if (fd != null) {
                try (ParcelFileDescriptor.AutoCloseInputStream in =
                             new ParcelFileDescriptor.AutoCloseInputStream(fd);
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    onPayload(out.toString(StandardCharsets.UTF_8.name()));
                } catch (Throwable t) {
                    Log.w(TAG, "fd 载荷读取失败: " + t.getClass().getSimpleName());
                }
            }
            if (reply != null) reply.writeNoException();
            return true;
        }
    }

    /* ---------------- 控制端（向高德 transact） ---------------- */

    private static final class Control {
        private static final String DESCRIPTOR = "com.amap.linksdk.aidldefine.ILinkSdkControl";
        private final IBinder b;

        Control(IBinder b) { this.b = b; }

        String authenticate() throws RemoteException { return call(1, null); }

        boolean register(IInterface cb) throws RemoteException { return call(3, cb) != null; }

        boolean unregister(IInterface cb) throws RemoteException { return call(4, cb) != null; }

        /** 1/4 返回 String，3 返回 int —— 统一跑一趟，按事务号取对应返回值。 */
        private String call(int code, IInterface cb) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                if (cb != null) data.writeStrongBinder(cb.asBinder());
                b.transact(code, data, reply, 0);
                reply.readException();
                return code == 3 ? (reply.readInt() != 0 ? "1" : null) : reply.readString();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
    }

    /* ---------------- 载荷编解码（明文 / AES-GCM 信封两种） ---------------- */

    private static final class PayloadCodec {
        private final String raw;

        PayloadCodec(String raw) { this.raw = raw; }

        JSONObject decode() throws Exception {
            JSONObject env = new JSONObject(raw);
            if (!env.optBoolean("encrypted", false)) return env;
            byte[] blob = Base64.decode(env.getString("content"), Base64.DEFAULT);
            ByteBuffer buf = ByteBuffer.wrap(blob);
            int aadLen = buf.getInt();
            if (aadLen < 0 || aadLen > 1024 || buf.remaining() < aadLen + 4) {
                throw new IllegalArgumentException("aad 长度非法");
            }
            byte[] aad = new byte[aadLen];
            buf.get(aad);
            int keyLen = buf.getInt();
            if ((keyLen != 16 && keyLen != 24 && keyLen != 32)
                    || buf.remaining() < keyLen + 28) {
                throw new IllegalArgumentException("key 长度非法");
            }
            byte[] key = new byte[keyLen];
            buf.get(key);
            byte[] nonce = new byte[12];
            buf.get(nonce);
            byte[] cipherText = new byte[buf.remaining()];
            buf.get(cipherText);

            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, nonce));
            c.updateAAD(aad);
            return new JSONObject(new String(c.doFinal(cipherText), StandardCharsets.UTF_8));
        }
    }
}
