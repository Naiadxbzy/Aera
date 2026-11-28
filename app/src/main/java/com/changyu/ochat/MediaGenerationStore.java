package com.changyu.ochat;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Jobs and result files are private to the app. Each metadata update is atomic. */
final class MediaGenerationStore {
    private static final Object LOCK = new Object();
    private final File root;

    MediaGenerationStore(Context context) {
        root = new File(context.getNoBackupFilesDir(), "generated-media");
    }

    static String newId() {
        return UUID.randomUUID().toString();
    }

    private File directory(String id) {
        if (id == null || id.length() != 36 || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw new IllegalArgumentException("无效的任务标识");
        }
        return new File(root, id);
    }

    /** Required fields: id, sessionId, createdAt (epoch ms). Never pass credentials. */
    void save(JSONObject job) throws IOException {
        synchronized (LOCK) {
            File dir = directory(job.optString("id"));
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法建立任务目录");
            AtomicFile metadata = new AtomicFile(new File(dir, "task.json"));
            FileOutputStream stream = null;
            try {
                byte[] data = job.toString().getBytes(StandardCharsets.UTF_8);
                if (data.length > MAX_METADATA_BYTES) throw new IOException("任务说明过长");
                stream = metadata.startWrite();
                stream.write(data);
                metadata.finishWrite(stream);
            } catch (IOException | RuntimeException e) {
                if (stream != null) metadata.failWrite(stream);
                throw e;
            }
        }
    }

    private static final int MAX_METADATA_BYTES = 1024 * 1024;
    JSONObject get(String id) {
        synchronized (LOCK) {
            AtomicFile file = new AtomicFile(new File(directory(id), "task.json"));
            try (FileInputStream input = file.openRead()) {
                long length = input.getChannel().size();
                if (length <= 0 || length > MAX_METADATA_BYTES) return null;
                byte[] bytes = new byte[(int) length];
                new java.io.DataInputStream(input).readFully(bytes);
                JSONObject record = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                return id.equals(record.optString("id")) ? record : null;
            } catch (IOException | JSONException e) {
                return null;
            }
        }
    }

    /** Returns newest first. A null sessionId lists jobs from all sessions. */
    List<JSONObject> list(String sessionId) {
        synchronized (LOCK) {
            File[] dirs = root.listFiles();
            if (dirs == null) return Collections.emptyList();
            List<JSONObject> result = new ArrayList<>();
            for (File dir : dirs) {
                if (!dir.isDirectory()) continue;
                JSONObject job;
                try { job = get(dir.getName()); }
                catch (IllegalArgumentException invalid) { continue; }
                if (job != null && (sessionId == null || sessionId.equals(job.optString("sessionId")))) {
                    result.add(job);
                }
            }
            result.sort(Comparator.comparingLong((JSONObject job) -> job.optLong("createdAt")).reversed());
            return result;
        }
    }

    /** Call save before passing this path to the network client. */
    File temporaryFile(String id) {
        return new File(directory(id), "output.part");
    }

    File resultFile(String id) {
        return new File(directory(id), "output.media");
    }

    /** Publish only after the network request has completed and the file has been validated. */
    void finish(String id) throws IOException {
        synchronized (LOCK) {
            File temp = temporaryFile(id);
            if (!temp.isFile() || temp.length() == 0) throw new IOException("生成结果为空");
            if (!temp.renameTo(resultFile(id))) throw new IOException("无法保存生成结果");
        }
    }

    void discardPartial(String id) {
        synchronized (LOCK) { temporaryFile(id).delete(); }
    }

    void delete(String id) throws IOException {
        synchronized (LOCK) {
            File dir = directory(id);
            if (!dir.exists()) return;
            File[] files = dir.listFiles();
            if (files == null) throw new IOException("无法读取任务目录");
            // Keep the job visible if deleting its media fails, so deletion can be retried.
            for (File file : files) {
                if (!file.getName().startsWith("task.json") && !file.delete()) {
                    throw new IOException("无法删除生成文件");
                }
            }
            new AtomicFile(new File(dir, "task.json")).delete();
            if (!dir.delete()) throw new IOException("无法删除任务目录");
        }
    }
}
