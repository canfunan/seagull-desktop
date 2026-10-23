package com.seagull.carlauncher;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 画中画适配名单（app 侧，与模块 XposedEntry 的 TARGETS + CAR_PKGS 同步）。
 *
 * 用户要求（1023）：「选择画中画应用只显示适配的应用，不适配的默认不能
 * 添加」—— 没进名单的 app 挂上画布也是黑屏/尺寸全错/启动被拒（技能
 * car-pip-adaptation 的问题模型），选它等于给用户找别扭。
 *
 * 维护口径：XposedEntry 的 TARGETS/CAR_PKGS 加包时，这里同步加一份
 * （模块侧钩子要它，app 侧过滤也要它，两边一致才不出「选得上但挂不好」）。
 */
public final class PipAdapted {

    private PipAdapted() {}

    /** 手机版全套钩子名单（XposedEntry.TARGETS 同步）。 */
    private static final Set<String> TARGETS = new HashSet<>(Arrays.asList(
            "com.autonavi.minimap",          // 高德地图（手机版）
            "com.autonavi.amapauto",         // 高德车机版
            "com.baidu.BaiduMap",            // 百度地图
            "com.tencent.map",               // 腾讯地图
            "com.netease.cloudmusic",        // 网易云音乐
            "com.netease.cloudmusic.iot",    // 网易云音乐 IoT
            "com.tencent.qqmusic"            // QQ 音乐手机版
    ));

    /** 车机/TV 版通用名单（XposedEntry.CAR_PKGS 同步，含批次 NEW-1023）。 */
    private static final Set<String> CAR_PKGS = new HashSet<>(Arrays.asList(
            "com.tencent.qqmusiccar",        // QQ音乐车机版
            "com.kugou.android.auto",        // 酷狗音乐车机版
            "cn.kuwo.kwmusiccarsnp",         // 酷我音乐车机版
            "com.baidu.naviauto",            // 百度地图汽车版
            "com.luna.music",                // 落雪音乐
            "com.luna.music.car",            // 落雪音乐车机版
            "com.tencent.wecarnavi",         // 腾讯WE车机导航
            "com.mapbar.android.mapbarmap",  // 图吧地图
            "com.sygic.aura",                // Sygic 导航
            "com.ktkid.video",               // 酷奇视频
            "com.bilibili.bilithings",       // B站 Things
            "com.huya.android.pad",          // 虎牙TV
            "com.tencent.qqlivepad",         // 腾讯视频TV
            "com.qiyi.video.pad",            // 爱奇艺TV
            "com.vcinema.cinema.pad",        // 影院pad
            "com.ximalayaos.pad.tingkid",    // 喜马拉雅儿童
            "bubei.tingshu.hd",              // 布贝听书
            "com.tv.loveyoga.hz",            // 瑜伽TV
            "com.ukids.client.tv"            // 优酷少儿
    ));

    /** 是否已适配（可挂画中画）。 */
    public static boolean isAdapted(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        return TARGETS.contains(pkg) || CAR_PKGS.contains(pkg);
    }
}
