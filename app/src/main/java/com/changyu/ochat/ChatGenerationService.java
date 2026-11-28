package com.changyu.ochat;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import androidx.core.app.NotificationCompat;

import androidx.core.content.ContextCompat;
import org.json.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

/** Owns the request and its persisted result independently of any Activity or view. */
public final class ChatGenerationService extends Service {
 static final String CHANNEL="chat_generation",STOP="com.changyu.ochat.STOP_GENERATION";
 private static final int NOTICE=71;
 private static volatile Run current;
 private static final Map<String,Run> runs=new ConcurrentHashMap<>();
 private static WeakReference<Listener> listener=new WeakReference<>(null);
 interface Listener {void changed(Run run);}
 static final class Run {
  final String id=UUID.randomUUID().toString(),sessionId,modelId,effort;
  final long timestamp;final StringBuffer partial=new StringBuffer(),reasoningText=new StringBuffer();
  final ChatApiClient.CancellationHandle cancel=new ChatApiClient.CancellationHandle();
  ChatApiClient client=new ChatApiClient(cancel),ocrClient;
  String base,key,protocol,branchId="",temperature="0.7",topP="0.9",outputTokens=ContextBudget.DEFAULT_OUTPUT_TEXT,query="",status="正在准备上下文和附件…";
  JSONArray history;JSONObject session;Map<String,String> prepared=Collections.emptyMap();
  final PdfPageFallback.Session pdfPages=new PdfPageFallback.Session();boolean pdfFallbackTried;String pdfOriginalError="";
  SearchOptions search=SearchOptions.OFF;SearchSources sources=new SearchSources();
  volatile boolean finished;private volatile String sourceUpdate="";boolean error,stopped,started;StreamingMarkdown stream,reasoningStream;
  boolean enhanced,networkDisrupted;String phaseStatus="正在准备上下文和附件…",networkNote="";Runnable pendingNetworkAction;long waitingSince,startedElapsed;
  JSONObject configuredModel=new JSONObject(),usage=new JSONObject();String completionState="complete",finishReason="",searchNote="",searchStatus="";final JSONArray toolEvents=new JSONArray(),generatedImages=new JSONArray();final StringBuffer imagePlanRaw=new StringBuffer();ImageToolConfig imageTool;int contextTokens,omittedMessages;long startedAt;
  Future<?> work;
  StreamingMarkdown.Updates renderUpdates;
  String lastCheckpointAnswer,lastCheckpointThought,lastCheckpointStatus;
  int lastCheckpointTools=-1,lastCheckpointImages=-1;
  volatile boolean stopRequested;
  SearchSources lastCheckpointSources;
  String answer(){return stream==null?partial.toString():stream.snapshot();}
  String thought(){return reasoningStream==null?reasoningText.toString():reasoningStream.snapshot();}
  Run(String sessionId,String modelId,String effort,long timestamp){this.sessionId=sessionId;this.modelId=modelId;this.effort=effort;this.timestamp=timestamp;}
 }
 private final Handler main=new Handler(Looper.getMainLooper());
 private final ExecutorService worker=Executors.newFixedThreadPool(2);
 private final Set<Run> ownedRuns=new HashSet<>();
 private PowerManager.WakeLock wake;
 private long wakeRenewAt;
 private GenerationNetworkMonitor network;
 private boolean foreground;
 private final Runnable checkpoint=new Runnable(){public void run(){
  for(Run run:new ArrayList<>(runs.values()))if(valid(run)&&run.started){
   persist(run,false,false);
   if(run.waitingSince>0&&SystemClock.elapsedRealtime()-run.waitingSince>=10*60*1000L){run.finishReason="等待网络恢复超过 10 分钟，请检查网络与后台设置后重试";finish(run,run.finishReason,true,false);}
   else if(!run.enhanced&&SystemClock.elapsedRealtime()-run.startedElapsed>30*60*1000L){run.finishReason="本次生成已达 30 分钟，可在设置 → 后台连接开启普通增强";finish(run,run.finishReason,true,true);}
  }
  if(!runs.isEmpty())renewWake();
  main.removeCallbacks(this);if(!runs.isEmpty())main.postDelayed(this,3000);
 }};
 static Run active(){return current;}
 static Run active(String sessionId){return runs.get(sessionId);}
 static int activeCount(){return runs.size();}
 static void attach(Listener target){listener=new WeakReference<>(target);for(Run run:new ArrayList<>(runs.values()))target.changed(run);}
 static void detach(Listener target){if(listener.get()==target)listener.clear();}
 static void launch(Context context,Run run){
  if(runs.putIfAbsent(run.sessionId,run)!=null)throw new IllegalStateException("本对话已有回复正在生成");
  current=run;
  try{
   if(context instanceof ChatGenerationService&&((ChatGenerationService)context).foreground){
    ChatGenerationService service=(ChatGenerationService)context;
    service.main.post(()->service.begin(run));
   }else ContextCompat.startForegroundService(context.getApplicationContext(),new Intent(context,ChatGenerationService.class));
  }catch(RuntimeException failure){
   runs.remove(run.sessionId,run);
   if(current==run)current=runs.isEmpty()?null:runs.values().iterator().next();
   throw failure;
  }
 }
 static void stop(Context context,Run run){
  if(!valid(run)||run.stopRequested)return;
  run.stopRequested=true;
  try{context.startService(new Intent(context,ChatGenerationService.class).setAction(STOP).putExtra("runId",run.id));}
  catch(RuntimeException failure){run.stopRequested=false;throw failure;}
 }
 private static boolean valid(Run run){return run!=null&&run==runs.get(run.sessionId)&&!run.finished&&!run.cancel.isCancelled();}
 private static void publish(Run run){Listener target=listener.get();if(target!=null)target.changed(run);}
 static JSONArray history(JSONObject session,String prompt)throws JSONException{
  JSONArray result=new JSONArray();if(!prompt.trim().isEmpty())result.put(new JSONObject().put("role","system").put("content",prompt));
  JSONArray messages=session.optJSONArray("messages");if(messages!=null)for(int i=0;i<messages.length();i++){JSONObject m=messages.optJSONObject(i);if(m!=null&&!m.optBoolean("error")){String text=m.optString("text");if(!m.optBoolean("user")){String imageNote=ImageToolPrompt.historyContextNote(m.optJSONArray("generatedImages"));if(!imageNote.isEmpty())text=text.isEmpty()?imageNote:text+"\n\n"+imageNote;}result.put(new JSONObject().put("role",m.optBoolean("user")?"user":"assistant").put("content",text).put("_messageId",m.optString("id")).put("reasoning_content",m.optString("reasoningContent")));}}
  return result;
 }
 static void enqueue(Context context,String sessionId,JSONObject queued)throws JSONException{
  SharedPreferences prefs=context.getSharedPreferences("chat",MODE_PRIVATE);
  SessionRepository repository=SessionRepository.get(prefs);
  JSONObject session=MessageEdits.findSession(repository.read(),sessionId);
  if(session==null)throw new JSONException("对话不存在");
  Run active=active(sessionId);
  if(active!=null&&active.stopRequested)throw new JSONException("回复正在结束，请稍后发送");
  JSONArray queue=session.optJSONArray("outbox");
  if(queue==null){queue=new JSONArray();session.put("outbox",queue);}
  if(queue.length()>=10)throw new JSONException("最多排队 10 条消息");
  queue.put(queued.put("branchId",active==null?session.optString("activeBranchId"):active.branchId));
  repository.upsertAfterSend(session);
 }

 /** Returns a user-facing reason when an item could not be started. */
 static String resumeQueue(Context context,String sessionId){
  if(active(sessionId)!=null)return "";
  SharedPreferences prefs=context.getSharedPreferences("chat",MODE_PRIVATE);
  SessionRepository repository=SessionRepository.get(prefs);
  JSONObject session=MessageEdits.findSession(repository.read(),sessionId);
  JSONArray queue=session==null?null:session.optJSONArray("outbox");
  if(queue==null||queue.length()==0)return "";
  try{
   String branchId=session.optString("activeBranchId");
   int index=GenerationQueue.next(queue,branchId);
   if(index<0)return "排队消息属于其他消息版本，请切回对应版本继续发送。";
   JSONObject next=queue.getJSONObject(index);
   JSONObject config=next.optJSONObject("config");
   if(config==null)config=SessionConfig.capture(prefs);
   SessionConfig scoped=new SessionConfig(prefs,new JSONObject().put("config",SessionRepository.detached(config)));
   JSONObject queuedMessage=next.getJSONObject("message");
   ImageToolConfig imageTool=ImageToolConfig.fromJson(next.has("imageTool")?next.optJSONObject("imageTool"):queuedMessage.optJSONObject("imageTool"));
   if(imageTool!=null){
    String imageError=imageTool.validate(ImageToolConfig.findProvider(ModelStore.read(prefs),imageTool.providerId));
    if(!imageError.isEmpty())return "排队生图任务不可用："+imageError;
   }
   JSONObject provider=scoped.provider();
   String model=scoped.getString("modelId","");
   JSONObject configuredModel=ModelCapabilities.find(provider,model);
   boolean directImage=imageTool!=null&&!imageTool.isAiMode();
   if(!directImage&&(provider.optString("api").isEmpty()||provider.optString("apiKey").isEmpty()||!configuredModel.has("id"))){
    return "排队消息的供应商或模型不可用。恢复该配置后可继续发送。";
   }
   JSONObject projected=GenerationQueue.promote(session,index);
   String runModel=directImage?imageTool.model:model;
   Run run=new Run(sessionId,runModel,directImage?"default":scoped.getString("reasoningEffort","default"),System.currentTimeMillis());
   run.imageTool=imageTool;
   run.base=provider.optString("api");run.key=provider.optString("apiKey");run.protocol=directImage?imageTool.protocol:provider.optString("protocol",ChatApiClient.CHAT_COMPLETIONS);
   run.configuredModel=directImage?new JSONObject().put("id",imageTool.model).put("displayName",imageTool.model):configuredModel;
   run.temperature=scoped.getString("conversationTemperature","0.7");run.topP=scoped.getString("conversationTopP","0.9");run.outputTokens=scoped.getString("outputTokens",configuredModel.optString("outputTokens",ContextBudget.DEFAULT_OUTPUT_TEXT));
   run.search=directImage?SearchOptions.OFF:SearchOptions.capture(scoped,run.protocol);
   if(!run.search.error.isEmpty())return run.search.error;
   run.branchId=branchId;run.history=history(projected,scoped.getString("systemPrompt",""));run.query=queuedMessage.optString("text");
   launch(context,run);
   repository.upsert(projected);
   run.session=repository.snapshot(sessionId);
   return "";
  }catch(Exception failure){
   android.util.Log.w("ChatGeneration","Unable to resume queue",failure);
   return "暂时无法启动排队消息，队列已保留，请重试。";
  }
 }
 @Override public void onCreate(){
  super.onCreate();if(Build.VERSION.SDK_INT>=26){
   NotificationChannel channel=new NotificationChannel(CHANNEL,"对话生成",NotificationManager.IMPORTANCE_LOW);
   channel.setDescription("回复生成期间保持连接，可随时停止");getSystemService(NotificationManager.class).createNotificationChannel(channel);
  }
  network=new GenerationNetworkMonitor(this,main,this::networkChanged);network.start();
 }
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  if(intent!=null&&STOP.equals(intent.getAction())){
   for(Run run:new ArrayList<>(runs.values()))if(run.id.equals(intent.getStringExtra("runId"))){
    finish(run,"",false,true,true);
    break;
   }
   if(runs.isEmpty())stopSelf(startId);
   return START_NOT_STICKY;
  }
  if(runs.isEmpty()){stopSelf(startId);return START_NOT_STICKY;}
  try{
   Run representative=active();
   if(Build.VERSION.SDK_INT>=29)startForeground(NOTICE,notification(representative),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
   else startForeground(NOTICE,notification(representative));
   foreground=true;
   renewWake();
  }catch(RuntimeException failure){
   for(Run run:new ArrayList<>(runs.values()))finish(run,"无法启动后台生成服务，请重试。",true,false);
   return START_NOT_STICKY;
  }
  for(Run run:new ArrayList<>(runs.values()))begin(run);
  return START_NOT_STICKY;
 }

 private void begin(Run run){
  if(!valid(run)||run.started)return;
  run.started=true;run.startedAt=System.currentTimeMillis();run.startedElapsed=SystemClock.elapsedRealtime();
  ownedRuns.add(run);
  run.enhanced=BackgroundProtection.enabled(this);
  run.client.withModel(run.configuredModel).withBackgroundResilience(run.enhanced);
  run.renderUpdates=new StreamingMarkdown.Updates(()->{if(valid(run))publish(run);});
  run.stream=new StreamingMarkdown(run.partial,()->renderLater(run));
  run.reasoningStream=new StreamingMarkdown(run.reasoningText,()->renderLater(run));
  if(run.imageTool!=null&&!run.imageTool.isAiMode()){
   run.status=run.phaseStatus="正在调用生成图片工具…";
   persist(run,false,false);
   publish(run);
   if(networkReady(run,()->generateImage(run,run.query,"","")))generateImage(run,run.query,"","");
  }else{
   persist(run,false,false);
   publish(run);
   if(networkReady(run,()->prepare(run)))prepare(run);
  }
  main.removeCallbacks(checkpoint);
  main.postDelayed(checkpoint,3000);
 }

 private void renderLater(Run run){
  if(valid(run))run.renderUpdates.schedule(Math.max(run.partial.length(),run.reasoningText.length()));
 }
 private void prepare(Run run){
  run.work=worker.submit(()->{
    try{if(!valid(run)||Thread.currentThread().isInterrupted())return;if(!new ModelCapabilities(run.modelId,run.protocol,run.configuredModel).preserveReasoning()||!ChatApiClient.CHAT_COMPLETIONS.equals(run.protocol))for(int i=0;i<run.history.length();i++)run.history.getJSONObject(i).remove("reasoning_content");int capacity=ModelCapabilities.positive(run.configuredModel,"contextLength",ContextBudget.DEFAULT_CONTEXT),output=Integer.parseInt(run.outputTokens);ContextBudget.Result initial=ContextBudget.trim(run.history,capacity,output);run.history=initial.messages;run.omittedMessages=initial.omitted;prepareAttachments(run);recognizeDocuments(run);DocumentOcr.scrub(run.history);run.history=DeepSeekDocumentInput.prepare(getApplicationContext(),run.base,run.modelId,run.protocol,run.history,run.pdfPages,()->valid(run),text->main.post(()->status(run,text)));ContextBudget.Result budget=ContextBudget.trim(run.history,capacity,output);run.history=budget.messages;run.contextTokens=budget.estimatedTokens;run.omittedMessages+=budget.omitted;main.post(()->{if(valid(run))search(run);});}
    catch(Exception failure){if(valid(run))main.post(()->finish(run,networkFailure(run,ApiFailure.safe(ApiFailure.local(failure),run.key)),true,false));}
    finally{run.session=null;run.prepared=Collections.emptyMap();if(!valid(run))run.pdfPages.close();}
   });
 }
 private void renewWake(){
  if(runs.isEmpty())return;
  if(wake==null){PowerManager power=getSystemService(PowerManager.class);if(power==null)return;wake=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,getPackageName()+":generation");wake.setReferenceCounted(false);}
  long now=SystemClock.elapsedRealtime();
  if(!wake.isHeld()||now>=wakeRenewAt){wake.acquire(10*60*1000L);wakeRenewAt=now+9*60*1000L;}
 }
 private boolean networkReady(Run run,Runnable action){
  if(!valid(run))return false;
  GenerationNetworkState.State state=network==null?GenerationNetworkState.State.UNKNOWN:network.state();
  if(!run.enhanced||!GenerationNetworkState.mustWait(state))return true;
  run.pendingNetworkAction=action;if(run.waitingSince==0)run.waitingSince=SystemClock.elapsedRealtime();
  if(state!=GenerationNetworkState.State.CHECKING)run.networkDisrupted=true;run.networkNote=GenerationNetworkState.note(state);status(run,run.phaseStatus);return false;
 }
 private void networkChanged(GenerationNetworkState.State state){
  for(Run run:new ArrayList<>(runs.values()))if(valid(run)&&run.enhanced){
   run.networkNote=GenerationNetworkState.note(state);
   if(state==GenerationNetworkState.State.OFFLINE||state==GenerationNetworkState.State.BLOCKED||state==GenerationNetworkState.State.CAPTIVE)run.networkDisrupted=true;
   status(run,run.phaseStatus);
   if(!GenerationNetworkState.mustWait(state)&&run.pendingNetworkAction!=null){
    Runnable action=run.pendingNetworkAction;run.pendingNetworkAction=null;run.waitingSince=0;action.run();
   }
  }
 }
 private String networkFailure(Run run,String message){
  if(!run.enhanced||!run.networkDisrupted)return message;
  return message+"\n本次生成期间网络曾断开或受系统限制。已保存收到的内容；为避免重复计费，未自动重发。请检查设置 → 后台连接。";
 }
 private Notification notification(Run run){
  Intent open=new Intent(this,MainActivity.class).putExtra("resumeGeneration",true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
  PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ChatGenerationService.class).setAction(STOP).putExtra("runId",run.id),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  return new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_chat_notification)
   .setContentTitle(runs.size()>1?runs.size()+" 个对话正在生成":"正在生成回复").setContentText(run.configuredModel.optString("displayName",run.modelId)+" · "+run.status).setContentIntent(content)
   .setOngoing(true).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW)
   .setCategory(NotificationCompat.CATEGORY_PROGRESS).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
   .addAction(0,"停止生成",stop).build();
 }
 private void status(Run run,String value){
  if(!valid(run))return;
  run.phaseStatus=value;
  String visible=run.networkNote.isEmpty()?value:run.networkNote;
  if(visible.equals(run.status))return;
  run.status=visible;
  publish(run);
  updateNotification();
 }
 private void updateNotification(){
  Run representative=current;
  if(!foreground||representative==null)return;
  try{getSystemService(NotificationManager.class).notify(NOTICE,notification(representative));}
  catch(SecurityException denied){android.util.Log.d("ChatGeneration","Notification permission unavailable");}
 }
 private void search(Run run){
  if(!networkReady(run,()->search(run)))return;
  if(!run.search.error.isEmpty()){finish(run,run.search.error,true,false);return;}
  if(run.search.builtin){try{run.history=SearchGrounding.withPolicy(run.history);status(run,"内置搜索可用 · 由模型决定是否搜索");chat(run);}catch(JSONException invalid){finish(run,"搜索上下文构建失败",true,false);}return;}
  if(!run.search.tavily){chat(run);return;}
  if(run.search.key.isEmpty()){run.searchNote="搜索未配置 · 本次未联网";run.search=run.search.withoutSearch();status(run,run.searchNote);chat(run);return;}
  status(run,"正在联网搜索…");
  run.client.tavilySources(run.search.key,run.query,run.search.count,run.search.depth,new ChatApiClient.CallbackResult<SearchSources>(){
   public void onSuccess(SearchSources sources){main.post(()->{if(!valid(run))return;run.sources=sources;int capacity=ModelCapabilities.positive(run.configuredModel,"contextLength",ContextBudget.DEFAULT_CONTEXT);String grounding=sources.grounding(SearchGrounding.characterBudget(run.history,capacity,Integer.parseInt(run.outputTokens)));
    if(grounding.isEmpty()&&sources.items.length()>0)run.searchNote="上下文空间不足 · 未加入搜索资料";
    run.searchStatus=grounding.isEmpty()?(sources.items.length()>0?run.searchNote:"搜索无有效来源 · 直接回答"):"搜索完成 · "+sources.items.length()+" 个来源";status(run,run.searchStatus);
    try{if(!grounding.isEmpty())run.history=MainActivity.groundedMessages(run.history,grounding);chat(run);}catch(JSONException e){finish(run,"搜索上下文构建失败",true,false);}
   });}
   public void onError(String message){main.post(()->{if(valid(run)){run.searchNote="搜索失败 · 本次未联网";run.searchStatus=run.searchNote;run.search=run.search.withoutSearch();status(run,run.searchNote);chat(run);}});}
  });
 }
 private void sources(Run run,SearchSources sources,String status){
  if(!run.search.grounded()||!valid(run))return;
  String signature=sources.items.toString()+"\u0000"+status;
  if(signature.equals(run.sourceUpdate))return;run.sourceUpdate=signature;
  SearchSources copy=SearchSources.parse(sources.items);
  main.post(()->{if(valid(run)){
   if(run.search.tavily){JSONArray combined=new JSONArray();for(int i=0;i<run.sources.items.length();i++)combined.put(run.sources.items.opt(i));for(int i=0;i<copy.items.length();i++)combined.put(copy.items.opt(i));run.sources=SearchSources.parse(combined);}else run.sources=copy;
   if(!status.isEmpty())run.searchStatus=status;
   publish(run);
  }});
 }
 private void chat(Run run){
  if(!networkReady(run,()->chat(run)))return;
  try{
   boolean imageAiMode=run.imageTool!=null&&run.imageTool.isAiMode();
   JSONArray messages=imageAiMode?ImageToolPrompt.prepareHistory(run.history,run.imageTool.model,run.imageTool.size):run.history;
   boolean sampling=new ModelCapabilities(run.modelId,run.protocol,run.configuredModel).sampling;double temperature=sampling?ModelStore.range(run.temperature,2):.7,top=sampling?ModelStore.range(run.topP,1):1;int max=Integer.parseInt(run.outputTokens);if(max<=0)throw new IllegalArgumentException();
   ContextBudget.Result budget=ContextBudget.trim(messages,ModelCapabilities.positive(run.configuredModel,"contextLength",ContextBudget.DEFAULT_CONTEXT),max);run.history=budget.messages;run.contextTokens=budget.estimatedTokens;run.omittedMessages+=budget.omitted;max=budget.outputTokens;
   if(new ModelCapabilities(run.modelId,run.protocol,run.configuredModel).needsBudget()&&!"default".equals(run.effort)&&!"none".equals(run.effort)&&max<=1024)throw new IllegalArgumentException("剩余上下文只能分配 "+max+" 个输出 token，此 Claude 模型开启思考至少需要 1025；请减少输入、增加模型上下文容量，或选择自动 / none");
   status(run,imageAiMode?"AI 正在分析请求并编写提示词…":"正在接收回复");
   run.client.streamChat(run.base,run.key,run.modelId,run.history,temperature,max,top,run.effort,run.protocol,run.search,new ChatApiClient.StreamCallback(){
    public void onDelta(String text){if(!valid(run))return;if(imageAiMode)synchronized(run.imagePlanRaw){run.imagePlanRaw.append(text);}else run.stream.append(text);}
    public void onSnapshot(String text){if(!valid(run))return;if(imageAiMode)synchronized(run.imagePlanRaw){run.imagePlanRaw.setLength(0);run.imagePlanRaw.append(text);}else run.stream.replace(text);}
    public void onReasoning(String text){if(valid(run))run.reasoningStream.append(text);}
    public void onReasoningSnapshot(String text){if(valid(run))run.reasoningStream.replace(text);}
    public void onStatus(String text){main.post(()->status(run,text));}
    public void onSearchSources(SearchSources value,String status){sources(run,value,status);}
    public void onToolEvent(JSONObject event){main.post(()->{if(valid(run))run.toolEvents.put(event);});}
    public void onDone(){main.post(()->{
     if(!valid(run))return;
     if(imageAiMode){proceedToImageGeneration(run,new GenerationResult("complete","",run.usage));return;}
     finish(run,"",run.partial.length()==0&&run.reasoningText.length()==0,false);
    });}
    public void onFinished(GenerationResult result){main.post(()->{
     if(!valid(run))return;
     run.completionState=result.state;
     run.finishReason="interrupted".equals(result.state)?networkFailure(run,result.reason):result.reason;
     run.usage=result.usage;
     if(imageAiMode){
      String raw;synchronized(run.imagePlanRaw){raw=run.imagePlanRaw.toString().trim();}
      if(("filtered".equals(result.state)||"refusal".equals(result.state))&&raw.isEmpty()){
       finish(run,"模型拒绝生成此图片提示词，未调用生成图片工具。",false,false);
       return;
      }
      if("interrupted".equals(result.state)&&raw.isEmpty()){
       finish(run,run.finishReason,true,true);
       return;
      }
      proceedToImageGeneration(run,result);
      return;
     }
     String notice="filtered".equals(result.state)&&run.partial.length()==0?"模型未提供可显示的回答，回复因内容限制而终止。":"";
     boolean interrupted="interrupted".equals(result.state);
     finish(run,notice,interrupted&&run.partial.length()==0&&run.reasoningText.length()==0,interrupted);
    });}
    public void onError(String message){main.post(()->{
     if(!valid(run))return;
     if(!run.pdfFallbackTried&&run.partial.length()==0&&run.reasoningText.length()==0&&run.imagePlanRaw.length()==0&&PdfPageFallback.shouldRetry(message,run.history)){retryPdfAsImages(run,message);return;}
     String detail=networkFailure(run,run.pdfFallbackTried?run.pdfOriginalError+"\n逐页图像兼容发送仍失败：\n"+message:message);
     run.completionState="interrupted";run.finishReason=detail;
     if(!imageAiMode&&(run.partial.length()>0||run.reasoningText.length()>0))finish(run,"",false,true);else finish(run,detail,true,false);
    });}
   });
  }catch(Exception error){finish(run,ApiFailure.safe("请求准备失败："+ApiFailure.local(error)+"\n模型："+run.modelId+" · 协议："+run.protocol+" · 输出上限："+run.outputTokens,run.key),true,false);}
 }

 private void proceedToImageGeneration(Run run,GenerationResult result){
  if(!valid(run))return;
  String raw;synchronized(run.imagePlanRaw){raw=run.imagePlanRaw.toString();}
  ImageToolPrompt.Plan plan=ImageToolPrompt.parse(raw,run.query);
  try{
   run.toolEvents.put(new JSONObject().put("tool","image_prompt").put("status","completed")
    .put("input",run.query).put("output",plan.prompt));
  }catch(JSONException ignored){}
  run.usage=result.usage;
  generateImage(run,plan.prompt,plan.aspectRatio,plan.reply);
 }

 private void generateImage(Run run,String prompt,String suggestedAspect,String replyText){
  if(!networkReady(run,()->generateImage(run,prompt,suggestedAspect,replyText)))return;
  String finalPrompt=prompt==null?"":prompt.trim();
  if(finalPrompt.isEmpty()){
   finish(run,"生图提示词为空，请重新输入描述。",true,false);
   return;
  }
  status(run,"正在调用生成图片工具…");
  run.work=worker.submit(()->{
   MediaGenerationStore store=new MediaGenerationStore(this);
   String jobId=MediaGenerationStore.newId();
   JSONObject imageProvider=ImageToolConfig.findProvider(ModelStore.read(getSharedPreferences("chat",MODE_PRIVATE)),run.imageTool.providerId);
   String validation=run.imageTool.validate(imageProvider);
   if(!validation.isEmpty()){
    main.post(()->{if(valid(run))finish(run,validation,true,false);});
    return;
   }
   String finalSize=!run.imageTool.size.isEmpty()?run.imageTool.size:ImageToolConfig.mapAspectRatioForProtocol(suggestedAspect,run.imageTool.protocol);
   JSONObject job=new JSONObject();
   try{
    job.put("id",jobId).put("sessionId",run.sessionId).put("createdAt",System.currentTimeMillis())
     .put("providerId",run.imageTool.providerId).put("providerName",imageProvider.optString("name",run.imageTool.providerName))
     .put("endpoint",MediaGenerationService.endpointIdentity(imageProvider))
     .put("model",run.imageTool.model).put("prompt",finalPrompt).put("video",false)
     .put("size",finalSize).put("seconds",0).put("state","running").put("detail","正在生成图片");
    store.save(job);
    String mime=MediaGenerationClient.generate(imageProvider,run.imageTool.model,finalPrompt,false,finalSize,0,"",
     store.temporaryFile(jobId),run.cancel,(remote,label)->{
      try{
       if(remote!=null&&!remote.isEmpty())job.put("remoteId",remote);
       if(label!=null&&!label.isEmpty()){
        job.put("detail",label);
        main.post(()->status(run,label));
       }
       store.save(job);
      }catch(JSONException e){throw new java.io.IOException(e);}
     });
    if(!valid(run)||run.cancel.isCancelled())throw new java.io.IOException("已停止");
    job.put("mime",mime).put("state","publishing").put("detail","正在保存图片");
    store.save(job);
    store.finish(jobId);
    job.put("state","complete").put("detail","");
    store.save(job);
    JSONObject imageRecord=new JSONObject()
     .put("jobId",jobId).put("mime",mime).put("prompt",finalPrompt)
     .put("originalRequest",run.query).put("mode",run.imageTool.mode)
     .put("providerId",run.imageTool.providerId)
     .put("providerName",imageProvider.optString("name",run.imageTool.providerName))
     .put("model",run.imageTool.model).put("size",finalSize);
    main.post(()->{
     if(!valid(run))return;
     run.generatedImages.put(imageRecord);
     try{
      run.toolEvents.put(new JSONObject().put("tool","generate_image").put("status","completed")
       .put("input",run.imageTool.model+(finalSize.isEmpty()?"":" · "+finalSize)+"\n"+finalPrompt)
       .put("output","图片已生成（"+mime+"）"));
     }catch(JSONException ignored){}
     run.completionState="complete";
     finish(run,replyText==null?"":replyText,false,false);
    });
   }catch(Exception failure){
    store.discardPartial(jobId);
    String imageKey=imageProvider.optString("apiKey");
    String detail=ApiFailure.safe(ApiFailure.local(failure),imageKey);
    try{
     job.put("state",run.cancel.isCancelled()?"stopped":"failed").put("detail",detail.substring(0,Math.min(detail.length(),1000)));
     if(job.has("id"))store.save(job);
    }catch(Exception ignored){}
    main.post(()->{
     if(!valid(run))return;
     try{
      run.toolEvents.put(new JSONObject().put("tool","generate_image").put("status","failed")
       .put("input",run.imageTool.model+(finalSize.isEmpty()?"":" · "+finalSize)+"\n"+finalPrompt)
       .put("output",detail));
     }catch(JSONException ignored){}
     if(run.cancel.isCancelled())finish(run,"",false,true);
     else finish(run,networkFailure(run,"图片生成失败："+detail),true,false);
    });
   }
  });
 }
 private void retryPdfAsImages(Run run,String original){
  run.pdfFallbackTried=true;run.pdfOriginalError=original;status(run,"接口拒绝 PDF，正在逐页转换为图像…");
  run.work=worker.submit(()->{
   try{
    if(!valid(run))return;
    JSONArray converted=PdfPageFallback.render(getApplicationContext(),run.history,run.pdfPages,()->valid(run),text->main.post(()->status(run,text)));
    main.post(()->{if(valid(run)){run.history=converted;chat(run);}});
   }catch(Exception failure){main.post(()->{if(valid(run))finish(run,original+"\nPDF 图像兼容处理失败："+ApiFailure.local(failure),true,false);});}
   finally{if(!valid(run))run.pdfPages.close();}
  });
 }
 private void finish(Run run,String text,boolean error,boolean stopped){finish(run,text,error,stopped,false);}

 private void finish(Run run,String text,boolean error,boolean stopped,boolean clearQueue){
  if(!valid(run))return;
  run.finished=true;
  run.pendingNetworkAction=null;
  if(run.renderUpdates!=null)run.renderUpdates.close();
  String received=run.stream==null?run.partial.toString():run.stream.seal();
  if(run.reasoningStream!=null)run.reasoningStream.seal();
  run.cancel.cancel();
  if(run.work!=null)run.work.cancel(true);
  run.pdfPages.close();
  if(!received.isEmpty())text=received;
  if(text.isEmpty()&&!stopped&&run.generatedImages.length()==0){
   text=run.thought().isEmpty()?"模型返回了空内容":"模型只返回了思考内容，尚未生成最终回答。";
  }
  if(error&&run.finishReason.isEmpty())run.finishReason=text.isEmpty()?"连接中断，请重试。":text;
  if(!error&&!stopped&&run.search.grounded()&&run.searchStatus.isEmpty())run.searchStatus="本次未调用搜索或网页读取";
  run.error=error;run.stopped=stopped;
  if(run.stream!=null)run.stream.finish(text);
  else synchronized(run.partial){run.partial.setLength(0);run.partial.append(text);}
  if(error){run.completionState="error";run.status="回复生成失败";}
  else if(stopped){run.completionState="interrupted";run.status="回复已停止或中断";}
  else run.status=new GenerationResult(run.completionState,run.finishReason,run.usage).label();

  persist(run,true,clearQueue);
  ownedRuns.remove(run);
  runs.remove(run.sessionId,run);
  if(current==run)current=runs.isEmpty()?null:runs.values().iterator().next();
  publish(run);
  if(!stopped&&!error){
   String pending=resumeQueue(this,run.sessionId);
   if(!pending.isEmpty())android.util.Log.i("ChatGeneration",pending);
  }
  if(runs.isEmpty()){
   main.removeCallbacks(checkpoint);
   if(wake!=null&&wake.isHeld())wake.release();
   wake=null;
   foreground=false;
   stopForeground(STOP_FOREGROUND_REMOVE);
   stopSelf();
  }else{
   updateNotification();
   main.removeCallbacks(checkpoint);
   main.postDelayed(checkpoint,3000);
  }
 }

 private void persist(Run run,boolean complete,boolean clearQueue){
  String answer=run.answer(),thought=run.thought(),search=run.searchStatus.isEmpty()?run.searchNote:run.searchStatus;
  if(!complete&&answer==run.lastCheckpointAnswer&&thought==run.lastCheckpointThought
    &&search.equals(run.lastCheckpointStatus)&&run.sources==run.lastCheckpointSources
    &&run.toolEvents.length()==run.lastCheckpointTools&&run.generatedImages.length()==run.lastCheckpointImages)return;
  SessionRepository repository=SessionRepository.get(getSharedPreferences("chat",MODE_PRIVATE));
  try{
   JSONObject session=MessageEdits.findSession(repository.read(),run.sessionId);
   if(session==null||!run.branchId.equals(session.optString("activeBranchId")))return;
   JSONObject message=new JSONObject().put("id",run.id).put("text",answer).put("user",false).put("timestamp",run.timestamp)
    .put("modelId",run.modelId).put("reasoningContent",thought).put("reasoningKind",ChatApiClient.RESPONSES.equals(run.protocol)||ChatApiClient.GEMINI.equals(run.protocol)?"summary":"reasoning").put("error",run.error).put("interrupted",run.stopped).put("searchStatus",search).put("toolEvents",run.toolEvents).put("sources",run.sources.items).put("completionState",run.completionState).put("finishReason",run.finishReason).put("usage",run.usage).put("modelName",run.configuredModel.optString("displayName",run.modelId)).put("estimatedInputTokens",run.contextTokens).put("omittedMessages",run.omittedMessages).put("searchNote",run.searchNote).put("pricing",run.configuredModel);
   if(run.generatedImages.length()>0)message.put("generatedImages",run.generatedImages);
   if(run.error&&ChatApiClient.RESPONSES.equals(run.protocol)&&run.search.hasTools()&&
    thought.isEmpty()&&ApiFailure.canRetryWithoutTools(run.finishReason))message.put("retryWithoutHostedTools",true);
   if(complete){
    if(clearQueue)session.remove("outbox");
    session.remove("pendingReply");
    JSONArray messages=session.optJSONArray("messages");
    if(messages!=null&&MessageEdits.indexOf(messages,run.id)<0&&(!run.stopped||run.error||!answer.isEmpty()||!thought.isEmpty()||run.generatedImages.length()>0)){
     messages.put(message);
    }
    session.put("updatedAt",System.currentTimeMillis());
    ConversationBranches.sync(session);
    repository.complete(session);
   }else{
    repository.checkpoint(run.sessionId,new JSONObject().put("branchId",run.branchId).put("message",message));
    run.lastCheckpointAnswer=answer;
    run.lastCheckpointThought=thought;
    run.lastCheckpointStatus=search;
    run.lastCheckpointSources=run.sources;
    run.lastCheckpointTools=run.toolEvents.length();
    run.lastCheckpointImages=run.generatedImages.length();
   }
  }catch(JSONException failure){android.util.Log.e("ChatGeneration","Unable to persist reply",failure);}
 }

 static void recoverInterrupted(Context context){
  if(!runs.isEmpty())return;
  SessionRepository repository=SessionRepository.get(context.getSharedPreferences("chat",MODE_PRIVATE));
  try{
   JSONArray all=repository.read();
   Set<String> checkpoints=new HashSet<>();
   for(int i=0;i<all.length();i++){
    JSONObject session=all.optJSONObject(i);
    if(session==null)continue;
    String id=session.optString("id");
    JSONObject draft=session.optJSONObject("pendingReply");
    if(draft==null){
     String saved=repository.checkpoint(id);
     if(saved.isEmpty())continue;
     try{draft=new JSONObject(saved);}
     catch(JSONException invalid){checkpoints.add(id);continue;}
    }
    session.remove("pendingReply");
    checkpoints.add(id);
    JSONObject message=draft.optJSONObject("message");
    JSONArray messages=session.optJSONArray("messages");
    if(message==null||messages==null||!draft.optString("branchId").equals(session.optString("activeBranchId"))
      ||MessageEdits.indexOf(messages,message.optString("id"))>=0)continue;
    String reason="上次生成被系统终止，请重试。";
    message.put("interrupted",true).put("completionState","interrupted").put("finishReason",reason).put("searchStatus","上次生成已中断 · 可重试");
    if(message.optString("text").isEmpty()&&message.optString("reasoningContent").isEmpty()){
     message.put("text",reason).put("error",true).put("completionState","error");
    }
    messages.put(message);
    ConversationBranches.sync(session);
   }
   if(!checkpoints.isEmpty())repository.replaceAndClear(all,checkpoints);
  }catch(JSONException failure){android.util.Log.e("ChatGeneration","Unable to restore interrupted reply",failure);}
 }
 private void prepareAttachments(Run run)throws Exception{
  JSONArray messages=run.session==null?null:run.session.optJSONArray("messages");Map<String,JSONObject> entries=new HashMap<>();
  for(int i=0;i<run.history.length();i++){JSONObject entry=run.history.optJSONObject(i);if(entry!=null&&!entry.optString("_messageId").isEmpty())entries.put(entry.optString("_messageId"),entry);}
  int latestUser=-1;
  if(messages!=null)for(int i=messages.length()-1;i>=0;i--){JSONObject m=messages.optJSONObject(i);if(m!=null&&m.optBoolean("user")&&!m.optBoolean("error")){latestUser=i;break;}}
  long retainedChars=0,limit=AttachmentSnapshots.requestCharacterLimit();
  if(messages!=null)for(int i=messages.length()-1;i>=0;i--){
   JSONObject message=messages.optJSONObject(i);if(message==null||message.optBoolean("error")||!message.optBoolean("user"))continue;
   JSONObject entry=entries.get(message.optString("id"));JSONArray attachments=message.optJSONArray("attachments");if(entry==null||attachments==null)continue;
   JSONArray content=new JSONArray().put(new JSONObject().put("type","text").put("text",message.optString("text")));
   for(int j=0;j<Math.min(10,attachments.length());j++){
    if(!valid(run)||Thread.currentThread().isInterrupted())return;
    JSONObject attachment=attachments.optJSONObject(j);if(attachment==null)continue;
    try{attachment=SessionRepository.detached(attachment);String uri=attachment.optString("uri"),payload=i==latestUser?run.prepared.get(uri):null;
     if(payload==null)payload=AttachmentSnapshots.read(this,attachment);
     if(payload==null){if(!"content".equals(Uri.parse(uri).getScheme()))throw new java.io.IOException("附件地址不可读取");payload=AttachmentBatch.payload(getApplicationContext(),attachment);}
     if(AttachmentBatch.isFailure(payload))throw new java.io.IOException(AttachmentBatch.failureMessage(payload));
     if(!AttachmentSnapshots.isPdfPayload(payload)&&retainedChars+payload.length()>limit){
      throw new java.io.IOException("内联附件累计过大，请减少图片或文本文档数量");
     }
     content.put(AttachmentBatch.part(getApplicationContext(),attachment,payload,run.protocol));
     if(!AttachmentSnapshots.isPdfPayload(payload))retainedChars+=payload.length();
    }catch(Exception unavailable){if(!valid(run)||Thread.currentThread().isInterrupted())return;String reason="附件 "+attachment.optString("displayName","文件")+" 未发送："+ApiFailure.local(unavailable);if(i==latestUser)throw new java.io.IOException(reason);content.put(new JSONObject().put("type","text").put("text",reason));}
   }entry.put("content",content).put("_ocrCache",message.optJSONArray("documentOcr"));
  }
 }
 private void recognizeDocuments(Run run)throws Exception{
  if((!DocumentOcr.enabled(run.configuredModel,true)&&!DocumentOcr.enabled(run.configuredModel,false))||!DocumentOcr.hasMedia(run.history))return;
  DocumentOcr.Plan plan=DocumentOcr.plan(ModelStore.read(getSharedPreferences("chat",MODE_PRIVATE)),run.configuredModel,run.base,run.modelId,run.history);
  if(plan.empty())return;
  ChatApiClient client=(run.ocrClient==null?new ChatApiClient(run.cancel):run.ocrClient).withBackgroundResilience(run.enhanced);
  run.history=DocumentOcr.prepare(getApplicationContext(),plan,run.history,run.pdfPages,()->valid(run),text->main.post(()->status(run,text)),client,(messageId,record)->main.post(()->cacheRecognition(run,messageId,record)));
  main.post(()->{if(valid(run))try{
   if(plan.pdf!=null)run.toolEvents.put(new JSONObject().put("tool","pdf_ocr").put("status","completed").put("input",plan.pdf.provider.optString("name")+" · "+plan.pdf.id).put("output","PDF 已转为文字"));
   if(plan.image!=null)run.toolEvents.put(new JSONObject().put("tool","image_ocr").put("status","completed").put("input",plan.image.provider.optString("name")+" · "+plan.image.id).put("output","图片已转为文字"));
  }catch(JSONException ignored){}});
 }
 private void cacheRecognition(Run run,String messageId,JSONObject record){
  if(!valid(run)||messageId.isEmpty())return;
  try{
   SessionRepository repository=SessionRepository.get(getSharedPreferences("chat",MODE_PRIVATE));JSONObject session=MessageEdits.findSession(repository.read(),run.sessionId);
   if(session==null||!run.branchId.equals(session.optString("activeBranchId")))return;
   JSONArray messages=session.optJSONArray("messages");
   for(int i=0;messages!=null&&i<messages.length();i++){
    JSONObject message=messages.optJSONObject(i);if(message==null||!messageId.equals(message.optString("id")))continue;
    JSONArray oldRecords=message.optJSONArray("documentOcr");JSONArray records=oldRecords==null?new JSONArray():new JSONArray(oldRecords.toString());boolean found=false;
    for(int j=0;j<records.length();j++)if(record.optString("key").equals(records.getJSONObject(j).optString("key"))){records.put(j,record);found=true;break;}
    if(!found)records.put(record);message.put("documentOcr",records);ConversationBranches.sync(session);repository.upsert(session);return;
   }
  }catch(JSONException invalid){android.util.Log.e("DocumentOcr","Unable to save recognition result",invalid);}
 }
 @Override public void onTaskRemoved(Intent rootIntent){SessionLaunchPolicy.markTaskRemoved();super.onTaskRemoved(rootIntent);}
 @Override public void onTimeout(int startId,int type){for(Run run:new ArrayList<>(runs.values()))finish(run,"后台运行时间已达系统限制，请重试。",true,true);}
 @Override public void onDestroy(){
  foreground=false;
  // The process-wide registry can already contain requests waiting for the next service instance.
  for(Run run:new ArrayList<>(ownedRuns))finish(run,"",run.partial.length()==0&&run.reasoningText.length()==0,true);
  if(network!=null)network.close();main.removeCallbacksAndMessages(null);worker.shutdownNow();if(wake!=null&&wake.isHeld())wake.release();super.onDestroy();
 }
 @Override public IBinder onBind(Intent intent){return null;}
}
