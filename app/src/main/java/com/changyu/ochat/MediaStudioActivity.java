package com.changyu.ochat;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MediaStudioActivity extends AppCompatActivity {
    static final String TITLE = "图片与视频";
    private static final int SAVE = 301;
    private ThemePalette palette;
    private SharedPreferences prefs;
    private MediaGenerationStore store;
    private final List<JSONObject> providers = new ArrayList<>();
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private final Runnable refresh = this::refreshHistory;
    private String sessionId, providerId, exportId = "", preferredProvider = "";
    private boolean video, allSessions, refreshing, pendingRefresh, started;
    private EditText prompt;
    private AutoCompleteTextView model;
    private Button typeChoice, providerChoice, sizeChoice, durationChoice, submit;
    private TextView lengthLabel, historyTitle;
    private LinearLayout history;
    private String size = "";
    private int seconds;
    private int shownCount = 20;
    private long submitAgainAt;

    static Intent intent(Context context, String session, String provider) {
        return new Intent(context, MediaStudioActivity.class)
                .putExtra("sessionId", session).putExtra("providerId", provider);
    }

    @Override protected void onCreate(Bundle saved) {
        ThemePalette.applyActivityTheme(this);
        super.onCreate(saved);
        palette = ThemePalette.resolve(this);
        prefs = getSharedPreferences("chat", MODE_PRIVATE);
        store = new MediaGenerationStore(this);
        sessionId = getIntent().getStringExtra("sessionId");
        if (sessionId == null || sessionId.isEmpty()) { finish(); return; }
        providerId = getIntent().getStringExtra("providerId");
        if (saved != null) {
            providerId = saved.getString("providerId", providerId);
            video = saved.getBoolean("video");
            allSessions = saved.getBoolean("all");
            exportId = saved.getString("exportId", "");
            submitAgainAt = saved.getLong("submitAgainAt");
        }
        LinearLayout root = column();
        root.setBackgroundColor(palette.page);
        LinearLayout bar = row();
        Button back = button("‹", v -> finish());
        back.setContentDescription("返回");
        bar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(48)));
        bar.addView(UiStyle.text(this, TITLE, 21, palette.ink, true));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(ScreenInsets.TOP_BAR_DP)));
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = column();
        body.setPadding(dp(18), 0, dp(18), dp(28));
        body.setFocusableInTouchMode(true);
        typeChoice = field(body, "生成类型", v -> choose("生成类型", new String[]{"图片", "视频"}, video ? 1 : 0, index -> {
            if (video != (index == 1)) {
                video = index == 1;
                resetModel();
                renderOptions();
            }
        }));
        providerChoice = field(body, "供应商", v -> chooseProvider());
        label(body, "模型 ID");
        model = new AutoCompleteTextView(this);
        model.setSingleLine(true);
        model.setHint("输入或选择模型 ID");
        model.setContentDescription("模型 ID");
        model.setThreshold(1);
        model.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        model.setFilters(new InputFilter[]{new InputFilter.LengthFilter(256)});
        body.addView(model);
        sizeChoice = field(body, "画幅", v -> chooseSize());
        lengthLabel = label(body, "时长");
        durationChoice = button("", v -> chooseDuration());
        body.addView(durationChoice);
        label(body, "提示词");
        prompt = new EditText(this);
        prompt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        prompt.setGravity(Gravity.TOP | Gravity.START);
        prompt.setMinLines(3);
        prompt.setMaxLines(6);
        prompt.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16000)});
        prompt.setHint("描述要生成的画面");
        prompt.setContentDescription("生成提示词");
        body.addView(prompt);
        submit = button("", v -> generate());
        submit.setTextColor(palette.onPrimary);
        UiStyle.clickable(submit, palette.primary, 16);
        body.addView(submit, spaced());
        LinearLayout heading = row();
        historyTitle = UiStyle.text(this, "生成记录", 19, palette.ink, true);
        heading.addView(historyTitle, new LinearLayout.LayoutParams(0,-2,1));
        Button scope = button(allSessions ? "全部会话" : "当前会话", null);
        scope.setOnClickListener(v -> {
            allSessions = !allSessions;
            scope.setText(allSessions ? "全部会话" : "当前会话");
            shownCount = 20;
            refreshHistory();
        });
        heading.addView(scope);
        body.addView(heading, spaced());
        history = column();
        body.addView(history);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        ScreenInsets.apply(this, root);
        loadProviders();
        resetModel();
        if (saved != null) {
            model.setText(saved.getString("model", ""), false);
            prompt.setText(saved.getString("prompt", ""));
            size = saved.getString("size", "");
            seconds = saved.getInt("seconds");
        }
        renderOptions();
    }

    private void loadProviders() {
        providers.clear();
        JSONArray entries = ModelStore.read(prefs);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject p = entries.optJSONObject(i);
            if (p == null) continue;
            String protocol = p.optString("protocol", ChatApiClient.CHAT_COMPLETIONS);
            if (ChatApiClient.CHAT_COMPLETIONS.equals(protocol) || ChatApiClient.RESPONSES.equals(protocol)
                    || ChatApiClient.GEMINI.equals(protocol)) providers.add(p);
        }
        if (selected() == null) providerId = providers.isEmpty() ? "" : providers.get(0).optString("id");
    }

    private JSONObject selected() {
        for (JSONObject p : providers) if (p.optString("id").equals(providerId)) return p;
        return null;
    }
    private boolean gemini() {
        JSONObject p = selected();
        return p != null && ChatApiClient.GEMINI.equals(p.optString("protocol"));
    }

    private void chooseProvider() {
        List<String> names = new ArrayList<>();
        int checked = -1;
        for (JSONObject p : providers) {
            if (p.optString("id").equals(providerId)) checked = names.size();
            names.add(p.optString("name", "供应商"));
        }
        names.add("管理供应商…");
        choose("供应商", names.toArray(new String[0]), checked, i -> {
            if (i == providers.size()) { startActivity(new Intent(this, ProviderListActivity.class)); return; }
            providerId = providers.get(i).optString("id");
            resetModel();
            renderOptions();
        });
    }

    private String lastModelKey() { return "media.model." + providerId + (video ? ".video" : ".image"); }

    private void resetModel() {
        preferredProvider = providerId;
        LinkedHashSet<String> choices = new LinkedHashSet<>();
        String last = prefs.getString(lastModelKey(), "");
        if (!last.isEmpty()) choices.add(last);
        JSONObject provider = selected();
        String suggestion = gemini() ? (video ? "gemini-omni-1.2-flash" : "gemini-3.1-flash-image")
                : video ? "" : "gpt-image-1";
        if (!suggestion.isEmpty()) choices.add(suggestion);
        JSONArray configured = provider == null ? null : provider.optJSONArray("models");
        if (configured != null) for (int i = 0; i < configured.length(); i++) {
            JSONObject item = configured.optJSONObject(i);
            if (item != null && !item.optString("id").isEmpty()) choices.add(item.optString("id"));
        }
        model.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, new ArrayList<>(choices)));
        model.setText(last.isEmpty() ? suggestion : last, false);
        model.setOnClickListener(v -> model.showDropDown());
        size = "";
        seconds = 0;
    }

    private void chooseSize() {
        String[] values = gemini()
                ? (video ? new String[]{"", "16:9", "9:16"} : new String[]{"", "1:1", "16:9", "9:16", "4:3", "3:4"})
                : (video ? new String[]{"", "1280x720", "720x1280"} : new String[]{"", "1024x1024", "1536x1024", "1024x1536"});
        String[] titles = values.clone();
        titles[0] = "模型默认";
        choose("画幅", titles, java.util.Arrays.asList(values).indexOf(size), i -> {size = values[i]; renderOptions();});
    }

    private void chooseDuration() {
        int[] values = gemini() ? new int[]{0, 4, 8} : new int[]{0, 4, 8, 12};
        String[] titles = new String[values.length];
        int selected = 0;
        for (int i=0; i<values.length; i++) {
            titles[i] = values[i]==0 ? "模型默认" : values[i]+" 秒";
            if (seconds==values[i]) selected=i;
        }
        choose("时长", titles, selected, i -> {seconds=values[i]; renderOptions();});
    }

    private void renderOptions() {
        JSONObject p=selected();
        typeChoice.setText(video ? "视频" : "图片");
        providerChoice.setText(p==null ? "配置供应商…" : p.optString("name", "供应商"));
        sizeChoice.setText(size.isEmpty() ? "模型默认" : size);
        durationChoice.setVisibility(video ? View.VISIBLE : View.GONE);
        lengthLabel.setVisibility(video ? View.VISIBLE : View.GONE);
        durationChoice.setText(seconds==0 ? "模型默认" : seconds+" 秒");
        submit.setText(video ? "生成视频" : "生成图片");
        long wait = submitAgainAt - android.os.SystemClock.uptimeMillis();
        submit.setEnabled(p != null && wait <= 0);
        if (wait > 0) submit.postDelayed(() -> {
            if (!isDestroyed() && android.os.SystemClock.uptimeMillis() >= submitAgainAt) submit.setEnabled(selected() != null);
        }, wait);
    }

    private void generate() {
        if (android.os.SystemClock.uptimeMillis() < submitAgainAt) return;
        String text=prompt.getText().toString().trim(), modelId=model.getText().toString().trim();
        if (modelId.isEmpty()) {model.setError("请输入模型 ID");model.requestFocus();return;}
        if (text.isEmpty()) {prompt.setError("请输入提示词");prompt.requestFocus();return;}
        JSONObject provider=selected();
        if (provider==null) return;
        try {
            JSONObject job=new JSONObject().put("id", MediaGenerationStore.newId()).put("sessionId",sessionId)
                    .put("createdAt",System.currentTimeMillis()).put("providerId",providerId)
                    .put("providerName",provider.optString("name")).put("endpoint",MediaGenerationService.endpointIdentity(provider))
                    .put("model",modelId).put("prompt",text).put("video",video).put("size",size)
                    .put("seconds",video?seconds:0);
            MediaGenerationService.start(this,job);
            submitAgainAt = android.os.SystemClock.uptimeMillis() + 1000;
            renderOptions();
            prefs.edit().putString(lastModelKey(),modelId).apply();
            toast("已提交生成任务");
        } catch (Exception failure) {toast(ApiFailure.local(failure));}
    }

    @Override protected void onStart() {
        super.onStart();
        if (history==null) return;
        started=true;
        loadProviders();
        if (!providerId.equals(preferredProvider)) resetModel();
        renderOptions();
        MediaGenerationService.listen(refresh);
        refreshHistory();
    }
    @Override protected void onStop() {started=false; MediaGenerationService.unlisten(refresh);super.onStop();}
    @Override protected void onDestroy() {files.shutdown();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (prompt==null) return;
        out.putString("prompt",prompt.getText().toString());
        out.putString("model",model.getText().toString());
        out.putString("providerId",providerId);
        out.putBoolean("video",video);out.putString("size",size);out.putInt("seconds",seconds);
        out.putBoolean("all",allSessions);out.putString("exportId",exportId);
        out.putLong("submitAgainAt", submitAgainAt);
    }

    private void refreshHistory() {
        if (!started) return;
        if (refreshing) {pendingRefresh=true; return;}
        refreshing=true;
        String filter=allSessions ? null : sessionId;
        files.execute(() -> {
            List<JSONObject> records=store.list(filter);
            MediaGenerationService.recover(store, records);
            runOnUiThread(() -> {
                refreshing=false;
                if (started && !isDestroyed()) renderHistory(records);
                if (pendingRefresh) {pendingRefresh=false;refreshHistory();}
            });
        });
    }

    private void renderHistory(List<JSONObject> records) {
        history.removeAllViews();
        if (records.isEmpty()) history.addView(UiStyle.text(this,"暂无生成记录",14,palette.muted,false));
        for (int i=0; i<Math.min(shownCount,records.size()); i++) {
            JSONObject job=records.get(i);
            String id=job.optString("id"), state=job.optString("state");
            boolean active=MediaGenerationService.active(id);
            boolean completed="complete".equals(state) && store.resultFile(id).isFile();
            String status=active ? job.optString("detail","生成中") : completed ? "已完成" :
                    "running".equals(state) ? "生成已中断" : "publishing".equals(state) ? "等待恢复生成文件" :
                    "complete".equals(state) ? "生成文件缺失" : job.optString("detail","生成失败");
            LinearLayout card=column();
            card.setPadding(dp(14),dp(10),dp(14),dp(10));
            card.setBackground(UiStyle.surface(this,palette.surface,16,true));
            String type=job.optBoolean("video")?"视频":"图片";
            card.addView(UiStyle.text(this,type+" · "+job.optString("model"),15,palette.ink,true));
            TextView description=UiStyle.text(this,job.optString("prompt"),15,palette.ink,false);
            description.setMaxLines(3);
            description.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(description);
            card.addView(UiStyle.text(this,status,13,palette.muted,false));
            LinearLayout actions=row();
            if (completed) {
                action(actions,"预览",v -> MediaPreview.show(this,store.resultFile(id),job.optString("mime")));
                action(actions,"保存到…",v -> saveAs(job));
            } else if (active) {
                action(actions,"停止",v -> {
                    v.setEnabled(false);
                    MediaGenerationService.stop(id);
                    toast("正在停止接收，供应商可能仍在生成。");
                });
            } else {
                if (!job.optString("remoteId").isEmpty()) action(actions,"继续获取",v -> resume(job));
                action(actions,"重新生成",v -> regenerate(job));
            }
            if (!active) action(actions,"删除",v -> delete(job));
            card.addView(actions);
            history.addView(card,spaced());
        }
        if (shownCount<records.size()) history.addView(button("显示更多",v->{shownCount+=20;refreshHistory();}));
    }

    private void resume(JSONObject job) {
        try {MediaGenerationService.start(this,job);}catch(Exception e){toast(ApiFailure.local(e));}
    }
    private void regenerate(JSONObject job) {
        new AlertDialog.Builder(this).setTitle("重新生成").setMessage("使用原提示词和模型提交新任务，供应商可能再次计费。")
                .setNegativeButton("取消",null).setPositiveButton("生成",(d,w)->{
                    try {
                        JSONObject copy=new JSONObject(job.toString());
                        copy.remove("remoteId");copy.remove("mime");
                        copy.put("id",MediaGenerationStore.newId()).put("createdAt",System.currentTimeMillis());
                        MediaGenerationService.start(this,copy);
                    } catch(Exception e){toast(ApiFailure.local(e));}
                }).show();
    }
    private void delete(JSONObject job) {
        new AlertDialog.Builder(this).setTitle("删除生成记录？").setMessage("此操作会同时删除应用内保存的生成文件。")
                .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{
                    String id=job.optString("id");
                    if (MediaGenerationService.active(id)) return;
                    files.execute(()->{try {store.delete(id);}catch(Exception e){runOnUiThread(()->toast(ApiFailure.local(e)));}
                        runOnUiThread(refresh);});
                }).show();
    }
    private void saveAs(JSONObject job) {
        exportId=job.optString("id");
        try {startActivityForResult(MediaPreview.createDocument(job.optString("mime"),exportId),SAVE);}
        catch(RuntimeException e){exportId="";toast("无法打开文件选择器");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request!=SAVE) return;
        String id=exportId;
        exportId="";
        Uri uri=data==null?null:data.getData();
        if(result!=RESULT_OK||uri==null||id.isEmpty())return;
        File file=store.resultFile(id);
        files.execute(()->{
            try {MediaPreview.export(getApplicationContext(),file,uri);runOnUiThread(()->toast("文件已保存"));}
            catch(Exception failure){runOnUiThread(()->toast(ApiFailure.local(failure)));}
        });
    }

    private interface Selected {void select(int index);}
    private void choose(String title,String[] items,int checked,Selected selected) {
        ChoiceDialogs.single(this,title,items,checked,selected::select);
    }
    private int dp(int value){return UiStyle.dp(this,value);}
    private LinearLayout column(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);return c;}
    private LinearLayout row(){LinearLayout c=new LinearLayout(this);c.setGravity(Gravity.CENTER_VERTICAL);return c;}
    private TextView label(LinearLayout p,String text){
        TextView v=UiStyle.text(this,text,14,palette.ink,true);p.addView(v,spaced());return v;
    }
    private Button button(String text,View.OnClickListener listener){
        Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextColor(palette.ink);
        UiStyle.clickable(b,palette.surface,14);b.setOnClickListener(listener);return b;
    }
    private Button field(LinearLayout p,String name,View.OnClickListener listener){
        label(p,name);Button b=button("",listener);p.addView(b);return b;
    }
    private void action(LinearLayout p,String text,View.OnClickListener listener){
        Button b=button(text,listener);b.setMinWidth(0);b.setMinimumWidth(0);
        p.addView(b,new LinearLayout.LayoutParams(0,-2,1));
    }
    private LinearLayout.LayoutParams spaced(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(10);return p;}
    private void toast(String s){Toast.makeText(getApplicationContext(),s,Toast.LENGTH_LONG).show();}
}
