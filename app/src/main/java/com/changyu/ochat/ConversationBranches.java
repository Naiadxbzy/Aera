package com.changyu.ochat;

import org.json.*;
import java.util.UUID;

/** Full downstream snapshots; the selected messages remain the legacy storage/API projection. */
final class ConversationBranches {
 static JSONArray copy(JSONArray a)throws JSONException{return new JSONArray(a.toString());}
 static void sync(JSONObject session)throws JSONException{
  JSONArray branches=session.optJSONArray("branches"),messages=session.optJSONArray("messages");
  if(branches==null||messages==null)return;
  String id=session.optString("activeBranchId");
  for(int i=0;i<branches.length();i++){
   JSONObject branch=branches.optJSONObject(i);
   if(branch!=null&&id.equals(branch.optString("id"))){
    branch.put("messages",messages);
    return;
   }
  }
 }
 static JSONObject edit(JSONArray sessions,String sessionId,String messageId,String text,boolean retry)throws JSONException{
  JSONObject s=MessageEdits.findSession(sessions,sessionId);if(s==null)return null;JSONArray m=s.optJSONArray("messages");if(MessageEdits.indexOf(m,messageId)<0)return null;
  JSONArray b=s.optJSONArray("branches");if(b==null){b=new JSONArray();String original=UUID.randomUUID().toString();b.put(new JSONObject().put("id",original).put("messages",m));s.put("branches",b).put("activeBranchId",original);GenerationQueue.retainBranch(s,"",original);}else{String selected=s.optString("activeBranchId");GenerationQueue.retainBranch(s,selected,selected);sync(s);}
  String newId=UUID.randomUUID().toString();JSONArray edited=copy(m);JSONObject branch=new JSONObject().put("id",newId).put("anchorId",messageId).put("messages",edited);b.put(branch);s.put("activeBranchId",newId).put("revisionAnchorId",messageId).put("messages",edited);
  MessageEdits.apply(sessions,sessionId,messageId,text,retry);
  // Direct assistant retry removes the addressed reply: keep the switch on its retained parent.
  JSONArray retained=s.getJSONArray("messages");boolean anchorPresent=MessageEdits.indexOf(retained,messageId)>=0;
  if(!anchorPresent&&retained.length()>0)s.put("revisionAnchorId",retained.getJSONObject(retained.length()-1).getString("id"));
  sync(s);return s;
 }
 static boolean select(JSONObject session,int index)throws JSONException{
  JSONArray branches=session.optJSONArray("branches");
  JSONObject branch=branches==null?null:branches.optJSONObject(index);
  if(branch==null||branch.optJSONArray("messages")==null||branch.optString("id").isEmpty())return false;
  sync(session);
  session.put("activeBranchId",branch.getString("id")).put("messages",branch.getJSONArray("messages"));
  return true;
 }
 static int selected(JSONObject s){JSONArray b=s.optJSONArray("branches");if(b!=null)for(int i=0;i<b.length();i++)if(s.optString("activeBranchId").equals(b.optJSONObject(i).optString("id")))return i;return 0;}
 static String anchor(JSONObject s){String id=s.optString("revisionAnchorId");JSONArray m=s.optJSONArray("messages");for(int i=0;m!=null&&i<m.length();i++)if(id.equals(m.optJSONObject(i).optString("id")))return id;return m!=null&&m.length()>0?m.optJSONObject(m.length()-1).optString("id"):"";}
}
