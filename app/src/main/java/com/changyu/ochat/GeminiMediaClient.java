package com.changyu.ochat;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import org.json.JSONArray;
import org.json.JSONObject;

/** Image and video outputs from the Gemini Interactions REST API. */
final class GeminiMediaClient {
    private GeminiMediaClient() {}

    static String run(MediaHttp http, String base, String model, String prompt, boolean video,
                      String size, int seconds, String resumeId, File destination,
                      MediaGenerationClient.Progress progress) throws Exception {
        String api = apiRoot(base);
        String type = video ? "video" : "image";
        String remote = resumeId == null ? "" : resumeId;
        if (remote.startsWith("files/")) {
            return receiveFile(http, api, remote, destination, video, progress);
        }
        JSONObject result;
        if (remote.isEmpty()) {
            JSONObject format = new JSONObject().put("type", type);
            if (video) {
                format.put("delivery", "uri");
                if (seconds > 0) format.put("duration", seconds + "s");
            }
            if (size != null && !size.isEmpty() && !"auto".equals(size)) {
                String ratio = aspectRatio(size);
                format.put("aspect_ratio", ratio);
            }
            JSONObject request = new JSONObject().put("model", model.replaceFirst("^models/", ""))
                    .put("input", prompt).put("response_format", format);
            result = http.json(api + "/interactions", request);
        } else {
            if (!remote.matches("interactions/[A-Za-z0-9_.~-]+")) throw new IOException("任务编号无效");
            result = http.json(api + "/" + remote, null);
        }
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(15);
        while (true) {
            String status = result.optString("status");
            if (!result.isNull("error") || "failed".equals(status) || "cancelled".equals(status)) {
                JSONObject error = result.optJSONObject("error");
                throw new IOException(error == null ? "供应商未完成生成任务" : error.optString("message", "生成失败"));
            }
            String id = result.optString("id");
            if (id.matches("[A-Za-z0-9_~-][A-Za-z0-9_.~-]*")) {
                remote = "interactions/" + id;
                progress.update(remote, "等待生成结果");
            }
            JSONObject output = modelOutput(result.optJSONArray("steps"), type);
            if (output != null) {
                if (!output.isNull("uri") && !output.optString("uri").isEmpty()) {
                    return receiveFile(http, api, fileName(api, output.getString("uri")), destination, video, progress);
                }
                String mime = output.isNull("mime_type") ? (video ? "video/mp4" : "image/png") : output.optString("mime_type");
                if (!mime.startsWith(type + "/")) throw new IOException("返回文件的类型与生成模式不符");
                if (!output.isNull("data") && !output.optString("data").isEmpty()) {
                    return http.base64(output.getString("data"), mime, destination);
                }
            }
            if (remote.isEmpty() || !("in_progress".equals(status) || "queued".equals(status))) {
                throw new IOException("供应商未返回" + (video ? "视频" : "图片") + "，请检查模型或调整提示词");
            }
            if (System.nanoTime() >= deadline) throw new IOException("等待超时，可继续查询原任务");
            http.pause();
            result = http.json(api + "/" + remote, null);
        }
    }

    static String apiRoot(String base) {
        String root = base.trim().replaceFirst("[?#].*$", "").replaceAll("/+$", "")
                .replaceFirst("/(?:models(?:/.*)?|interactions)$", "");
        return root.matches(".*/v[0-9]+(?:beta|alpha)?$") ? root : root + "/v1beta";
    }

    static String aspectRatio(String size) throws IOException {
        if (size.matches("[1-9][0-9]?:[1-9][0-9]?")) return size;
        if (!size.matches("[1-9][0-9]{0,4}x[1-9][0-9]{0,4}")) throw new IOException("画幅格式无效");
        String[] parts = size.split("x");
        int width = Integer.parseInt(parts[0]), height = Integer.parseInt(parts[1]);
        int a = width, b = height;
        while (b != 0) { int next = a % b; a = b; b = next; }
        return (width / a) + ":" + (height / a);
    }

    static JSONObject modelOutput(JSONArray steps, String type) {
        if (steps == null) return null;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null || !"model_output".equals(step.optString("type"))) continue;
            JSONArray blocks = step.optJSONArray("content");
            if (blocks == null) continue;
            for (int k = 0; k < blocks.length(); k++) {
                JSONObject block = blocks.optJSONObject(k);
                if (block != null && type.equals(block.optString("type"))) return block;
            }
        }
        return null;
    }

    static String fileName(String api, String value) throws IOException {
        String resource = value;
        if (!value.startsWith("files/")) {
            HttpUrl url = HttpUrl.parse(value);
            HttpUrl root = HttpUrl.parse(api);
            if (url == null || root == null || !root.scheme().equals(url.scheme())
                    || !root.host().equals(url.host()) || root.port() != url.port()
                    || !url.encodedPath().startsWith(root.encodedPath() + "/files/")) {
                throw new IOException("供应商返回了无效的文件地址");
            }
            resource = url.encodedPath().substring(root.encodedPath().length() + 1);
        }
        resource = resource.replaceFirst(":download$", "");
        if (!resource.matches("files/[A-Za-z0-9_~-][A-Za-z0-9_.~-]*")) throw new IOException("文件编号无效");
        return resource;
    }

    private static String receiveFile(MediaHttp http, String api, String remote, File destination,
                                      boolean video, MediaGenerationClient.Progress progress) throws Exception {
        String file = fileName(api, remote);
        progress.update(file, "等待文件就绪");
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(15);
        while (true) {
            JSONObject metadata = http.json(api + "/" + file, null);
            String state = metadata.optString("state");
            if ("ACTIVE".equals(state)) break;
            if (!"PROCESSING".equals(state) && !"STATE_UNSPECIFIED".equals(state)) {
                throw new IOException("供应商文件处理失败（" + state + "）");
            }
            if (System.nanoTime() >= deadline) throw new IOException("文件处理超时，可继续查询原任务");
            http.pause();
        }
        progress.update(file, "正在下载");
        return http.download(api + "/" + file + ":download?alt=media", destination, video);
    }
}
