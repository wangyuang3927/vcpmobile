package com.vcp.vcpmobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 公共 API 辅助类：
 * 1. 调用 OpenAI 兼容 /v1/chat/completions（非流式）
 * 2. 调用 /admin_api/agents/vcpchat-append-history 将消息写入 VCPChat Agent 话题
 */
public class VCPApiHelper {
    private static final String TAG = "VCPApiHelper";
    public static final String PREFS_NAME = "screenshot_sender_prefs";

    // ========== 文件日志（绕过 ColorOS 日志过滤） ==========

    private static Context sContext;

    public static void initContext(Context ctx) {
        sContext = ctx.getApplicationContext();
    }

    public static void fileLog(String msg) {
        Log.e(TAG, msg);
        if (sContext == null) return;
        try {
            File logFile = new File(sContext.getFilesDir(), "vcp_debug.log");
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            FileWriter fw = new FileWriter(logFile, true);
            fw.write(time + " " + msg + "\n");
            fw.close();
        } catch (Exception ignored) {}
    }

    // ========== 配置读取 ==========

    public static SharedPreferences getPrefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String getBaseUrl(SharedPreferences prefs) {
        return prefs.getString("baseUrl", "").replaceAll("/+$", "");
    }

    // ========== 1. 调用 AI API（非流式） ==========

    /**
     * 发送纯文本消息给 AI
     * @return AI 回复内容
     */
    public static String chatText(SharedPreferences prefs, String userText) throws Exception {
        JSONArray messages = new JSONArray();

        String systemPrompt = prefs.getString("systemPrompt", "");
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.put(new JSONObject()
                    .put("role", "system")
                    .put("content", systemPrompt));
        }

        messages.put(new JSONObject()
                .put("role", "user")
                .put("content", userText));

        return callCompletions(prefs, messages);
    }

    /**
     * 发送图片+文本消息给 AI
     * @param base64Jpeg JPEG 图片的 base64 编码
     * @param userText 用户文本
     * @return AI 回复内容
     */
    public static String chatImage(SharedPreferences prefs, String base64Jpeg, String userText) throws Exception {
        JSONArray messages = new JSONArray();

        String systemPrompt = prefs.getString("systemPrompt", "");
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.put(new JSONObject()
                    .put("role", "system")
                    .put("content", systemPrompt));
        }

        // 多模态用户消息
        JSONArray contentParts = new JSONArray();
        contentParts.put(new JSONObject()
                .put("type", "text")
                .put("text", userText));
        contentParts.put(new JSONObject()
                .put("type", "image_url")
                .put("image_url", new JSONObject()
                        .put("url", "data:image/jpeg;base64," + base64Jpeg)));

        messages.put(new JSONObject()
                .put("role", "user")
                .put("content", contentParts));

        return callCompletions(prefs, messages);
    }

    private static final int MAX_RETRIES = 2;

    private static String callCompletions(SharedPreferences prefs, JSONArray messages) throws Exception {
        String baseUrl = getBaseUrl(prefs);
        String apiKey = prefs.getString("apiKey", "");
        String model = prefs.getString("model", "");

        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
            throw new Exception("请先在 VCPMobile 设置中配置 API");
        }

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", false);

        String apiUrl = baseUrl + "/v1/chat/completions";
        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);

        Exception lastException = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                fileLog("[API] 第 " + (attempt + 1) + " 次重试...");
                Thread.sleep(3000);
            }
            fileLog("[API] 请求: " + apiUrl + " model=" + model);

            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setDoOutput(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(120000);

                conn.setFixedLengthStreamingMode(bodyBytes.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bodyBytes);
                }

                int code = conn.getResponseCode();
                fileLog("[API] 响应码: " + code);

                if (code == 200) {
                    byte[] respBytes = conn.getInputStream().readAllBytes();
                    String resp = new String(respBytes, StandardCharsets.UTF_8);
                    conn.disconnect();
                    fileLog("[API] 响应前200字符: " + resp.substring(0, Math.min(resp.length(), 200)));

                    // 检测 HTML 响应（CDN/代理拦截）
                    String trimmed = resp.trim();
                    if (trimmed.startsWith("<!") || trimmed.startsWith("<html")) {
                        lastException = new Exception("API 返回了 HTML 而非 JSON（CDN/代理拦截）");
                        fileLog("[API] CDN 返回 HTML，准备重试");
                        continue;
                    }

                    JSONObject json = new JSONObject(resp);
                    String content = json.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content");
                    fileLog("[API] AI 回复长度=" + content.length());
                    return content;
                } else if (code >= 500) {
                    byte[] errBytes = conn.getErrorStream() != null ?
                            conn.getErrorStream().readAllBytes() : new byte[0];
                    String error = new String(errBytes, StandardCharsets.UTF_8);
                    conn.disconnect();
                    fileLog("[API] 服务端错误 " + code + "，准备重试");
                    lastException = new Exception("API 错误 " + code + ": " + error.substring(0, Math.min(error.length(), 500)));
                    continue;
                } else {
                    byte[] errBytes = conn.getErrorStream() != null ?
                            conn.getErrorStream().readAllBytes() : new byte[0];
                    String error = new String(errBytes, StandardCharsets.UTF_8);
                    conn.disconnect();
                    fileLog("[API] 错误 " + code + ": " + error.substring(0, Math.min(error.length(), 200)));
                    throw new Exception("API 错误 " + code + ": " + error.substring(0, Math.min(error.length(), 500)));
                }
            } catch (java.net.SocketTimeoutException e) {
                fileLog("[API] 请求超时，准备重试");
                lastException = new Exception("API 请求超时");
                continue;
            }
        }
        throw lastException != null ? lastException : new Exception("API 调用失败（已重试 " + MAX_RETRIES + " 次）");
    }

    // ========== 2. 写入 VCPChat Agent 话题 ==========

    /**
     * 将用户消息和 AI 回复追加到 VCPChat 桌面端的 Agent 话题
     * @param userContent 用户消息内容（纯文本，截图场景传 "[图片] + presetMessage"）
     * @param aiContent AI 回复内容
     * @param topicName 话题名称（用于新建话题时显示）
     */
    public static boolean appendToAgentHistory(SharedPreferences prefs,
                                                String userContent, String aiContent,
                                                String topicName) {
        try {
            String baseUrl = getBaseUrl(prefs);
            String adminUsername = prefs.getString("adminUsername", "");
            String adminPassword = prefs.getString("adminPassword", "");
            String agentDirId = prefs.getString("agentDirId", "");

            if (baseUrl.isEmpty() || adminUsername.isEmpty() || agentDirId.isEmpty()) {
                Log.e(TAG, "缺少 adminUsername/agentDirId，跳过话题同步");
                return false;
            }

            // 生成话题 ID
            String topicId = "topic_" + System.currentTimeMillis();
            long now = System.currentTimeMillis();

            // 构建消息数组
            JSONArray messages = new JSONArray();

            // 用户消息
            JSONObject userMsg = new JSONObject();
            userMsg.put("id", "msg_" + now + "_user");
            userMsg.put("role", "user");
            userMsg.put("content", userContent);
            userMsg.put("timestamp", now);
            messages.put(userMsg);

            // AI 回复
            JSONObject aiMsg = new JSONObject();
            aiMsg.put("id", "msg_" + now + "_ai");
            aiMsg.put("role", "assistant");
            aiMsg.put("content", aiContent);
            aiMsg.put("timestamp", now + 1);
            messages.put(aiMsg);

            // 构建请求体
            JSONObject body = new JSONObject();
            body.put("agentDirId", agentDirId);
            body.put("topicId", topicId);
            body.put("topicName", topicName);
            body.put("messages", messages);

            String apiUrl = baseUrl + "/admin_api/agents/vcpchat-append-history";
            Log.e(TAG, "写入话题: " + apiUrl + " topicId=" + topicId);

            HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");

            // Basic Auth
            String credentials = Base64.encodeToString(
                    (adminUsername + ":" + adminPassword).getBytes(StandardCharsets.UTF_8),
                    Base64.NO_WRAP);
            conn.setRequestProperty("Authorization", "Basic " + credentials);

            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);

            byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bodyBytes.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                byte[] respBytes = conn.getInputStream().readAllBytes();
                String resp = new String(respBytes, StandardCharsets.UTF_8);
                conn.disconnect();
                JSONObject json = new JSONObject(resp);
                boolean success = json.optBoolean("success", false);
                int appended = json.optInt("appended", 0);
                Log.e(TAG, "话题写入成功: appended=" + appended);
                return success;
            } else {
                byte[] errBytes = conn.getErrorStream() != null ?
                        conn.getErrorStream().readAllBytes() : new byte[0];
                String error = new String(errBytes, StandardCharsets.UTF_8);
                conn.disconnect();
                Log.e(TAG, "话题写入失败 " + code + ": " + error);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "话题写入异常", e);
            return false;
        }
    }
}
