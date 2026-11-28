package com.changyu.ochat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Prompt-planning instruction and response parser for AI-driven image tool calls. */
final class ImageToolPrompt {
    private static final Pattern FENCE = Pattern.compile("```(?:json|JSON)?\\s*([\\s\\S]*?)```");
    private static final Pattern PROMPT_PREFIX = Pattern.compile("^(?:(?:image\\s*)?prompt|提示词|生图提示词)\\s*[:：]\\s*", Pattern.CASE_INSENSITIVE);

    static final class Plan {
        final String prompt;
        final String aspectRatio;
        final String reply;
        final boolean structured;

        Plan(String prompt, String aspectRatio, String reply, boolean structured) {
            this.prompt = prompt;
            this.aspectRatio = aspectRatio;
            this.reply = reply;
            this.structured = structured;
        }
    }

    static JSONArray prepareHistory(JSONArray history, String targetModel, String userSize) throws JSONException {
        JSONArray copy = new JSONArray(history.toString());
        String instruction = buildInstruction(targetModel, userSize);
        if (copy.length() > 0 && "system".equals(copy.optJSONObject(0).optString("role"))) {
            JSONObject system = copy.getJSONObject(0);
            String existing = system.optString("content", "").trim();
            system.put("content", existing.isEmpty() ? instruction : existing + "\n\n" + instruction);
        } else {
            JSONArray out = new JSONArray();
            out.put(new JSONObject().put("role", "system").put("content", instruction));
            for (int i = 0; i < copy.length(); i++) out.put(copy.get(i));
            copy = out;
        }
        return copy;
    }

    private static String buildInstruction(String targetModel, String userSize) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户已开启「生成图片」工具，即将调用客户端图片生成模型");
        if (targetModel != null && !targetModel.trim().isEmpty()) {
            sb.append("（").append(targetModel.trim()).append("）");
        }
        sb.append("。\n");
        sb.append("请你先分析用户的最新请求以及上下文（包括引用或附件内容），提炼并编写一段具体、清晰、画面感强、可直接用于生图模型的提示词。\n");
        sb.append("要求：\n");
        sb.append("1. 只输出一个 JSON 对象，不要在 JSON 外附加多余说明或解释。\n");
        sb.append("2. JSON 字段格式如下：\n");
        sb.append("{\"prompt\":\"完整独立的画面描述（主体、构图、光线、风格、色彩、细节、氛围）\",\"aspect_ratio\":\"");
        if (userSize != null && !userSize.trim().isEmpty()) {
            sb.append("用户已固定画幅为 ").append(userSize.trim()).append("，此处留空字符串即可");
        } else {
            sb.append("仅当用户明确要求画幅/横竖屏时填写 1:1 / 16:9 / 9:16 / 4:3 / 3:4，否则留空字符串");
        }
        sb.append("\",\"reply\":\"给用户的一句简短中文说明\"}\n");
        sb.append("3. prompt 必须是独立完整的画面描述，不能包含“按上面说的”“延续刚才”等指代词；若用户要求图片中出现特定文字，请原样保留该文字。\n");
        sb.append("4. 严禁输出 Markdown 以外的无关前后缀。");
        return sb.toString();
    }

    static Plan parse(String raw, String fallbackRequest) {
        String safeFallback = fallbackRequest == null ? "" : fallbackRequest.trim();
        if (raw == null || raw.trim().isEmpty()) {
            return new Plan(safeFallback, "", "", false);
        }
        String trimmed = raw.trim();
        Plan parsed = tryJsonObject(trimmed);
        if (parsed != null) return parsed;

        Matcher fenced = FENCE.matcher(trimmed);
        while (fenced.find()) {
            String block = fenced.group(1);
            parsed = tryJsonObject(block == null ? "" : block.trim());
            if (parsed != null) return parsed;
        }

        String embedded = extractJsonObject(trimmed);
        if (embedded != null) {
            parsed = tryJsonObject(embedded);
            if (parsed != null) return parsed;
        }

        String cleaned = stripFences(trimmed);
        cleaned = PROMPT_PREFIX.matcher(cleaned).replaceFirst("").trim();
        if (cleaned.length() >= 2 && ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
                || (cleaned.startsWith("“") && cleaned.endsWith("”")))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        if (cleaned.isEmpty()) cleaned = safeFallback;
        return new Plan(cleaned, "", "", false);
    }

    private static Plan tryJsonObject(String candidate) {
        if (candidate == null || !candidate.startsWith("{") || !candidate.endsWith("}")) return null;
        try {
            JSONObject obj = new JSONObject(candidate);
            String prompt = obj.optString("prompt", "").trim();
            if (prompt.isEmpty()) prompt = obj.optString("image_prompt", "").trim();
            if (prompt.isEmpty()) return null;
            String ratio = obj.optString("aspect_ratio", obj.optString("size", "")).trim();
            String reply = obj.optString("reply", obj.optString("summary", "")).trim();
            return new Plan(prompt, ratio, reply, true);
        } catch (JSONException ignored) {
            return null;
        }
    }

    private static String extractJsonObject(String text) {
        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                if (depth > 0) {
                    depth--;
                    if (depth == 0) {
                        String slice = text.substring(start, i + 1);
                        if (slice.contains("\"prompt\"") || slice.contains("\"image_prompt\"")) {
                            return slice;
                        }
                        start = -1;
                    }
                }
            }
        }
        return null;
    }

    private static String stripFences(String text) {
        String out = text.trim();
        if (out.startsWith("```")) {
            int nl = out.indexOf('\n');
            if (nl >= 0) out = out.substring(nl + 1);
            if (out.endsWith("```")) out = out.substring(0, out.length() - 3);
        }
        return out.trim();
    }

    /** Concise context summary appended to assistant history turns so follow-up image edits stay coherent. */
    static String historyContextNote(JSONArray generatedImages) {
        if (generatedImages == null || generatedImages.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < generatedImages.length(); i++) {
            JSONObject image = generatedImages.optJSONObject(i);
            if (image == null) continue;
            if (sb.length() > 0) sb.append('\n');
            String model = image.optString("model", "");
            String prompt = image.optString("prompt", "").trim();
            sb.append("[已生成图片");
            if (!model.isEmpty()) sb.append("，模型：").append(model);
            if (!prompt.isEmpty()) {
                if (prompt.length() > 600) prompt = prompt.substring(0, 600) + "…";
                sb.append("，提示词：").append(prompt);
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private ImageToolPrompt() {}
}
