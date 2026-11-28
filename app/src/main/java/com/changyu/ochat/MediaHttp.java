package com.changyu.ochat;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;
import okio.ByteString;
import org.json.JSONObject;

/** Bounded transfers for generated media. Credentials are scoped to the configured origin. */
final class MediaHttp {
    private static final long MIB = 1024L * 1024L;
    static final long IMAGE_LIMIT = 32 * MIB, VIDEO_LIMIT = 256 * MIB;
    private static final long JSON_LIMIT = Math.min(32 * MIB, Math.max(2 * MIB, Runtime.getRuntime().maxMemory() / 16));
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS).readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(1, TimeUnit.MINUTES).callTimeout(15, TimeUnit.MINUTES)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
    private final HttpUrl origin;
    private final String key, protocol;
    private final ChatApiClient.CancellationHandle cancel;

    MediaHttp(String base, String key, String protocol, ChatApiClient.CancellationHandle cancel) throws IOException {
        this.origin = url(base);
        this.key = key == null ? "" : key.trim();
        this.protocol = protocol;
        this.cancel = cancel;
    }

    JSONObject json(String address, JSONObject data) throws Exception {
        RequestBody body = data == null ? null : RequestBody.create(data.toString(), JSON);
        return requestJson(address, body);
    }

    JSONObject requestJson(String address, RequestBody body) throws Exception {
        HttpUrl url = url(address);
        if (!sameOrigin(url)) throw new IOException("任务接口地址与供应商地址不一致");
        Request.Builder request = request(url).header("Accept", "application/json");
        if (body != null) request.post(body);
        return execute(request.build(), response -> {
            try (Buffer contents = new Buffer()) {
                byte[] chunk = new byte[16384];
                InputStream input = response.body().byteStream();
                if (response.body().contentLength() > JSON_LIMIT) throw tooLarge();
                int count;
                while ((count = input.read(chunk)) != -1) {
                    check();
                    if (contents.size() + count > JSON_LIMIT) throw tooLarge();
                    contents.write(chunk, 0, count);
                }
                return new JSONObject(contents.readUtf8());
            }
        });
    }

    String download(String address, File destination, boolean video) throws Exception {
        HttpUrl current = url(address);
        for (int redirects = 0; redirects <= 5; redirects++) {
            final HttpUrl location = current;
            String result = execute(request(current).build(), response -> {
                if (response.isRedirect()) {
                    String header = response.header("Location");
                    HttpUrl next = header == null ? null : location.resolve(header);
                    if (next == null || location.isHttps() && !next.isHttps()) throw new IOException("下载重定向地址无效");
                    return next.toString();
                }
                long limit = video ? VIDEO_LIMIT : IMAGE_LIMIT;
                if (response.body().contentLength() > limit) throw tooLarge();
                return save(response.body().byteStream(), video, destination);
            });
            if (!result.startsWith("https://") && !result.startsWith("http://")) return result;
            current = url(result);
        }
        throw new IOException("下载重定向次数超限");
    }

    String base64(String base64, String mime, File destination) throws Exception {
        boolean video = mime.startsWith("video/");
        if (base64 == null || base64.length() > JSON_LIMIT) throw tooLarge();
        ByteString decoded = ByteString.decodeBase64(base64);
        if (decoded == null) throw new IOException("生成结果的 Base64 编码无效");
        try (Buffer buffer = new Buffer()) {
            buffer.write(decoded);
            return save(buffer.inputStream(), video, destination);
        }
    }

    void check() throws InterruptedIOException {
        if ((cancel != null && cancel.isCancelled()) || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("生成已停止");
    }

    void pause() throws InterruptedIOException { pause(10000L); }

    void pause(long millis) throws InterruptedIOException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < end) {
            check();
            long remaining = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
            try { Thread.sleep(Math.min(200, Math.max(1, remaining))); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new InterruptedIOException("生成已停止"); }
        }
        check();
    }

    private Request.Builder request(HttpUrl target) throws IOException {
        check();
        if (origin.isHttps() && !target.isHttps()) throw new IOException("下载地址需要 HTTPS");
        Request.Builder builder = new Request.Builder().url(target);
        if (sameOrigin(target) && !key.isEmpty()) NativeProtocols.auth(builder, key, protocol);
        return builder;
    }

    private boolean sameOrigin(HttpUrl target) {
        return target.scheme().equals(origin.scheme()) && target.host().equals(origin.host()) && target.port() == origin.port();
    }

    private static HttpUrl url(String raw) throws IOException {
        HttpUrl url = raw == null ? null : HttpUrl.parse(raw.trim());
        if (url == null || !url.username().isEmpty() || !url.password().isEmpty()) throw new IOException("媒体服务地址无效");
        return url;
    }

    private interface Reader<T> { T read(Response response) throws Exception; }

    private <T> T execute(Request request, Reader<T> reader) throws Exception {
        Call call = CLIENT.newCall(request);
        if (cancel != null) cancel.register(call);
        try (Response response = call.execute()) {
            check();
            if ((!response.isSuccessful() && !(response.isRedirect() && request.method().equals("GET")))
                    || response.body() == null)
                throw new IOException(ApiFailure.http(response, protocol, null, key));
            return reader.read(response);
        } finally {
            if (cancel != null) cancel.remove(call);
        }
    }

    private String save(InputStream input, boolean video, File destination) throws IOException {
        check();
        long limit = video ? VIDEO_LIMIT : IMAGE_LIMIT;
        BufferedInputStream buffered = new BufferedInputStream(input);
        buffered.mark(16);
        byte[] header = new byte[12];
        int read = 0;
        while (read < header.length) {
            int count = buffered.read(header, read, header.length - read);
            if (count < 0) break;
            read += count;
        }
        buffered.reset();
        String mime = detect(header, read, video);
        File temp = File.createTempFile("media-", ".part", destination.getAbsoluteFile().getParentFile());
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                byte[] chunk = new byte[32768];
                long total = 0;
                int count;
                while ((count = buffered.read(chunk)) != -1) {
                    check();
                    total += count;
                    if (total > limit) throw tooLarge();
                    out.write(chunk, 0, count);
                }
            }
            check();
            if (!temp.renameTo(destination)) throw new IOException("无法保存生成文件");
            return mime;
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    private static String detect(byte[] h, int count, boolean video) throws IOException {
        if (video) {
            if (count >= 12 && ascii(h, 4, 4).equals("ftyp")) return "video/mp4";
            if (count >= 4 && (h[0] & 255) == 0x1a && h[1] == 0x45 && (h[2] & 255) == 0xdf && (h[3] & 255) == 0xa3)
                return "video/webm";
        } else {
            if (count >= 8 && (h[0] & 255) == 137 && ascii(h, 1, 7).equals("PNG\r\n\u001a\n")) return "image/png";
            if (count >= 3 && h[0] == (byte)0xff && h[1] == (byte)0xd8 && h[2] == (byte)0xff) return "image/jpeg";
            if (count >= 12 && ascii(h, 0, 4).equals("RIFF") && ascii(h, 8, 4).equals("WEBP")) return "image/webp";
            if (count >= 6 && (ascii(h, 0, 6).equals("GIF89a") || ascii(h, 0, 6).equals("GIF87a"))) return "image/gif";
        }
        throw new IOException(video ? "接口未返回可识别的视频文件" : "接口未返回可识别的图片文件");
    }

    private static String ascii(byte[] bytes, int offset, int size) {
        return new String(bytes, offset, size, StandardCharsets.US_ASCII);
    }

    private static IOException tooLarge() { return new IOException("生成结果超过文件大小限制"); }
}
