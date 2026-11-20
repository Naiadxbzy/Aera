package com.changyu.ochat;

import org.json.*;

/** Hosted tools execute at the configured provider, with protocol-specific declarations. */
final class BuiltinTools {
 static JSONObject apply(JSONObject body,String protocol,SearchOptions options)throws JSONException{
  if(!options.error.isEmpty())throw new IllegalArgumentException(options.error);
  if(!options.hasTools())return body;JSONArray tools=new JSONArray();
  if(ChatApiClient.RESPONSES.equals(protocol)){
   if(options.builtin){tools.put(new JSONObject().put("type","web_search"));body.put("include",new JSONArray().put("web_search_call.action.sources"));}
   if(options.codeExecution)tools.put(new JSONObject().put("type","code_interpreter").put("container",new JSONObject().put("type","auto")));
   if(tools.length()>0)body.put("tools",tools).put("tool_choice","auto");
  }else if(ChatApiClient.ANTHROPIC.equals(protocol)){
   if(options.builtin)tools.put(new JSONObject().put("type","web_search_20250305").put("name","web_search"));
   if(options.urlContext)tools.put(new JSONObject().put("type","web_fetch_20250910").put("name","web_fetch").put("citations",new JSONObject().put("enabled",true)));
   if(options.codeExecution)tools.put(new JSONObject().put("type","code_execution_20260521").put("name","code_execution"));
   if(tools.length()>0)body.put("tools",tools);
  }else if(ChatApiClient.GEMINI.equals(protocol)){
   if(options.builtin)tools.put(new JSONObject().put("googleSearch",new JSONObject()));
   if(options.urlContext)tools.put(new JSONObject().put("urlContext",new JSONObject()));
   if(options.codeExecution)tools.put(new JSONObject().put("codeExecution",new JSONObject()));
   if(tools.length()>0)body.put("tools",tools);
  }else if(ChatApiClient.CHAT_COMPLETIONS.equals(protocol)&&options.builtin)body.put("web_search_options",new JSONObject());
  return body;
 }
 static java.util.Set<String> sentNames(JSONObject body){
  java.util.Set<String> names=new java.util.LinkedHashSet<>();
  if(body==null)return names;
  if(body.has("web_search_options"))names.add("web_search_options");
  JSONArray tools=body.optJSONArray("tools");
  for(int i=0;tools!=null&&i<tools.length();i++){
   JSONObject tool=tools.optJSONObject(i);
   if(tool==null)continue;
   String name=tool.optString("name",tool.optString("type"));
   if(!name.isEmpty())names.add(name);
   else for(String entry:new String[]{"googleSearch","urlContext","codeExecution"})if(tool.has(entry))names.add(entry);
  }
  return names;
 }
 static String rejection(int status,JSONObject payload,JSONObject sent){
  if(status!=400&&status!=403&&status!=422)return "";
  java.util.Set<String> names=sentNames(sent);
  if(names.isEmpty()||payload==null)return "";
  JSONObject error=payload.optJSONObject("error");
  if(error==null)error=payload;
  String field=error.optString("param").toLowerCase(java.util.Locale.ROOT);
  String message=error.optString("message").toLowerCase(java.util.Locale.ROOT);
  boolean toolField=field.equals("tools")||field.startsWith("tools[")||field.startsWith("tools.")||
   field.equals("tool_choice")||field.equals("web_search_options")||field.startsWith("web_search_options.");
  if(!toolField){
   boolean namedTool=false;
   for(String name:names)if(message.contains(name.toLowerCase(java.util.Locale.ROOT)))namedTool=true;
   boolean rejected=message.contains("unsupported")||message.contains("not supported")||message.contains("not allowed")||
    message.contains("unknown tool")||message.contains("permission denied");
   if(!namedTool||!rejected)return "";
  }
  boolean searchOnly=names.size()==1&&(names.iterator().next().startsWith("web_search")||names.contains("googleSearch"));
  return searchOnly?"web_search_error":"builtin_tools_error";
 }
 static String details(JSONArray events){
  if(events==null||events.length()==0)return "";StringBuilder text=new StringBuilder("\n\n工具记录：");
  for(int i=0;i<events.length();i++){
   JSONObject event=events.optJSONObject(i);if(event==null)continue;String state=event.optString("status");
   text.append("\n\n").append(event.optString("tool")).append(" · ").append(state.equals("completed")?"完成":state.equals("failed")?"失败":"调用");
   if(!event.optString("input").isEmpty())text.append("\n输入：\n").append(event.optString("input"));
   if(!event.optString("output").isEmpty())text.append("\n输出：\n").append(event.optString("output"));
  }
  return text.toString();
 }
 private BuiltinTools(){}
}
