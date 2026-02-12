package com.vcp.vcpmobile;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/**
 * 无 UI Activity，用于被 Button Mapper 等外部工具启动。
 * 启动后立即触发 ClipboardSenderService 读取剪贴板并发送给 AI，然后自行关闭。
 */
public class ClipboardSendActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent serviceIntent = new Intent(this, ClipboardSenderService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        finish();
    }
}
