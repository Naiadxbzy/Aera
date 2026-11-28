package com.changyu.ochat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class GeminiMediaClientTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private MediaFixtureServer server;
    private String base;
    private File out;
    private MediaHttp http;
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAOxPo9sAAAAASUVORK5CYII=");
    private static final byte[] MP4_HEADER = {0,0,0,24,102,116,121,112,105,115,111,109};

    @Before public void setup() throws Exception {
        server = new MediaFixtureServer();
        base = server.base();
        http = new MediaHttp(base, "fixture-key", ChatApiClient.GEMINI, new ChatApiClient.CancellationHandle());
        out = new File(folder.getRoot(), "output");
    }
    @After public void close() throws Exception { if (server != null) server.close(); }

    private static void send(MediaFixtureServer.Exchange e, byte[] bytes) throws IOException {
        e.respond(200, bytes);
    }

    private JSONObject reply(JSONObject block) throws Exception {
        JSONObject thought = new JSONObject().put("type", "thought").put("content", new JSONArray().put(
                new JSONObject().put("type", "image").put("data", "invalid")));
        JSONObject output = new JSONObject().put("type", "model_output").put("content", new JSONArray().put(block));
        return new JSONObject().put("status", "completed").put("id", "job-1").put("steps", new JSONArray().put(thought).put(output));
    }

    @Test public void imageRequestUsesModelOutputAndActualBytes() throws Exception {
        byte[] response = reply(new JSONObject().put("type","image").put("mime_type","image/png")
                .put("uri", JSONObject.NULL).put("data", Base64.getEncoder().encodeToString(PNG)))
                .put("error", JSONObject.NULL).toString().getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> body = new AtomicReference<>(), apiKey = new AtomicReference<>();
        server.createContext("/v1beta/interactions", exchange -> {
            body.set(new String(exchange.body, StandardCharsets.UTF_8));
            apiKey.set(exchange.headers.get("x-goog-api-key"));
            send(exchange, response);
        });
        String mime = GeminiMediaClient.run(http,base,"models/gemini-3.1-flash-image","一座桥",false,"1024x1024",0,"",out,(id, label)->{});
        assertEquals("image/png",mime);
        assertArrayEquals(PNG, Files.readAllBytes(out.toPath()));
        JSONObject request = new JSONObject(body.get());
        assertEquals("fixture-key",apiKey.get());
        assertEquals("一座桥",request.getString("input"));
        assertEquals("gemini-3.1-flash-image",request.getString("model"));
        assertEquals("1:1", request.getJSONObject("response_format").getString("aspect_ratio"));
        assertFalse(request.getJSONObject("response_format").has("duration"));
    }

    @Test public void videoCanResumeDownloadAfterDisconnectWithoutCreatingAnotherJob() throws Exception {
        byte[] response = reply(new JSONObject().put("type","video").put("uri",base + "/v1beta/files/clip:download?alt=media"))
                .toString().getBytes(StandardCharsets.UTF_8);
        AtomicInteger submissions = new AtomicInteger(), downloads = new AtomicInteger();
        AtomicReference<String> request = new AtomicReference<>(), query = new AtomicReference<>();
        server.createContext("/v1beta/interactions", e -> {
            submissions.incrementAndGet();
            request.set(new String(e.body, StandardCharsets.UTF_8));
            send(e, response);
        });
        server.createContext("/v1beta/files/clip", e -> send(e, "{\"state\":\"ACTIVE\"}".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/v1beta/files/clip:download", e -> {
            query.set(e.path.substring(e.path.indexOf('?') + 1));
            if (downloads.incrementAndGet()==1) {e.respond(503,new byte[0]);} else send(e, MP4_HEADER);
        });
        List<String> ids = new ArrayList<>();
        try {
            GeminiMediaClient.run(http,base,"gemini-omni-1.2-flash","海上日出",true,"16:9",6,"",out,(id,label)->ids.add(id));
            fail("first download should fail");
        } catch (IOException expected) { assertFalse(out.exists()); }
        assertTrue(ids.containsAll(Arrays.asList("interactions/job-1","files/clip")));
        String mime = GeminiMediaClient.run(http,base,"gemini-omni-1.2-flash","海上日出",true,"16:9",6,
                ids.get(ids.size()-1),out,(id,label)->{});
        assertEquals(1,submissions.get());
        assertEquals(2,downloads.get());
        assertEquals("video/mp4",mime);
        assertEquals("alt=media",query.get());
        assertArrayEquals(MP4_HEADER,Files.readAllBytes(out.toPath()));
        JSONObject format = new JSONObject(request.get()).getJSONObject("response_format");
        assertEquals("6s",format.getString("duration"));
        assertEquals("uri",format.getString("delivery"));
    }

    @Test public void rejectsUnexpectedFileAuthorityAndTraversal() throws Exception {
        for (String bad : new String[]{"files/..","files/a/b","https://different.invalid/v1beta/files/key",
                base + "/other/files/key","files/%2f"}) {
            try { GeminiMediaClient.fileName(base + "/v1beta",bad); fail(bad); } catch (IOException expected) {}
        }
        assertEquals("files/name-2",GeminiMediaClient.fileName(base + "/v1beta","files/name-2"));
        assertEquals(base + "/v1beta",GeminiMediaClient.apiRoot(base + "/v1beta/models/example:generateContent?alt=sse"));
    }
}
