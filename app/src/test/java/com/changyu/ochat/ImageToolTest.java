package com.changyu.ochat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.StringWriter;
import java.util.Collections;
import static org.junit.Assert.*;

public class ImageToolTest {
    @Test public void defaultsToAiModeAndNormalizesProtocolSizes() throws Exception {
        ImageToolConfig defaultConfig = new ImageToolConfig("", "p1", "OpenAI", ChatApiClient.CHAT_COMPLETIONS, "gpt-image-1", "16:9");
        assertTrue(defaultConfig.isAiMode());
        assertEquals(ImageToolConfig.MODE_AI, defaultConfig.mode);
        assertEquals("1536x1024", defaultConfig.size);

        ImageToolConfig directGemini = new ImageToolConfig(ImageToolConfig.MODE_DIRECT, "p2", "Gemini", ChatApiClient.GEMINI, "gemini-3.1-flash-image", "1024x1536");
        assertFalse(directGemini.isAiMode());
        assertEquals(ImageToolConfig.MODE_DIRECT, directGemini.mode);
        assertEquals("9:16", directGemini.size);

        ImageToolConfig restored = ImageToolConfig.fromJson(directGemini.toJson());
        assertNotNull(restored);
        assertEquals(directGemini.mode, restored.mode);
        assertEquals(directGemini.providerId, restored.providerId);
        assertEquals(directGemini.model, restored.model);
        assertEquals(directGemini.size, restored.size);
    }

    @Test public void validatesCompatibleProvidersAndRequiredFields() throws Exception {
        JSONObject anthropic = new JSONObject().put("id", "c1").put("name", "Claude")
                .put("protocol", ChatApiClient.ANTHROPIC).put("api", "https://api.anthropic.com/v1").put("apiKey", "sk-ant");
        JSONObject openai = new JSONObject().put("id", "o1").put("name", "OpenAI")
                .put("protocol", ChatApiClient.RESPONSES).put("api", "https://api.openai.com/v1").put("apiKey", "sk-test");
        JSONObject gemini = new JSONObject().put("id", "g1").put("name", "Gemini")
                .put("protocol", ChatApiClient.GEMINI).put("api", "https://generativelanguage.googleapis.com").put("apiKey", "");

        assertFalse(ImageToolConfig.supportsImageGeneration(anthropic));
        assertTrue(ImageToolConfig.supportsImageGeneration(openai));
        assertTrue(ImageToolConfig.supportsImageGeneration(gemini));
        assertEquals(2, ImageToolConfig.compatibleProviders(new JSONArray().put(anthropic).put(openai).put(gemini)).size());

        ImageToolConfig valid = new ImageToolConfig(ImageToolConfig.MODE_AI, "o1", "OpenAI", ChatApiClient.RESPONSES, "gpt-image-1", "");
        assertEquals("", valid.validate(openai));

        ImageToolConfig missingKey = new ImageToolConfig(ImageToolConfig.MODE_DIRECT, "g1", "Gemini", ChatApiClient.GEMINI, "gemini-3.1-flash-image", "");
        assertTrue(missingKey.validate(gemini).contains("API Key"));

        ImageToolConfig emptyModel = new ImageToolConfig(ImageToolConfig.MODE_DIRECT, "o1", "OpenAI", ChatApiClient.RESPONSES, "", "");
        assertTrue(emptyModel.validate(openai).contains("模型"));
    }

    @Test public void preparesHistoryWithImageToolInstruction() throws Exception {
        JSONArray withoutSystem = new JSONArray().put(new JSONObject().put("role", "user").put("content", "画一只橘猫"));
        JSONArray prepared = ImageToolPrompt.prepareHistory(withoutSystem, "gpt-image-1", "");
        assertEquals(2, prepared.length());
        assertEquals("system", prepared.getJSONObject(0).getString("role"));
        assertTrue(prepared.getJSONObject(0).getString("content").contains("gpt-image-1"));
        assertEquals("画一只橘猫", prepared.getJSONObject(1).getString("content"));

        JSONArray withSystem = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", "你擅长电影摄影构图"))
                .put(new JSONObject().put("role", "user").put("content", "画一座雪山"));
        JSONArray merged = ImageToolPrompt.prepareHistory(withSystem, "gemini-3.1-flash-image", "16:9");
        assertEquals(2, merged.length());
        String systemText = merged.getJSONObject(0).getString("content");
        assertTrue(systemText.contains("你擅长电影摄影构图"));
        assertTrue(systemText.contains("16:9"));
    }

    @Test public void parsesStructuredFencedEmbeddedAndFallbackPrompts() {
        ImageToolPrompt.Plan direct = ImageToolPrompt.parse(
                "{\"prompt\":\"A cyberpunk orange tabby cat eating ramen in rainy Tokyo\",\"aspect_ratio\":\"16:9\",\"reply\":\"已为你生成雨夜拉面橘猫。\"}",
                "画猫");
        assertTrue(direct.structured);
        assertEquals("A cyberpunk orange tabby cat eating ramen in rainy Tokyo", direct.prompt);
        assertEquals("16:9", direct.aspectRatio);
        assertEquals("已为你生成雨夜拉面橘猫。", direct.reply);

        ImageToolPrompt.Plan fenced = ImageToolPrompt.parse(
                "好的，这是规划结果：\n```json\n{\"prompt\":\"Watercolor portrait of a girl with \\\"blue\\\" eyes {soft light}\",\"aspect_ratio\":\"3:4\",\"reply\":\"已生成水彩肖像。\"}\n```",
                "画肖像");
        assertTrue(fenced.structured);
        assertEquals("Watercolor portrait of a girl with \"blue\" eyes {soft light}", fenced.prompt);
        assertEquals("3:4", fenced.aspectRatio);
        assertEquals("已生成水彩肖像。", fenced.reply);

        ImageToolPrompt.Plan plain = ImageToolPrompt.parse("提示词：金色夕阳下的海边灯塔，胶片质感", "灯塔");
        assertFalse(plain.structured);
        assertEquals("金色夕阳下的海边灯塔，胶片质感", plain.prompt);
        assertEquals("", plain.reply);

        ImageToolPrompt.Plan empty = ImageToolPrompt.parse("   ", "一只小狗");
        assertEquals("一只小狗", empty.prompt);
    }

    @Test public void exportsGeneratedImagesInMarkdownAndHistoryNote() throws Exception {
        JSONArray images = new JSONArray().put(new JSONObject()
                .put("jobId", "00000000-0000-0000-0000-000000000001")
                .put("mime", "image/png")
                .put("model", "gpt-image-1")
                .put("mode", ImageToolConfig.MODE_AI)
                .put("prompt", "A calm misty lake at sunrise"));

        String note = ImageToolPrompt.historyContextNote(images);
        assertTrue(note.contains("gpt-image-1"));
        assertTrue(note.contains("A calm misty lake at sunrise"));

        JSONObject session = new JSONObject()
                .put("id", "s1")
                .put("title", "生图测试")
                .put("activeBranchId", "")
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("id", "u1").put("user", true).put("text", "画个清晨湖泊"))
                        .put(new JSONObject().put("id", "a1").put("user", false).put("text", "已生成清晨薄雾湖景。")
                                .put("modelName", "gpt-4o").put("generatedImages", images)));
        StringWriter writer = new StringWriter();
        ConversationMarkdown.write(writer, session, "", "");
        String md = writer.toString();
        assertTrue(md.contains("**生成图片**"));
        assertTrue(md.contains("gpt-image-1（AI 编写提示词）"));
        assertTrue(md.contains("A calm misty lake at sunrise"));
    }

    @Test public void composerDraftPreservesImageToolStateWithoutDirtyingEmptyDrafts() throws Exception {
        assertNull(ComposerDraft.capture("", 0, 0, Collections.emptyList(), new JSONArray(), true));
        JSONObject captured = ComposerDraft.capture("雨夜霓虹街景", 6, 6, Collections.emptyList(), new JSONArray(), true);
        assertNotNull(captured);
        ComposerDraft restored = ComposerDraft.read(captured);
        assertTrue(restored.imageTool);
        assertEquals("雨夜霓虹街景", restored.text);
    }
}
