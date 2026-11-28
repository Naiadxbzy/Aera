package com.changyu.ochat;

import android.content.Context;
import android.os.*;
import android.text.*;
import android.text.style.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.*;

/** Recycled history rows, debounced off-thread full-text search with exact message targets. */
final class HistoryBrowser {
 interface Open {void open(String sessionId,String messageId,String query);}
 private final Context context;private final ThemePalette palette;private final Handler main=new Handler(Looper.getMainLooper());private final ExecutorService search=Executors.newSingleThreadExecutor();
 private final List<Row> rows=new ArrayList<>();private final Map<String,Index> index=new HashMap<>();private final BaseAdapter adapter;private final ListView list;
 private volatile int generation;
 private Runnable debounce;
 private Future<?> task;
 private String query="",current="";
 private Pattern pattern;
 private boolean archived;
 private List<JSONObject> sessions=Collections.emptyList();
 private Set<String> drafts=Collections.emptySet();
 private final Open open;
 private final Consumer<String> actions;
 static final class Row {String id,title,snippet,message,group;boolean selected;}
 static final class Index {JSONObject snapshot;String title;final List<String[]> messages=new ArrayList<>();}
 HistoryBrowser(Context context,Open open,Consumer<String> actions){
  this.context=context;this.open=open;this.actions=actions;palette=ThemePalette.resolve(context);list=new ListView(context);list.setDivider(null);list.setClipToPadding(false);list.setPadding(0,UiStyle.dp(context,8),0,UiStyle.dp(context,16));
  adapter=new BaseAdapter(){
   public int getCount(){return rows.size();}public Object getItem(int i){return rows.get(i);}public long getItemId(int i){return i;}
   public View getView(int position,View convert,ViewGroup parent){
    LinearLayout box;if(convert instanceof LinearLayout)box=(LinearLayout)convert;else{box=new LinearLayout(context);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(UiStyle.dp(context,14),0,UiStyle.dp(context,14),0);for(int size:new int[]{12,15,12})box.addView(UiStyle.text(context,"",size,palette.ink,false),new LinearLayout.LayoutParams(-1,-2));}
    Row row=rows.get(position);TextView group=(TextView)box.getChildAt(0),title=(TextView)box.getChildAt(1),snippet=(TextView)box.getChildAt(2);
    group.setText(row.group);group.setVisibility(row.group.isEmpty()?View.GONE:View.VISIBLE);group.setTextColor(palette.muted);group.setPadding(0,UiStyle.dp(context,14),0,UiStyle.dp(context,8));
    title.setText(row.title);title.setTextColor(row.selected?palette.primary:palette.ink);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);title.setMinHeight(UiStyle.dp(context,48));title.setGravity(Gravity.CENTER_VERTICAL);
    snippet.setText(highlight(row.snippet));snippet.setTextColor(palette.muted);snippet.setMaxLines(2);snippet.setEllipsize(TextUtils.TruncateAt.END);snippet.setPadding(0,0,0,UiStyle.dp(context,10));snippet.setVisibility(row.snippet.isEmpty()?View.GONE:View.VISIBLE);
    UiStyle.clickable(box,row.selected?androidx.core.graphics.ColorUtils.blendARGB(palette.page,palette.primary,.08f):palette.page,12,false);
    box.setContentDescription(row.title+(row.snippet.isEmpty()?"":"，"+row.snippet)+"，长按管理");return box;
   }
  };
  list.setAdapter(adapter);list.setOnItemClickListener((p,v,i,id)->{Row r=rows.get(i);open.open(r.id,r.message,query);});list.setOnItemLongClickListener((p,v,i,id)->{actions.accept(rows.get(i).id);return true;});
 }
 View view(){return list;}
 void filter(String query,boolean archived){
  this.query=query.trim();
  pattern=this.query.isEmpty()?null:TextSearch.pattern(this.query,false,false);
  this.archived=archived;
  refresh(sessions,current,drafts);
 }

 void refresh(List<JSONObject> all,String current,Set<String> drafts){
  sessions=all;
  this.drafts=drafts;
  this.current=current;
  final int ticket=++generation;
  final Pattern term=pattern;
  final boolean filterArchive=archived;
  if(debounce!=null)main.removeCallbacks(debounce);
  if(task!=null)task.cancel(true);
  debounce=()->task=search.submit(()->build(all,current,term,filterArchive,drafts,ticket));
  main.postDelayed(debounce,term==null?0:180);
 }

 private void build(List<JSONObject> all,String current,Pattern term,boolean archived,Set<String> drafts,int ticket){
  List<Row> found=new ArrayList<>();
  Map<String,Long> times=new HashMap<>();
  Set<String> alive=new HashSet<>();
  long now=System.currentTimeMillis();
  for(JSONObject session:all){
   if(ticket!=generation)return;
   String id=session.optString("id");
   alive.add(id);
   if(session.optBoolean("archived")!=archived)continue;
   Index doc=index.get(id);
   if(doc==null||doc.snapshot!=session){
    doc=new Index();
    doc.snapshot=session;
    doc.title=session.optString("title","新对话");
    JSONArray messages=session.optJSONArray("messages");
    for(int j=0;messages!=null&&j<messages.length();j++){
     if(ticket!=generation)return;
     JSONObject message=messages.optJSONObject(j);
     if(message!=null)doc.messages.add(new String[]{message.optString("id"),message.optString("text")});
    }
    index.put(id,doc);
   }
   Row row=new Row();
   row.id=id;
   row.title=(session.optBoolean("pinned")?"置顶 · ":"")+doc.title;
   row.selected=id.equals(current);
   row.snippet=term==null&&drafts.contains(id)?"草稿":"";
   row.message="";
   boolean match=term==null||term.matcher(doc.title).find();
   if(term!=null)for(String[] message:doc.messages){
    if(ticket!=generation)return;
    Matcher matcher=term.matcher(message[1]);
    if(matcher.find()){
     match=true;
     row.message=message[0];
     row.snippet=TextSearch.excerpt(message[1],matcher.start(),matcher.end());
     break;
    }
   }
   if(match){
    long timestamp=session.optLong("updatedAt",session.optLong("createdAt",HistoryDates.parseLegacy(session.optString("time"),now)));
    row.group=session.optBoolean("pinned")?"置顶":HistoryDates.group(timestamp,now);
    found.add(row);
    times.put(id,timestamp);
   }
  }
  index.keySet().retainAll(alive);
  found.sort((a,b)->{
   boolean ap=a.group.equals("置顶"),bp=b.group.equals("置顶");
   if(ap!=bp)return ap?-1:1;
   return Long.compare(times.get(b.id),times.get(a.id));
  });
  String previous="";
  for(Row row:found){
   String group=row.group;
   row.group=group.equals(previous)?"":group;
   previous=group;
  }
  main.post(()->{
   if(ticket!=generation)return;
   rows.clear();
   rows.addAll(found);
   adapter.notifyDataSetChanged();
  });
 }

 private CharSequence highlight(String text){
  if(pattern==null)return text;
  Matcher matcher=pattern.matcher(text);
  if(!matcher.find())return text;
  SpannableString value=new SpannableString(text);
  value.setSpan(new ForegroundColorSpan(palette.primary),matcher.start(),matcher.end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
  return value;
 }

 void close(){
  generation++;
  main.removeCallbacksAndMessages(null);
  search.shutdownNow();
 }
}
