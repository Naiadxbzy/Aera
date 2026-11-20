package com.changyu.ochat;

import org.json.*;
import okhttp3.Response;

/** Bounded, readable diagnostics. Only request settings are shown, never message/file bodies. */
final class ApiFailure {
 static String http(Response response,String protocol,JSONObject sent,String secret){
  String raw="";
  try{if(response.body()!=null)raw=response.peekBody(32768).string();}catch(Exception ignored){}
  JSONObject payload=null;
  try{payload=new JSONObject(raw);}catch(JSONException ignored){}
  String detail=payload==null?nonJsonDetail(raw,response.header("Content-Type","")):provider(payload);
  String category=BuiltinTools.rejection(response.code(),payload,sent);
  StringBuilder result=new StringBuilder();
  if(!category.isEmpty())result.append(category).append("：");
  result.append("接口错误（").append(response.code()).append("） · ").append(status(response.code())).append('\n').append(detail);
  for(String header:new String[]{"x-request-id","request-id","x-goog-request-id","cf-ray"}){
   String id=response.header(header);
   if(id!=null&&!id.isEmpty()){result.append("\n请求 ID：").append(header).append(": ").append(shorten(id,160));break;}
  }
  String settings=settings(protocol,sent);
  if(!settings.isEmpty())result.append("\n本次请求：").append(settings);
  return safe(result.toString(),secret);
 }
 private static final java.util.regex.Pattern HTML_START=java.util.regex.Pattern.compile("(?is)^\\s*<(?:!doctype\\s+html|html\\b|head\\b|body\\b|title\\b|h1\\b)");
 private static final java.util.regex.Pattern HTML_HEADING=java.util.regex.Pattern.compile("(?is)<(title|h1)\\b[^>]*>(.*?)</\\1\\s*>");
 private static final java.util.regex.Pattern HTTP_STATUS=java.util.regex.Pattern.compile("(?m)^(?:(?:builtin_tools_error|web_search_error)：\\s*)?接口错误[（(](\\d{3})[）)]");
 private static String nonJsonDetail(String raw,String contentType){
  if((contentType!=null&&contentType.toLowerCase(java.util.Locale.ROOT).contains("text/html"))||HTML_START.matcher(raw).find()){
   java.util.regex.Matcher heading=HTML_HEADING.matcher(raw);
   if(heading.find()){
    String title=raw.substring(heading.start(2),heading.end(2)).replaceAll("<[^>]*>"," ").replaceAll("\\s+"," ").trim();
    if(!title.isEmpty())return "网关错误页："+shorten(title,180);
   }
   return "接口返回了网页错误";
  }
  String detail=raw.replaceAll("(?is)<script[^>]*>.*?</script>"," ").replaceAll("(?s)<[^>]*>"," ").replaceAll("\\s+"," ").trim();
  return detail.isEmpty()?"服务端未提供错误正文":shorten(detail,600);
 }
 static boolean isToolError(String detail){
  return detail.startsWith("web_search_error：")||detail.startsWith("builtin_tools_error：");
 }
 static boolean canRetryWithoutTools(String detail){
  if(isToolError(detail))return true;
  java.util.regex.Matcher http=HTTP_STATUS.matcher(detail);
  return http.find()&&("502".equals(http.group(1))||"503".equals(http.group(1))||"504".equals(http.group(1)));
 }
 static String summary(String detail){
  java.util.regex.Matcher http=HTTP_STATUS.matcher(detail);
  if(http.find()){
   int code=Integer.parseInt(detail.substring(http.start(1),http.end(1)));
   return "接口错误（"+code+"） · "+(code>=500?"服务暂不可用":code==400||code==422?"请求参数未被接受":status(code));
  }
  if(isToolError(detail))return "内置工具请求失败";
  return "回复生成失败";
 }
 static String provider(JSONObject root){
  JSONObject response=root.optJSONObject("response");if(response!=null)root=response;
  Object value=root.opt("error");if(value==null||value==JSONObject.NULL)value=root;
  StringBuilder out=new StringBuilder("provider_error：");
  if(value instanceof JSONObject){
   JSONObject e=(JSONObject)value;String message=e.optString("message","");out.append(message.isEmpty()?"服务端拒绝或未能完成请求":shorten(message,1800));
   for(String k:new String[]{"code","type","param","status"}){Object v=e.opt(k);if(v!=null&&v!=JSONObject.NULL&&!(v instanceof JSONObject)&&!(v instanceof JSONArray))out.append("\n").append(k).append("：").append(shorten(String.valueOf(v),240));}
   JSONArray details=e.optJSONArray("details");
   if(details!=null)for(int i=0;i<Math.min(6,details.length());i++){
    JSONObject d=details.optJSONObject(i);if(d==null)continue;JSONArray fields=d.optJSONArray("fieldViolations");if(fields!=null)for(int j=0;j<Math.min(6,fields.length());j++){JSONObject f=fields.optJSONObject(j);if(f!=null)out.append("\n字段 ").append(shorten(f.optString("field"),160)).append("：").append(shorten(f.optString("description"),500));}
    for(String k:new String[]{"reason","message","description"})if(d.has(k))out.append("\n").append(k).append("：").append(shorten(d.optString(k),500));
   }
  }else out.append(shorten(String.valueOf(value),1800));
  return safe(out.toString(),"");
 }
 static String settings(String protocol,JSONObject body){
  StringBuilder out=new StringBuilder(protocol==null?"":protocol);if(body==null)return out.toString();
  for(String key:new String[]{"model","max_tokens","max_completion_tokens","max_output_tokens","temperature","top_p","reasoning_effort"})append(out,key,body.opt(key));
  JSONObject reasoning=body.optJSONObject("reasoning");if(reasoning!=null)append(out,"reasoning.effort",reasoning.opt("effort"));
  JSONObject thinking=body.optJSONObject("thinking");if(thinking!=null){append(out,"thinking.type",thinking.opt("type"));append(out,"thinking.budget_tokens",thinking.opt("budget_tokens"));}
  JSONObject output=body.optJSONObject("output_config");if(output!=null)append(out,"output_config.effort",output.opt("effort"));
  JSONObject config=body.optJSONObject("generationConfig");if(config!=null){
   for(String key:new String[]{"maxOutputTokens","temperature","topP"})append(out,key,config.opt(key));
   JSONObject tc=config.optJSONObject("thinkingConfig");if(tc!=null)for(String key:new String[]{"thinkingLevel","thinkingBudget"})append(out,key,tc.opt(key));
  }
  if(body.has("stream_options"))append(out,"stream_options.include_usage",true);
  java.util.Set<String> tools=BuiltinTools.sentNames(body);
  if(!tools.isEmpty())append(out,"tools",tools);
  return out.toString();
 }
 private static void append(StringBuilder b,String key,Object value){if(value==null||value==JSONObject.NULL||value instanceof JSONObject||value instanceof JSONArray)return;if(b.length()>0)b.append(" · ");b.append(key).append('=').append(shorten(String.valueOf(value),160));}
 static String local(Throwable error){String message=error.getMessage();return safe(error.getClass().getSimpleName()+(message==null||message.trim().isEmpty()?"":": "+message),"");}
 static String safe(String text,String secret){
  String s=text==null?"未知错误":text;
  if(secret!=null&&!secret.isEmpty())s=s.replace(secret,"[已隐藏密钥]");
  s=s.replaceAll("(?i)Bearer\\s+[^\\s,;\\\"'<>]+","Bearer [已隐藏密钥]")
   .replaceAll("(?i)([\\\"']?(?:api[_-]?key|authorization|access[_-]?token|x-api-key)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s,;\\\"'<>]+","$1[已隐藏密钥]")
   .replaceAll("\\bsk-[A-Za-z0-9_-]{8,}","[已隐藏密钥]")
   .replaceAll("[\\p{Cc}&&[^\\n\\t]]"," ");
  return shorten(s,6000);
 }
 private static String status(int code){switch(code){case 400:case 422:return "参数或协议不被接受，请核对下方字段与模型能力";case 401:return "身份验证失败，请检查 API Key";case 403:return "无访问权限，请检查模型权限和区域限制";case 404:return "模型或接口路径不存在";case 413:return "请求或附件过大";case 429:return "额度不足或请求过于频繁";case 502:return "网关返回异常，可稍后重试";case 504:return "网关等待超时，可稍后重试";default:return code>=500?"服务端暂时异常，可稍后重试":"请求未成功";}}
 private static String shorten(String s,int max){return s.length()<=max?s:s.substring(0,max)+"…";}
 private ApiFailure(){}
}
