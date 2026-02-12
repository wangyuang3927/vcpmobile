package com.vcp.vcpmobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/**
 * 透明 Activity，用于在前台读取剪贴板内容。
 * Android 10+ 限制后台 Service 读取剪贴板，只有前台 Activity 才能读取。
 * VolumeKeyService 启动此 Activity → 获得焦点后读取剪贴板 → 传给 ClipboardSenderService → finish。
 */
public class ClipboardReaderActivity extends Activity {

    private boolean hasRead = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        VCPApiHelper.initContext(this);
        VCPApiHelper.fileLog("[ClipboardReader] onCreate");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        VCPApiHelper.fileLog("[ClipboardReader] onWindowFocusChanged hasFocus=" + hasFocus);

        if (hasFocus && !hasRead) {
            hasRead = true;
            readAndSend();
        }
    }

    private void readAndSend() {
        String clipText = null;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData clip = cm.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence text = clip.getItemAt(0).getText();
                    if (text != null) {
                        clipText = text.toString();
                    }
                }
            }
        } catch (Exception e) {
            VCPApiHelper.fileLog("[ClipboardReader] 读取剪贴板异常: " + e.getMessage());
        }

        VCPApiHelper.fileLog("[ClipboardReader] 剪贴板内容: " + (clipText == null ? "null" : clipText.length() + "字符"));

        // 启动 ClipboardSenderService，传入剪贴板内容
        Intent serviceIntent = new Intent(this, ClipboardSenderService.class);
        if (clipText != null && !clipText.trim().isEmpty()) {
            serviceIntent.putExtra("clip_text", clipText.trim());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        finish();
    }
}
