package com.seagull.carlauncher;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * M3 本地音乐页：播放头（歌名/进度/上一首-播放-下一首）+ 全部曲目列表。
 *
 * 点列表 = 置队列从该首播（M3 语义：列表即队列）；正在播的一行高亮。
 * 进度条 500ms 轮询（仅本页活着时）；拖动松手才 seek（onStopTrackingTouch）。
 * 扫描是异步的：进页先展示上次缓存，随后扫描回调刷新。
 */
public class MusicActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TrackAdapter adapter;
    private TextView title, artist;
    private SeekBar bar;
    private TextView pos, dur;
    private View playBtn;
    private boolean seeking;
    private int lastCur = -2;      // 高亮行缓存：变了才 notifyDataSetChanged
    private List<MusicLib.Track> shown;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            refreshHead();
            ui.postDelayed(this, 500L);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        refreshHead();
        ui.removeCallbacks(poll);
        ui.postDelayed(poll, 500L);   // 播放头 500ms 轮询（onCreate 里没人起过它，全页冻结）
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(poll);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Skin.c(R.color.ground));
        root.setPadding(dp(14), dp(10), dp(14), dp(14));

        /* ---- 播放头 ---- */
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable hbg = new GradientDrawable();
        hbg.setColor(Skin.c(R.color.panel));
        hbg.setCornerRadius(dp(12));
        head.setBackground(hbg);
        head.setPadding(dp(14), dp(10), dp(14), dp(10));

        title = tx(17, R.color.text);
        head.addView(title);
        artist = tx(12, R.color.text_dim);
        head.addView(artist);

        LinearLayout pro = new LinearLayout(this);
        pro.setOrientation(LinearLayout.HORIZONTAL);
        pro.setGravity(Gravity.CENTER_VERTICAL);
        pos = tx(11, R.color.text_dim);
        pro.addView(pos);
        bar = new SeekBar(this);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar s) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
                MusicService.seek(MusicActivity.this, s.getProgress());
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, -2, 1f);
        blp.leftMargin = dp(6);
        blp.rightMargin = dp(6);
        pro.addView(bar, blp);
        dur = tx(11, R.color.text_dim);
        pro.addView(dur);
        head.addView(pro);

        head.addView(ctrlRow());
        root.addView(head);

        /* ---- 曲目列表 ---- */
        TextView cap = tx(13, R.color.leaf);
        cap.setText("全部曲目");
        cap.setPadding(0, dp(10), 0, dp(4));
        root.addView(cap);

        adapter = new TrackAdapter();
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setCacheColorHint(Color.TRANSPARENT);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2, 1f);
        llp.topMargin = dp(4);
        root.addView(list, llp);
        setContentView(root);

        shown = MusicLib.last();
        adapter.notifyDataSetChanged();
        MusicLib.scan(this, tracks -> {
            if (isFinishing() || isDestroyed()) return;
            shown = tracks;
            adapter.notifyDataSetChanged();
            // 冷启断点恢复：扫描结果就位后按存档路径重挂队列（重启后播放列表与进度保留）
            MusicService.tryRestore(this);
            refreshHead();
            adapter.notifyDataSetChanged();
            if (tracks.isEmpty()) toast("三个来源都没扫到歌（Music 目录 / U盘 / root）");
        });
    }

    private LinearLayout ctrlRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(btn("⏮", v -> MusicService.prev(this)));
        playBtn = btn("▶", v -> MusicService.toggle(this));
        row.addView(playBtn);
        row.addView(btn("⏭", v -> MusicService.next(this)));
        return row;
    }

    private TextView btn(String label, View.OnClickListener l) {
        TextView t = tx(20, R.color.text);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(22), dp(4), dp(22), dp(4));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(14);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView tx(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(Skin.c(color));
        return t;
    }

    private void refreshHead() {
        boolean on = MusicService.index() >= 0 && MusicService.index() < MusicService.queue().size();
        if (on) {
            MusicLib.Track t = MusicService.queue().get(MusicService.index());
            title.setText(MusicLib.label(t));
            artist.setText(MusicService.isPlaying() ? "本地音乐 · 播放中" : "本地音乐 · 已暂停");
            ((TextView) playBtn).setText(MusicService.isPlaying() ? "⏸" : "▶");
            int d = MusicService.duration();
            bar.setMax(Math.max(1, d));
            if (!seeking) {
                int p = MusicService.position();
                bar.setProgress(p);
                pos.setText(fmt(p));
                dur.setText(fmt(d));
            }
        } else {
            title.setText("没在播本地音乐");
            artist.setText("点下面任意一首开始");
            ((TextView) playBtn).setText("▶");
            bar.setProgress(0);
            pos.setText("0:00");
            dur.setText("0:00");
        }
        // 高亮正在播的行：行号变了才整表重绑（M4 审计纪律：轮询别全量刷）
        if (MusicService.index() != lastCur) {
            lastCur = MusicService.index();
            adapter.notifyDataSetChanged();
        }
    }

    private static String fmt(int ms) {
        int s = ms / 1000;
        return s / 60 + ":" + String.format(java.util.Locale.US, "%02d", s % 60);
    }

    private int dp(float v) {
        return Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /* ---------------- 列表适配器 ---------------- */

    private class TrackAdapter extends BaseAdapter {
        @Override public int getCount() { return shown == null ? 0 : shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View cv, ViewGroup parent) {
            TextView t = (TextView) cv;
            if (t == null) {
                t = new TextView(MusicActivity.this);
                t.setTextSize(15);
                t.setPadding(dp(6), dp(9), dp(6), dp(9));
            }
            MusicLib.Track tr = shown.get(i);
            boolean cur = MusicService.index() == i && !MusicService.queue().isEmpty();
            t.setText((cur ? "▶ " : "") + MusicLib.label(tr));
            t.setTextColor(Skin.c(cur ? R.color.leaf : R.color.text));
            t.setOnClickListener(v -> {
                MusicService.play(MusicActivity.this, shown, i);
                adapter.notifyDataSetChanged();
            });
            return t;
        }
    }
}
