package com.vcp.vcpmobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;

/**
 * 前台服务：录音 → 发送给 AI → 写入 VCPChat 话题
 *
 * 生命周期：
 * 1. VolumeKeyService 长按音量下 → startService(ACTION_START_RECORDING)
 * 2. 松开音量下 → startService(ACTION_STOP_AND_SEND)
 * 3. 录音结束后自动调 AI API + 写入话题 → stopSelf()
 */
public class VoiceRecorderService extends Service {
    private static final String TAG = "VoiceRecorderService";
    private static final String CHANNEL_ID = "voice_recorder_channel";
    private static final int NOTIFICATION_ID = 9528;

    public static final String ACTION_START_RECORDING = "com.vcp.vcpmobile.START_RECORDING";
    public static final String ACTION_STOP_AND_SEND = "com.vcp.vcpmobile.STOP_AND_SEND";

    private MediaRecorder mediaRecorder;
    private File audioFile;
    private boolean isRecording = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        VCPApiHelper.initContext(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_START_RECORDING.equals(action)) {
            startRecording();
        } else if (ACTION_STOP_AND_SEND.equals(action)) {
            stopRecordingAndSend();
        }

        return START_NOT_STICKY;
    }

    private void startRecording() {
        if (isRecording) {
            VCPApiHelper.fileLog("[Voice] 已在录音中，忽略重复请求");
            return;
        }

        // 启动前台通知
        Notification notification = buildNotification("🎤 正在录音...");
        startForeground(NOTIFICATION_ID, notification);

        try {
            // 临时文件
            audioFile = new File(getCacheDir(), "vcp_voice_" + System.currentTimeMillis() + ".m4a");

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioEncodingBitRate(128000);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setOutputFile(audioFile.getAbsolutePath());

            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;

            VCPApiHelper.fileLog("[Voice] 开始录音: " + audioFile.getName());
        } catch (Exception e) {
            VCPApiHelper.fileLog("[Voice] 录音启动失败: " + e.getMessage());
            updateNotification("❌ 录音启动失败: " + e.getMessage());
            cleanupAndStop();
        }
    }

    private void stopRecordingAndSend() {
        if (!isRecording) {
            VCPApiHelper.fileLog("[Voice] 未在录音中，忽略停止请求");
            stopSelf();
            return;
        }

        // 停止录音
        try {
            mediaRecorder.stop();
            mediaRecorder.release();
            mediaRecorder = null;
            isRecording = false;
            VCPApiHelper.fileLog("[Voice] 录音停止，文件大小: " + audioFile.length() + " bytes");
        } catch (Exception e) {
            VCPApiHelper.fileLog("[Voice] 停止录音异常: " + e.getMessage());
            cleanupAndStop();
            return;
        }

        // 文件太小（< 1KB）可能是误触
        if (audioFile.length() < 1024) {
            VCPApiHelper.fileLog("[Voice] 录音太短，丢弃");
            updateNotification("录音太短，已丢弃");
            cleanupAndStop();
            return;
        }

        updateNotification("正在发送给 AI...");

        // 后台线程处理 API 调用
        new Thread(() -> {
            try {
                sendToAI();
            } catch (Exception e) {
                VCPApiHelper.fileLog("[Voice] 发送失败: " + e.getMessage());
                updateNotification("❌ 发送失败: " + e.getMessage());
            } finally {
                // 延迟 8 秒让用户看到通知结果
                try { Thread.sleep(8000); } catch (InterruptedException ignored) {}
                cleanupAndStop();
            }
        }).start();
    }

    private void sendToAI() throws Exception {
        SharedPreferences prefs = VCPApiHelper.getPrefs(this);
        String presetVoiceMessage = prefs.getString("presetVoiceMessage", "请听取并回复这段语音");

        // 读取音频文件为 base64
        byte[] audioBytes = readFileBytes(audioFile);
        String base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP);
        VCPApiHelper.fileLog("[Voice] base64长度=" + base64Audio.length());

        // 调用 AI API（带音频）
        updateNotification("正在等待 AI 回复...");
        String aiReply = VCPApiHelper.chatAudio(prefs, base64Audio, "audio/mp4", presetVoiceMessage);
        VCPApiHelper.fileLog("[Voice] AI 回复长度=" + aiReply.length());

        String preview = aiReply.length() > 80 ? aiReply.substring(0, 80) + "..." : aiReply;
        updateNotification("✅ AI: " + preview);

        // 写入 VCPChat Agent 话题
        String topicName = "🎤 语音 " + new java.text.SimpleDateFormat("MM-dd HH:mm",
                java.util.Locale.getDefault()).format(new java.util.Date());
        String userContent = "[语音消息] " + presetVoiceMessage;
        boolean synced = VCPApiHelper.appendToAgentHistory(prefs, userContent, aiReply, topicName);
        VCPApiHelper.fileLog("[Voice] 话题写入: " + synced);
    }

    private byte[] readFileBytes(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = fis.read(bytes);
            if (read != bytes.length) {
                throw new Exception("读取不完整: " + read + "/" + bytes.length);
            }
            return bytes;
        }
    }

    private void cleanupAndStop() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.release();
            } catch (Exception ignored) {}
            mediaRecorder = null;
        }
        isRecording = false;
        if (audioFile != null && audioFile.exists()) {
            audioFile.delete();
            audioFile = null;
        }
        stopForeground(true);
        stopSelf();
    }

    // ========== 通知 ==========

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "语音录制服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("后台录音并发送给 AI Agent");
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
                .setContentTitle("VCPMobile 语音录制")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
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

    @Override
    public void onDestroy() {
        if (isRecording) {
            try {
                mediaRecorder.stop();
                mediaRecorder.release();
            } catch (Exception ignored) {}
            mediaRecorder = null;
            isRecording = false;
        }
        if (audioFile != null && audioFile.exists()) {
            audioFile.delete();
        }
        super.onDestroy();
        Log.i(TAG, "VoiceRecorderService 已销毁");
    }
}
