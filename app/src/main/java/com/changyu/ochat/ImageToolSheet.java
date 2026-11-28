package com.changyu.ochat;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** In-chat image generation tool configuration panel (prompt mode, provider, model, aspect ratio). */
final class ImageToolSheet {
    static PanelDialog show(Context context, SharedPreferences prefs, String preferredProviderId,
                            boolean currentlyEnabled, Consumer<ImageToolConfig> onApply,
                            Runnable onDisable, Runnable onOpenStudio) {
        ThemePalette palette = ThemePalette.resolve(context);
        List<JSONObject> providers = ImageToolConfig.compatibleProviders(prefs);
        ImageToolConfig initial = ImageToolConfig.resolve(prefs, preferredProviderId);

        final String[] mode = {initial.mode};
        final String[] providerId = {initial.providerId};
        final String[] size = {initial.size};

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);

        TextView modeHeading = UiStyle.text(context, "提示词方式", 14, palette.ink, true);
        body.addView(modeHeading);

        LinearLayout aiCard = modeCard(context, palette, "AI 分析并编写提示词（默认）",
                "先由当前对话模型分析你的请求和上下文，优化提示词后再调用生成图片工具");
        LinearLayout directCard = modeCard(context, palette, "自己写提示词（直接生图）",
                "直接将输入内容作为生图提示词提交，不经过对话模型分析改写");
        body.addView(aiCard, spaced(context, 8));
        body.addView(directCard, spaced(context, 8));

        TextView providerHeading = UiStyle.text(context, "生图供应商", 14, palette.ink, true);
        body.addView(providerHeading, spaced(context, 14));
        Button providerButton = optionButton(context, palette);
        body.addView(providerButton, spaced(context, 6));

        TextView modelHeading = UiStyle.text(context, "生图模型 ID", 14, palette.ink, true);
        body.addView(modelHeading, spaced(context, 12));
        AutoCompleteTextView modelInput = new AutoCompleteTextView(context);
        modelInput.setSingleLine(true);
        modelInput.setHint("输入或选择生图模型 ID");
        modelInput.setContentDescription("生图模型 ID");
        modelInput.setThreshold(1);
        modelInput.setTextColor(palette.ink);
        modelInput.setHintTextColor(palette.muted);
        modelInput.setTextSize(15);
        modelInput.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        modelInput.setMinHeight(dp(context, 48));
        modelInput.setBackground(UiStyle.surface(context, palette.page, 14, true));
        modelInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        modelInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(256)});
        modelInput.setOnClickListener(v -> modelInput.showDropDown());
        body.addView(modelInput, spaced(context, 6));

        TextView sizeHeading = UiStyle.text(context, "画幅", 14, palette.ink, true);
        body.addView(sizeHeading, spaced(context, 12));
        Button sizeButton = optionButton(context, palette);
        body.addView(sizeButton, spaced(context, 6));

        if (onOpenStudio != null) {
            TextView studioLink = UiStyle.text(context, "打开图片与视频工作台  ›", 14,
                    ThemePalette.readable(palette.primary, palette.surface), true);
            studioLink.setGravity(Gravity.CENTER_VERTICAL);
            studioLink.setMinHeight(dp(context, 48));
            studioLink.setPadding(dp(context, 4), dp(context, 8), dp(context, 4), dp(context, 4));
            body.addView(studioLink, spaced(context, 8));
            studioLink.setTag("open-studio");
        }

        java.util.function.Supplier<JSONObject> currentProvider = () -> {
            for (JSONObject p : providers) {
                if (p.optString("id").equals(providerId[0])) return p;
            }
            return null;
        };

        Runnable refreshModelSuggestions = () -> {
            JSONObject p = currentProvider.get();
            List<String> choices = ImageToolConfig.modelSuggestions(prefs, p);
            modelInput.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_dropdown_item_1line, choices));
            modelInput.setText(ImageToolConfig.savedModel(prefs, p), false);
            String protocol = p == null ? ChatApiClient.CHAT_COMPLETIONS : p.optString("protocol", ChatApiClient.CHAT_COMPLETIONS);
            size[0] = ImageToolConfig.normalizeSize(size[0], protocol);
        };

        Runnable render = () -> {
            boolean ai = !ImageToolConfig.MODE_DIRECT.equals(mode[0]);
            styleModeCard(palette, aiCard, ai);
            styleModeCard(palette, directCard, !ai);
            JSONObject p = currentProvider.get();
            providerButton.setText(p == null ? "去配置支持生图的供应商…" : p.optString("name", "供应商"));
            String protocol = p == null ? ChatApiClient.CHAT_COMPLETIONS : p.optString("protocol", ChatApiClient.CHAT_COMPLETIONS);
            size[0] = ImageToolConfig.normalizeSize(size[0], protocol);
            sizeButton.setText(size[0].isEmpty() ? "模型默认" : size[0]);
        };

        aiCard.setOnClickListener(v -> {
            mode[0] = ImageToolConfig.MODE_AI;
            render.run();
        });
        directCard.setOnClickListener(v -> {
            mode[0] = ImageToolConfig.MODE_DIRECT;
            render.run();
        });

        providerButton.setOnClickListener(v -> {
            List<String> names = new ArrayList<>();
            int checked = -1;
            for (JSONObject p : providers) {
                if (p.optString("id").equals(providerId[0])) checked = names.size();
                names.add(p.optString("name", "供应商"));
            }
            names.add("管理供应商…");
            ChoiceDialogs.single(context, "选择生图供应商", names.toArray(new String[0]), checked, index -> {
                if (index == providers.size()) {
                    context.startActivity(new Intent(context, ProviderListActivity.class));
                    return;
                }
                providerId[0] = providers.get(index).optString("id");
                refreshModelSuggestions.run();
                render.run();
            });
        });

        sizeButton.setOnClickListener(v -> {
            JSONObject p = currentProvider.get();
            String protocol = p == null ? ChatApiClient.CHAT_COMPLETIONS : p.optString("protocol", ChatApiClient.CHAT_COMPLETIONS);
            String[] values = ImageToolConfig.sizeValues(protocol);
            String[] labels = ImageToolConfig.sizeLabels(protocol);
            int checked = Math.max(0, Arrays.asList(values).indexOf(size[0]));
            ChoiceDialogs.single(context, "选择画幅", labels, checked, index -> {
                size[0] = values[index];
                render.run();
            });
        });

        refreshModelSuggestions.run();
        if (!initial.model.isEmpty()) modelInput.setText(initial.model, false);
        render.run();

        ScrollView scroll = new ScrollView(context);
        scroll.addView(body);
        PanelDialog dialog = new PanelDialog(context, "生成图片工具", "在对话中直接调用图片生成", scroll).compact();
        dialog.action(AlertDialog.BUTTON_NEGATIVE, "取消", null);
        if (currentlyEnabled && onDisable != null) {
            dialog.action(AlertDialog.BUTTON_NEUTRAL, "关闭工具", onDisable);
        }
        dialog.action(AlertDialog.BUTTON_POSITIVE, "应用", null);

        View studioLink = body.findViewWithTag("open-studio");
        if (studioLink != null && onOpenStudio != null) {
            studioLink.setOnClickListener(v -> {
                dialog.dismiss();
                onOpenStudio.run();
            });
        }

        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            JSONObject p = currentProvider.get();
            String modelId = modelInput.getText().toString().trim();
            if (p != null && modelId.isEmpty()) {
                modelInput.setError("请输入生图模型 ID");
                modelInput.requestFocus();
                return;
            }
            ImageToolConfig.save(prefs, mode[0], providerId[0], modelId, size[0]);
            ImageToolConfig saved = ImageToolConfig.resolve(prefs, preferredProviderId);
            dialog.dismiss();
            if (onApply != null) onApply.accept(saved);
        });
        return dialog;
    }

    private static LinearLayout modeCard(Context context, ThemePalette palette, String title, String summary) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        card.setMinimumHeight(dp(context, 60));
        TextView titleView = UiStyle.text(context, title, 15, palette.ink, true);
        titleView.setTag("mode-title");
        TextView summaryView = UiStyle.text(context, summary, 12, palette.muted, false);
        summaryView.setPadding(0, dp(context, 4), 0, 0);
        card.addView(titleView);
        card.addView(summaryView);
        return card;
    }

    private static void styleModeCard(ThemePalette palette, LinearLayout card, boolean selected) {
        int fill = selected ? ColorUtils.blendARGB(palette.page, palette.primary, .12f) : palette.page;
        UiStyle.clickable(card, fill, 14, true);
        TextView title = card.findViewWithTag("mode-title");
        if (title != null) {
            title.setTextColor(selected ? ThemePalette.readable(palette.primary, fill) : palette.ink);
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            card.setStateDescription(selected ? "已选中" : "未选中");
        }
    }

    private static Button optionButton(Context context, ThemePalette palette) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10));
        button.setMinHeight(dp(context, 48));
        button.setTextSize(15);
        button.setTextColor(palette.ink);
        UiStyle.clickable(button, palette.page, 14, true);
        return button;
    }

    private static LinearLayout.LayoutParams spaced(Context context, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(context, topDp);
        return params;
    }

    private static int dp(Context context, int value) {
        return UiStyle.dp(context, value);
    }

    private ImageToolSheet() {}
}
