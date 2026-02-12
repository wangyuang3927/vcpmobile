package com.vcp.vcpmobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Arrays;

public class ScreenshotSenderService extends Service {
    private static final String TAG = "ScreenshotSender";
    private static final String CHANNEL_ID = "screenshot_sender_channel";
    private static final int NOTIFICATION_ID = 9527;
    public static final String PREFS_NAME = VCPApiHelper.PREFS_NAME;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 启动前台通知
        Notification notification = buildNotification("正在发送截图...");
        startForeground(NOTIFICATION_ID, notification);

        // 在后台线程执行
        VCPApiHelper.initContext(this);
        new Thread(() -> {
            try {
                VCPApiHelper.fileLog("[Screenshot] 服务已启动，开始发送截图");
                sendLatestScreenshot();
            } catch (Exception e) {
                VCPApiHelper.fileLog("[Screenshot] 异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                updateNotification("截图发送失败: " + e.getMessage());
            } finally {
                try { Thread.sleep(10000); } catch (InterruptedException ignored) {}
                stopForeground(true);
                stopSelf();
            }
        }).start();

        return START_NOT_STICKY;
    }

    private void sendLatestScreenshot() throws Exception {
        SharedPreferences prefs = VCPApiHelper.getPrefs(this);
        String presetMessage = prefs.getString("presetMessage", "识别截图内容并记录日记");

        // 查找最新截图（带重试：系统截图可能有几秒延迟才写入磁盘）
        File screenshotDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "Screenshots");
        if (!screenshotDir.exists() || !screenshotDir.isDirectory()) {
            screenshotDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DCIM), "Screenshots");
        }
        VCPApiHelper.fileLog("[Screenshot] 截图目录: " + screenshotDir.getAbsolutePath() + " exists=" + screenshotDir.exists());
        if (!screenshotDir.exists() || !screenshotDir.isDirectory()) {
            updateNotification("未找到截图目录");
            VCPApiHelper.fileLog("[Screenshot] 未找到截图目录");
            return;
        }

        File latestScreenshot = null;
        long ageMs = Long.MAX_VALUE;
        int maxScanRetries = 5;
        for (int scan = 0; scan < maxScanRetries; scan++) {
            if (scan > 0) {
                VCPApiHelper.fileLog("[Screenshot] 等待截图写入... 第" + (scan + 1) + "次扫描");
                updateNotification("等待截图写入... (" + scan + "/" + maxScanRetries + ")");
                Thread.sleep(2000);
            }

            File[] files = screenshotDir.listFiles((dir, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp");
            });

            if (files == null || files.length == 0) {
                VCPApiHelper.fileLog("[Screenshot] 截图文件数: " + (files == null ? "null" : "0"));
                continue;
            }

            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            latestScreenshot = files[0];
            ageMs = System.currentTimeMillis() - latestScreenshot.lastModified();
            VCPApiHelper.fileLog("[Screenshot] 扫描" + (scan + 1) + " 最新: " + latestScreenshot.getName() + " age=" + ageMs + "ms 文件数=" + files.length);

            if (ageMs < 10000) {
                break; // 10秒内的截图，立即使用
            }
        }

        if (latestScreenshot == null) {
            updateNotification("截图目录为空");
            VCPApiHelper.fileLog("[Screenshot] 截图目录为空");
            return;
        }

        if (ageMs > 600000) {
            updateNotification("未检测到最近截图（最近截图已超过10分钟）");
            VCPApiHelper.fileLog("[Screenshot] 截图超过10分钟，跳过");
            return;
        }

        VCPApiHelper.fileLog("[Screenshot] 找到截图: " + latestScreenshot.getName() + " age=" + ageMs + "ms");
        updateNotification("正在处理截图: " + latestScreenshot.getName());

        // 读取并压缩图片
        Bitmap bitmap = BitmapFactory.decodeFile(latestScreenshot.getAbsolutePath());
        if (bitmap == null) {
            updateNotification("无法读取截图文件");
            return;
        }

        int maxDim = 1024;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (Math.max(w, h) > maxDim) {
            float scale = (float) maxDim / Math.max(w, h);
            w = Math.round(w * scale);
            h = Math.round(h * scale);
            bitmap = Bitmap.createScaledBitmap(bitmap, w, h, true);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos);
        String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        bitmap.recycle();

        // 调用 AI API
        VCPApiHelper.fileLog("[Screenshot] base64长度=" + base64.length() + "，开始调用 AI API");
        updateNotification("正在发送给 AI...");
        String aiReply = VCPApiHelper.chatImage(prefs, base64, presetMessage);
        VCPApiHelper.fileLog("[Screenshot] AI 回复长度=" + aiReply.length());

        String preview = aiReply.length() > 100 ? aiReply.substring(0, 100) + "..." : aiReply;
        updateNotification("✅ AI 回复: " + preview);

        // 写入 Nova Agent 话题（用户打开 App 后可见）
        String topicName = "📸 " + latestScreenshot.getName();
        // 用户消息用纯文本描述（base64 太大不写入话题）
        String userContent = "[截图] " + presetMessage + "\n\n(文件: " + latestScreenshot.getName() + ")";
        boolean synced = VCPApiHelper.appendToAgentHistory(prefs, userContent, aiReply, topicName);
        VCPApiHelper.fileLog("[Screenshot] 话题写入结果: " + synced);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "截图发送服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("后台发送截图给 AI Agent");
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
                .setContentTitle("VCPMobile 截图发送")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
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
