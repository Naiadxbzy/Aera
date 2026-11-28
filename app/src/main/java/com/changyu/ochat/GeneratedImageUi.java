package com.changyu.ochat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.graphics.ColorUtils;
import org.json.JSONObject;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** Inline generated image cards rendered inside assistant chat messages. */
final class GeneratedImageUi implements AutoCloseable {
    private final Activity owner;
    private final ThemePalette palette;
    private final MediaGenerationStore store;
    private final ExecutorService decoder = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getAllocationByteCount();
        }
    };
    private volatile boolean closed;

    GeneratedImageUi(Activity owner) {
        this.owner = owner;
        this.palette = ThemePalette.resolve(owner);
        this.store = new MediaGenerationStore(owner);
    }

    View card(JSONObject record, Consumer<JSONObject> onSave, Consumer<String> onUsePrompt) {
        LinearLayout card = new LinearLayout(owner);
        card.setTag("generated-image-card");
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setBackground(UiStyle.surface(owner, palette.surface, 18, true));

        String jobId = record.optString("jobId");
        String mime = record.optString("mime", "image/png");
        File file = jobId.isEmpty() ? null : safeResultFile(jobId);

        ImageStage stage = new ImageStage();
        if (file != null && file.isFile()) {
            stage.setOnClickListener(v -> MediaPreview.show(owner, file, mime));
            load(stage, jobId, file);
        } else {
            stage.missing();
        }
        card.addView(stage, new LinearLayout.LayoutParams(-1, -2));

        String model = record.optString("model", "");
        String mode = ImageToolConfig.MODE_DIRECT.equals(record.optString("mode")) ? "自写提示词" : "AI 编写提示词";
        String size = record.optString("size", "");
        StringBuilder caption = new StringBuilder();
        if (!model.isEmpty()) caption.append(model).append(" · ");
        caption.append(mode);
        if (!size.isEmpty()) caption.append(" · ").append(size);
        TextView meta = UiStyle.text(owner, caption.toString(), 12, palette.muted, false);
        meta.setPadding(dp(4), dp(8), dp(4), dp(4));
        card.addView(meta);

        LinearLayout actions = new LinearLayout(owner);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(2), 0, 0);

        TextView promptButton = chip("查看提示词", v -> showPromptDialog(record, onUsePrompt));
        actions.addView(promptButton, new LinearLayout.LayoutParams(0, dp(40), 1));

        if (file != null && file.isFile() && onSave != null) {
            View gap = new View(owner);
            actions.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
            TextView saveButton = chip("保存到…", v -> onSave.accept(record));
            actions.addView(saveButton, new LinearLayout.LayoutParams(0, dp(40), 1));
        }

        card.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private File safeResultFile(String jobId) {
        try {
            return store.resultFile(jobId);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private TextView chip(String label, View.OnClickListener listener) {
        int bg = ColorUtils.blendARGB(palette.surface, palette.ink, palette.dark ? .10f : .04f);
        TextView button = UiStyle.text(owner, label, 13, ThemePalette.readable(palette.ink, bg), true);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(label);
        UiStyle.clickable(button, bg, 12, false);
        button.setOnClickListener(listener);
        return button;
    }

    private void showPromptDialog(JSONObject record, Consumer<String> onUsePrompt) {
        String prompt = record.optString("prompt", "").trim();
        String original = record.optString("originalRequest", "").trim();
        String mode = ImageToolConfig.MODE_DIRECT.equals(record.optString("mode")) ? "自写提示词" : "AI 编写提示词";
        String model = record.optString("model", "");
        String size = record.optString("size", "");

        StringBuilder content = new StringBuilder();
        if (!model.isEmpty()) content.append("模型：").append(model).append('\n');
        content.append("方式：").append(mode);
        if (!size.isEmpty()) content.append(" · 画幅：").append(size);
        if (!original.isEmpty() && !original.equals(prompt)) {
            content.append("\n\n原始请求：\n").append(original);
        }
        content.append("\n\n最终生图提示词：\n").append(prompt.isEmpty() ? "（无）" : prompt);

        TextView text = UiStyle.text(owner, content.toString(), 14, palette.ink, false);
        text.setTextIsSelectable(true);
        text.setPadding(dp(4), dp(4), dp(4), dp(12));
        ScrollView scroll = new ScrollView(owner);
        scroll.addView(text);

        PanelDialog dialog = new PanelDialog(owner, "生图提示词", "", scroll).compact()
                .action(AlertDialog.BUTTON_NEGATIVE, "关闭", null);
        if (!prompt.isEmpty()) {
            dialog.action(AlertDialog.BUTTON_NEUTRAL, "复制提示词", () -> copy(prompt));
            if (onUsePrompt != null) {
                dialog.action(AlertDialog.BUTTON_POSITIVE, "填入输入框", () -> onUsePrompt.accept(prompt));
            }
        }
        dialog.show();
    }

    private void copy(String value) {
        try {
            ClipboardManager clipboard = (ClipboardManager) owner.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) throw new IllegalStateException();
            clipboard.setPrimaryClip(ClipData.newPlainText("生图提示词", value));
            Toast.makeText(owner, "提示词已复制", Toast.LENGTH_SHORT).show();
        } catch (Exception unavailable) {
            Toast.makeText(owner, "复制失败：剪贴板不可用", Toast.LENGTH_SHORT).show();
        }
    }

    private void load(ImageStage stage, String key, File file) {
        stage.key = key;
        Bitmap cached = cache.get(key);
        if (cached != null) {
            stage.showBitmap(cached);
            return;
        }
        WeakReference<ImageStage> ref = new WeakReference<>(stage);
        int maxSide = Math.min(1280, Math.max(512, owner.getResources().getDisplayMetrics().widthPixels));
        stage.work = decoder.submit(() -> {
            Bitmap bitmap = decode(file, maxSide);
            if (closed || Thread.currentThread().isInterrupted()) return;
            if (bitmap != null) cache.put(key, bitmap);
            main.post(() -> {
                ImageStage view = ref.get();
                if (closed || view == null || !key.equals(view.key)) return;
                if (bitmap != null) view.showBitmap(bitmap);
                else view.missing();
            });
        });
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

    private int dp(int value) {
        return UiStyle.dp(owner, value);
    }

    @Override public void close() {
        closed = true;
        decoder.shutdownNow();
        cache.evictAll();
    }

    private final class ImageStage extends FrameLayout {
        final AppCompatImageView image;
        final ProgressBar busy;
        final TextView unavailable;
        String key;
        Future<?> work;
        Bitmap bitmap;

        ImageStage() {
            super(owner);
            setContentDescription("生成的图片，点击预览");
            setBackgroundColor(palette.page);
            setClipToOutline(true);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
                }
            });
            image = new AppCompatImageView(owner);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(image, new LayoutParams(-1, -1));

            busy = new ProgressBar(owner);
            busy.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(busy, new LayoutParams(dp(24), dp(24), Gravity.CENTER));

            unavailable = UiStyle.text(owner, "生成图片已删除或不可用", 13, palette.muted, false);
            unavailable.setGravity(Gravity.CENTER);
            unavailable.setVisibility(GONE);
            addView(unavailable, new LayoutParams(-1, -1));
        }

        void showBitmap(Bitmap value) {
            bitmap = value;
            image.setImageBitmap(value);
            busy.setVisibility(GONE);
            unavailable.setVisibility(GONE);
            requestLayout();
        }

        void missing() {
            busy.setVisibility(GONE);
            unavailable.setVisibility(VISIBLE);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int height;
            if (bitmap == null) {
                height = dp(200);
            } else {
                height = Math.round(width * bitmap.getHeight() / (float) bitmap.getWidth());
                height = Math.max(dp(120), Math.min(dp(340), height));
            }
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }

        @Override protected void onDetachedFromWindow() {
            if (work != null) work.cancel(true);
            super.onDetachedFromWindow();
        }
    }
}
