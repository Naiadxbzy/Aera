package com.changyu.ochat;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Configuration for in-chat image generation tool (AI-authored prompt by default, or direct prompt). */
final class ImageToolConfig {
    static final String MODE_AI = "ai";
    static final String MODE_DIRECT = "direct";
    static final String PREF_MODE = "imageToolMode";
    static final String PREF_PROVIDER = "imageToolProviderId";
    static final String PREF_SIZE = "imageToolSize";

    final String mode;
    final String providerId;
    final String providerName;
    final String protocol;
    final String model;
    final String size;

    ImageToolConfig(String mode, String providerId, String providerName, String protocol, String model, String size) {
        this.mode = normalizeMode(mode);
        this.providerId = providerId == null ? "" : providerId.trim();
        this.providerName = providerName == null ? "" : providerName.trim();
        this.protocol = protocol == null || protocol.trim().isEmpty() ? ChatApiClient.CHAT_COMPLETIONS : protocol.trim();
        this.model = model == null ? "" : model.trim();
        this.size = normalizeSize(size, this.protocol);
    }

    static String normalizeMode(String mode) {
        return MODE_DIRECT.equals(mode) ? MODE_DIRECT : MODE_AI;
    }

    boolean isAiMode() {
        return !MODE_DIRECT.equals(mode);
    }

    String modeLabel() {
        return isAiMode() ? "AI 写提示词" : "自写提示词";
    }

    String modeShortSummary() {
        return isAiMode() ? "AI 分析并编写提示词" : "自己写提示词直接生图";
    }

    static boolean supportsImageGeneration(JSONObject provider) {
        if (provider == null) return false;
        String protocol = provider.optString("protocol", ChatApiClient.CHAT_COMPLETIONS);
        return ChatApiClient.CHAT_COMPLETIONS.equals(protocol)
                || ChatApiClient.RESPONSES.equals(protocol)
                || ChatApiClient.GEMINI.equals(protocol);
    }

    static List<JSONObject> compatibleProviders(SharedPreferences prefs) {
        return compatibleProviders(ModelStore.read(prefs));
    }

    static List<JSONObject> compatibleProviders(JSONArray entries) {
        List<JSONObject> result = new ArrayList<>();
        if (entries == null) return result;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject provider = entries.optJSONObject(i);
            if (supportsImageGeneration(provider)) result.add(provider);
        }
        return result;
    }

    static JSONObject findProvider(JSONArray entries, String id) {
        if (entries == null || id == null || id.isEmpty()) return null;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject provider = entries.optJSONObject(i);
            if (provider != null && id.equals(provider.optString("id"))) return provider;
        }
        return null;
    }

    static String modelPrefKey(String providerId) {
        return "media.model." + providerId + ".image";
    }

    static String defaultModel(String protocol) {
        return ChatApiClient.GEMINI.equals(protocol) ? "gemini-3.1-flash-image" : "gpt-image-1";
    }

    static String savedModel(SharedPreferences prefs, JSONObject provider) {
        if (provider == null) return "";
        String id = provider.optString("id");
        String saved = id.isEmpty() ? "" : prefs.getString(modelPrefKey(id), "").trim();
        if (!saved.isEmpty()) return saved;
        return defaultModel(provider.optString("protocol", ChatApiClient.CHAT_COMPLETIONS));
    }

    static List<String> modelSuggestions(SharedPreferences prefs, JSONObject provider) {
        LinkedHashSet<String> choices = new LinkedHashSet<>();
        if (provider != null) {
            String saved = prefs.getString(modelPrefKey(provider.optString("id")), "").trim();
            if (!saved.isEmpty()) choices.add(saved);
            String suggestion = defaultModel(provider.optString("protocol", ChatApiClient.CHAT_COMPLETIONS));
            if (!suggestion.isEmpty()) choices.add(suggestion);
            JSONArray configured = provider.optJSONArray("models");
            if (configured != null) {
                for (int i = 0; i < configured.length(); i++) {
                    JSONObject item = configured.optJSONObject(i);
                    String id = item == null ? "" : item.optString("id").trim();
                    if (!id.isEmpty()) choices.add(id);
                }
            }
        }
        return new ArrayList<>(choices);
    }

    static String[] sizeValues(String protocol) {
        return ChatApiClient.GEMINI.equals(protocol)
                ? new String[]{"", "1:1", "16:9", "9:16", "4:3", "3:4"}
                : new String[]{"", "1024x1024", "1536x1024", "1024x1536"};
    }

    static String[] sizeLabels(String protocol) {
        String[] values = sizeValues(protocol);
        String[] labels = values.clone();
        labels[0] = "模型默认";
        return labels;
    }

    static String normalizeSize(String raw, String protocol) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.isEmpty() || "auto".equalsIgnoreCase(value)) return "";
        String[] valid = sizeValues(protocol);
        for (String item : valid) {
            if (item.equals(value)) return item;
        }
        return mapAspectRatioForProtocol(value, protocol);
    }

    /** Maps a suggested aspect ratio or dimension string to a valid size value for the target protocol. */
    static String mapAspectRatioForProtocol(String raw, String protocol) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase(Locale.ROOT).replace("×", "x").replace("：", ":");
        if (value.isEmpty() || "auto".equals(value) || "default".equals(value)) return "";
        boolean gemini = ChatApiClient.GEMINI.equals(protocol);
        if (gemini) {
            switch (value) {
                case "1:1":
                case "1024x1024":
                case "square":
                    return "1:1";
                case "16:9":
                case "1536x1024":
                case "landscape":
                case "wide":
                    return "16:9";
                case "9:16":
                case "1024x1536":
                case "portrait":
                case "vertical":
                    return "9:16";
                case "4:3":
                    return "4:3";
                case "3:4":
                    return "3:4";
                default:
                    if (value.matches("[1-9][0-9]?:[1-9][0-9]?")) {
                        return Arrays.asList(sizeValues(protocol)).contains(value) ? value : "";
                    }
                    return "";
            }
        }
        switch (value) {
            case "1:1":
            case "1024x1024":
            case "square":
                return "1024x1024";
            case "16:9":
            case "4:3":
            case "3:2":
            case "1536x1024":
            case "landscape":
            case "wide":
                return "1536x1024";
            case "9:16":
            case "3:4":
            case "2:3":
            case "1024x1536":
            case "portrait":
            case "vertical":
                return "1024x1536";
            default:
                return "";
        }
    }

    static ImageToolConfig resolve(SharedPreferences prefs, String preferredProviderId) {
        String mode = normalizeMode(prefs.getString(PREF_MODE, MODE_AI));
        JSONArray all = ModelStore.read(prefs);
        List<JSONObject> compatible = compatibleProviders(all);
        JSONObject provider = findProvider(all, prefs.getString(PREF_PROVIDER, ""));
        if (!supportsImageGeneration(provider)) provider = null;
        if (provider == null && preferredProviderId != null && !preferredProviderId.isEmpty()) {
            JSONObject preferred = findProvider(all, preferredProviderId);
            if (supportsImageGeneration(preferred)) provider = preferred;
        }
        if (provider == null && !compatible.isEmpty()) provider = compatible.get(0);
        if (provider == null) {
            return new ImageToolConfig(mode, "", "", ChatApiClient.CHAT_COMPLETIONS, "", "");
        }
        String protocol = provider.optString("protocol", ChatApiClient.CHAT_COMPLETIONS);
        String model = savedModel(prefs, provider);
        String size = normalizeSize(prefs.getString(PREF_SIZE, ""), protocol);
        return new ImageToolConfig(mode, provider.optString("id"), provider.optString("name", "供应商"),
                protocol, model, size);
    }

    static void save(SharedPreferences prefs, String mode, String providerId, String model, String size) {
        SharedPreferences.Editor editor = prefs.edit()
                .putString(PREF_MODE, normalizeMode(mode))
                .putString(PREF_PROVIDER, providerId == null ? "" : providerId.trim())
                .putString(PREF_SIZE, size == null ? "" : size.trim());
        if (providerId != null && !providerId.trim().isEmpty() && model != null && !model.trim().isEmpty()) {
            editor.putString(modelPrefKey(providerId.trim()), model.trim());
        }
        editor.apply();
    }

    String validate(JSONObject provider) {
        if (provider == null || providerId.isEmpty()) {
            return "未找到支持图片生成的供应商。请先配置 OpenAI / 兼容接口或 Gemini 供应商。";
        }
        if (!supportsImageGeneration(provider)) {
            return "当前所选生图供应商协议不支持图片生成，请改选 OpenAI / 兼容接口或 Gemini 供应商。";
        }
        String api = provider.optString("api").trim();
        if (!api.startsWith("http://") && !api.startsWith("https://")) {
            return "生图供应商的 API 地址无效，请在供应商设置中检查。";
        }
        if (provider.optString("apiKey").trim().isEmpty()) {
            return "请填写生图供应商「" + provider.optString("name", "供应商") + "」的 API Key。";
        }
        if (model.isEmpty()) {
            return "请选择或输入图片生成模型 ID。";
        }
        return "";
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("mode", mode)
                .put("providerId", providerId)
                .put("providerName", providerName)
                .put("protocol", protocol)
                .put("model", model)
                .put("size", size);
    }

    static ImageToolConfig fromJson(JSONObject value) {
        if (value == null) return null;
        return new ImageToolConfig(
                value.optString("mode", MODE_AI),
                value.optString("providerId", ""),
                value.optString("providerName", ""),
                value.optString("protocol", ChatApiClient.CHAT_COMPLETIONS),
                value.optString("model", ""),
                value.optString("size", "")
        );
    }
}
