package com.vcp.vcpmobile;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.List;

/**
 * Capacitor 插件：桥接 JS ↔ VolumeKeyService
 * - checkAccessibility: 检查辅助功能是否已开启
 * - openAccessibilitySettings: 打开系统辅助功能设置页
 * - setEnabled / isEnabled: 控制音量键监听开关
 */
@CapacitorPlugin(name = "VolumeKey")
public class VolumeKeyPlugin extends Plugin {
    private static final String PREFS_NAME = "volume_key_prefs";
    private static final String KEY_ENABLED = "enabled";

    @PluginMethod
    public void checkAccessibility(PluginCall call) {
        boolean granted = isAccessibilityServiceEnabled();
        JSObject ret = new JSObject();
        ret.put("enabled", granted);
        call.resolve(ret);
    }

    @PluginMethod
    public void openAccessibilitySettings(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);

        JSObject ret = new JSObject();
        ret.put("opened", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void setEnabled(PluginCall call) {
        boolean enabled = call.getBoolean("enabled", true);
        VolumeKeyService.setEnabled(enabled);

        // 持久化
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();

        JSObject ret = new JSObject();
        ret.put("enabled", enabled);
        call.resolve(ret);
    }

    @PluginMethod
    public void isEnabled(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("enabled", VolumeKeyService.isEnabled());
        ret.put("accessibilityGranted", isAccessibilityServiceEnabled());
        call.resolve(ret);
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;

        List<AccessibilityServiceInfo> enabledServices = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_GENERIC);
        String targetService = getContext().getPackageName() + "/" + VolumeKeyService.class.getName();

        for (AccessibilityServiceInfo info : enabledServices) {
            if (info.getId().equals(targetService)) {
                return true;
            }
        }
        return false;
    }
}
