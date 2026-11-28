package com.changyu.ochat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns media requests independently of the generating activity. */
public class MediaGenerationService extends Service {
    private static final String CHANNEL = "media_generation";
    private static final int NOTICE = 4182;
    private static final int MAX_RUNNING = 2;
    private static final Map<String, Run> runs = Collections.synchronizedMap(new LinkedHashMap<>());
    private static final Set<Runnable> listeners = new CopyOnWriteArraySet<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newFixedThreadPool(MAX_RUNNING);
    private final Map<String, Run> owned = new LinkedHashMap<>();
    private int lastStart;
    private boolean destroyed;

    private static final class Run {
        final JSONObject job;
        final JSONObject provider;
        final String requestKey;
        final ChatApiClient.CancellationHandle cancellation = new ChatApiClient.CancellationHandle();
        volatile boolean interrupted;

        Run(JSONObject job, JSONObject provider) {
            this.job = job;
            this.provider = provider;
            this.requestKey = new JSONArray().put(job.optString("sessionId")).put(job.optString("providerId"))
                    .put(job.optString("model")).put(job.optString("prompt")).put(job.optBoolean("video"))
                    .put(job.optString("size")).put(job.optInt("seconds")).toString();
        }
    }

    static JSONObject provider(Context context, String id) {
        JSONArray providers = ModelStore.read(context.getSharedPreferences("chat", MODE_PRIVATE));
        for (int i = 0; i < providers.length(); i++) {
            JSONObject entry = providers.optJSONObject(i);
            if (entry != null && id.equals(entry.optString("id"))) return entry;
        }
        return null;
    }

    static String endpointIdentity(JSONObject provider) throws Exception {
        String endpoint = provider.optString("protocol") + "\n" + provider.optString("api").trim().replaceAll("/+$", "");
        return android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(endpoint.getBytes(StandardCharsets.UTF_8)), android.util.Base64.NO_WRAP);
    }

    static boolean active(String id) { return runs.containsKey(id); }

    static void listen(Runnable listener) { listeners.add(listener); }
    static void unlisten(Runnable listener) { listeners.remove(listener); }

    private static void changed() {
        main.post(() -> { for (Runnable listener : listeners) listener.run(); });
    }

    /** Called only from a visible activity. A resumed remote ID must stay with its original endpoint. */
    static void start(Context context, JSONObject job) throws Exception {
        String id = job.getString("id");
        JSONObject provider = provider(context, job.getString("providerId"));
        if (provider == null) throw new IOException("供应商已删除，请重新选择供应商。");
        if (!endpointIdentity(provider).equals(job.optString("endpoint")))
            throw new IOException("供应商地址或协议已变更，请新建任务。");
        Run run = new Run(new JSONObject(job.toString()), provider);
        synchronized (runs) {
            if (runs.containsKey(id)) throw new IOException("此任务正在执行");
            for (Run current : runs.values()) {
                if (current.requestKey.equals(run.requestKey)) throw new IOException("相同的生成任务正在执行");
            }
            if (runs.size() >= MAX_RUNNING) throw new IOException("已有两项任务正在执行，请在其中一项结束后提交。");
            runs.put(id, run);
        }
        MediaGenerationStore store = new MediaGenerationStore(context);
        try {
            run.job.put("state", "running").put("detail", "等待生成");
            store.save(run.job);
            ContextCompat.startForegroundService(context, new Intent(context, MediaGenerationService.class).putExtra("id", id));
        } catch (Exception failure) {
            runs.remove(id);
            run.job.put("state", "failed").put("detail", "生成服务启动失败");
            store.save(run.job);
            throw failure;
        } finally {
            changed();
        }
    }

    static void stop(String id) {
        Run run = runs.get(id);
        if (run != null) run.cancellation.cancel();
    }

    /** Reconciles persisted work without replaying a potentially billable request. Called on a file worker. */
    static void recover(MediaGenerationStore store, java.util.List<JSONObject> jobs) {
        for (int i = 0; i < jobs.size(); i++) {
            JSONObject snapshot = jobs.get(i);
            if (!unfinished(snapshot)) continue;
            String id = snapshot.optString("id");
            synchronized (runs) {
                if (runs.containsKey(id)) continue;
                JSONObject job = store.get(id);
                if (job == null || !unfinished(job)) continue;
                try {
                    File partial = store.temporaryFile(id);
                    File result = store.resultFile(id);
                    // The transfer layer renames a fully received file to output.part.
                    // Its in-flight transfer files have unique media-*.part names.
                    File ready = result.isFile() ? result : partial;
                    if (ready.isFile() && ready.length() > 0) {
                        String mime = job.optString("mime");
                        if (mime.isEmpty()) mime = recoveredMime(ready, job.optBoolean("video"));
                        if (!result.isFile()) store.finish(id);
                        job.put("mime", mime).put("state", "complete").put("detail", "");
                    } else {
                        job.put("state", "interrupted").put("detail", "本机任务已中断");
                    }
                    File dir = partial.getParentFile();
                    File[] leftovers = dir == null ? null :
                            dir.listFiles((parent, name) -> name.startsWith("media-") && name.endsWith(".part"));
                    if (leftovers != null) for (File file : leftovers) file.delete();
                    store.save(job);
                    jobs.set(i, job);
                } catch (IOException | JSONException e) {
                    android.util.Log.w("MediaGeneration", "Could not recover interrupted media", e);
                }
            }
        }
    }

    private static boolean unfinished(JSONObject job) {
        String state = job.optString("state");
        return "running".equals(state) || "publishing".equals(state);
    }

    private static String recoveredMime(File ready, boolean video) throws IOException {
        if (!video) {
            android.graphics.BitmapFactory.Options probe = new android.graphics.BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(ready.getPath(), probe);
            if (probe.outMimeType != null) return probe.outMimeType;
        }
        // A published video has already passed the transfer layer's MP4/WebM header check.
        if (video) try (java.io.FileInputStream input = new java.io.FileInputStream(ready)) {
            return input.read() == 0x1a ? "video/webm" : "video/mp4";
        }
        throw new IOException("无法识别生成结果的格式");
    }

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(CHANNEL, "图片与视频生成", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        lastStart = startId;
        String id = intent == null ? "" : intent.getStringExtra("id");
        Run run = runs.get(id);
        Notification notice = notification(run);
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTICE, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTICE, notice);
        } catch (RuntimeException failure) {
            if (run != null && !owned.containsKey(id)) {
                recordEnd(run, "failed", "系统未能启动后台生成服务");
                runs.remove(id);
                changed();
            }
            if (owned.isEmpty()) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (run != null && !owned.containsKey(id)) {
            owned.put(id, run);
            workers.execute(() -> execute(id, run));
        } else if (owned.isEmpty()) {
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private Notification notification(Run run) {
        Intent open = run == null ? new Intent(this, MainActivity.class) :
                MediaStudioActivity.intent(this, run.job.optString("sessionId"), run.job.optString("providerId"));
        PendingIntent action = PendingIntent.getActivity(this, NOTICE, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_chat_notification)
                .setContentTitle("正在生成图片或视频").setContentText("查看生成进度")
                .setContentIntent(action).setOnlyAlertOnce(true).setOngoing(true).build();
    }

    private void execute(String id, Run run) {
        MediaGenerationStore store = new MediaGenerationStore(this);
        JSONObject job = run.job;
        try {
            if (run.cancellation.isCancelled()) throw new IOException("已停止");
            File target = store.temporaryFile(id);
            String mime = MediaGenerationClient.generate(run.provider, job.getString("model"), job.getString("prompt"),
                    job.optBoolean("video"), job.optString("size"), job.optInt("seconds"), job.optString("remoteId"),
                    target, run.cancellation, (remote, label) -> {
                        try {
                            boolean idChanged = remote != null && !remote.isEmpty() && !remote.equals(job.optString("remoteId"));
                            boolean statusChanged = label != null && !label.equals(job.optString("detail"));
                            if (!idChanged && !statusChanged) return;
                            if (idChanged) job.put("remoteId", remote);
                            if (statusChanged) job.put("detail", label);
                            store.save(job);
                            changed();
                        } catch (JSONException e) { throw new IOException(e); }
                    });
            if (run.cancellation.isCancelled()) throw new IOException("已停止");
            job.put("mime", mime).put("state", "publishing").put("detail", "正在保存结果");
            store.save(job);
            store.finish(id);
            job.put("state", "complete").put("detail", "");
            store.save(job);
        } catch (Exception failure) {
            if (job.has("mime") && (store.resultFile(id).isFile() || store.temporaryFile(id).isFile())) {
                // Leave the published bytes in place. History will reconcile them on the next read.
                android.util.Log.w("MediaGeneration", "Media available, metadata recovery pending", failure);
            } else {
                store.discardPartial(id);
                if (run.interrupted) recordEnd(run, "interrupted", "后台任务已中断");
                else if (run.cancellation.isCancelled()) recordEnd(run, "stopped", "已停止接收，供应商可能仍在生成。");
                else recordEnd(run, "failed", ApiFailure.local(failure));
            }
        } finally {
            main.post(() -> {
                runs.remove(id, run);
                owned.remove(id);
                changed();
                if (!destroyed && owned.isEmpty()) stopSelf(lastStart);
                else if (!destroyed) getSystemService(NotificationManager.class)
                        .notify(NOTICE, notification(owned.values().iterator().next()));
            });
        }
    }

    private void recordEnd(Run run, String state, String detail) {
        try {
            String safe = detail == null ? "生成失败" : detail;
            String key = run.provider.optString("apiKey").trim();
            if (!key.isEmpty()) safe = safe.replace(key, "••••");
            run.job.put("state", state).put("detail", safe.substring(0, Math.min(safe.length(), 1000)));
            new MediaGenerationStore(this).save(run.job);
        } catch (IOException | JSONException e) {
            android.util.Log.e("MediaGeneration", "Could not persist task state", e);
        }
    }

    @Override public void onTimeout(int startId, int type) { stopSelf(); }

    @Override public void onDestroy() {
        destroyed = true;
        for (Run run : owned.values()) {
            run.interrupted = true;
            run.cancellation.cancel();
        }
        workers.shutdown();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
