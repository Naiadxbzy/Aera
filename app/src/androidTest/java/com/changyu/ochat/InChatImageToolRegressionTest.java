package com.changyu.ochat;

import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class InChatImageToolRegressionTest {
    private static final String PNG_B64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAOxPo9sAAAAASUVORK5CYII=";

    @Test public void aiModeWritesPromptThenGeneratesInlineImageAndDirectModeSkipsChatLlm() throws Exception {
        try (RegressionPreferences state = new RegressionPreferences(); MockGateway gateway = new MockGateway()) {
            JSONObject provider = new JSONObject()
                    .put("id", "image-fixture")
                    .put("name", "Image Fixture")
                    .put("api", gateway.base())
                    .put("apiKey", "fixture-secret")
                    .put("protocol", ChatApiClient.CHAT_COMPLETIONS)
                    .put("selectedModelId", "gpt-4o-mini")
                    .put("models", new JSONArray().put(ModelStore.model("gpt-4o-mini")));
            ModelStore.activate(state.prefs, new JSONArray().put(provider), provider);
            state.prefs.edit()
                    .putBoolean("webSearchEnabled", false)
                    .putBoolean(BackgroundProtection.PREF, false)
                    .putBoolean("askedGenerationNotifications", true)
                    .commit();

            MediaGenerationStore store = new MediaGenerationStore(state.context);
            try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(new Intent(state.context, MainActivity.class))) {
                // 1. Default AI prompt mode
                screen.onActivity(activity -> {
                    View root = activity.getWindow().getDecorView();
                    View toggle = described(root, "生成图片工具");
                    assertNotNull(toggle);
                    assertTrue(toggle.performClick());
                    View bar = root.findViewWithTag("image-tool-bar");
                    assertNotNull(bar);
                    assertEquals(View.VISIBLE, bar.getVisibility());
                    TextView summary = (TextView) described(root, "配置生成图片工具");
                    assertNotNull(summary);
                    assertTrue(summary.getText().toString().contains("AI 写提示词"));
                    ((EditText) described(root, "消息输入框")).setText("画一只雨夜吃拉面的橘猫");
                    assertTrue(described(root, "发送消息").performClick());
                });

                awaitIdle();
                SessionRepository.get(state.prefs).flush();

                assertEquals(1, gateway.chatBodies.size());
                assertEquals(1, gateway.imageBodies.size());
                JSONObject imageReq1 = new JSONObject(gateway.imageBodies.get(0));
                assertEquals("gpt-image-1", imageReq1.getString("model"));
                assertEquals("A cinematic orange cat eating ramen in rainy Tokyo", imageReq1.getString("prompt"));
                assertEquals("1536x1024", imageReq1.getString("size"));

                String sessionId = state.prefs.getString("currentSessionId", "");
                JSONObject session = MessageEdits.findSession(SessionRepository.get(state.prefs).read(), sessionId);
                assertNotNull(session);
                JSONArray messages = session.getJSONArray("messages");
                JSONObject aiReply = messages.getJSONObject(messages.length() - 1);
                assertEquals("已为你生成雨夜拉面橘猫。", aiReply.getString("text"));
                JSONArray generated1 = aiReply.getJSONArray("generatedImages");
                assertEquals(1, generated1.length());
                String jobId1 = generated1.getJSONObject(0).getString("jobId");
                assertTrue(store.resultFile(jobId1).isFile());
                assertEquals(ImageToolConfig.MODE_AI, generated1.getJSONObject(0).getString("mode"));

                screen.onActivity(activity -> {
                    View root = activity.getWindow().getDecorView();
                    assertNotNull(root.findViewWithTag("generated-image-card"));
                });

                // 2. Direct prompt mode (skips chat LLM completely)
                ImageToolConfig.save(state.prefs, ImageToolConfig.MODE_DIRECT, "image-fixture", "gpt-image-1", "1024x1024");
                screen.onActivity(activity -> {
                    View root = activity.getWindow().getDecorView();
                    TextView summary = (TextView) described(root, "配置生成图片工具");
                    assertNotNull(summary);
                    assertTrue(summary.getText().toString().contains("自写提示词"));
                    EditText input = (EditText) described(root, "消息输入框");
                    assertNotNull(input);
                    input.setText("minimalist watercolor mountain sunrise");
                    View send = described(root, "发送消息");
                    assertNotNull(send);
                    assertTrue(send.performClick());
                });

                awaitIdle();
                SessionRepository.get(state.prefs).flush();

                assertEquals("Direct mode must not call chat LLM", 1, gateway.chatBodies.size());
                assertEquals(2, gateway.imageBodies.size());
                JSONObject imageReq2 = new JSONObject(gateway.imageBodies.get(1));
                assertEquals("minimalist watercolor mountain sunrise", imageReq2.getString("prompt"));
                assertEquals("1024x1024", imageReq2.getString("size"));

                JSONObject session2 = MessageEdits.findSession(SessionRepository.get(state.prefs).read(), sessionId);
                assertNotNull(session2);
                JSONArray messages2 = session2.getJSONArray("messages");
                JSONObject directReply = messages2.getJSONObject(messages2.length() - 1);
                JSONArray generated2 = directReply.getJSONArray("generatedImages");
                assertEquals(1, generated2.length());
                String jobId2 = generated2.getJSONObject(0).getString("jobId");
                assertTrue(store.resultFile(jobId2).isFile());
                assertEquals(ImageToolConfig.MODE_DIRECT, generated2.getJSONObject(0).getString("mode"));

                store.delete(jobId1);
                store.delete(jobId2);
            }
        }
    }

    private static void awaitIdle() {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (ChatGenerationService.activeCount() > 0 && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(30);
        }
        assertEquals(0, ChatGenerationService.activeCount());
    }

    private static View described(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View hit = described(group.getChildAt(i), description);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static final class MockGateway implements AutoCloseable {
        final ServerSocket server;
        final List<String> chatBodies = new CopyOnWriteArrayList<>();
        final List<String> imageBodies = new CopyOnWriteArrayList<>();

        MockGateway() throws Exception {
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            Thread thread = new Thread(this::serve, "in-chat-image-mock");
            thread.setDaemon(true);
            thread.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getLocalPort() + "/v1";
        }

        private void serve() {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    InputStream in = new BufferedInputStream(socket.getInputStream());
                    ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
                    int state = 0, b;
                    while ((b = in.read()) != -1) {
                        headerBytes.write(b);
                        if ((state == 0 || state == 2) && b == '\r') state++;
                        else if ((state == 1 || state == 3) && b == '\n') state++;
                        else state = 0;
                        if (state == 4) break;
                    }
                    String headers = new String(headerBytes.toByteArray(), StandardCharsets.US_ASCII);
                    if (headers.isEmpty()) continue;
                    String requestLine = headers.split("\r\n")[0];
                    int contentLength = 0;
                    for (String line : headers.split("\r\n")) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            contentLength = Integer.parseInt(line.substring(15).trim());
                        }
                    }
                    byte[] bodyBytes = new byte[contentLength];
                    int off = 0;
                    while (off < contentLength) {
                        int n = in.read(bodyBytes, off, contentLength - off);
                        if (n < 0) break;
                        off += n;
                    }
                    String body = new String(bodyBytes, StandardCharsets.UTF_8);

                    if (requestLine.contains("/chat/completions")) {
                        chatBodies.add(body);
                        String plan = new JSONObject()
                                .put("prompt", "A cinematic orange cat eating ramen in rainy Tokyo")
                                .put("aspect_ratio", "16:9")
                                .put("reply", "已为你生成雨夜拉面橘猫。")
                                .toString();
                        String chunk = new JSONObject().put("choices", new JSONArray().put(
                                new JSONObject().put("delta", new JSONObject().put("content", plan))
                                        .put("finish_reason", "stop"))).toString();
                        byte[] sse = ("data: " + chunk + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                        String resp = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: "
                                + sse.length + "\r\nConnection: close\r\n\r\n";
                        socket.getOutputStream().write(resp.getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().write(sse);
                        socket.getOutputStream().flush();
                    } else if (requestLine.contains("/images/generations")) {
                        imageBodies.add(body);
                        byte[] json = new JSONObject().put("data", new JSONArray().put(
                                new JSONObject().put("b64_json", PNG_B64))).toString().getBytes(StandardCharsets.UTF_8);
                        String resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                + json.length + "\r\nConnection: close\r\n\r\n";
                        socket.getOutputStream().write(resp.getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().write(json);
                        socket.getOutputStream().flush();
                    }
                } catch (Exception ignored) {
                }
            }
        }

        @Override public void close() throws Exception {
            server.close();
        }
    }
}
