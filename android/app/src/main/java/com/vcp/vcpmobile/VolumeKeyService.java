package com.vcp.vcpmobile;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * AccessibilityService 监听音量上键的双击和长按手势：
 * - 双击音量上键 → 启动 ScreenshotSenderService（截图发送给 AI）
 * - 长按音量上键 → 启动 ClipboardSenderService（剪贴板发送给 AI）
 *
 * 音量下键不拦截，保持系统默认行为。
 */
public class VolumeKeyService extends AccessibilityService {
    private static final String TAG = "VolumeKeyService";
    private static final String PREFS_NAME = "volume_key_prefs";
    private static final String KEY_ENABLED = "enabled";

    // 双击检测参数
    private static final long DOUBLE_CLICK_INTERVAL = 400; // ms
    // 长按检测参数
    private static final long LONG_PRESS_DURATION = 600; // ms

    private final Handler handler = new Handler(Looper.getMainLooper());

    // 双击状态
    private long lastVolumeUpTime = 0;
    private int clickCount = 0;
    private Runnable singleClickRunnable = null;

    // 长按状态
    private boolean volumeUpDown = false;
    private long volumeUpDownTime = 0;
    private boolean longPressTriggered = false;
    private Runnable longPressRunnable = null;

    // 全局开关（可通过 Capacitor 插件控制）
    private static volatile boolean serviceEnabled = true;

    public static void setEnabled(boolean enabled) {
        serviceEnabled = enabled;
    }

    public static boolean isEnabled() {
        return serviceEnabled;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "VolumeKeyService 已连接");
        showToast("VCP 音量键服务已启动");

        // 读取持久化的开关状态
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        serviceEnabled = prefs.getBoolean(KEY_ENABLED, true);

        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不需要处理无障碍事件
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "VolumeKeyService 被中断");
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (!serviceEnabled) return false;
        if (event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_UP) return false;

        int action = event.getAction();

        if (action == KeyEvent.ACTION_DOWN) {
            if (!volumeUpDown) {
                // 按键按下
                volumeUpDown = true;
                volumeUpDownTime = System.currentTimeMillis();
                longPressTriggered = false;

                // 安排长按检测
                if (longPressRunnable != null) handler.removeCallbacks(longPressRunnable);
                longPressRunnable = () -> {
                    if (volumeUpDown) {
                        longPressTriggered = true;
                        onVolumeLongPress();
                    }
                };
                handler.postDelayed(longPressRunnable, LONG_PRESS_DURATION);
            }
            return true; // 消费按键，阻止系统音量调节

        } else if (action == KeyEvent.ACTION_UP) {
            volumeUpDown = false;

            // 取消长按检测
            if (longPressRunnable != null) {
                handler.removeCallbacks(longPressRunnable);
            }

            if (longPressTriggered) {
                // 长按已触发，忽略抬起
                longPressTriggered = false;
                return true;
            }

            // 短按：双击检测
            long now = System.currentTimeMillis();
            if (now - lastVolumeUpTime < DOUBLE_CLICK_INTERVAL) {
                // 双击确认
                clickCount = 0;
                lastVolumeUpTime = 0;
                if (singleClickRunnable != null) {
                    handler.removeCallbacks(singleClickRunnable);
                    singleClickRunnable = null;
                }
                onVolumeDoubleClick();
            } else {
                // 第一次点击，等待可能的第二次
                clickCount = 1;
                lastVolumeUpTime = now;
                if (singleClickRunnable != null) {
                    handler.removeCallbacks(singleClickRunnable);
                }
                singleClickRunnable = () -> {
                    // 超时未双击 → 单击，通过 AudioManager 手动调高音量
                    clickCount = 0;
                    lastVolumeUpTime = 0;
                    singleClickRunnable = null;
                    adjustVolume(AudioManager.ADJUST_RAISE);
                };
                handler.postDelayed(singleClickRunnable, DOUBLE_CLICK_INTERVAL);
            }
            return true;
        }

        return false;
    }

    private void onVolumeDoubleClick() {
        Log.i(TAG, "双击音量上键 → 发送截图");
        showToast("VCP: 正在发送截图...");
        Intent intent = new Intent(this, ScreenshotSenderService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void onVolumeLongPress() {
        Log.i(TAG, "长按音量上键 → 发送剪贴板");
        showToast("VCP: 正在发送剪贴板...");
        // 启动透明 Activity 在前台读取剪贴板（Android 10+ 后台 Service 无法读取）
        Intent intent = new Intent(this, ClipboardReaderActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void adjustVolume(int direction) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        }
    }

    private void showToast(String msg) {
        handler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "VolumeKeyService 已销毁");
    }
}
