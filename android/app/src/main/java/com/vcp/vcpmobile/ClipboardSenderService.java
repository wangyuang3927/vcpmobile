package com.vcp.vcpmobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;


public class ClipboardSenderService extends Service {
    private static final String TAG = "ClipboardSender";
    private static final String CHANNEL_ID = "clipboard_sender_channel";
    private static final int NOTIFICATION_ID = 9528;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification("正在读取剪贴板...");
        startForeground(NOTIFICATION_ID, notification);

        // 从 Intent extra 读取剪贴板内容（由 ClipboardReaderActivity 在前台读取后传入）
        VCPApiHelper.initContext(this);
        String clipText = intent != null ? intent.getStringExtra("clip_text") : null;
        VCPApiHelper.fileLog("[Clipboard] 服务已启动，剪贴板内容: " + (clipText == null ? "null" : clipText.length() + "字符"));

        new Thread(() -> {
            try {
                if (clipText == null || clipText.trim().isEmpty()) {
                    updateNotification("剪贴板为空");
                    VCPApiHelper.fileLog("[Clipboard] 剪贴板为空");
                } else {
                    sendClipboardContent(clipText.trim());
                }
            } catch (Exception e) {
                VCPApiHelper.fileLog("[Clipboard] 异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                updateNotification("发送失败: " + e.getMessage());
            } finally {
                try { Thread.sleep(10000); } catch (InterruptedException ignored) {}
                stopForeground(true);
                stopSelf();
            }
        }).start();

        return START_NOT_STICKY;
    }

    private String getClipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence text = clip.getItemAt(0).getText();
            return text != null ? text.toString() : null;
        } catch (Exception e) {
            Log.e(TAG, "读取剪贴板失败", e);
            return null;
        }
    }

    private void sendClipboardContent(String content) throws Exception {
        SharedPreferences prefs = VCPApiHelper.getPrefs(this);
        String clipPresetMessage = prefs.getString("clipPresetMessage", "分析以下内容");

        // 预览剪贴板内容
        String preview = content.length() > 50 ? content.substring(0, 50) + "..." : content;
        updateNotification("正在发送: " + preview);

        // 调用 AI API
        String userText = clipPresetMessage + "\n\n" + content;
        VCPApiHelper.fileLog("[Clipboard] 开始调用 AI API");
        updateNotification("正在发送给 AI...");
        String aiReply = VCPApiHelper.chatText(prefs, userText);
        VCPApiHelper.fileLog("[Clipboard] AI 回复长度=" + aiReply.length());

        String aiPreview = aiReply.length() > 100 ? aiReply.substring(0, 100) + "..." : aiReply;
        updateNotification("✅ AI 回复: " + aiPreview);

        // 写入 Nova Agent 话题
        String topicName = "📋 " + preview;
        boolean synced = VCPApiHelper.appendToAgentHistory(prefs, userText, aiReply, topicName);
        VCPApiHelper.fileLog("[Clipboard] 话题写入结果: " + synced);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "剪贴板发送服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("后台发送剪贴板内容给 AI Agent");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("VCPMobile 剪贴板发送")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentIntent(pi)
                .setOngoing(false)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
