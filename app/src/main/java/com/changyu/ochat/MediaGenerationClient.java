package com.changyu.ochat;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.MultipartBody;
import org.json.JSONArray;
import org.json.JSONObject;

/** Generates one artifact on a worker thread; durable progress is reported before polling or downloading. */
final class MediaGenerationClient {
    interface Progress {
        void update(String remoteId, String status) throws Exception;
    }

    static String generate(JSONObject provider, String model, String prompt, boolean video, String size,
                           int seconds, String resumeId, File destination, ChatApiClient.CancellationHandle cancel,
                           Progress progress) throws Exception {
        String base = provider.optString("api").trim();
        String protocol = provider.optString("protocol", ChatApiClient.CHAT_COMPLETIONS);
        String key = provider.optString("apiKey");
        if (model == null || model.trim().isEmpty()) throw new IOException("请选择生成模型");
        if (prompt == null || prompt.trim().isEmpty()) throw new IOException("请输入提示词");
        MediaHttp http = new MediaHttp(base, key, protocol, cancel);
        try {
            http.check();
            if (ChatApiClient.GEMINI.equals(protocol)) {
                return GeminiMediaClient.run(http, base, model.trim(), prompt, video, size, seconds,
                        resumeId, destination, progress);
            }
            if (!ChatApiClient.CHAT_COMPLETIONS.equals(protocol) && !ChatApiClient.RESPONSES.equals(protocol))
                throw new IOException("该供应商协议不支持媒体生成");
            String root = apiRoot(base);
            if (video) {
                if ("api.openai.com".equals(HttpUrl.get(root).host()))
                    throw new IOException("OpenAI 官方视频接口已停用，请选用支持视频生成的供应商");
                return video(http, root, model.trim(), prompt, size, seconds, resumeId, destination, progress);
            }
            JSONObject body = new JSONObject().put("model", model.trim()).put("prompt", prompt).put("n", 1);
            if (size != null && !size.isEmpty() && !"auto".equals(size)) body.put("size", size);
            JSONObject result = http.json(root + "/images/generations", body);
            if (result.has("error") && !result.isNull("error")) throw new IOException(ApiFailure.provider(result));
            JSONArray results = result.optJSONArray("data");
            JSONObject image = results == null ? null : results.optJSONObject(0);
            if (image == null) throw new IOException("接口未返回生成的图片");
            progress.update("", "正在保存图片");
            if (!image.isNull("b64_json") && !image.optString("b64_json").isEmpty())
                return http.base64(image.getString("b64_json"), "image/png", destination);
            if (!image.isNull("url") && !image.optString("url").isEmpty())
                return http.download(image.getString("url"), destination, false);
            throw new IOException("接口未返回图片内容或下载地址");
        } catch (IOException failure) {
            http.check();
            throw new IOException(ApiFailure.safe(failure.getMessage(), key), failure);
        }
    }

    private static String video(MediaHttp http, String root, String model, String prompt, String size,
                                int seconds, String remote, File destination, Progress progress) throws Exception {
        String endpoint = root + "/videos";
        JSONObject job;
        if (remote == null || remote.isEmpty()) {
            MultipartBody.Builder form = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("model", model).addFormDataPart("prompt", prompt);
            if (size != null && !size.isEmpty() && !"auto".equals(size)) form.addFormDataPart("size", size);
            if (seconds > 0) form.addFormDataPart("seconds", Integer.toString(seconds));
            job = http.requestJson(endpoint, form.build());
            if (job.has("error") && !job.isNull("error")) throw new IOException(ApiFailure.provider(job));
            remote = job.optString("id");
        } else {
            job = null;
        }
        if (remote == null || !remote.matches("[A-Za-z0-9_~-][A-Za-z0-9_.~-]{0,255}"))
            throw new IOException("视频任务编号无效");
        String statusUrl = endpoint + "/" + remote;
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(30);
        progress.update(remote, "正在查询视频任务");
        while (true) {
            if (job == null) job = http.json(statusUrl, null);
            if (job.has("error") && !job.isNull("error")) throw new IOException(ApiFailure.provider(job));
            String status = job.optString("status");
            if ("completed".equals(status)) {
                progress.update(remote, "正在下载视频");
                return http.download(statusUrl + "/content", destination, true);
            }
            if (!"queued".equals(status) && !"in_progress".equals(status))
                throw new IOException("视频任务未完成（" + ApiFailure.safe(status, "") + "）");
            int percent = job.optInt("progress", -1);
            String message = "queued".equals(status) ? "视频排队中" : "正在生成视频";
            if (percent >= 0 && percent <= 100) message += " · " + percent + "%";
            progress.update(remote, message);
            if (System.nanoTime() >= deadline) throw new IOException("等待视频超时，可继续查询原任务");
            http.pause();
            job = null;
        }
    }

    private static String apiRoot(String base) {
        return base.replaceFirst("[?#].*$", "").replaceAll("/+$", "")
                .replaceFirst("/(?:chat/completions|responses|images/generations|videos)$", "");
    }

    private MediaGenerationClient() {}
}
