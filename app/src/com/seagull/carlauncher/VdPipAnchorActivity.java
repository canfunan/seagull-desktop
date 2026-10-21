package com.seagull.carlauncher;

import android.app.Activity;
import android.os.Bundle;

/**
 * 画中画锚点（批次 W，一比一复刻车联助手 com.leting.pip.PipAnchorActivity）。
 *
 * 作用：落屏前先用「自家应用」身份把一个 Activity 启动到私有虚拟屏上
 * （属主启动必然成功），私有屏上因此出现一个 root task；
 * root 脚本再把目标应用的任务 am stack move-task 搬进这个 root task ——
 * 绕开 Android 14 对第三方应用直启 display 的限制。这是车联助手
 * 「没理由不行」的核心机制。
 *
 * 复刻要点（车联 manifest 原属性）：
 *   taskAffinity 独立（com.leting.pip.anchor → 我们的 pipanchor）→ 新屏上独立成 task；
 *   excludeFromRecents（manifest）；不 finish（setFinishOnTouchOutside(false)），
 *   常驻作 task 基座，目标应用搬入后盖在其上；透明主题，视觉无感。
 */
public class VdPipAnchorActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setFinishOnTouchOutside(false);
    }
}
