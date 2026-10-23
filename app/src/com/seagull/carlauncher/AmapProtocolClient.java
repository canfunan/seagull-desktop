package com.seagull.carlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 批次 NC：高德车机版协议客户端（JsonProtocolService，反编译 amapauto 坐实）。
 *
 * 数据通道（用户「肯定有别的」方向挖通的官方 exported 通道）：
 *   com.autonavi.amapauto/.protocol.service.JsonProtocolService
 *     action = com.autonavi.amapauto.aidl.json_protocol_service
 *     exported=true、onBind 零校验（不查 uid/包名/权限）
 *   IJsonProtocolInterface（DESCRIPTOR com.autonavi.amapauto.aidl.IJsonProtocolInterface）：
 *     code 1 = h1(String,String)   code 2 = X(String, IBinder) 注册
 *     code 3 = z0(String, IBinder) 注销
 *   IJsonProtocolReceiveInterface（...IJsonProtocolReceiveInterface）：
 *     code 1 = O(String) 收 JSON 推送   code 2 = V0(String) 请求响应
 * 注册后高德把车机屏同源协议推过来，GuideInfoModel（protocolId 30407）
 * 60 字段：segRemainDis/nextRoadName/routeRemainDis/routeRemainTime/
 * curSpeed/limitedSpeed/cameraDist/routeRemainTrafficLightNum/etaText...
 *
 * 契约照扒 defpackage.lw（高德官方 SdkClient 参考实现）：bind →
 * X(type, cbStub) → O(json) 收。本类手工复刻两个 AIDL 的 Binder
 * （DESCRIPTOR 必须逐字一致，不 import 高德类）。
 */
public final class AmapProtocolClient {

    private static final String TAG = "SeagullAmapProto";

    private static final String SVC_PKG = "com.autonavi.amapauto";
    private static final String SVC_CLS =
            "com.autonavi.amapauto.protocol.service.JsonProtocolService";
    private static final String SVC_ACTION =
            "com.autonavi.amapauto.aidl.json_protocol_service";
    private static final String IFACE = "com.autonavi.amapauto.aidl.IJsonProtocolInterface";
    private static final String RECV_IFACE =
            "com.autonavi.amapauto.aidl.IJsonProtocolReceiveInterface";
    /** 注册 type 串——**必须跟高德官方 SdkClient 一致**：服务端 g2.e(nc) 按
     *  requestAuthor 定向推送（this.d.get(str)），自定义 type 有被过滤不收的
     *  风险（复盘 g2.java:AidlJsonConnection 坐实）。服务端不校验 type 内容，
     *  但推送路由按它。 */
    private static final String CLIENT_TYPE = "com.amapauto.autonavi.jsonsimulate.client";

    /** 解析出来的一条导航引导信息（对应 GuideInfoModel 核心字段）。 */
    public static final class Guide {
        public int icon;                      // icon（int——高德图标 id 制，
                                              // 2/3/4=右转组 6/7/8=左转组 5=掉头）
        public int newIcon;                   // NB15：新版图标 id（官方取值 newIcon 优先，0 回落 icon）
        public int segRemainDis;              // NB17：本段剩余距离（米）——官方 Parser 第 30 行
                                              // 真实 key「segRemainDis」！左屏「186米」= 它。
                                              // NB14-16 一直误读 nextSegRemainDis（下下段，
                                              // 常态为 0）——「即将」和距离空白的总根因
        public int nextSegRemainTime;         // 下一段剩余时间（秒）
        public String segRemainDisAuto = "";  // NB17：本段剩余官方现成文案（"186米"）——
                                              // 真实 key「segRemainDisAuto」（Parser 第 57 行），
                                              // 与左屏同源
        public String nextRoadName = "";      // 下一道路名（与 segRemainDis 配对构成
                                              // 「186米后右转进入沂河路」，已实测有值）
        public String nextNextRoadName = "";  // 再下一道路名
        public int routeRemainDis;            // 全程剩余距离（米）
        public int routeRemainTime;           // 全程剩余时间（秒）
        public String routeRemainDistanceAuto = ""; // NB15：全程剩余距离官方文案（"18.1公里"）
        public String routeRemainTimeAuto = "";     // NB15：全程剩余时间官方文案（"20分钟"）
        public int curSpeed;                  // 当前速度（km/h）
        public int limitedSpeed;              // 限速（km/h）
        public int cameraDist;                // 电子眼距离（米，0=无）
        public int trafficLights;             // 全程红绿灯数
        public String etaText = "";           // ETA 文本
        public String curRoadName = "";       // 当前路名
        public int roundaboutOutAngle;        // 环岛出口角
        public String sapaName = "";          // 服务区名
        public int sapaDist;                  // 服务区距离
    }

    public interface Listener {
        void onGuide(Guide g);
        /** 导航结束（协议里 routeRemainDis<=0 且无 nextRoadName 的空帧）。 */
        void onGuideEnd();
    }

    /* -------------------- 单例 + sink -------------------- */

    private static volatile AmapProtocolClient inst;
    private final CopyOnWriteArrayList<Listener> sinks = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Context appCtx;
    private IBinder svc;                       // 服务端 binder
    private boolean bound, registering;

    public static AmapProtocolClient get() {
        if (inst == null) {
            synchronized (AmapProtocolClient.class) {
                if (inst == null) inst = new AmapProtocolClient();
            }
        }
        return inst;
    }

    public static void addSink(Listener l) {
        if (l != null) get().sinks.addIfAbsent(l);
    }

    public static void removeSink(Listener l) {
        if (l != null && inst != null) inst.sinks.remove(l);
    }

    /* -------------------- 服务端接口代理（X/h1/z0） -------------------- */

    /** IJsonProtocolInterface 客户端代理：手工 transact（code 2 = X 注册）。 */
    private boolean register(IBinder binder, IBinder cb) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IFACE);
            data.writeString(CLIENT_TYPE);
            data.writeStrongBinder(cb);
            // flags=0（同步）：高德 Stub 的 X 在 onTransact 里 reply.writeNoException()，
            // ONE_WAY(1) 时 reply=null → 服务端 NPE → crashed service（实测翻车点）
            binder.transact(2, data, reply, 0);
            reply.readException();
            handshake(binder);   // ★ 注册后必发版本握手（pk0.e → h1 protocolId=99999），
                                 // 漏了它高德 3 秒后销毁 binder（实测稳定复现）
            return true;
        } catch (RemoteException e) {
            Log.w(TAG, "注册失败: " + e);
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "注册异常", t);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * 版本握手（照扒 defpackage.pk0.e → lw.n → svc.h1）：
     * protocolId=99999 的 requestVersion JSON。高德 SdkClient 注册后第一件事，
     * 不发的客户端会被服务端在数秒后销毁 binder（实测：注册成功 3 秒 binderDied）。
     */
    private void handshake(IBinder binder) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            String json = "{\"requestCode\":\"0\",\"responseCode\":\"0\","
                    + "\"needResponse\":true,\"protocolId\":99999,\"versionName\":\"0\","
                    + "\"requestAuthor\":\"" + CLIENT_TYPE + "\","
                    + "\"message\":\"\",\"messageType\":\"request\",\"statusCode\":0,"
                    + "\"data\":{\"versionName\":\"3.2.0.20190521\"}}";
            data.writeInterfaceToken(IFACE);
            data.writeString(CLIENT_TYPE);
            data.writeString(json);
            binder.transact(1, data, reply, 0);   // code 1 = h1（同步，reply 有 NoException）
            reply.readException();
            Log.i(TAG, "版本握手已发送（protocolId=99999）");
        } catch (Throwable t) {
            Log.w(TAG, "握手失败", t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** IJsonProtocolReceiveInterface.Stub 复刻（DESCRIPTOR 逐字一致）。 */
    // NB10：Binder 必须带 DESCRIPTOR 构造——无参构造的 getInterfaceDescriptor()
    // 返回 null，高德侧 RemoteCallbackList.register/asInterface 流程读它直接 NPE
    // （crashed service 第二真凶，flags 修了还崩就是它）。
    private final android.os.Binder recvStub = new android.os.Binder(RECV_IFACE) {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(RECV_IFACE);
                return true;
            }
            if (code == 1) {                       // O(String) 收 JSON 推送
                data.enforceInterface(RECV_IFACE);
                String json = data.readString();
                reply.writeNoException();
                if (json != null) onProtocol(json);
                return true;
            }
            if (code == 2) {                       // V0(String) 请求响应
                data.enforceInterface(RECV_IFACE);
                data.readString();
                reply.writeNoException();
                reply.writeString(null);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    /* -------------------- bind / 重连 -------------------- */

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.i(TAG, "JsonProtocolService 已连接");
            svc = binder;
            try {
                binder.linkToDeath(death, 0);
            } catch (RemoteException ignore) {
            }
            registering = register(binder, recvStub);
            Log.i(TAG, registering ? "已注册协议接收" : "注册失败");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "JsonProtocolService 断开");
            svc = null;
            registering = false;
            bound = false;   // ★NB13：不置 false 的话 rebind→bind() 被 if(bound)return
                             // 挡住，22:51 断开后实测永久卡死（用户「信息不上卡片」真凶）
            scheduleReconnect();
        }
    };

    private final IBinder.DeathRecipient death = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.w(TAG, "协议 binder died");
            svc = null;
            registering = false;
            bound = false;   // ★NB13 同上（binderDied 走的是 disconnect 之外的路径）
            scheduleReconnect();
        }
    };

    private void scheduleReconnect() {
        main.removeCallbacks(rebind);
        main.postDelayed(rebind, 5000);
    }

    private final Runnable rebind = this::bind;

    /** 启动（幂等）。Context 用 applicationContext。 */
    public synchronized void bind() {
        if (appCtx == null) return;
        if (bound) return;
        try {
            Intent i = new Intent(SVC_ACTION);
            i.setComponent(new ComponentName(SVC_PKG, SVC_CLS));
            boolean ok = appCtx.bindService(i, conn, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "bindService " + (ok ? "请求已发" : "失败"));
            // NB10：失败也要 5s 重试——bindService 返回 false 时 conn 的
            // onServiceDisconnected **不会来**（根本没连上），旧代码 bound=true
            // 一置就永久卡死（实测：amapauto enabled=0 时 bind 失败后再也不重试）
            if (ok) {
                bound = true;
            } else {
                scheduleReconnect();
            }
        } catch (Throwable t) {
            Log.w(TAG, "bind 异常", t);
            scheduleReconnect();   // 异常同理
        }
    }

    /** 宿主（MediaListenerService/HomeActivity）调一次；带 Context 才真绑。 */
    public static void start(Context ctx) {
        get().appCtx = ctx == null ? null : ctx.getApplicationContext();
        if (get().appCtx != null) get().bind();
    }

    public synchronized void unbind() {
        main.removeCallbacks(rebind);
        if (appCtx != null && bound) {
            try {
                appCtx.unbindService(conn);
            } catch (Throwable ignore) {
            }
        }
        bound = false;
        svc = null;
    }

    /* -------------------- 下发（client → 高德，批次 NB15） -------------------- */

    /**
     * 下发一条 client 协议（照扒 lw.n → a.h1(type, json) 的包裹格式，i2.a 坐实）：
     *   {requestCode, responseCode:"", needResponse:false, protocolId, versionName,
     *    requestAuthor:CLIENT_TYPE, message:"", messageType:"request", statusCode:0, data:{...}}
     * 走与 handshake 相同的 transact code 1（= h1(String,String)）。未连接时静默丢弃。
     */
    private boolean sendRequest(int protocolId, JSONObject data) {
        IBinder b = svc;
        if (b == null || !registering) return false;
        Parcel parcel = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            JSONObject o = new JSONObject();
            o.put("requestCode", String.valueOf(SystemClock.elapsedRealtime()));
            o.put("responseCode", "");
            o.put("needResponse", false);
            o.put("protocolId", protocolId);
            o.put("versionName", "3.2.0.20190521");
            o.put("requestAuthor", CLIENT_TYPE);
            o.put("message", "");
            o.put("messageType", "request");
            o.put("statusCode", 0);
            o.put("data", data);
            parcel.writeInterfaceToken(IFACE);
            parcel.writeString(CLIENT_TYPE);
            parcel.writeString(o.toString());
            b.transact(1, parcel, reply, 0);   // code 1 = h1（同步）
            reply.readException();
            Log.i(TAG, "下发 protocolId=" + protocolId + " data=" + data);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "下发失败 protocolId=" + protocolId + ": " + t);
            return false;
        } finally {
            parcel.recycle();
            reply.recycle();
        }
    }

    /**
     * 页面跳转（PageJumpModel，protocolId 30306）。
     * pageType：7=JUMP_HOME 8=JUMP_COMPANY 14=JUMP_GO_HOME_OR_COMPANY_PNAV_PAGE；
     * displayId：高德把目标页画到指定 Android display —— 海鸥传画中画屏 id
     * 即「回家页直接落在画中画」（displayID 字段官方多屏指引用途，0=主屏）。
     */
    public static boolean pageJump(int pageType, int displayId) {
        JSONObject d = new JSONObject();
        try {
            d.put("pageType", pageType);
            d.put("displayID", Math.max(0, displayId));
        } catch (Throwable t) {
            return false;
        }
        return get().sendRequest(30306, d);
    }

    /**
     * 回家/公司直达（GoToHomeOrCopType，protocolId 30010）。
     * mGoToPageType：0=家 1=公司（官方 z70 注解 max=1）——高德内部解析用户收藏
     * 地址直接起导航（与语音「带我回家」同通道）。
     */
    public static boolean goToHomeOrCompany(boolean home) {
        JSONObject d = new JSONObject();
        try {
            d.put("mGoToPageType", home ? 0 : 1);
        } catch (Throwable t) {
            return false;
        }
        return get().sendRequest(30010, d);
    }

    /* -------------------- 协议解析（GuideInfoModel 60 字段） -------------------- */

    private void onProtocol(String json) {
        try {
            // NB14：协议原文日志（截断 600 字符）——用户实测「现在右转进入」疑似
            // 距离字段没取对，下次导航直接看原文对齐 key（导航数据不敏感，仍截断）
            Log.i(TAG, "protocol: " + (json.length() > 600
                    ? json.substring(0, 600) + "...(" + json.length() + "B)" : json));
            JSONObject o = new JSONObject(json);
            // 协议包裹：可能是 {"protocolId":30407,"data":{...}} 或平铺——两层都试
            JSONObject f = o.has("data") && o.get("data") instanceof JSONObject
                    ? o.getJSONObject("data") : o;
            final Guide g = new Guide();
            g.icon = f.optInt("icon", 0);
            g.newIcon = f.optInt("newIcon", 0);
            g.segRemainDis = f.optInt("segRemainDis", 0);       // NB17：真实 key（本段剩余）
            g.nextSegRemainTime = f.optInt("nextSegRemainTime", 0);
            g.segRemainDisAuto = opt(f, "segRemainDisAuto");    // NB17：真实 key（官方文案）
            g.nextRoadName = opt(f, "nextRoadName");
            g.nextNextRoadName = opt(f, "nextNextRoadName");
            g.routeRemainDis = f.optInt("routeRemainDis", 0);
            g.routeRemainTime = f.optInt("routeRemainTime", 0);
            g.routeRemainDistanceAuto = opt(f, "routeRemainDistanceAuto");
            g.routeRemainTimeAuto = opt(f, "routeRemainTimeAuto");
            g.curSpeed = f.optInt("curSpeed", 0);
            g.limitedSpeed = f.optInt("limitedSpeed", 0);
            g.cameraDist = f.optInt("cameraDist", 0);
            g.trafficLights = f.optInt("routeRemainTrafficLightNum", 0);
            g.etaText = opt(f, "etaText");
            g.curRoadName = opt(f, "curRoadName");
            g.roundaboutOutAngle = f.optInt("roundaboutOutAngle", 0);
            g.sapaName = opt(f, "sapaName");
            g.sapaDist = f.optInt("sapaDist", 0);
            final boolean end = g.routeRemainDis <= 0 && g.nextRoadName.isEmpty()
                    && g.segRemainDis <= 0 && g.segRemainDisAuto.isEmpty();
            main.post(() -> {
                for (Listener l : sinks) {
                    try {
                        if (end) l.onGuideEnd();
                        else l.onGuide(g);
                    } catch (Throwable t) {
                        Log.w(TAG, "sink 分发失败", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "协议解析失败", t);
        }
    }

    private static String opt(JSONObject o, String k) {
        String v = o.optString(k, "");
        if (v == null || "null".equals(v)) return "";
        return v.trim();   // NB16：空白串归空——高德推过「有 key 但值为空格」的帧，
                           // 不 trim 会骗过 isEmpty() 把距离行渲染成不可见空串
    }
}
