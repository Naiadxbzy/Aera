package com.changyu.ochat;

import org.json.*;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Writes just the chosen conversation version or message as portable Markdown. */
final class ConversationMarkdown {
 static void write(Writer out,JSONObject session,String branchId,String messageId)throws IOException{
  JSONArray messages=messages(session,branchId);
  int selected=messageId.isEmpty()?-1:MessageEdits.indexOf(messages,messageId);
  if(messages==null||messages.length()==0||!messageId.isEmpty()&&selected<0){
   throw new IOException("所选对话版本或消息已不可用。");
  }
  out.write("# "+label(session.optString("title","对话"))+"\n\n");
  SimpleDateFormat time=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.ROOT);
  int start=selected<0?0:selected,end=selected<0?messages.length():selected+1;
  boolean first=true;
  for(int i=start;i<end;i++){
   JSONObject message=messages.optJSONObject(i);
   if(message==null)continue;
   if(!first)out.write("\n\n---\n\n");
   first=false;
   writeMessage(out,message,time);
  }
 }

 private static JSONArray messages(JSONObject session,String branchId){
  if(session==null)return null;
  if(branchId.equals(session.optString("activeBranchId")))return session.optJSONArray("messages");
  JSONArray branches=session.optJSONArray("branches");
  for(int i=0;branches!=null&&i<branches.length();i++){
   JSONObject branch=branches.optJSONObject(i);
   if(branch!=null&&branchId.equals(branch.optString("id")))return branch.optJSONArray("messages");
  }
  return null;
 }

 private static void writeMessage(Writer out,JSONObject message,SimpleDateFormat time)throws IOException{
  boolean user=message.optBoolean("user");
  String role=user?"用户":"模型",model=user?"":message.optString("modelName",message.optString("modelId"));
  out.write("## "+role+(model.isEmpty()?"":" · "+label(model))+"\n\n");
  long timestamp=message.optLong("timestamp",0);
  if(timestamp>0)out.write("> "+time.format(new Date(timestamp))+"\n\n");
  String body=message.optBoolean("error")?"本次回复生成失败。":message.optString("text");
  out.write(body);
  for(MarkdownRenderer.Fence fence:MarkdownRenderer.fences(body)){
   if(!fence.closed){
    int at=fence.start,length=0;
    char marker=body.charAt(at);
    while(at+length<body.length()&&body.charAt(at+length)==marker)length++;
    out.write('\n');
    for(int i=0;i<length;i++)out.write(marker);
    break;
   }
  }

  JSONArray attachments=message.optJSONArray("attachments");
  if(attachments!=null&&attachments.length()>0){
   out.write("\n\n**附件**\n");
   for(int i=0;i<attachments.length();i++){
    JSONObject item=attachments.optJSONObject(i);
    if(item!=null)out.write("\n- "+label(item.optString("displayName","附件")));
   }
  }else if(!message.optString("attachment").isEmpty()){
   out.write("\n\n**附件**\n\n- "+label(message.optString("attachment")));
  }

  JSONArray generatedImages=message.optJSONArray("generatedImages");
  if(generatedImages!=null&&generatedImages.length()>0){
   out.write("\n\n**生成图片**\n");
   for(int i=0;i<generatedImages.length();i++){
    JSONObject image=generatedImages.optJSONObject(i);
    if(image==null)continue;
    String imageModel=image.optString("model","");
    String imageMode=ImageToolConfig.MODE_DIRECT.equals(image.optString("mode"))?"自写提示词":"AI 编写提示词";
    String imagePrompt=image.optString("prompt","").trim();
    out.write("\n- "+(imageModel.isEmpty()?"图片":label(imageModel))+"（"+imageMode+"）");
    if(!imagePrompt.isEmpty())out.write("："+label(imagePrompt));
   }
  }

  JSONArray sources=message.optJSONArray("sources");
  boolean heading=false;
  for(int i=0;sources!=null&&i<sources.length();i++){
   JSONObject source=sources.optJSONObject(i);
   if(source==null)continue;
   String url=source.optString("url");
   if(!safeUrl(url))continue;
   if(!heading){out.write("\n\n**来源**\n");heading=true;}
   out.write("\n- ["+label(source.optString("title",url))+"](<"+url+">)");
  }
  out.write('\n');
 }

 static String fileName(String title){
  String clean=title.replaceAll("[\\\\/:*?\"<>|\\p{Cc}]"," ").trim().replaceFirst("[. ]+$","");
  if(clean.isEmpty())clean="Aera 对话";
  int length=clean.offsetByCodePoints(0,Math.min(64,clean.codePointCount(0,clean.length())));
  return clean.substring(0,length)+".md";
 }

 private static boolean safeUrl(String value){
  try{
   URI uri=new URI(value);
   return ("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme()))
    &&uri.getHost()!=null&&uri.getUserInfo()==null;
  }catch(java.net.URISyntaxException invalid){return false;}
 }

 private static String label(String value){
  return value.replaceAll("\\p{Cc}"," ").replace("\\","\\\\").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
   .replace("[","\\[").replace("]","\\]").replace("*","\\*").replace("_","\\_").replace("\u0060","\\\u0060");
 }

 private ConversationMarkdown(){}
}
