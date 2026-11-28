package com.changyu.ochat;

import org.json.*;
import java.io.IOException;
import java.util.*;

/** Provider part order is canonical. Ordinary tail deltas never rebuild the full answer. */
final class ResponseStream {
 private final Parts answer=new Parts(""),thought=new Parts("\n\n");
 private final Map<String,Integer> items=new HashMap<>();
 private final Set<Long> sequences=new HashSet<>();
 final SearchSources sources=new SearchSources();
 String searchStatus="",answerDelta="",thoughtDelta="";
 boolean searchCalled,answerReplaced,thoughtReplaced;

 String text(){return answer.snapshot();}
 String reasoning(){return thought.snapshot();}
 boolean hasText(){return answer.length>0;}
 boolean hasReasoning(){return thought.length>0;}

 void accept(String event,JSONObject data)throws Exception{
  answerDelta="";thoughtDelta="";answerReplaced=false;thoughtReplaced=false;
  String type=data.optString("type",event==null?"":event);
  if(data.has("sequence_number")){
   long sequence=data.optLong("sequence_number",-1);
   if(sequence>=0&&!sequences.add(sequence))return;
   if(sequences.size()>262144)throw SseReader.malformed("事件数量超过限制");
  }

  sources.collect(data);
  if(type.startsWith("response.web_search_call.")){
   searchCalled=true;
   searchStatus=toolStatus(type.substring("response.web_search_call.".length()));
  }
  JSONObject item=data.optJSONObject("item");
  if(item!=null&&"web_search_call".equals(item.optString("type"))){
   searchCalled=true;
   searchStatus=toolStatus(item.optString("status"));
  }
  JSONObject response=data.optJSONObject("response");
  if(response!=null)inspectSearch(response);
  if("error".equals(type)||"response.failed".equals(type))throw new IOException(ApiFailure.provider(data));
  if(type.startsWith("response.refusal."))throw new IOException("refusal：模型拒绝回答");

  String id=data.optString("item_id",item==null?"":item.optString("id"));
  int output=data.optInt("output_index",items.getOrDefault(id,0));
  if(!id.isEmpty()){
   if(items.size()>=4096&&!items.containsKey(id))throw SseReader.malformed("条目数量超过限制");
   items.put(id,output);
  }
  long contentKey=key(output,data.optInt("content_index",0));
  long summaryKey=key(output,data.optInt("summary_index",0));
  switch(type){
   case "response.output_text.delta":
    answerDelta=answer.append(contentKey,data.optString("delta"));
    answerReplaced=answerDelta==null;
    break;
   case "response.output_text.done":
    if(!data.optString("text").isEmpty())answerReplaced=answer.replace(contentKey,data.optString("text"));
    break;
   case "response.content_part.done":
    JSONObject part=data.optJSONObject("part");
    if(part!=null&&"output_text".equals(part.optString("type"))&&!part.optString("text").isEmpty()){
     answerReplaced=answer.replace(contentKey,part.optString("text"));
    }
    break;
   case "response.reasoning_summary_text.delta":
    thoughtDelta=thought.append(summaryKey,data.optString("delta"));
    thoughtReplaced=thoughtDelta==null;
    break;
   case "response.reasoning_summary_text.done":
    if(!data.optString("text").isEmpty())thoughtReplaced=thought.replace(summaryKey,data.optString("text"));
    break;
   case "response.reasoning_summary_part.added":
   case "response.reasoning_summary_part.done":
    String summary=ReasoningText.read(data.optJSONObject("part"));
    if(!summary.isEmpty())thoughtReplaced=thought.replace(summaryKey,summary);
    break;
   case "response.output_item.done":
    if(item!=null)replaceItem(output,item);
    break;
   case "response.completed":
   case "response.incomplete":
    if(response!=null){
     if(ApiFailure.isProviderError(response,response.optString("status")))throw new IOException(ApiFailure.provider(data));
     JSONArray finalOutput=response.optJSONArray("output");
     for(int i=0;finalOutput!=null&&i<finalOutput.length();i++){
      JSONObject finalItem=finalOutput.optJSONObject(i);
      if(finalItem!=null)replaceItem(i,finalItem);
     }
    }
    if(type.equals("response.incomplete")&&!hasText()&&!hasReasoning()){
     throw new IOException("token_limit：输出预算耗尽，没有可用答案");
    }
    break;
  }
 }

 void inspectSearch(JSONObject response){
  JSONArray output=response.optJSONArray("output");
  for(int i=0;output!=null&&i<output.length();i++){
   JSONObject item=output.optJSONObject(i);
   if(item!=null&&"web_search_call".equals(item.optString("type"))){
    searchCalled=true;
    searchStatus=toolStatus(item.optString("status"));
   }
  }
  if("incomplete".equals(response.optString("status"))&&searchCalled)searchStatus="内置搜索已调用 · 回复未完成";
 }

 private static String toolStatus(String status){
  switch(status){
   case "in_progress":return "内置搜索已调用 · 准备中…";
   case "searching":return "内置搜索正在搜索…";
   case "completed":return "内置搜索完成";
   case "failed":return "内置搜索失败";
   case "incomplete":return "内置搜索未完成";
   default:return "内置搜索已调用";
  }
 }

 private void replaceItem(int output,JSONObject item)throws IOException{
  if("reasoning".equals(item.optString("type"))){
   JSONArray summaries=item.optJSONArray("summary");
   for(int i=0;summaries!=null&&i<summaries.length();i++){
    String value=ReasoningText.read(summaries.opt(i));
    if(!value.isEmpty())thoughtReplaced|=thought.replace(key(output,i),value);
   }
  }else if("message".equals(item.optString("type"))){
   JSONArray content=item.optJSONArray("content");
   for(int i=0;content!=null&&i<content.length();i++){
    JSONObject part=content.optJSONObject(i);
    if(part==null)continue;
    if("refusal".equals(part.optString("type")))throw new IOException("refusal：模型拒绝回答");
    if("output_text".equals(part.optString("type"))&&!part.optString("text").isEmpty()){
     answerReplaced|=answer.replace(key(output,i),part.optString("text"));
    }
   }
  }
 }

 private static long key(int output,int content){return ((long)output<<32)|(content&0xffffffffL);}

 private static final class Parts {
  private final TreeMap<Long,StringBuilder> values=new TreeMap<>();
  private final String separator;
  int length;
  Parts(String separator){this.separator=separator;}

  String append(long key,String delta)throws IOException{
   if(delta.isEmpty())return "";
   ensureCapacity(key,length+delta.length());
   boolean atEnd=values.isEmpty()||key>=values.lastKey(),newPart=!values.containsKey(key);
   String prefix=newPart&&length>0?separator:"";
   values.computeIfAbsent(key,ignored->new StringBuilder()).append(delta);
   length+=delta.length();
   return atEnd?prefix+delta:null;
  }

  boolean replace(long key,String text)throws IOException{
   StringBuilder old=values.get(key);
   if(old==null&&text.isEmpty()||old!=null&&text.contentEquals(old))return false;
   int next=length-(old==null?0:old.length())+text.length();
   ensureCapacity(key,next);
   if(text.isEmpty())values.remove(key);
   else values.put(key,new StringBuilder(text));
   length=next;
   return true;
  }

  private void ensureCapacity(long key,int next)throws IOException{
   if(next>SseReader.MAX_TEXT||values.size()>=4096&&!values.containsKey(key)){
    throw new IOException("response_limit：回复超过本机可显示的大小，已保留收到的内容。");
   }
  }

  String snapshot(){
   StringBuilder result=new StringBuilder(length);
   for(StringBuilder value:values.values()){
    if(result.length()>0)result.append(separator);
    result.append(value);
   }
   return result.toString();
  }
 }
}
