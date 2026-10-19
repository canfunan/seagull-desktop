package com.seagull.carlauncher;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 画中画界面（批次 M 薄壳化）—— 躯干就是一块 {@link PipBoard}。
 *
 * 为什么留薄壳：首屏画中画搬进 HomeActivity 之后，本页只剩两个用途：
 *   ① 桌面 Dock 的「投进画中画」快捷入口（DesktopView 长按菜单）；
 *   ② SelfTestMirror 的 L3 全链路自检（"selftest" extra）。
 * 两块画布的实现在 PipBoard 里只有一份，避免与 HomeActivity 首屏漂移。
 *
 * 常驻语义（批次 L）：VD 归进程级 MirrorHost 所有，退出本页只断 Surface、
 * 不拆屏 —— 拆屏会把屏上的任务倒回默认屏，桌面立刻冒出全屏应用
 * （用户说的「每次进入桌面还是应用界面」的根因）。
 */
public class MirrorActivity extends BaseActivity {

    private static final String TAG = "SeagullMirrorAct";

    /** 外部（桌面 Dock）指定「把这个应用搬进 slot 号槽位」。 */
    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_SLOT = "slot";

    /** 供桌面调用：打开画中画页并指定目标应用与槽位（1/2）。 */
    public static Intent intentFor(Context ctx, String pkg, int slot) {
        Intent i = new Intent(ctx, MirrorActivity.class);
        i.putExtra(EXTRA_PKG, pkg);
        i.putExtra(EXTRA_SLOT, slot);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return i;
    }

    private PipBoard board;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Skin.c(R.color.ground));
        int pad = dp(10);
        root.setPadding(pad, dp(8), pad, pad);

        TextView back = new TextView(this);
        back.setText("← 画中画");
        back.setTextColor(Skin.c(R.color.leaf));
        back.setTextSize(13);
        back.setPadding(dp(2), dp(2), dp(2), dp(6));
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(-2, -2));

        boolean selftest = getIntent() != null && getIntent().getBooleanExtra("selftest", false);
        board = new PipBoard(this, slot -> PipBoard.showPicker(this, slot, board), !selftest);
        root.addView(board, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);

        if (selftest) {
            // 自检页只走 logcat，不让画布抢在它前面部署
            SelfTestMirror.begin(this);
            return;
        }

        // 桌面指定了目标应用：先写进槽位再部署（缺录屏 token 时 PipBoard 自动补授权）
        String pkg = getIntent() != null ? getIntent().getStringExtra(EXTRA_PKG) : null;
        if (pkg != null && !pkg.isEmpty()) {
            int slot = getIntent().getIntExtra(EXTRA_SLOT, 1);
            if (slot < 1 || slot > 2) slot = 1;
            PipBoard.savePkg(this, slot, pkg);
            // 批次 CG：本页是「用户显式指定投屏」的入口，解除槽 2 的点击闸门
            PipBoard.markEntered(slot);
            Log.i(TAG, "桌面指定：槽 " + slot + " → " + pkg);
        }
        board.deployIfBound();
    }

    @Override protected void onResume() {
        super.onResume();
        if (board != null) board.onResume();
    }

    @Override protected void onDestroy() {
        if (board != null) board.onDestroy();
        super.onDestroy();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (SelfTestMirror.isOurRequest(req)) {
            SelfTestMirror.onConsent(this, res, data);
            return;
        }
        if (board != null) board.onActivityResult(req, res, data);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
