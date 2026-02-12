package com.vcp.vcpmobile;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "ScreenshotSender")
public class ScreenshotSenderPlugin extends Plugin {

    @PluginMethod
    public void configure(PluginCall call) {
        String baseUrl = call.getString("baseUrl", "");
        String apiKey = call.getString("apiKey", "");
        String model = call.getString("model", "");
        String presetMessage = call.getString("presetMessage", "识别截图内容并记录日记");
        String clipPresetMessage = call.getString("clipPresetMessage", "分析以下内容");
        String systemPrompt = call.getString("systemPrompt", "");
        String adminUsername = call.getString("adminUsername", "");
        String adminPassword = call.getString("adminPassword", "");
        String agentDirId = call.getString("agentDirId", "");

        SharedPreferences prefs = getContext().getSharedPreferences(
                VCPApiHelper.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString("baseUrl", baseUrl)
                .putString("apiKey", apiKey)
                .putString("model", model)
                .putString("presetMessage", presetMessage)
                .putString("clipPresetMessage", clipPresetMessage)
                .putString("systemPrompt", systemPrompt)
                .putString("adminUsername", adminUsername)
                .putString("adminPassword", adminPassword)
                .putString("agentDirId", agentDirId)
                .apply();

        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void getConfig(PluginCall call) {
        SharedPreferences prefs = getContext().getSharedPreferences(
                VCPApiHelper.PREFS_NAME, Context.MODE_PRIVATE);

        JSObject ret = new JSObject();
        ret.put("baseUrl", prefs.getString("baseUrl", ""));
        ret.put("apiKey", prefs.getString("apiKey", ""));
        ret.put("model", prefs.getString("model", ""));
        ret.put("presetMessage", prefs.getString("presetMessage", "识别截图内容并记录日记"));
        ret.put("clipPresetMessage", prefs.getString("clipPresetMessage", "分析以下内容"));
        ret.put("systemPrompt", prefs.getString("systemPrompt", ""));
        ret.put("adminUsername", prefs.getString("adminUsername", ""));
        ret.put("adminPassword", prefs.getString("adminPassword", ""));
        ret.put("agentDirId", prefs.getString("agentDirId", ""));
        call.resolve(ret);
    }

    @PluginMethod
    public void sendNow(PluginCall call) {
        Intent serviceIntent = new Intent(getContext(), ScreenshotSenderService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(serviceIntent);
        } else {
            getContext().startService(serviceIntent);
        }

        JSObject ret = new JSObject();
        ret.put("started", true);
        call.resolve(ret);
    }
}
