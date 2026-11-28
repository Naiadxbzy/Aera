package com.changyu.ochat;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.*;
import android.text.style.ForegroundColorSpan;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Session-local navigation and options for searching saved messages. */
final class ConversationFindBar extends LinearLayout implements AutoCloseable {
 private static final String CASE="findMatchCase",WORD="findWholeWord",THINKING="findThinking";
 private final SharedPreferences prefs;
 private final ThemePalette palette;
 private final Supplier<JSONObject> snapshot;
 private final java.util.function.Predicate<ConversationMatches.Hit> open;
 private final Runnable clearHighlight;
 private final java.util.function.Consumer<Boolean> modeChanged;
 private final Handler main=new Handler(Looper.getMainLooper());
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final EditText query;
 private final TextView count,preview,previous,next;
 private volatile int generation;
 private Runnable pending;
 private Future<?> task;
 private List<ConversationMatches.Hit> hits=Collections.emptyList();
 private Pattern active;
 private int selected=-1;

 ConversationFindBar(Context context,SharedPreferences prefs,Supplier<JSONObject> snapshot,
  java.util.function.Predicate<ConversationMatches.Hit> open,Runnable clearHighlight,
  java.util.function.Consumer<Boolean> modeChanged){
  super(context);
  this.prefs=prefs;this.snapshot=snapshot;this.open=open;this.clearHighlight=clearHighlight;this.modeChanged=modeChanged;
  palette=ThemePalette.resolve(context);
  setOrientation(VERTICAL);
  setPadding(dp(16),0,dp(16),dp(8));
  setVisibility(GONE);
  LinearLayout controls=new LinearLayout(context);
  controls.setGravity(Gravity.CENTER_VERTICAL);
  query=EditorDialogs.editor(EditorDialogs.themedContext(context));
  query.setSingleLine(true);query.setTextSize(15);query.setHint("查找当前对话");
  query.setContentDescription("查找当前对话");query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
  query.setPadding(dp(12),0,dp(12),0);
  query.setBackground(UiStyle.surface(context,palette.surface,14,true));
  controls.addView(query,new LayoutParams(0,dp(46),1));
  controls.addView(button("⋮","查找选项",this::options));
  controls.addView(button("×","关闭查找",this::hide));
  addView(controls,new LayoutParams(-1,-2));
  LinearLayout navigation=new LinearLayout(context);
  navigation.setGravity(Gravity.CENTER_VERTICAL);
  preview=UiStyle.text(context,"",13,palette.ink,false);
  preview.setMaxLines(2);preview.setEllipsize(TextUtils.TruncateAt.END);
  preview.setPadding(dp(5),0,dp(4),0);
  navigation.addView(preview,new LayoutParams(0,-2,1));
  count=UiStyle.text(context,"",12,palette.muted,true);
  count.setGravity(Gravity.CENTER);
  navigation.addView(count,new LayoutParams(-2,dp(44)));
  previous=button("↑","上一个匹配",()->move(-1));
  next=button("↓","下一个匹配",()->move(1));
  navigation.addView(previous);
  navigation.addView(next);
  addView(navigation,new LayoutParams(-1,-2));
  updateControls("");
  query.addTextChangedListener(new TextWatcher(){
   public void beforeTextChanged(CharSequence value,int start,int count,int after){}
   public void onTextChanged(CharSequence value,int start,int before,int count){search(false);}
   public void afterTextChanged(Editable value){}
  });
  query.setOnEditorActionListener((view,action,event)->{
   if(action!=EditorInfo.IME_ACTION_SEARCH)return false;
   hideKeyboard();
   if(selected>=0)showHit(false);
   return true;
  });
 }

 boolean showing(){return getVisibility()==VISIBLE;}
 void show(){
  setVisibility(VISIBLE);
  modeChanged.accept(true);
  query.requestFocus();
  query.post(()->{
   InputMethodManager keyboard=getContext().getSystemService(InputMethodManager.class);
   if(showing()&&keyboard!=null)keyboard.showSoftInput(query,InputMethodManager.SHOW_IMPLICIT);
  });
  search(false);
 }

 void hide(){
  if(!showing())return;
  hideKeyboard();
  invalidateSearch();
  setVisibility(GONE);
  modeChanged.accept(false);
  query.clearFocus();
  query.setText("");
  hits=Collections.emptyList();
  selected=-1;
  clearHighlight.run();
 }

 void refresh(){if(showing())search(true);}

 private void search(boolean retain){
  if(!showing())return;
  ConversationMatches.Hit previous=retain&&selected>=0?hits.get(selected):null;
  int ticket=invalidateSearch();
  String term=query.getText().toString().trim();
  hits=Collections.emptyList();
  selected=-1;
  if(term.isEmpty()){active=null;clearHighlight.run();updateControls("");return;}
  if(!retain)clearHighlight.run();
  Pattern pattern=TextSearch.pattern(term,prefs.getBoolean(CASE,false),prefs.getBoolean(WORD,false));
  boolean thinking=prefs.getBoolean(THINKING,false);
  JSONObject session=snapshot.get();
  active=pattern;
  updateControls("查找中");
  pending=()->task=worker.submit(()->{
   List<ConversationMatches.Hit> result=ConversationMatches.find(session,pattern,thinking,()->ticket==generation);
   main.post(()->{
    if(ticket!=generation||!showing())return;
    hits=result;
    if(hits.isEmpty()){clearHighlight.run();updateControls("0 条");return;}
    selected=0;
    if(previous!=null)for(int i=0;i<hits.size();i++){
     ConversationMatches.Hit hit=hits.get(i);
     if(previous.id.equals(hit.id)&&previous.reasoning==hit.reasoning){selected=i;break;}
    }
    showHit(previous==null||!previous.id.equals(hits.get(selected).id));
   });
  });
  main.postDelayed(pending,150);
 }

 private void move(int direction){
  if(hits.size()<2)return;
  selected=(selected+direction+hits.size())%hits.size();
  showHit(true);
 }

 private void showHit(boolean locate){
  ConversationMatches.Hit hit=hits.get(selected);
  if(locate&&!open.test(hit)){search(false);return;}
  updateControls((selected+1)+" / "+hits.size()+" 条");
  String value=(hit.reasoning?"思考 · ":"")+hit.excerpt;
  SpannableString text=new SpannableString(value);
  Matcher matcher=active.matcher(value);
  if(matcher.find())text.setSpan(new ForegroundColorSpan(palette.primary),matcher.start(),matcher.end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
  preview.setText(text);
 }

 private void updateControls(String value){
  UiStyle.textIfChanged(count,value);
  if(hits.isEmpty())preview.setText("");
  for(TextView button:new TextView[]{previous,next}){
   button.setEnabled(hits.size()>1);
   button.setAlpha(button.isEnabled()?1f:.43f);
  }
 }

 private void options(){
  String[] labels={"区分大小写","匹配完整单词","包含思考内容"};
  boolean[] checked={prefs.getBoolean(CASE,false),prefs.getBoolean(WORD,false),prefs.getBoolean(THINKING,false)};
  ChoiceDialogs.multiple(getContext(),"查找选项",labels,checked,values->{
   prefs.edit().putBoolean(CASE,values[0]).putBoolean(WORD,values[1]).putBoolean(THINKING,values[2]).apply();
   search(false);
  });
 }

 private TextView button(String label,String description,Runnable click){
  TextView view=UiStyle.text(getContext(),label,23,palette.primary,true);
  view.setGravity(Gravity.CENTER);view.setContentDescription(description);
  view.setLayoutParams(new LayoutParams(dp(46),dp(48)));
  UiStyle.clickable(view,palette.page,14,false);
  view.setOnClickListener(v->click.run());
  return view;
 }

 private int invalidateSearch(){
  if(pending!=null)main.removeCallbacks(pending);
  if(task!=null)task.cancel(true);
  return ++generation;
 }

 private void hideKeyboard(){
  InputMethodManager keyboard=getContext().getSystemService(InputMethodManager.class);
  if(keyboard!=null)keyboard.hideSoftInputFromWindow(query.getWindowToken(),0);
 }

 @Override public void close(){
  invalidateSearch();
  main.removeCallbacksAndMessages(null);
  worker.shutdownNow();
 }

 private int dp(int value){return UiStyle.dp(getContext(),value);}
}
