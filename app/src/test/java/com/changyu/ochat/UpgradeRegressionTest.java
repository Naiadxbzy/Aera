package com.changyu.ochat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class UpgradeRegressionTest {
 @Test public void sseDecodesSplitUtf8AndBackToBackEvents()throws Exception{
  String frames="\ufeff: connected\r\nevent: answer\r\ndata: {\"text\":\"汉\r\n"
   +"data: 字\\\"\"}{\"text\":\"next\"}\r"
   +"data: [DONE]\n";
  try(InputStream chunks=new FilterInputStream(bytes(frames)){
   @Override public int read(byte[] result,int start,int count)throws IOException{
    return super.read(result,start,Math.min(count,2));
   }
  }){
   SseReader reader=new SseReader(chunks);
   SseReader.Event first=reader.next();
   assertEquals("answer",first.type);
   assertEquals("汉字\"",first.data.getString("text"));
   assertEquals("next",reader.next().data.getString("text"));
   assertNull(reader.next().data);
   assertNull(reader.next());
  }
 }

 @Test public void sseSurfacesATruncatedFrameAfterTheLastGoodFrame()throws Exception{
  SseReader reader=new SseReader(bytes("data: {\"text\":\"saved\"}\r\n\ndata: {\"text\":\"partial"));
  assertEquals("saved",reader.next().data.getString("text"));
  IOException error=assertThrows(IOException.class,reader::next);
  assertTrue(error.getMessage(),error.getMessage().contains("连接在事件中途断开"));
 }

 @Test public void sseRejectsExcessiveNestingBeforeParsing()throws Exception{
  String data="data: {\"x\":"+"[".repeat(104)+"0"+"]".repeat(104)+"}\n";
  SseReader reader=new SseReader(bytes(data));
  assertTrue(assertThrows(IOException.class,reader::next).getMessage().contains("JSON 嵌套过深"));
 }

 @Test public void queuePromotionKeepsOtherBranchesAndLeavesTheInputUntouched()throws Exception{
  JSONObject first=message("first","started");
  JSONObject assistant=message("answer","finished");
  JSONArray messages=new JSONArray().put(first).put(assistant);
  JSONObject source=new JSONObject().put("id","s").put("activeBranchId","a").put("messages",messages)
   .put("branches",new JSONArray().put(new JSONObject().put("id","a").put("messages",messages))
    .put(new JSONObject().put("id","b").put("messages",new JSONArray().put(message("other","other branch")))))
   .put("outbox",new JSONArray().put(queued("b","other-queued")).put(queued("a","next-a")).put(queued("a","last-a")));
  assertEquals(1,GenerationQueue.next(source.getJSONArray("outbox"),"a"));
  JSONObject promoted=GenerationQueue.promote(source,1);
  assertEquals(2,source.getJSONArray("messages").length());
  assertEquals(3,source.getJSONArray("outbox").length());
  assertEquals("next-a",promoted.getJSONArray("messages").getJSONObject(2).getString("id"));
  assertEquals("other-queued",promoted.getJSONArray("outbox").getJSONObject(0).getJSONObject("message").getString("id"));
  assertEquals("last-a",promoted.getJSONArray("outbox").getJSONObject(1).getJSONObject("message").getString("id"));
  assertEquals("next-a",promoted.getJSONArray("branches").getJSONObject(0).getJSONArray("messages").getJSONObject(2).getString("id"));
  assertEquals(1,promoted.getJSONArray("branches").getJSONObject(1).getJSONArray("messages").length());
 }

 @Test public void draftsCopyAttachmentDescriptorsAndKeepCursor()throws Exception{
  JSONObject file=new JSONObject().put("uri","content://draft/file").put("displayName","A.pdf")
   .put("payloadSnapshotFile","fixture").put("payloadSnapshot","a".repeat(AttachmentSnapshots.INLINE_CHARS+1));
  JSONObject captured=ComposerDraft.capture("草稿📝",1,-1,Collections.singletonList(file),new JSONArray());
  file.put("displayName","mutated");
  ComposerDraft restored=ComposerDraft.read(new JSONObject(captured.toString()));
  assertEquals("草稿📝",restored.text);
  assertEquals(1,restored.start);
  assertEquals(restored.text.length(),restored.end);
  JSONObject exportedFile=restored.attachments.getJSONObject(0);
  assertEquals("A.pdf",exportedFile.getString("displayName"));
  assertEquals("fixture",exportedFile.getString("payloadSnapshotFile"));
  assertFalse(exportedFile.has("payloadSnapshot"));
  assertNull(ComposerDraft.capture("",0,0,Collections.emptyList(),new JSONArray()));
 }

 @Test public void promptSubstitutionsAreOrderedLiteralAndValidated(){
  String template="{{主题}} / {{target}} / {{主题}}";
  assertEquals(Arrays.asList("主题","target"),PromptLibrary.variables(template));
  Map<String,String> values=new HashMap<>();
  values.put("主题","$4\\路径");
  assertThrows(IllegalArgumentException.class,()->PromptLibrary.render(template,values));
  values.put("target","{{主题}}");
  assertEquals("$4\\路径 / {{主题}} / $4\\路径",PromptLibrary.render(template,values));
 }

 @Test public void conversationSearchHonorsWordBoundariesUnicodeAndThinking()throws Exception{
  JSONObject session=new JSONObject().put("messages",new JSONArray()
   .put(message("one","I after İ — résumé and WORD"))
   .put(message("two","sword").put("reasoningContent","a word in the reasoning"))
   .put(message("three","word reported by the gateway").put("error",true)));
  List<ConversationMatches.Hit> hits=ConversationMatches.find(session,TextSearch.pattern("word",false,true),false,()->true);
  assertEquals(1,hits.size());
  assertEquals("one",hits.get(0).id);
  assertTrue(hits.get(0).excerpt.contains("WORD"));
  List<ConversationMatches.Hit> withThought=ConversationMatches.find(session,TextSearch.pattern("word",true,true),true,()->true);
  assertEquals(1,withThought.size());
  assertEquals("two",withThought.get(0).id);
  assertTrue(withThought.get(0).reasoning);
  assertTrue(ConversationMatches.find(session,TextSearch.pattern("İ",true,false),false,()->true).get(0).excerpt.contains("İ"));
  assertTrue(ConversationMatches.find(session,TextSearch.pattern("word",false,false),true,()->false).isEmpty());
 }

 @Test public void markdownExportsSelectedVersionAndLeavesOutInternalData()throws Exception{
  String raw="### 表格\n\n~~~java\nint result = 1;";
  JSONObject answer=message("reply",raw).put("modelName","fixture_[x]").put("timestamp",1710000000000L)
   .put("reasoningContent","private-why").put("apiKey","private-key")
   .put("attachments",new JSONArray().put(new JSONObject().put("displayName","本地.xlsx").put("uri","content://private/document")))
   .put("sources",new JSONArray().put(new JSONObject().put("title","参考资料").put("url","https://example.test/a"))
    .put(new JSONObject().put("title","local").put("url","file:///private/path")));
  JSONArray earlier=new JSONArray().put(message("ask","old question").put("user",true)).put(answer);
  JSONObject conversation=new JSONObject().put("title","测试 [A]").put("activeBranchId","new")
   .put("messages",new JSONArray().put(message("different","current branch")))
   .put("branches",new JSONArray().put(new JSONObject().put("id","old").put("messages",earlier)));
  StringWriter exported=new StringWriter();
  ConversationMarkdown.write(exported,conversation,"old","reply");
  String text=exported.toString();
  assertTrue(text.startsWith("# 测试 \\[A\\]"));
  assertTrue(text.contains("fixture\\_\\[x\\]"));
  assertTrue(text.contains("> 2024-03-"));
  assertTrue(text.contains(raw+"\n~~~"));
  assertTrue(text.contains("本地.xlsx"));
  assertTrue(text.contains("[参考资料](<https://example.test/a>)"));
  for(String hidden:Arrays.asList("private-why","private-key","content://","file:///","old question","current branch")){
   assertFalse(hidden,text.contains(hidden));
  }
  assertThrows(IOException.class,()->ConversationMarkdown.write(new StringWriter(),conversation,"old","missing"));
  assertEquals("Aera 对话.md",ConversationMarkdown.fileName("/\\:"));
 }

 private static JSONObject queued(String branch,String id)throws Exception{
  return new JSONObject().put("branchId",branch).put("message",message(id,id).put("user",true));
 }

 private static JSONObject message(String id,String body)throws Exception{
  return new JSONObject().put("id",id).put("text",body);
 }

 private static ByteArrayInputStream bytes(String input){return new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));}
}
