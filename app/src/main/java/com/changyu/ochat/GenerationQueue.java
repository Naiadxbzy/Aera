package com.changyu.ochat;

import org.json.*;

/** Promotes a queued turn without mutating the stored session before launch succeeds. */
final class GenerationQueue {
 static int next(JSONArray queue,String branchId){
  for(int i=0;i<queue.length();i++){
   JSONObject entry=queue.optJSONObject(i);
   if(entry!=null&&branchId.equals(entry.optString("branchId",branchId))&&entry.optJSONObject("message")!=null)return i;
  }
  return -1;
 }

 static JSONObject promote(JSONObject source,int index)throws JSONException{
  JSONObject session=SessionRepository.detached(source);
  JSONArray queue=session.getJSONArray("outbox");
  JSONObject message=queue.getJSONObject(index).getJSONObject("message");
  session.getJSONArray("messages").put(message);
  queue.remove(index);
  if(queue.length()==0)session.remove("outbox");
  session.put("updatedAt",System.currentTimeMillis());
  ConversationBranches.sync(session);
  return session;
 }

 static void retainBranch(JSONObject session,String original,String target)throws JSONException{
  JSONArray queue=session.optJSONArray("outbox");
  for(int i=0;queue!=null&&i<queue.length();i++){
   JSONObject entry=queue.optJSONObject(i);
   if(entry!=null&&original.equals(entry.optString("branchId",original)))entry.put("branchId",target);
  }
 }

 private GenerationQueue(){}
}
