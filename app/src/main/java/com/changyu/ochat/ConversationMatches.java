package com.changyu.ochat;

import org.json.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Search the selected branch using stable message IDs and coordinates on the original text. */
final class ConversationMatches {
 static final class Hit {
  final String id,excerpt;
  final boolean reasoning;
  Hit(String id,String excerpt,boolean reasoning){this.id=id;this.excerpt=excerpt;this.reasoning=reasoning;}
 }

 static List<Hit> find(JSONObject session,Pattern query,boolean includeThinking,BooleanSupplier current){
  ArrayList<Hit> hits=new ArrayList<>();
  JSONArray messages=session==null?null:session.optJSONArray("messages");
  for(int i=0;messages!=null&&i<messages.length();i++){
   if(!current.getAsBoolean())return Collections.emptyList();
   JSONObject message=messages.optJSONObject(i);
   if(message==null||message.optString("id").isEmpty())continue;
   String id=message.optString("id");
   Hit hit=message.optBoolean("error")?null:match(id,message.optString("text"),query,false);
   if(hit==null&&includeThinking)hit=match(id,message.optString("reasoningContent"),query,true);
   if(hit!=null)hits.add(hit);
  }
  return hits;
 }

 private static Hit match(String id,String text,Pattern query,boolean thinking){
  Matcher matcher=query.matcher(text);
  return matcher.find()?new Hit(id,TextSearch.excerpt(text,matcher.start(),matcher.end()),thinking):null;
 }

 private ConversationMatches(){}
}
