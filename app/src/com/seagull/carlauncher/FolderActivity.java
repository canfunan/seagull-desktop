package com.seagull.carlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 文件夹：打开查看 / 改名 / 解散 / 把某个应用移出。
 * 对齐海鸥桌面「桌面」组里的整理能力。
 */
public class FolderActivity extends BaseActivity {

    public static final String EXTRA_NAME = "folder_name";

    private String folderName;
    private LinearLayout rootCol;

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        folderName = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_NAME);
        setContentView(build());
    }

    private LauncherModel.Folder folder() {
        for (LauncherModel.Folder f : model.folders) {
            if (f.name.equals(folderName)) return f;
        }
        return null;
    }

    private View build() {
        ScrollView sv = new ScrollView(this);
        rootCol = new LinearLayout(this);
        rootCol.setOrientation(LinearLayout.VERTICAL);
        rootCol.setBackgroundColor(Skin.c(R.color.ground));
        rootCol.setPadding(dp(16), dp(12), dp(16), dp(16));

        LauncherModel.Folder f = folder();
        String title = f == null ? "文件夹已不存在" : f.name;

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("← 返回");
        back.setTextColor(Skin.c(R.color.leaf));
        back.setTextSize(15);
        back.setOnClickListener(v -> finish());
        head.addView(back);
        TextView t = new TextView(this);
        t.setText("  " + title + (f == null ? "" : "  共 " + f.keys.size() + " 个"));
        t.setTextColor(Skin.c(R.color.text));
        t.setTextSize(17);
        head.addView(t);
        rootCol.addView(head);

        if (f == null) { sv.addView(rootCol); return sv; }

        // 操作条
        LinearLayout ops = new LinearLayout(this);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        ops.addView(op("改名", v -> rename(f)), new LinearLayout.LayoutParams(0, -2, 1f));
        ops.addView(op("解散", v -> dissolve(f)), new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, -2);
        op.topMargin = dp(10);
        rootCol.addView(ops, op);

        // 应用网格
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, -2);
        gp.topMargin = dp(16);
        rootCol.addView(grid, gp);

        for (final String key : new java.util.ArrayList<>(f.keys)) {
            LauncherModel.App a = model.find(key);
            if (a == null) continue;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);

            ImageView iv = new ImageView(this);
            Drawable d = model.icon(a);
            if (d != null) iv.setImageDrawable(d);
            cell.addView(iv, new LinearLayout.LayoutParams(dp(48), dp(48)));

            TextView tv = new TextView(this);
            tv.setText(a.label);
            tv.setTextColor(Skin.c(R.color.text_dim));
            tv.setTextSize(12);
            tv.setMaxLines(1);
            cell.addView(tv);

            cell.setOnClickListener(v -> {
                if (model != null) model.launch(a, false);
            });
            cell.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle(a.label)
                        .setItems(new String[]{"移出文件夹", "打开"}, (dg, which) -> {
                            if (which == 0) {
                                model.removeFromFolder(f, key);
                                Toast.makeText(this, "已移出", Toast.LENGTH_SHORT).show();
                                recreate();
                            } else model.launch(a, false);
                        }).show();
                return true;
            });

            GridLayout.LayoutParams cp = new GridLayout.LayoutParams();
            cp.width = 0; cp.height = dp(88);
            cp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            cp.setMargins(dp(2), dp(6), dp(2), dp(6));
            grid.addView(cell, cp);
        }

        sv.addView(rootCol);
        return sv;
    }

    private View op(String label, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Skin.c(R.color.text));
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(12), dp(10), dp(12));
        tv.setBackground(Skin.round(Skin.c(R.color.card), 12f));
        tv.setOnClickListener(l);
        return tv;
    }

    private void rename(LauncherModel.Folder f) {
        EditText et = new EditText(this);
        et.setText(f.name);
        et.setTextColor(Skin.c(R.color.text));
        et.setHint("文件夹名字");
        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    model.renameFolder(f, et.getText().toString());
                    folderName = f.name;
                    setContentView(build());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void dissolve(LauncherModel.Folder f) {
        new AlertDialog.Builder(this)
                .setTitle("解散「" + f.name + "」？")
                .setMessage("里面的 " + f.keys.size() + " 个应用会回到主屏。")
                .setPositiveButton("解散", (d, w) -> {
                    model.dissolveFolder(f);
                    Toast.makeText(this, "已解散", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
