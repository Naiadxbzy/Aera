package com.changyu.ochat;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inspector.WindowInspector;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.filters.SdkSuppress;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion=29)
public class UpgradeWorkflowRegressionTest {
 @Test public void draftsKeepTextCursorAndAttachmentsInTheirOwnConversation()throws Exception{
  try(RegressionPreferences state=new RegressionPreferences()){
   Uri source=Uri.parse("content://com.changyu.ochat.test.attachments/one");
   {
    SessionRepository repository=SessionRepository.get(state.prefs);
    String[] ids=new String[2];
    try(ActivityScenario<MainActivity> screen=ActivityScenario.launch(MainActivity.class)){
     screen.onActivity(activity->{
      ids[0]=state.prefs.getString("currentSessionId","");
      activity.onNewIntent(new Intent(Intent.ACTION_SEND).setType("text/plain")
       .putExtra(Intent.EXTRA_STREAM,source).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
     });
     await(screen,activity->attachments(activity)==1&&Boolean.FALSE.equals(field(activity,"attachmentLoading")));
     screen.onActivity(activity->{
      EditText composer=composer(activity);
      composer.setText("第一份草稿");
      composer.setSelection(1,3);
      call(activity,"resetConversation",new Class<?>[0]);
      ids[1]=state.prefs.getString("currentSessionId","");
      assertNotEquals(ids[0],ids[1]);
      assertEquals("",composer.getText().toString());
      assertEquals(0,attachments(activity));
      composer.setText("第二份草稿");
      composer.setSelection(2);
      openSession(activity,repository,ids[0]);
      assertEquals("第一份草稿",composer.getText().toString());
      assertEquals(1,composer.getSelectionStart());
      assertEquals(3,composer.getSelectionEnd());
      assertEquals(1,attachments(activity));
     });
     screen.recreate();
     await(screen,activity->attachments(activity)==1&&Boolean.FALSE.equals(field(activity,"attachmentLoading")));
     screen.onActivity(activity->{
      assertEquals("第一份草稿",composer(activity).getText().toString());
      assertEquals(1,composer(activity).getSelectionStart());
      assertEquals(3,composer(activity).getSelectionEnd());
      openSession(activity,repository,ids[1]);
      assertEquals("第二份草稿",composer(activity).getText().toString());
      assertEquals(2,composer(activity).getSelectionStart());
      assertEquals(0,attachments(activity));
     });
     repository.flush();
     assertTrue(repository.draftIds().contains(ids[0]));
     assertTrue(repository.draftIds().contains(ids[1]));
    }
    JSONObject sent=SessionRepository.detached(repository.snapshot(ids[0]));
    sent.getJSONArray("messages").put(new JSONObject().put("id","saved").put("user",true).put("text","第一份草稿"));
    repository.upsertAfterSend(sent);
    repository.flush();
    assertNull(repository.composerDraft(ids[0]));
    assertEquals("第二份草稿",repository.composerDraft(ids[1]).getString("text"));
    assertFalse(state.prefs.contains("composer_draft_"+ids[0]));
    assertTrue(state.prefs.getString("sessions","").contains("\"id\":\"saved\""));
   }
  }
 }

 @Test public void fillsPromptVariablesAndInsertsAtTheSelectedRange()throws Exception{
  try(RegressionPreferences state=new RegressionPreferences()){
   new PromptLibrary(state.prefs).save("","语气模板","答：{{主题}}");
   try(ActivityScenario<MainActivity> screen=ActivityScenario.launch(MainActivity.class)){
    screen.onActivity(activity->{composer(activity).setText("A待替换B");composer(activity).setSelection(1,4);});
    tap("插入提示词");
    tap("语气模板");
    enter("填写 主题","$7\\路径");
    tap("插入");
    screen.onActivity(activity->{
     assertEquals("A答：$7\\路径B",composer(activity).getText().toString());
     assertEquals(composer(activity).length()-1,composer(activity).getSelectionStart());
     assertEquals(0,ChatGenerationService.activeCount());
    });
   }
  }
 }

 @Test public void findsMessagesAcrossPagesAndSavesMarkdownThroughDocumentContract()throws Exception{
  try(RegressionPreferences state=new RegressionPreferences();ActivityScenario<MainActivity> screen=ActivityScenario.launch(MainActivity.class)){
   SessionRepository repository=SessionRepository.get(state.prefs);
   JSONArray messages=new JSONArray();
   for(int i=0;i<66;i++){
    String text=i==0?"跨页目标 早期":i==61?"跨页目标 最近":"第 "+i+" 条";
    messages.put(new JSONObject().put("id","find-"+i).put("user",(i&1)==0).put("text",text).put("timestamp",1710000000000L));
   }
   JSONObject session=new JSONObject().put("id","find-regression").put("title","查找回归").put("messages",messages);
   screen.onActivity(activity->{repository.upsert(session);openSession(activity,repository,"find-regression");});
   chooseConversationAction("查找消息");
   enter("查找当前对话","跨页目标");
   await(screen,activity->highlighted(activity,"find-0"));
   tap("下一个匹配");
   await(screen,activity->highlighted(activity,"find-61"));
   tap("上一个匹配");
   await(screen,activity->highlighted(activity,"find-0"));
   screen.onActivity(activity->{
    View bar=(View)field(activity,"conversationFind");
    TextView count=(TextView)field(bar,"count");
    assertEquals("1 / 2 条",count.getText().toString());
   });
   tap("关闭查找");

   File destination=file(state,"upgrade-export-",".md");
   Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
   IntentFilter contract=new IntentFilter(Intent.ACTION_CREATE_DOCUMENT);
   contract.addCategory(Intent.CATEGORY_OPENABLE);
   contract.addDataType("text/markdown");
   Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(contract,
    new Instrumentation.ActivityResult(Activity.RESULT_OK,new Intent().setData(uri(state,destination))),true);
   try{
    chooseConversationAction("导出为 Markdown");
    assertEquals(1,monitor.getHits());
    long deadline=SystemClock.elapsedRealtime()+7000;
    String markdown="";
    while(SystemClock.elapsedRealtime()<deadline){
     markdown=read(destination);
     if(markdown.contains("第 65 条"))break;
     SystemClock.sleep(40);
    }
    assertTrue(markdown.startsWith("# 查找回归"));
    assertTrue(markdown.contains("跨页目标 早期"));
    assertTrue(markdown.contains("跨页目标 最近"));
    assertTrue(markdown.contains("第 65 条"));
   }finally{instrumentation.removeMonitor(monitor);destination.delete();}
  }
 }

 private static void chooseConversationAction(String action){
  tap("对话操作");
  tap(action);
  tap("确认");
 }

 private static void tap(String label){
  Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
  instrumentation.waitForIdleSync();
  instrumentation.runOnMainSync(()->{
   View view=find(label);
   assertNotNull(label,view);
   for(View item=view;item!=null;){
    if(item.hasOnClickListeners()){assertTrue(label,item.performClick());return;}
    ViewParent parent=item.getParent();
    if(parent instanceof AdapterView){
     AdapterView<?> rows=(AdapterView<?>)parent;
     int position=rows.getPositionForView(item);
     assertTrue(label,position>=0);
     assertTrue(label,rows.performItemClick(item,position,rows.getItemIdAtPosition(position)));
     return;
    }
    item=parent instanceof View?(View)parent:null;
   }
   fail("Cannot click "+label);
  });
  instrumentation.waitForIdleSync();
 }

 private static void enter(String label,String value){
  Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
  instrumentation.waitForIdleSync();
  instrumentation.runOnMainSync(()->{
   View view=find(label);
   assertTrue(label,view instanceof EditText);
   ((EditText)view).setText(value);
  });
 }

 private static View find(String label){
  List<View> windows=WindowInspector.getGlobalWindowViews();
  for(int i=windows.size()-1;i>=0;i--){
   View view=find(windows.get(i),label);
   if(view!=null)return view;
  }
  return null;
 }

 private static View find(View view,String label){
  if(!view.isShown())return null;
  if(label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())
   ||view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return view;
  if(view instanceof ViewGroup){
   ViewGroup group=(ViewGroup)view;
   for(int i=0;i<group.getChildCount();i++){
    View found=find(group.getChildAt(i),label);
    if(found!=null)return found;
   }
  }
  return null;
 }

 private static void openSession(MainActivity activity,SessionRepository repository,String id){
  JSONObject session=MessageEdits.findSession(repository.read(),id);
  assertNotNull(session);
  call(activity,"restoreSession",new Class<?>[]{JSONObject.class},session);
 }

 private static void await(ActivityScenario<MainActivity> screen,Predicate<MainActivity> condition){
  long deadline=SystemClock.elapsedRealtime()+7000;
  boolean[] ready={false};
  do{
   screen.onActivity(activity->ready[0]=condition.test(activity));
   if(ready[0])return;
   SystemClock.sleep(40);
  }while(SystemClock.elapsedRealtime()<deadline);
  fail("Timed out waiting for the Android view to reflect its state");
 }

 private static boolean highlighted(MainActivity activity,String message){
  View highlighted=(View)field(activity,"highlightedMessage");
  return highlighted!=null&&("message:"+message).equals(highlighted.getTag());
 }

 private static EditText composer(MainActivity activity){return (EditText)field(activity,"composer");}
 private static int attachments(MainActivity activity){return ((List<?>)field(activity,"pendingAttachments")).size();}

 private static Object field(Object object,String name){
  try{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
  catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
 }

 private static void call(MainActivity activity,String name,Class<?>[] types,Object...args){
  try{Method method=MainActivity.class.getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(activity,args);}
  catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
 }

 private static File file(RegressionPreferences state,String prefix,String extension)throws Exception{
  File directory=new File(state.context.getFilesDir(),"imported_attachments");
  assertTrue(directory.isDirectory()||directory.mkdirs());
  return File.createTempFile(prefix,extension,directory);
 }

 private static Uri uri(RegressionPreferences state,File file){
  return FileProvider.getUriForFile(state.context,state.context.getPackageName()+".attachments",file);
 }

 private static String read(File file)throws Exception{
  try(FileInputStream input=new FileInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream()){
   byte[] buffer=new byte[4096];
   for(int count;(count=input.read(buffer))!=-1;)output.write(buffer,0,count);
   return new String(output.toByteArray(),StandardCharsets.UTF_8);
  }
 }
}
