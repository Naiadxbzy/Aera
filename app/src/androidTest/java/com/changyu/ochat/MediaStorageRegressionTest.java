package com.changyu.ochat;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MediaStorageRegressionTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final MediaGenerationStore store = new MediaGenerationStore(context);

    @Test public void recordsAndFilesAreIsolatedAndSurviveNewStoreInstance() throws Exception {
        String first = MediaGenerationStore.newId();
        String second = MediaGenerationStore.newId();
        String session = "media-test-" + first;
        try {
            JSONObject job = new JSONObject().put("id", first).put("sessionId", session)
                    .put("createdAt", 1).put("status", "running");
            store.save(job);
            store.save(new JSONObject().put("id", second).put("sessionId", session + "-other"));
            assertEquals(1, store.list(session).size());
            try (FileOutputStream out = new FileOutputStream(store.temporaryFile(first))) { out.write(99); }
            assertFalse(store.resultFile(first).exists());
            store.finish(first);
            assertFalse(store.temporaryFile(first).exists());
            store.save(job.put("status", "done"));
            MediaGenerationStore reopened = new MediaGenerationStore(context);
            assertEquals("done", reopened.get(first).getString("status"));
            assertEquals(1, reopened.resultFile(first).length());
            store.delete(first);
            assertNull(reopened.get(first));
            assertTrue(reopened.list(session).isEmpty());
            assertNotNull(reopened.get(second));
            try {
                store.resultFile("../other");
                fail("Untrusted ID used as a file path");
            } catch (IllegalArgumentException expected) {}
        } finally {
            store.delete(first);
            store.delete(second);
        }
    }

    @Test public void canRecoverPublishedFilesAndCleanInterruptedTransfers() throws Exception {
        String videoId = MediaGenerationStore.newId();
        String pendingId = MediaGenerationStore.newId();
        String session = "recovery-" + videoId;
        byte[] mp4Header = new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        try {
            store.save(new JSONObject().put("id", videoId).put("sessionId", session)
                    .put("video", true).put("mime", "video/mp4").put("state", "publishing"));
            store.save(new JSONObject().put("id", pendingId).put("sessionId", session)
                    .put("remoteId", "files/clip").put("state", "running"));
            try (FileOutputStream out = new FileOutputStream(store.temporaryFile(videoId))) {
                out.write(mp4Header);
            }
            File interrupted = new File(store.temporaryFile(pendingId).getParentFile(), "media-incomplete.part");
            try (FileOutputStream out = new FileOutputStream(interrupted)) {
                out.write(42);
            }

            MediaGenerationService.recover(store, store.list(session));

            assertEquals("complete", store.get(videoId).getString("state"));
            assertEquals("video/mp4", store.get(videoId).getString("mime"));
            assertEquals(mp4Header.length, store.resultFile(videoId).length());
            assertFalse(store.temporaryFile(videoId).exists());
            assertEquals("interrupted", store.get(pendingId).getString("state"));
            assertEquals("files/clip", store.get(pendingId).getString("remoteId"));
            assertFalse(interrupted.exists());
            assertFalse(store.resultFile(pendingId).exists());
        } finally {
            store.delete(videoId);
            store.delete(pendingId);
        }
    }

    @Test public void incompleteOutputsCannotReplaceResultsAndExportTruncates() throws Exception {
        String id = MediaGenerationStore.newId();
        File exports = new File(context.getFilesDir(), "imported_attachments");
        assertTrue(exports.isDirectory() || exports.mkdirs());
        File destination = File.createTempFile("media-export-", ".png", exports);
        byte[] expected = new byte[]{0, 1, 2, 3};
        try {
            store.save(new JSONObject().put("id", id));
            try (FileOutputStream output = new FileOutputStream(store.temporaryFile(id))) {
                output.write(expected);
            }
            store.finish(id);
            try {
                store.finish(id);
                fail("Missing partial accepted");
            } catch (IOException missing) {}
            assertEquals(expected.length, store.resultFile(id).length());
            try (FileOutputStream output = new FileOutputStream(destination)) { output.write(new byte[120]); }
            Uri target = FileProvider.getUriForFile(context, context.getPackageName() + ".attachments", destination);
            MediaPreview.export(context, store.resultFile(id), target);
            assertEquals(expected.length, destination.length());
            byte[] actual = new byte[expected.length];
            try (DataInputStream input = new DataInputStream(new FileInputStream(destination))) {
                input.readFully(actual);
            }
            assertArrayEquals(expected, actual);
            Intent intent = MediaPreview.createDocument("video/mp4", id);
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.getAction());
            assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE));
            assertEquals("video/mp4", intent.getType());
            assertEquals("Aera-" + id + ".mp4", intent.getStringExtra(Intent.EXTRA_TITLE));
        } finally {
            destination.delete();
            store.delete(id);
        }
    }
}
