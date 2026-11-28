package com.changyu.ochat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class OpenAiMediaClientTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAOxPo9sAAAAASUVORK5CYII=");
    private static final byte[] VIDEO = {0,0,0,24,102,116,121,112,105,115,111,109};

    private static JSONObject provider(String root) throws Exception {
        return new JSONObject().put("api",root + "/v1/chat/completions").put("apiKey","fixture-secret");
    }
    private static byte[] json(JSONObject obj) { return obj.toString().getBytes(StandardCharsets.UTF_8); }

    @Test public void acceptsInlineImageAndSubmitsOnlyOnce() throws Exception {
        try (MediaFixtureServer api = new MediaFixtureServer()) {
            AtomicReference<String> posted = new AtomicReference<>(), auth = new AtomicReference<>();
            api.createContext("/v1/images/generations", e -> {
                auth.set(e.headers.get("Authorization"));
                posted.set(new String(e.body, StandardCharsets.UTF_8));
                e.respond(200,json(new JSONObject().put("data",new JSONArray().put(new JSONObject()
                        .put("b64_json",Base64.getEncoder().encodeToString(PNG))))));
            });
            File output=new File(files.getRoot(),"inline");
            assertEquals("image/png",MediaGenerationClient.generate(provider(api.base()),"custom-image","山水",false,
                    "1024x1024",0,"",output,new ChatApiClient.CancellationHandle(),(id,status)->{}));
            assertArrayEquals(PNG,Files.readAllBytes(output.toPath()));
            JSONObject request = new JSONObject(posted.get());
            assertEquals("custom-image",request.getString("model")); assertEquals("1024x1024",request.getString("size"));
            assertEquals("山水",request.getString("prompt")); assertEquals(1,api.requests.size());
            assertEquals("Bearer fixture-secret",auth.get());
        }
    }
    @Test public void downloadsExternalImageWithoutForwardingKey() throws Exception {
        try (MediaFixtureServer api = new MediaFixtureServer(); MediaFixtureServer cdn = new MediaFixtureServer()) {
            AtomicReference<String> auth=new AtomicReference<>("unreached");
            cdn.createContext("/asset",e->{auth.set(e.headers.get("Authorization"));e.respond(200,PNG);});
            api.createContext("/v1/images/generations",e-> e.respond(200,json(new JSONObject().put("data",new JSONArray()
                    .put(new JSONObject().put("url",cdn.base()+"/asset"))))));
            File output=new File(files.getRoot(),"external");
            MediaGenerationClient.generate(provider(api.base()),"m","p",false,"",0,"",output,null,(id,msg)->{});
            assertNull(auth.get()); assertArrayEquals(PNG,Files.readAllBytes(output.toPath()));
        }
    }
    @Test public void resumesExistingVideoIdWithoutPosting() throws Exception {
        try (MediaFixtureServer api = new MediaFixtureServer()) {
            api.createContext("/v1/videos/job_202", e -> e.respond(200,json(new JSONObject().put("status","completed"))));
            api.createContext("/v1/videos/job_202/content", e -> e.respond(200,VIDEO));
            File output = new File(files.getRoot(),"video");
            assertEquals("video/mp4", MediaGenerationClient.generate(provider(api.base()),"custom","p",true,"",0,"job_202",output,null,(id,label)->{}));
            assertEquals(java.util.Arrays.asList("GET /v1/videos/job_202","GET /v1/videos/job_202/content"),api.requests);
            assertArrayEquals(VIDEO,Files.readAllBytes(output.toPath()));
        }
    }
    @Test public void videoCreateUsesMultipartAndFailedJobHasNoOutput() throws Exception {
        try (MediaFixtureServer api = new MediaFixtureServer()) {
            AtomicReference<String> form=new AtomicReference<>(),type=new AtomicReference<>();
            api.createContext("/v1/videos", e -> {
                form.set(new String(e.body,StandardCharsets.UTF_8)); type.set(e.headers.get("Content-Type"));
                e.respond(200,json(new JSONObject().put("id","video_1").put("status","failed")));
            });
            File output=new File(files.getRoot(),"failed");
            try {
                MediaGenerationClient.generate(provider(api.base()),"model-video","海浪",true,"1280x720",8,"",output,null,(id,msg)->{});
                fail();
            } catch (IOException expected) { assertFalse(output.exists()); }
            assertTrue(type.get().startsWith("multipart/form-data"));
            for(String field:new String[]{"name=\"model\"","model-video","海浪","1280x720","name=\"seconds\""}) assertTrue(field, form.get().contains(field));
            assertEquals(1,api.requests.size());
        }
    }
    @Test public void cancellationPreventsSubmission() throws Exception {
        try (MediaFixtureServer api=new MediaFixtureServer()) {
            File output=new File(files.getRoot(),"cancel");
            ChatApiClient.CancellationHandle cancel=new ChatApiClient.CancellationHandle();cancel.cancel();
            try {
                MediaGenerationClient.generate(provider(api.base()),"model","prompt",true,"",0,"",output,cancel,(id,msg)->{});
                fail();
            } catch(IOException expected) { assertFalse(output.exists()); assertTrue(api.requests.isEmpty()); }
        }
    }
}
