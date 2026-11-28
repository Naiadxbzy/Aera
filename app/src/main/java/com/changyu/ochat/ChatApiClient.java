package com.changyu.ochat;

import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** Small OpenAI-compatible client. Protocol is explicit and never inferred from model names. */
public class ChatApiClient {
 private static final MediaType JSON=MediaType.get("application/json; charset=utf-8");
 private static final OkHttpClient HTTP=new OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(120,TimeUnit.SECONDS).writeTimeout(120,TimeUnit.SECONDS).build();
 private static final OkHttpClient BACKGROUND_HTTP=HTTP.newBuilder().readTimeout(10,TimeUnit.MINUTES).retryOnConnectionFailure(false).build();
 private boolean backgroundResilience;
 ChatApiClient withBackgroundResilience(boolean enabled){backgroundResilience=enabled;return this;}
 public static final String CHAT_COMPLETIONS="chat_completions", RESPONSES="responses", ANTHROPIC="anthropic", GEMINI="gemini";
 private JSONObject configuredModel=new JSONObject();
 ChatApiClient withModel(JSONObject value){configuredModel=value==null?new JSONObject():value;return this;}
 public static final class CancellationHandle {private final Set<Call> calls=new HashSet<>();private volatile boolean cancelled;public boolean isCancelled(){return cancelled;}synchronized void register(Call c){if(cancelled)c.cancel();else calls.add(c);}synchronized void remove(Call c){calls.remove(c);}public synchronized void cancel(){cancelled=true;for(Call c:calls)c.cancel();calls.clear();}}
 private final CancellationHandle cancellation; public ChatApiClient(){this(null);} public ChatApiClient(CancellationHandle c){cancellation=c;} boolean cancelled(){return cancellation!=null&&cancellation.isCancelled();}
 void enqueue(Request r,Callback cb){Call c=(backgroundResilience?BACKGROUND_HTTP:HTTP).newCall(r);if(cancellation!=null)cancellation.register(c);c.enqueue(new Callback(){public void onFailure(Call x,IOException e){try{if(!cancelled())cb.onFailure(x,e);}finally{if(cancellation!=null)cancellation.remove(x);}}public void onResponse(Call x,Response v)throws IOException{try{if(cancelled())v.close();else cb.onResponse(x,v);}finally{if(cancellation!=null)cancellation.remove(x);}}});}
 public interface CallbackResult<T>{void onSuccess(T v);void onError(String m);default void onSearchSources(SearchSources sources,String status){} default void onToolEvent(JSONObject event){} } public interface StreamCallback{void onDelta(String s);void onReasoning(String s);default void onReasoningSnapshot(String text){} void onStatus(String s);void onDone();void onError(String m);default void onFinished(GenerationResult result){onDone();} default void onSnapshot(String text){} default void onSearchSources(SearchSources sources,String status){} default void onToolEvent(JSONObject event){} }
 public void chat(String b,String k,String m,JSONArray h,double t,int max,double top,CallbackResult<String> cb){chat(b,k,m,h,t,max,top,"",CHAT_COMPLETIONS,cb);} public void chat(String b,String k,String m,JSONArray h,double t,int max,double top,String e,CallbackResult<String> cb){chat(b,k,m,h,t,max,top,e,CHAT_COMPLETIONS,cb);}
 public void chat(String b,String k,String m,JSONArray h,double t,int max,double top,String e,String protocol,CallbackResult<String> cb){chat(b,k,m,h,t,max,top,e,protocol,SearchOptions.OFF,cb);}
 public void chat(String b,String k,String m,JSONArray h,double t,int max,double top,String e,String protocol,SearchOptions search,CallbackResult<String> cb){try{DeepSeekInputPolicy.validate(b,m,protocol,h);JSONObject body=requestBody(m,h,t,max,top,e,protocol,search);if(!GEMINI.equals(protocol))body.put("stream",false);if(ANTHROPIC.equals(protocol)){new AnthropicToolRequest(this,b,k,body,search).chat(cb);return;}request("POST",NativeProtocols.nativeProtocol(protocol)?NativeProtocols.endpoint(b,protocol,m,false):endpoint(b,protocol),k,protocol,body,new CallbackResult<JSONObject>(){public void onSuccess(JSONObject x){try{NativeToolState tools=new NativeToolState();tools.complete(protocol,x,NativeToolState.callback(cb));String text;try{text=NativeProtocols.nativeProtocol(protocol)?NativeProtocols.text(x,protocol):ChatResponseParser.parse(x,false);}catch(Exception empty){if(tools.fallback().isEmpty())throw empty;text=tools.fallback();}if(search.grounded()){if(RESPONSES.equals(protocol)){ResponseStream state=new ResponseStream();state.inspectSearch(x);cb.onSearchSources(SearchSources.fromResponse(x),state.searchCalled?state.searchStatus:"本次未调用内置搜索");}else cb.onSearchSources(tools.sources,tools.searchStatus.isEmpty()?(tools.sources.items.length()>0?"已收到模型引用来源":"本次未调用搜索或网页读取"):tools.searchStatus);}cb.onSuccess(text);}catch(Exception z){cb.onError(ApiFailure.safe("无法解析模型回复："+ApiFailure.local(z),k));}}public void onError(String x){cb.onError(x);}});}catch(Exception x){cb.onError(ApiFailure.safe("请求构建失败："+ApiFailure.local(x)+"\n模型："+m+" · 协议："+protocol,k));}}
 public void streamChat(String b,String k,String m,JSONArray h,double t,int max,double top,String e,StreamCallback cb){streamChat(b,k,m,h,t,max,top,e,CHAT_COMPLETIONS,cb);}
 public void streamChat(String b,String k,String m,JSONArray h,double t,int max,double top,String e,String protocol,StreamCallback cb){streamChat(b,k,m,h,t,max,top,e,protocol,SearchOptions.OFF,cb);}
 public void streamChat(String base,String key,String model,JSONArray history,double t,int max,double top,String effort,String protocol,SearchOptions search,StreamCallback callback){
  try{
   DeepSeekInputPolicy.validate(base,model,protocol,history);
   JSONObject body=requestBody(model,history,t,max,top,effort,protocol,search);
   if(!GEMINI.equals(protocol))body.put("stream",true);
   if(CHAT_COMPLETIONS.equals(protocol))body.put("stream_options",new JSONObject().put("include_usage",true));
   if(ANTHROPIC.equals(protocol)){
    new AnthropicToolRequest(this,base,key,body,search).stream(callback);
    return;
   }
   String url=NativeProtocols.nativeProtocol(protocol)?NativeProtocols.endpoint(base,protocol,model,true):endpoint(base,protocol);
   streamRequest(url,key,protocol,body,callback);
  }catch(Exception error){
   callback.onError(ApiFailure.safe("请求构建失败："+ApiFailure.local(error)+"\n模型："+model+" · 协议："+protocol,key));
  }
 }

 private void streamRequest(String url,String key,String protocol,JSONObject body,StreamCallback callback){
  Request.Builder request=new Request.Builder().url(url).header("Accept","text/event-stream");
  NativeProtocols.auth(request,key,protocol);
  enqueue(request.post(JsonRequestBody.create(body,JSON)).build(),new Callback(){
   public void onFailure(Call call,IOException failure){
    if(!cancelled())callback.onError(ApiFailure.safe("网络请求失败："+ApiFailure.local(failure)+"\n"+ApiFailure.settings(protocol,body),key));
   }
   public void onResponse(Call call,Response response){
    boolean retry=false;
    try(Response result=response){
     if(!result.isSuccessful()){
      String detail=ApiFailure.http(result,protocol,body,key);
      retry=body.has("stream_options")&&ApiFailure.rejectsStreamUsage(result.code(),detail);
      if(!retry)callback.onError(detail);
     }else{
      if(result.body()==null)throw new IOException("接口没有返回内容");
      if(result.headers().get("Content-Type")!=null&&result.header("Content-Type").toLowerCase(Locale.ROOT).contains("application/json")){
       JSONObject value=readJson(result);
       if(ApiFailure.isProviderError(value,value.optString("type")))throw new IOException(ApiFailure.provider(value));
       throw new IOException("invalid_stream：已请求流式回复，但接口返回普通 JSON，请检查供应商的流式支持和接口协议。");
      }
      parseSse(result.body().byteStream(),protocol,callback);
     }
    }catch(Exception error){
     if(!cancelled())callback.onError(ApiFailure.safe(ApiFailure.local(error)+"\n本次请求："+ApiFailure.settings(protocol,body),key));
     return;
    }
    if(retry&&!cancelled()){
     body.remove("stream_options");
     streamRequest(url,key,protocol,body,callback);
    }
   }
  });
 }

 static JSONObject readJson(Response response)throws IOException{
  ResponseBody body=response.body();
  if(body==null)throw new IOException("接口没有返回内容");
  long limit=8L*1024*1024;
  if(body.contentLength()>limit||body.source().request(limit+1)){
   throw new IOException("response_limit：接口响应超过 8 MiB。");
  }
  try{return new JSONObject(body.string());}
  catch(JSONException invalid){
   throw new IOException("invalid_json：服务端返回的不是有效 JSON，请核对接口协议和 API 路径（HTTP "+response.code()+"，Content-Type: "+response.header("Content-Type","未提供")+"）");
  }
 }

 private static final class TextLimit {
  private final java.util.function.Consumer<String> sink;
  private boolean received;
  private int length;
  TextLimit(java.util.function.Consumer<String> sink){this.sink=sink;}
  void append(String text){
   if(text.isEmpty())return;
   if(length+text.length()>SseReader.MAX_TEXT){
    throw new IllegalArgumentException("response_limit：回复超过本机可显示的大小，已保留收到的内容。");
   }
   length+=text.length();
   received=true;
   sink.accept(text);
  }
 }

 JSONObject parseSse(InputStream input,String protocol,StreamCallback cb)throws Exception{
  SseReader reader=new SseReader(input);
  String reason="";
  ResponseStream state=new ResponseStream();
  NativeToolState nativeTools=new NativeToolState();
  TextLimit answer=new TextLimit(cb::onDelta),thinking=new TextLimit(cb::onReasoning);
  ReasoningText.Tags tagged=new ReasoningText.Tags(answer::append,thinking::append);
  JSONObject usage=new JSONObject();
  boolean terminal=false;
  String searchStatus="";
  int sourceRevision=-1;
  while(!cancelled()){
   SseReader.Event frame=reader.next();
   if(frame==null)break;
   if(frame.data==null){
    terminal=true;
    if(reason.isEmpty())reason="stop";
    break;
   }
   JSONObject value=frame.data;
   String type=value.optString("type",frame.type);
   if(ApiFailure.isProviderError(value,type))throw new IOException(ApiFailure.provider(value));
   nativeTools.observe(protocol,type,value,cb);
   if(RESPONSES.equals(protocol)){
    try{state.accept(type,value);}
    catch(IOException failure){
     if(failure.getMessage()!=null&&failure.getMessage().startsWith("refusal")){
      cb.onSnapshot(state.text());
      cb.onFinished(GenerationResult.of("refusal",usage));
      return null;
     }
     throw failure;
    }
    if(state.answerReplaced)cb.onSnapshot(state.text());
    else if(!state.answerDelta.isEmpty())cb.onDelta(state.answerDelta);
    if(state.thoughtReplaced)cb.onReasoningSnapshot(state.reasoning());
    else if(!state.thoughtDelta.isEmpty())cb.onReasoning(state.thoughtDelta);
    terminal=type.equals("response.completed")||type.equals("response.incomplete");
    String status=state.searchCalled?state.searchStatus:terminal?"本次未调用内置搜索":"";
    if(sourceRevision!=state.sources.revision()||!status.equals(searchStatus)){
     sourceRevision=state.sources.revision();searchStatus=status;
     cb.onSearchSources(state.sources,status);
    }
    JSONObject response=value.optJSONObject("response");
    if(response!=null)GenerationResult.merge(usage,GenerationResult.usage(response.optJSONObject("usage")));
    if(terminal){
     JSONObject detail=response==null?null:response.optJSONObject("incomplete_details");
     reason=type.equals("response.completed")?"stop":detail==null?"max_output_tokens":detail.optString("reason","max_output_tokens");
     break;
    }
   }else if(ANTHROPIC.equals(protocol)){
    JSONObject message=value.optJSONObject("message");
    if(message!=null)GenerationResult.merge(usage,GenerationResult.usage(message.optJSONObject("usage")));
    GenerationResult.merge(usage,GenerationResult.usage(value.optJSONObject("usage")));
    JSONObject delta=value.optJSONObject("delta"),initial=value.optJSONObject("content_block");
    if(type.equals("content_block_start")&&initial!=null){
     if("thinking".equals(initial.optString("type")))thinking.append(initial.optString("thinking"));
     if("text".equals(initial.optString("type")))answer.append(initial.optString("text"));
    }
    if(type.equals("content_block_delta")&&delta!=null){
     answer.append(delta.optString("text"));
     thinking.append(delta.optString("thinking"));
    }
    if(type.equals("message_delta")&&delta!=null)reason=delta.optString("stop_reason",reason);
    if(type.equals("message_stop")){terminal=true;break;}
   }else if(GEMINI.equals(protocol)){
    GenerationResult.merge(usage,GenerationResult.usage(value.optJSONObject("usageMetadata")));
    JSONArray candidates=value.optJSONArray("candidates");
    JSONObject first=candidates==null?null:candidates.optJSONObject(0),feedback=value.optJSONObject("promptFeedback");
    if(feedback!=null&&!feedback.optString("blockReason").isEmpty()){
     reason="blocked";terminal=true;break;
    }
    if(first!=null){
     JSONObject content=first.optJSONObject("content");
     JSONArray parts=content==null?null:content.optJSONArray("parts");
     for(int i=0;parts!=null&&i<parts.length();i++){
      JSONObject part=parts.optJSONObject(i);
      if(part!=null)(part.optBoolean("thought")?thinking:answer).append(part.optString("text"));
     }
     if(!first.isNull("finishReason")&&!first.optString("finishReason").isEmpty()){
      reason=first.optString("finishReason");terminal=true;
     }
    }
   }else{
    GenerationResult.merge(usage,GenerationResult.usage(value.optJSONObject("usage")));
    JSONArray choices=value.optJSONArray("choices");
    JSONObject choice=choices==null?null:choices.optJSONObject(0),delta=choice==null?null:choice.optJSONObject("delta");
    if(delta!=null){
     if(ChatResponseParser.hasRefusal(delta)){
      reason="refusal";terminal=true;
      answer.append(ChatResponseParser.text(delta.opt("refusal")));
     }
     thinking.append(ReasoningText.delta(delta));
     tagged.append(ChatResponseParser.text(delta.opt("content")));
    }
    if(choice!=null&&!choice.isNull("finish_reason")&&!choice.optString("finish_reason").isEmpty()){
     if(!"refusal".equals(reason))reason=choice.optString("finish_reason");
     terminal=true;
    }
   }
  }
  if(cancelled())return null;
  if(ANTHROPIC.equals(protocol)&&terminal&&"pause_turn".equals(reason))return nativeTools.paused(usage);
  if(CHAT_COMPLETIONS.equals(protocol))tagged.finish();
  boolean hasText=answer.received||state.hasText(),hasThought=thinking.received||state.hasReasoning();
  if(!hasText&&!nativeTools.fallback().isEmpty()){
   answer.append(nativeTools.fallback());
   hasText=true;
  }
  if(!hasText&&!hasThought){
   if(!terminal)throw new IOException("empty_stream：连接已结束，但未收到有效答案或结束事件。请检查接口的 SSE 支持。");
   if("stop".equalsIgnoreCase(reason)||"end_turn".equals(reason)){
    cb.onError("no_text：模型返回了空内容");
    return null;
   }
  }
  if(RESPONSES.equals(protocol)&&state.hasText())cb.onSnapshot(state.text());
  cb.onFinished(terminal?GenerationResult.of(reason,usage):new GenerationResult("interrupted","流连接在结束事件前关闭。",usage));
  return null;
 }
 static String searchError(int code){return "web_search_error："+(code>0?"接口错误（"+code+"）":"请求未完成")+"。请检查供应商 / 模型是否支持 Responses 的 web_search 工具及工具权限、计费；可在设置 → 联网搜索选择 Tavily。不自动重发或退回无搜索。";}
 private JSONObject requestBody(String model,JSONArray messages,double t,int max,double top,String effort,String protocol)throws Exception{return requestBody(model,messages,t,max,top,effort,protocol,SearchOptions.OFF);}
 JSONObject requestBody(String model,JSONArray messages,double t,int max,double top,String effort,String protocol,SearchOptions search)throws Exception{if(!search.error.isEmpty())throw new IllegalArgumentException(search.error);if(search.grounded())messages=SearchGrounding.withPolicy(messages);ModelCapabilities caps=new ModelCapabilities(model,protocol,configuredModel);if(NativeProtocols.nativeProtocol(protocol))return BuiltinTools.apply(NativeProtocols.body(model,messages,t,max,top,effort,protocol,caps),protocol,search);if(RESPONSES.equals(protocol)){JSONObject b=new JSONObject().put("model",model).put("input",responsesInput(messages)).put("max_output_tokens",max).put("store",false);if(caps.sampling)b.put("temperature",t).put("top_p",top);String instruction=messages.length()>0&&messages.optJSONObject(0)!=null&&"system".equals(messages.optJSONObject(0).optString("role"))?messages.optJSONObject(0).optString("content",""):"";if(!instruction.isEmpty()){b.put("instructions",instruction);b.getJSONArray("input").remove(0);}ReasoningRequest.responses(b,caps,effort);return BuiltinTools.apply(b,protocol,search);}JSONObject b=new JSONObject().put("model",model).put("messages",chatInput(messages,caps.preserveReasoning())).put(caps.outputTokenKey(),max);if(caps.sampling)b.put("temperature",t).put("top_p",top);ReasoningRequest.chat(b,caps,effort,max);return BuiltinTools.apply(b,protocol,search);}
 private JSONArray chatInput(JSONArray messages,boolean preserveReasoning)throws JSONException{
  JSONArray result=ReasoningRequest.chatMessages(messages,preserveReasoning);
  for(int i=0;i<result.length();i++){
   JSONObject message=result.getJSONObject(i);JSONArray parts=message.optJSONArray("content");if(parts==null)continue;JSONArray content=new JSONArray();
   for(int j=0;j<parts.length();j++){
    JSONObject part=parts.optJSONObject(j);if(part==null)throw new IllegalArgumentException("附件内容块无效");
    String type=part.optString("type");
    if("text".equals(type)||"input_text".equals(type)||"output_text".equals(type))content.put(new JSONObject().put("type","text").put("text",part.optString("text")));
    else if("image_url".equals(type)||"input_image".equals(type)){
     JSONObject image="image_url".equals(type)?part.optJSONObject("image_url"):new JSONObject().put("url",part.optString("image_url"));
     if(image==null||image.optString("url").isEmpty())throw new IllegalArgumentException("图片附件缺少地址");
     content.put(new JSONObject().put("type","image_url").put("image_url",image));
    }else if("file".equals(type)||"input_file".equals(type)){
     JSONObject file=NativeProtocols.fileData(part);
     if(!"application/pdf".equals(NativeProtocols.embeddedMime(file.get("file_data"))))throw new IllegalArgumentException("Chat Completions 原文件输入仅支持 PDF，其他文件请使用 Responses 或提取文字");
     content.put(new JSONObject().put("type","file").put("file",file));
    }else throw new IllegalArgumentException("不支持的附件内容类型："+type);
   }
   message.put("content",content);
  }
  return result;
 }
 private JSONArray responsesInput(JSONArray messages)throws Exception{
  JSONArray out=new JSONArray();
  for(int i=0;i<messages.length();i++){
   JSONObject message=messages.optJSONObject(i);if(message==null)continue;String role=message.optString("role","user");JSONArray content=new JSONArray();Object value=message.opt("content");
   if(value instanceof JSONArray){
    JSONArray parts=(JSONArray)value;
    for(int j=0;j<parts.length();j++){
     JSONObject part=parts.optJSONObject(j);if(part==null)throw new IllegalArgumentException("附件内容块无效");
     String type=part.optString("type");
     if("image_url".equals(type)||"input_image".equals(type)){
      JSONObject image=part.optJSONObject("image_url");Object url="image_url".equals(type)?image==null?null:image.opt("url"):part.opt("image_url");
      if(url==null||url instanceof String&&((String)url).isEmpty())throw new IllegalArgumentException("图片附件缺少地址");
      JSONObject converted=new JSONObject().put("type","input_image").put("image_url",url);
      String detail=image==null?part.optString("detail"):image.optString("detail");if(!detail.isEmpty())converted.put("detail",detail);content.put(converted);
     }else if("input_file".equals(type)||"file".equals(type)){
      JSONObject file=NativeProtocols.fileData(part);NativeProtocols.embeddedMime(file.get("file_data"));
      JSONObject converted=new JSONObject().put("type","input_file").put("file_data",file.get("file_data")).put("filename",file.getString("filename"));
      if(part.has("detail"))converted.put("detail",part.get("detail"));content.put(converted);
     }else if("text".equals(type)||"input_text".equals(type)||"output_text".equals(type))content.put(new JSONObject().put("type","assistant".equals(role)?"output_text":"input_text").put("text",part.optString("text",part.optString("value",""))));
     else throw new IllegalArgumentException("不支持的附件内容类型："+type);
    }
   }else content.put(new JSONObject().put("type","assistant".equals(role)?"output_text":"input_text").put("text",String.valueOf(value==null||value==JSONObject.NULL?"":value)));
   out.put(new JSONObject().put("type","message").put("role","system".equals(role)?"developer":role).put("content",content));
  }
  return out;
 }
 public void topic(String b,String k,String m,String prompt,String first,CallbackResult<String> cb){topic(b,k,m,prompt,first,CHAT_COMPLETIONS,cb);}public void topic(String b,String k,String m,String prompt,String first,String protocol,CallbackResult<String> cb){try{JSONArray h=new JSONArray().put(new JSONObject().put("role","system").put("content",prompt)).put(new JSONObject().put("role","user").put("content",first));chat(b,k,m,h,.2,2048,1,"default",protocol,cb);}catch(Exception e){cb.onError("invalid_config：命名请求参数无效");}}
 public void models(String base,String key,CallbackResult<JSONArray> cb){models(base,key,CHAT_COMPLETIONS,cb);}
 public void models(String base,String key,String protocol,CallbackResult<JSONArray> cb){
  try{String url=NativeProtocols.nativeProtocol(protocol)?NativeProtocols.modelsEndpoint(base,protocol):endpoint(base,"models");request("GET",url,key,protocol,null,new CallbackResult<JSONObject>(){
   public void onSuccess(JSONObject x){JSONArray data=x.optJSONArray(GEMINI.equals(protocol)?"models":"data"),out=new JSONArray();if(data!=null)for(int i=0;i<data.length();i++){JSONObject m=data.optJSONObject(i);String id=m==null?data.optString(i):m.optString(GEMINI.equals(protocol)?"name":"id");if(GEMINI.equals(protocol)){JSONArray supported=m==null?null:m.optJSONArray("supportedGenerationMethods");if(supported!=null&&!supported.toString().contains("generateContent"))continue;if(id.startsWith("models/"))id=id.substring(7);}if(!id.isEmpty())out.put(id);}cb.onSuccess(out);}
   public void onError(String message){cb.onError(message);}
  });}catch(Exception e){cb.onError("API 地址或请求参数无效："+readable(e.getMessage()));}
 }
 private void request(String method,String url,String key,JSONObject json,CallbackResult<JSONObject> cb){request(method,url,key,CHAT_COMPLETIONS,json,cb);}
 private void request(String method,String url,String key,String protocol,JSONObject json,CallbackResult<JSONObject> cb){Request.Builder b=new Request.Builder().url(url).header("Accept","application/json");NativeProtocols.auth(b,key,protocol);if("POST".equals(method))b.post(JsonRequestBody.create(json,JSON));else b.get();enqueue(b.build(),new Callback(){public void onFailure(Call c,IOException e){cb.onError(ApiFailure.safe("网络请求失败："+ApiFailure.local(e),key));}public void onResponse(Call c,Response r){try(Response x=r){if(!x.isSuccessful()){cb.onError(ApiFailure.http(x,protocol,json,key));return;}JSONObject o=readJson(x);if(ApiFailure.isProviderError(o,o.optString("type"))){cb.onError(ApiFailure.safe(ApiFailure.provider(o),key));return;}cb.onSuccess(o);}catch(Exception e){cb.onError(ApiFailure.safe(ApiFailure.local(e),key));}}});}
 private static void auth(Request.Builder b,String k){if(k!=null&&!k.trim().isEmpty())b.header("Authorization","Bearer "+k.trim());}
 static String endpoint(String base,String suffix){String b=base==null?"":base.trim();while(b.endsWith("/"))b=b.substring(0,b.length()-1);if(!(b.startsWith("http://")||b.startsWith("https://")))throw new IllegalArgumentException("API 地址必须以 http:// 或 https:// 开头");for(String x:new String[]{"/chat/completions","/responses"})if(b.endsWith(x))b=b.substring(0,b.length()-x.length());if("models".equals(suffix))return b+"/models";return b+(RESPONSES.equals(suffix)?"/responses":"/chat/completions");}
 private static String readable(String s){return s==null||s.trim().isEmpty()?"未知网络错误":s;}
 public void tavily(String k,String q,int count,String depth,CallbackResult<String> cb){tavilySources(k,q,count,depth,new CallbackResult<SearchSources>(){public void onSuccess(SearchSources s){cb.onSuccess(s.grounding());}public void onError(String error){cb.onError(error);}});}
 public void tavilySources(String k,String q,int count,String depth,CallbackResult<SearchSources> cb){if(k==null||k.trim().isEmpty()){cb.onError("请在设置 → 联网搜索中填写 Tavily API Key");return;}if(count<1||count>20||!("basic".equals(depth)||"advanced".equals(depth))){cb.onError("搜索参数无效：数量应为 1–20，深度为 basic/advanced");return;}try{JSONObject body=new JSONObject().put("api_key",k).put("query",q).put("max_results",count).put("search_depth",depth);request("POST","https://api.tavily.com/search",null,body,new CallbackResult<JSONObject>(){public void onSuccess(JSONObject v){if(v.has("error")){cb.onError("搜索服务返回错误，请检查配置");return;}cb.onSuccess(SearchSources.parse(v.optJSONArray("results")));}public void onError(String x){cb.onError(ApiFailure.safe(x,k));}});}catch(Exception e){cb.onError("搜索请求无效，请检查配置");}}
}