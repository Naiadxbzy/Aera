package com.changyu.ochat;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Mutable session data belongs to the UI thread. Disk and search use detached snapshots. */
final class SessionRepository {
 private static final String SESSIONS="sessions", CHECKPOINT="draft_", EDITOR="composer_draft_";
 private static final long SWEEP_INTERVAL=60L*60*1000;
 private static final Map<SharedPreferences,SessionRepository> INSTANCES=new WeakHashMap<>();
 static synchronized SessionRepository get(SharedPreferences prefs){
  return INSTANCES.computeIfAbsent(prefs,SessionRepository::new);
 }

 private final SharedPreferences prefs;
 private final ExecutorService disk=Executors.newSingleThreadExecutor();
 private final Map<String,Snapshot> snapshots=new LinkedHashMap<>();
 private final Map<String,JSONObject> checkpointChanges=new LinkedHashMap<>(),draftChanges=new LinkedHashMap<>();
 private volatile Context snapshotContext;
 private long lastSnapshotSweep=-SWEEP_INTERVAL;
 private JSONArray cached;
 private String observed;
 private long revision;
 private boolean sessionsDirty, writeScheduled;

 private SessionRepository(SharedPreferences prefs){this.prefs=prefs;}

 private static final class Snapshot {
  final JSONObject value;
  private String encoded;
  Snapshot(JSONObject value){this.value=detached(value);}
  String encode(){
   if(encoded==null)encoded=value.toString();
   return encoded;
  }
 }

 /** Captures containers while sharing immutable values such as text and attachment strings. */
 static JSONObject detached(JSONObject object){
  try{return (JSONObject)copy(object,new IdentityHashMap<>());}
  catch(JSONException invalid){throw new IllegalArgumentException("会话数据无效",invalid);}
 }

 private static Object copy(Object value,Map<Object,Object> copied)throws JSONException{
  Object existing=copied.get(value);
  if(existing!=null)return existing;
  if(value instanceof JSONObject){
   JSONObject source=(JSONObject)value,result=new JSONObject();
   copied.put(source,result);
   for(Iterator<String> keys=source.keys();keys.hasNext();){
    String key=keys.next();
    result.put(key,copy(source.opt(key),copied));
   }
   return result;
  }
  if(value instanceof JSONArray){
   JSONArray source=(JSONArray)value,result=new JSONArray();
   copied.put(source,result);
   for(int i=0;i<source.length();i++)result.put(copy(source.opt(i),copied));
   return result;
  }
  return value;
 }

 synchronized JSONArray read(){
  String raw=prefs.getString(SESSIONS,"[]");
  if(cached==null||!sessionsDirty&&!Objects.equals(raw,observed)){
   try{
    cached=new JSONArray(raw);
    MessageEdits.ensureIds(cached);
   }catch(JSONException invalid){
    cached=new JSONArray();
    Log.w("Sessions","Unable to parse saved conversations",invalid);
   }
   index(cached);
   observed=raw;
  }
  return cached;
 }

 /** Values are read-only and remain valid after subsequent writes. */
 synchronized List<JSONObject> snapshots(){
  read();
  List<JSONObject> result=new ArrayList<>(snapshots.size());
  for(Snapshot snapshot:snapshots.values())result.add(snapshot.value);
  return result;
 }

 synchronized JSONObject snapshot(String id){
  read();
  Snapshot snapshot=snapshots.get(id);
  return snapshot==null?null:snapshot.value;
 }

 private void index(JSONArray sessions){
  snapshots.clear();
  for(int i=0;i<sessions.length();i++){
   JSONObject session=sessions.optJSONObject(i);
   if(session!=null)snapshots.put(session.optString("id"),new Snapshot(session));
  }
 }

 synchronized void replace(JSONArray sessions){replaceAndClear(sessions,Collections.emptySet());}

 synchronized void replaceAndClear(JSONArray sessions,Collection<String> checkpoints){
  read();
  Set<String> removed=new HashSet<>(snapshots.keySet());
  cached=sessions;
  index(sessions);
  removed.removeAll(snapshots.keySet());
  for(String id:removed)draftChanges.put(id,null);
  for(String id:checkpoints)checkpointChanges.put(id,null);
  sessionsDirty=true;
  enqueue();
 }

 synchronized void upsert(JSONObject session){update(session,false);}

 synchronized void complete(JSONObject session){update(session,true);}

 synchronized void upsertAfterSend(JSONObject session){
  draftChanges.put(session.optString("id"),null);
  update(session,false);
 }

 synchronized JSONObject composerDraft(String id){
  if(draftChanges.containsKey(id)){
   JSONObject draft=draftChanges.get(id);
   return draft==null?null:detached(draft);
  }
  String raw=prefs.getString(EDITOR+id,"");
  if(raw.isEmpty())return null;
  try{return new JSONObject(raw);}
  catch(JSONException invalid){Log.w("Sessions","Unable to read composer draft",invalid);return null;}
 }

 synchronized Set<String> draftIds(){
  Set<String> result=new HashSet<>();
  for(String key:prefs.getAll().keySet())if(key.startsWith(EDITOR))result.add(key.substring(EDITOR.length()));
  for(Map.Entry<String,JSONObject> change:draftChanges.entrySet()){
   if(change.getValue()==null)result.remove(change.getKey());
   else result.add(change.getKey());
  }
  return result;
 }

 synchronized boolean hasDraft(String id){
  return draftChanges.containsKey(id)?draftChanges.get(id)!=null:prefs.contains(EDITOR+id);
 }

 synchronized void saveComposerDraft(String id,JSONObject draft){
  read();
  if(!snapshots.containsKey(id)||draft==null&&!hasDraft(id))return;
  draftChanges.put(id,draft==null?null:detached(draft));
  enqueue();
 }

 private void update(JSONObject session,boolean clearCheckpoint){
  JSONArray all=read();
  String id=session.optString("id");
  int at=0;
  for(;at<all.length();at++){
   JSONObject old=all.optJSONObject(at);
   if(old!=null&&id.equals(old.optString("id")))break;
  }
  try{all.put(at,session);}
  catch(JSONException invalid){throw new IllegalArgumentException("无法保存对话",invalid);}
  snapshots.put(id,new Snapshot(session));
  if(clearCheckpoint)checkpointChanges.put(id,null);
  sessionsDirty=true;
  enqueue();
 }

 synchronized void checkpoint(String id,JSONObject draft){
  checkpointChanges.put(id,detached(draft));
  enqueue();
 }

 synchronized void clearCheckpoint(String id){
  checkpointChanges.put(id,null);
  enqueue();
 }

 synchronized String checkpoint(String id){
  if(checkpointChanges.containsKey(id)){
   JSONObject value=checkpointChanges.get(id);
   return value==null?"":value.toString();
  }
  return prefs.getString(CHECKPOINT+id,"");
 }

 private void enqueue(){
  revision++;
  if(!writeScheduled){
   writeScheduled=true;
   disk.execute(this::writePending);
  }
 }

 private void writePending(){
  while(true){
   long writing;
   List<Snapshot> sessions;
   Map<String,JSONObject> checkpoints,drafts;
   synchronized(this){
    writing=revision;
    sessions=sessionsDirty?new ArrayList<>(snapshots.values()):null;
    checkpoints=new LinkedHashMap<>(checkpointChanges);
    drafts=new LinkedHashMap<>(draftChanges);
   }
   String value=null;
   if(sessions!=null){
    StringJoiner json=new StringJoiner(",","[","]");
    for(Snapshot session:sessions)json.add(session.encode());
    value=json.toString();
   }
   SharedPreferences.Editor editor=prefs.edit();
   if(value!=null)editor.putString(SESSIONS,value);
   updateJson(editor,CHECKPOINT,checkpoints);
   updateJson(editor,EDITOR,drafts);
   synchronized(this){if(writing!=revision)continue;}
   boolean saved=editor.commit();
   synchronized(this){
    if(value!=null)observed=value;
    if(writing!=revision)continue;
    if(saved){
     sessionsDirty=false;
     checkpointChanges.clear();
     draftChanges.clear();
    }
    writeScheduled=false;
   }
   if(saved)pruneSnapshots();
   else Log.e("Sessions","Unable to write conversations to disk");
   return;
  }
 }

 private static void updateJson(SharedPreferences.Editor editor,String prefix,Map<String,JSONObject> changes){
  for(Map.Entry<String,JSONObject> entry:changes.entrySet()){
   String key=prefix+entry.getKey();
   if(entry.getValue()==null)editor.remove(key);
   else editor.putString(key,entry.getValue().toString());
  }
 }

 /** Register restored drafts before asynchronous cleanup and retain application context only. */
 synchronized void manageSnapshots(Context context,Collection<JSONObject> pendingAttachments){
  AttachmentSnapshots.keepAlive(pendingAttachments);
  snapshotContext=context.getApplicationContext();
  read();
  disk.execute(this::pruneSnapshots);
 }

 private void pruneSnapshots(){
  Context context=snapshotContext;
  if(context==null)return;
  long elapsed=SystemClock.elapsedRealtime();
  if(elapsed-lastSnapshotSweep<SWEEP_INTERVAL)return;
  lastSnapshotSweep=elapsed;
  List<Snapshot> current;
  synchronized(this){current=new ArrayList<>(snapshots.values());}
  List<String> records=new ArrayList<>();
  for(Snapshot snapshot:current)records.add(snapshot.encode());
  for(Map.Entry<String,?> entry:prefs.getAll().entrySet()){
   String key=entry.getKey();
   if((SESSIONS.equals(key)||key.startsWith(CHECKPOINT)||key.startsWith(EDITOR))&&entry.getValue() instanceof String){
    records.add((String)entry.getValue());
   }
  }
  AttachmentSnapshots.prune(context,records,System.currentTimeMillis());
 }

 void flush()throws Exception{disk.submit(()->{}).get(10,TimeUnit.SECONDS);}
}
