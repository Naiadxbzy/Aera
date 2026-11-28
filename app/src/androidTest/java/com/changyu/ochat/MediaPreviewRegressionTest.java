package com.changyu.ochat;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.ImageView;
import android.widget.VideoView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.Assert.*;

/** Drives the production preview entry point with decodable image and moving H.264 samples. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 29)
public class MediaPreviewRegressionTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();

    @Test public void previewShowsDecodedPixelsFromPrivateImage() throws Exception {
        File image = File.createTempFile("preview-png-", ".media", context.getCacheDir());
        try {
            Bitmap source = Bitmap.createBitmap(61, 37, Bitmap.Config.ARGB_8888);
            source.eraseColor(Color.rgb(31, 150, 92));
            try (FileOutputStream out = new FileOutputStream(image)) {
                assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, out));
            } finally { source.recycle(); }
            try (ActivityScenario<SettingsHomeActivity> scenario = ActivityScenario.launch(SettingsHomeActivity.class)) {
                scenario.onActivity(activity -> MediaPreview.show(activity, image, "image/png"));
                awaitView(view -> {
                    if (!(view instanceof ImageView) || !"生成的图片".contentEquals(
                            view.getContentDescription() == null ? "" : view.getContentDescription())) return false;
                    ImageView preview = (ImageView) view;
                    if (!(preview.getDrawable() instanceof BitmapDrawable)) return false;
                    Bitmap decoded = ((BitmapDrawable) preview.getDrawable()).getBitmap();
                    return decoded.getWidth() == 61 && decoded.getHeight() == 37
                            && decoded.getPixel(30, 18) == Color.rgb(31, 150, 92);
                }, "Image panel did not display decoded pixels");

            }
        } finally { image.delete(); }
    }

    @Test public void previewPlaysPrivateH264AndUsesEncodedFrameSize() throws Exception {
        File video = File.createTempFile("preview-avc-", ".media", context.getCacheDir());
        try {
            // Three seconds at 12 fps, with a yellow rectangle moving on a green background.
            try (InputStream input = instrumentation.getContext().getAssets().open("media_preview_sample.mp4");
                 FileOutputStream out = new FileOutputStream(video)) {
                byte[] buffer = new byte[4096];
                int size;
                while ((size = input.read(buffer)) != -1) out.write(buffer, 0, size);
            }
            try (ActivityScenario<SettingsHomeActivity> scenario = ActivityScenario.launch(SettingsHomeActivity.class)) {
                scenario.onActivity(activity -> MediaPreview.show(activity, video, "video/mp4"));
                awaitView(view -> {
                    if (!(view instanceof VideoView)) return false;
                    VideoView player = (VideoView) view;
                    Rect frame = player.getHolder().getSurfaceFrame();
                    return player.getHolder().getSurface().isValid() && frame.width() == 160 && frame.height() == 96
                            && player.getCurrentPosition() >= 80;
                }, "Video panel did not play the 160 x 96 H.264 stream");

            }
        } finally { video.delete(); }
    }

    private void awaitView(Predicate<View> target, String failure) {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        boolean[] found = {false};
        do {
            instrumentation.runOnMainSync(() -> {
                List<View> roots = WindowInspector.getGlobalWindowViews();
                for (View root : roots) {
                    if (contains(root, target)) { found[0] = true; break; }
                }
            });
            if (found[0]) return;
            SystemClock.sleep(40);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail(failure);
    }

    private boolean contains(View view, Predicate<View> target) {
        if (!view.isShown()) return false;
        if (target.test(view)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                if (contains(group.getChildAt(i), target)) return true;
        }
        return false;
    }
}
