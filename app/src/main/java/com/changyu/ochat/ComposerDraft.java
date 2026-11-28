package com.changyu.ochat;

import org.json.*;
import java.util.List;

/** Persisted input state scoped to one conversation. Attachment bytes stay in their snapshots. */
final class ComposerDraft {
 final String text;
 final int start,end;
 final JSONArray attachments,imports;
 final boolean imageTool;

 private ComposerDraft(JSONObject value){
  text=value==null?"":value.optString("text");
  start=selection(value==null?0:value.optInt("start",text.length()),text.length());
  end=selection(value==null?0:value.optInt("end",start),text.length());
  JSONArray saved=value==null?null:value.optJSONArray("attachments"),pending=value==null?null:value.optJSONArray("imports");
  attachments=saved==null?new JSONArray():saved;
  imports=pending==null?new JSONArray():pending;
  imageTool=value!=null&&value.optBoolean("imageTool",false);
 }

 static ComposerDraft read(JSONObject value){return new ComposerDraft(value);}

 static JSONObject capture(String text,int start,int end,List<JSONObject> descriptors,JSONArray imports)throws JSONException{
  return capture(text,start,end,descriptors,imports,false);
 }

 static JSONObject capture(String text,int start,int end,List<JSONObject> descriptors,JSONArray imports,boolean imageTool)throws JSONException{
  if(text.isEmpty()&&descriptors.isEmpty()&&imports.length()==0)return null;
  JSONArray attachments=new JSONArray();
  for(JSONObject descriptor:descriptors){
   JSONObject copy=SessionRepository.detached(descriptor);
   if(copy.optString("payloadSnapshot").length()>AttachmentSnapshots.INLINE_CHARS)copy.remove("payloadSnapshot");
   attachments.put(copy);
  }
  JSONObject draft=new JSONObject().put("text",text).put("start",selection(start,text.length()))
   .put("end",selection(end,text.length())).put("attachments",attachments).put("imports",imports);
  if(imageTool)draft.put("imageTool",true);
  return draft;
 }

 private static int selection(int value,int length){return value<0?length:Math.min(value,length);}
}
