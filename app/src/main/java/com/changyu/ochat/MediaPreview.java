package com.changyu.ochat;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Private-file media preview and document-provider export. */
final class MediaPreview {
    private static final ExecutorService IMAGE_DECODER = Executors.newSingleThreadExecutor();

    private MediaPreview() {}

    static void show(Activity owner, File file, String mime) {
        boolean video = mime.startsWith("video/");
        FrameLayout frame = new FrameLayout(owner);
        PanelDialog dialog = new PanelDialog(owner, video ? "视频预览" : "图片预览", null, frame);
        if (!file.isFile()) {
            message(owner, frame, "生成文件已丢失");
            dialog.show();
            return;
        }
        if (video) {
            VideoView player = new VideoView(owner);
            frame.addView(player, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            MediaController controls = new MediaController(owner);
            controls.setAnchorView(player);
            player.setMediaController(controls);
            player.setOnErrorListener((mp, what, extra) -> {
                message(owner, frame, "此设备无法播放该视频，可保存后使用其他播放器打开。");
                return true;
            });
            dialog.setOnDismissListener(ignored -> {
                controls.hide();
                player.stopPlayback();
            });
            dialog.show();
            player.setVideoPath(file.getAbsolutePath());
            player.start();
        } else {
            ProgressBar progress = new ProgressBar(owner);
            frame.addView(progress, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            int maxSide = Math.min(2048, Math.max(512, owner.getResources().getDisplayMetrics().widthPixels));
            dialog.show();
            Future<?> pending = IMAGE_DECODER.submit(() -> {
                Bitmap bitmap = decode(file, maxSide);
                if (Thread.currentThread().isInterrupted()) return;
                owner.runOnUiThread(() -> {
                    if (!dialog.isShowing() || owner.isDestroyed()) return;
                    if (bitmap == null) {
                        message(owner, frame, "此设备无法显示该图片，可保存原文件。");
                        return;
                    }
                    ImageView image = new ImageView(owner);
                    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    image.setContentDescription("生成的图片");
                    image.setImageBitmap(bitmap);
                    frame.removeAllViews();
                    frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
                });
            });
            dialog.setOnDismissListener(ignored -> pending.cancel(true));
        }
    }

    private static Bitmap decode(File file, int maxSide) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        int side = Math.max(options.outWidth, options.outHeight);
        options.inSampleSize = 1;
        while (side / options.inSampleSize > maxSide) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    private static void message(Activity owner, FrameLayout frame, String text) {
        frame.removeAllViews();
        TextView label = UiStyle.text(owner, text, 15, ThemePalette.resolve(owner).ink, false);
        label.setGravity(Gravity.CENTER);
        frame.addView(label, new FrameLayout.LayoutParams(-1, -1));
    }

    static Intent createDocument(String mime, String id) {
        String extension;
        switch (mime.toLowerCase(Locale.ROOT)) {
            case "image/jpeg": extension = ".jpg"; break;
            case "image/webp": extension = ".webp"; break;
            case "image/gif": extension = ".gif"; break;
            case "image/png": extension = ".png"; break;
            case "video/webm": extension = ".webm"; break;
            case "video/mp4": extension = ".mp4"; break;
            default: extension = ""; break;
        }
        return new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime)
                .putExtra(Intent.EXTRA_TITLE, "Aera-" + id + extension);
    }

    /** Run on a worker thread. Open the input first to avoid truncating the destination on a missing result. */
    static void export(Context context, File file, Uri target) throws IOException {
        if (target == null || !ContentResolver.SCHEME_CONTENT.equals(target.getScheme())) {
            throw new IOException("请选择文件保存位置");
        }
        ContentResolver resolver = context.getContentResolver();
        try (FileInputStream input = new FileInputStream(file);
             OutputStream output = resolver.openOutputStream(target, "wt")) {
            if (output == null) throw new IOException("无法写入目标文件");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        } catch (SecurityException e) {
            throw new IOException("文件保存权限已失效", e);
        }
    }
}
