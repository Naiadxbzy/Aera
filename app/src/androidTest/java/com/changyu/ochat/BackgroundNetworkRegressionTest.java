package com.changyu.ochat;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Real default-network callbacks, on an emulator only. Restores network settings in finally. */
@RunWith(AndroidJUnit4.class)
public class BackgroundNetworkRegressionTest {
 private static String shell(String command)throws Exception{
  try(ParcelFileDescriptor descriptor=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
      InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(descriptor);ByteArrayOutputStream output=new ByteArrayOutputStream()){
   byte[] bytes=new byte[1024];int count;while((count=input.read(bytes))!=-1)output.write(bytes,0,count);return output.toString("UTF-8").trim();
  }
 }
 private static void waitFor(java.util.function.BooleanSupplier condition)throws Exception{
  long end=SystemClock.elapsedRealtime()+15000;
  while(!condition.getAsBoolean()&&SystemClock.elapsedRealtime()<end)Thread.sleep(50);
  assertTrue("Condition timed out",condition.getAsBoolean());
 }
 private void run(boolean stopWhileWaiting)throws Exception{
  org.junit.Assume.assumeTrue("Network manipulation is limited to an emulator",Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  ConnectivityManager networks=context.getSystemService(ConnectivityManager.class);
  String wifi=shell("settings get global wifi_on"),data=shell("settings get global mobile_data");
  SharedPreferences prefs=context.getSharedPreferences("chat",0);Map<String,?> backup=new HashMap<>(prefs.getAll());
  BackgroundGenerationRegressionTest.Transport transport=new BackgroundGenerationRegressionTest.Transport();
  ChatGenerationService.Run[] request=new ChatGenerationService.Run[1];
  try{
   shell("svc wifi disable");shell("svc data disable");waitFor(()->networks.getActiveNetwork()==null);
   JSONObject session=new JSONObject().put("id","network-background-test").put("title","Network test").put("messages",new JSONArray());
   prefs.edit().clear().putBoolean(BackgroundProtection.PREF,true).putString("sessions",new JSONArray().put(session).toString()).putString("currentSessionId","network-background-test").commit();
   try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
    scenario.onActivity(activity->{
     ChatGenerationService.Run run=new ChatGenerationService.Run("network-background-test","mock","default",1234);
     run.client=transport;run.base="https://unused.invalid/v1";run.key="test";run.protocol=ChatApiClient.CHAT_COMPLETIONS;
     run.history=new JSONArray();run.session=session;request[0]=run;ChatGenerationService.launch(activity,run);
    });
    waitFor(()->request[0]!=null&&request[0].status.contains("网络"));
    assertFalse(transport.started.await(400,TimeUnit.MILLISECONDS));assertEquals(0,transport.requests);
    if(stopWhileWaiting){scenario.onActivity(activity->ChatGenerationService.stop(activity,request[0]));waitFor(()->ChatGenerationService.active()==null);}
    shell("svc wifi enable");shell("svc data enable");waitFor(()->networks.getActiveNetwork()!=null);
    if(stopWhileWaiting){Thread.sleep(500);assertEquals(0,transport.requests);assertTrue(request[0].cancel.isCancelled());}
    else{
     assertTrue(transport.started.await(15,TimeUnit.SECONDS));assertEquals(1,transport.requests);
     scenario.moveToState(Lifecycle.State.CREATED);
     transport.callback.onDelta("已接收");
     shell("svc wifi disable");shell("svc data disable");waitFor(()->networks.getActiveNetwork()==null);
     waitFor(()->request[0].status.contains("网络已断开"));assertFalse(request[0].cancel.isCancelled());
     shell("svc wifi enable");shell("svc data enable");waitFor(()->networks.getActiveNetwork()!=null);
     Thread.sleep(700);assertEquals(1,transport.requests);
     transport.callback.onDelta("并保留");transport.callback.onError("Socket closed");
     waitFor(()->ChatGenerationService.active()==null);InstrumentationRegistry.getInstrumentation().waitForIdleSync();
     JSONObject saved=new JSONArray(prefs.getString("sessions","[]")).getJSONObject(0).getJSONArray("messages").getJSONObject(0);
     assertEquals("已接收并保留",saved.getString("text"));assertTrue(saved.getBoolean("interrupted"));
     assertTrue(saved.getString("finishReason").contains("未自动重发"));assertEquals(1,transport.requests);
    }
   }
  }finally{
   if(request[0]!=null&&ChatGenerationService.active()!=null){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->ChatGenerationService.stop(context,request[0]));waitFor(()->ChatGenerationService.active()==null);}
   shell("svc wifi "+("1".equals(wifi)?"enable":"disable"));shell("svc data "+("0".equals(data)?"disable":"enable"));
   SharedPreferences.Editor editor=prefs.edit().clear();
   for(Map.Entry<String,?> entry:backup.entrySet()){
    Object value=entry.getValue();String key=entry.getKey();
    if(value instanceof String)editor.putString(key,(String)value);else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
    else if(value instanceof Integer)editor.putInt(key,(Integer)value);else if(value instanceof Long)editor.putLong(key,(Long)value);
    else if(value instanceof Float)editor.putFloat(key,(Float)value);else if(value instanceof Set)editor.putStringSet(key,(Set<String>)value);
   }editor.commit();
  }
 }
 @Test public void offlineRequestWaitsAndReconnectNeverReplaysSubmittedRequest()throws Exception{run(false);}
 @Test public void cancellingOfflineRequestCannotSendItAfterReconnect()throws Exception{run(true);}
}
