package com.changyu.ochat;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MessageControlsRegressionTest {
 private View described(View root,String value){if(value.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){View v=described(g.getChildAt(i),value);if(v!=null)return v;}}return null;}
 private View text(View root,String value){if(root instanceof TextView&&value.contentEquals(((TextView)root).getText()))return root;if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){View v=text(g.getChildAt(i),value);if(v!=null)return v;}}return null;}
 private void assertDialogAndCancel(Instrumentation ins,String title){ins.waitForIdleSync();ins.runOnMainSync(()->{boolean found=false;for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews())if(text(root,title)!=null){assertTrue(text(root,title).isShown());assertEquals(ThemePalette.resolve(ins.getTargetContext()).ink,((TextView)text(root,title)).getCurrentTextColor());assertNotNull(text(root,"仅编辑"));assertNotNull(text(root,"编辑并重试"));View cancel=text(root,"取消");assertNotNull(cancel);assertTrue(cancel.performClick());found=true;break;}assertTrue(title,found);});ins.waitForIdleSync();}
 private JSONArray messages(SharedPreferences prefs)throws JSONException{
  JSONObject session=MessageEdits.findSession(new JSONArray(prefs.getString("sessions","[]")),"ui-test");
  assertNotNull(session);
  return session.getJSONArray("messages");
 }
 private void awaitIdle(){
  long deadline=android.os.SystemClock.elapsedRealtime()+6000;
  while(ChatGenerationService.active("ui-test")!=null&&android.os.SystemClock.elapsedRealtime()<deadline)android.os.SystemClock.sleep(20);
  assertNull(ChatGenerationService.active("ui-test"));
 }
 @Test public void savedIconsDispatchEditCopyAndRetryAndSettingsIsPinned()throws Exception{
  Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context c=ins.getTargetContext();SharedPreferences p=c.getSharedPreferences("chat",0);SessionRepository repository=SessionRepository.get(p);repository.flush();Map<String,?> backup=new HashMap<>(p.getAll());Activity activity=null;
  try{
   JSONObject session=new JSONObject().put("id","ui-test").put("title","manual").put("titleManual",true).put("messages",new JSONArray().put(new JSONObject().put("id","user-id").put("user",true).put("text","original")).put(new JSONObject().put("id","assistant-id").put("user",false).put("text","Math $x^2$").put("timestamp",123456789).put("modelId","gpt-4o-mini")));
   p.edit().clear().putString("sessions",new JSONArray().put(session).toString()).putString("currentSessionId","ui-test")
    .putString("themeMode","dark").putBoolean("askedGenerationNotifications",true).putBoolean(BackgroundProtection.PREF,false).commit();
   JSONObject provider=new JSONObject().put("id","controls-fixture").put("name","Controls fixture")
    .put("api","https://127.0.0.1:1/v1").put("apiKey","fixture-only").put("protocol",ChatApiClient.CHAT_COMPLETIONS)
    .put("selectedModelId","gpt-4o-mini").put("models",new JSONArray().put(ModelStore.model("gpt-4o-mini")));
   ModelStore.activate(p,new JSONArray().put(provider),provider);
   activity=ins.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Activity a=activity;
   ins.runOnMainSync(()->{
    try{
     Method restore=MainActivity.class.getDeclaredMethod("restoreSession",JSONObject.class);restore.setAccessible(true);
     restore.invoke(a,MessageEdits.findSession(repository.read(),"ui-test"));
     View root=a.getWindow().getDecorView();View add=described(root,"新建对话");assertNotNull(add);
     View settings=described(root,"打开设置");assertNotNull(settings);assertFalse(settings.getParent() instanceof ScrollView);
     Field field=MainActivity.class.getDeclaredField("messageList");field.setAccessible(true);LinearLayout list=(LinearLayout)field.get(a);assertEquals(3,list.getChildCount());
     for(int i=1;i<3;i++){LinearLayout actions=list.getChildAt(i).findViewWithTag("message-actions");assertNotNull(actions);assertEquals(3,actions.getChildCount());assertEquals(44,(int)(actions.getChildAt(0).getLayoutParams().width/c.getResources().getDisplayMetrics().density+.5f));assertTrue(actions.getChildAt(0).isEnabled());for(int j=0;j<3;j++){assertTrue(actions.getChildAt(j) instanceof ImageButton);ImageButton icon=(ImageButton)actions.getChildAt(j);assertEquals(ImageView.ScaleType.CENTER,icon.getScaleType());assertNotNull(icon.getDrawable());assertEquals(20,(int)(icon.getDrawable().getIntrinsicWidth()/c.getResources().getDisplayMetrics().density+.5f));assertEquals(44,(int)(icon.getLayoutParams().height/c.getResources().getDisplayMetrics().density+.5f));assertEquals(0,icon.getPaddingTop());}}
     assertTrue(((TextView)text(root,"original")).isTextSelectable());assertTrue(((TextView)text(root,"original")).getMovementMethod() instanceof android.text.method.ArrowKeyMovementMethod);assertNotNull(described(root,"展开输入框"));View attach=described(root,"添加附件"),expand=described(root,"展开输入框"),send=described(root,"发送消息");for(View control:new View[]{attach,expand,send}){assertTrue(control instanceof ImageButton);assertEquals(48,(int)(control.getLayoutParams().width/c.getResources().getDisplayMetrics().density+.5f));assertEquals(attach.getTop(),control.getTop());assertEquals(20,(int)(((ImageButton)control).getDrawable().getIntrinsicWidth()/c.getResources().getDisplayMetrics().density+.5f));}View copy=described(root,"复制用户消息");assertTrue(copy.performClick());assertEquals("original",((ClipboardManager)c.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip().getItemAt(0).getText().toString());
     described(root,"仅编辑用户消息").performClick();
    }catch(Exception e){throw new AssertionError(e);}
   });
   assertDialogAndCancel(ins,"编辑消息");
   ins.runOnMainSync(()->described(a.getWindow().getDecorView(),"编辑模型消息").performClick());
   assertDialogAndCancel(ins,"编辑消息");
   // The loopback fixture fails locally after the outgoing message and branch have been saved.
   ins.runOnMainSync(()->assertTrue(described(a.getWindow().getDecorView(),"重试模型回复").performClick()));
   ins.waitForIdleSync();
   awaitIdle();
   repository.flush();
   JSONArray retried=messages(p);
   assertEquals("The assistant retry records the loopback failure",2,retried.length());
   assertEquals("user-id",retried.getJSONObject(0).getString("id"));
   assertFalse(retried.toString().contains("assistant-id"));
   ins.runOnMainSync(()->assertTrue(described(a.getWindow().getDecorView(),"重试用户消息").performClick()));
   ins.waitForIdleSync();
   awaitIdle();repository.flush();
   JSONArray retriedAgain=messages(p);
   assertEquals("The user retry records the loopback failure",2,retriedAgain.length());
   assertEquals("user-id",retriedAgain.getJSONObject(0).getString("id"));
   for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews())assertNull(text(root,"重试回复"));
   ins.runOnMainSync(()->{
    try{Method restore=MainActivity.class.getDeclaredMethod("restoreSession",JSONObject.class);restore.setAccessible(true);repository.replace(new JSONArray().put(session));restore.invoke(a,session);Field f=MainActivity.class.getDeclaredField("messageList");f.setAccessible(true);LinearLayout list=(LinearLayout)f.get(a);assertEquals(3,list.getChildCount());assertEquals(3,((LinearLayout)list.getChildAt(1).findViewWithTag("message-actions")).getChildCount());Method row=MainActivity.class.getDeclaredMethod("assistantRow",MarkdownMessageView.class,long.class,String.class);row.setAccessible(true);View unsaved=(View)row.invoke(a,new MarkdownMessageView(a,MarkdownRenderer.create(a)),1L,"test");LinearLayout actions=unsaved.findViewWithTag("message-actions");for(int i=0;i<3;i++)assertFalse(actions.getChildAt(i).isEnabled());}catch(Exception e){throw new AssertionError(e);}
   });
  }finally{
   if(activity!=null){Activity a=activity;ins.runOnMainSync(a::finish);ins.waitForIdleSync();}
   repository.flush();SharedPreferences.Editor e=p.edit().clear();for(Map.Entry<String,?> entry:backup.entrySet()){Object v=entry.getValue();String k=entry.getKey();if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);else if(v instanceof Float)e.putFloat(k,(Float)v);else if(v instanceof Set)e.putStringSet(k,(Set<String>)v);}e.commit();
  }
 }
}
