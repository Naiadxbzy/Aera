package com.changyu.ochat;

import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.widget.SwitchCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ResponsesStatusRegressionTest {
 @Test public void gatewayErrorKeepsDiagnosticsAndAllowsOneRetryWithoutHostedTools()throws Exception{
  org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT>=29);
  try(RegressionPreferences state=new RegressionPreferences();Gateway gateway=new Gateway()){
   JSONObject provider=new JSONObject().put("id","fixture").put("name","Fixture")
    .put("api",gateway.base()).put("apiKey","fixture-secret").put("protocol",ChatApiClient.RESPONSES)
    .put("selectedModelId","gpt-4o-mini").put("models",new JSONArray().put(ModelStore.model("gpt-4o-mini")));
   ModelStore.activate(state.prefs,new JSONArray().put(provider),provider);
   state.prefs.edit().putBoolean("webSearchEnabled",false).putBoolean(SearchOptions.CODE_PREF,true)
    .putBoolean(BackgroundProtection.PREF,false).putBoolean("askedGenerationNotifications",true).commit();
   try(ActivityScenario<MainActivity> screen=ActivityScenario.launch(new Intent(state.context,MainActivity.class))){
    send(screen,"hello Aera");
    JSONObject request=gateway.next();
    assertEquals("code_interpreter",request.getJSONArray("tools").getJSONObject(0).getString("type"));
    assertEquals(1,request.getJSONArray("tools").length());
    screen.onActivity(activity->{
     View root=activity.getWindow().getDecorView();
     MessageStatusView pending=root.findViewWithTag("generation-status");
     assertEquals("正在接收回复",pending.getText().toString());
     assertNotNull(pending.getBackground());
     assertNotNull(pending.getCompoundDrawablesRelative()[0]);
     assertNull(root.findViewWithTag("search-heading"));
    });

    gateway.failFirst.countDown();
    awaitFinished();
    assertNull("A 502 must not replay the POST",gateway.requests.poll(200,TimeUnit.MILLISECONDS));
    screen.onActivity(activity->{
     View root=activity.getWindow().getDecorView();
     MessageStatusView error=root.findViewWithTag("reply-error");
     assertTrue(error.getText().toString().contains("502"));
     assertFalse(error.getText().toString().contains("Bad gateway"));
     assertEquals(View.GONE,root.findViewWithTag("markdown-message").getVisibility());
     assertTrue(error.performClick());
    });
    screen.onActivity(activity->{
     View retry=null;
     for(View window:WindowInspector.getGlobalWindowViews()){
      TextView detail=findText(window,"fixture-ray",false);
      if(detail==null)continue;
      assertTrue(detail.getText().toString().contains("tools=[code_interpreter]"));
      assertFalse(detail.getText().toString().contains("builtin_tools_error"));
      assertFalse(detail.getText().toString().contains("fixture-secret"));
      assertFalse(detail.getText().toString().contains("gateway diagnostic page body"));
      retry=findText(window,"跳过内置工具重试",true);
     }
     assertNotNull(retry);
     assertTrue(retry.performClick());
    });
    JSONObject retried=gateway.next();
    assertFalse(retried.has("tools"));
    assertFalse(retried.has("tool_choice"));
    assertEquals(request.getJSONArray("input").toString(),retried.getJSONArray("input").toString());
    awaitFinished();
    assertTrue(state.prefs.getBoolean(SearchOptions.CODE_PREF,false));

    screen.onActivity(activity->{
     SwitchCompat search=(SwitchCompat)control(activity.getWindow().getDecorView(),"联网搜索");
     assertFalse(search.isChecked());
     search.performClick();
    });
    send(screen,"search if needed");
    JSONObject withSearch=gateway.next();
    assertEquals("web_search",withSearch.getJSONArray("tools").getJSONObject(0).getString("type"));
    awaitFinished();
    screen.onActivity(activity->{
     View root=activity.getWindow().getDecorView();
     MessageStatusView status=root.findViewWithTag("search-heading");
     assertEquals("本次未调用联网搜索",status.getText().toString());
     assertNotNull(status.getCompoundDrawablesRelative()[0]);
     assertFalse(status.isClickable());
     assertNull(root.findViewWithTag("search-content"));
    });
   }
  }
 }

 private static void send(ActivityScenario<MainActivity> screen,String value){
  screen.onActivity(activity->{
   View root=activity.getWindow().getDecorView();
   ((EditText)control(root,"消息输入框")).setText(value);
   assertTrue(control(root,"发送消息").performClick());
  });
 }

 private static void awaitFinished()throws InterruptedException{
  long until=SystemClock.uptimeMillis()+8000;
  while(ChatGenerationService.activeCount()>0&&SystemClock.uptimeMillis()<until)Thread.sleep(25);
  InstrumentationRegistry.getInstrumentation().waitForIdleSync();
  assertEquals(0,ChatGenerationService.activeCount());
 }

 private static View control(View root,String description){
  java.util.ArrayList<View> matches=new java.util.ArrayList<>();
  root.findViewsWithText(matches,description,View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
  for(View candidate:matches)if(description.contentEquals(candidate.getContentDescription()))return candidate;
  throw new AssertionError("expected control: "+description);
 }

 private static TextView findText(View view,String text,boolean exact){
  if(view instanceof TextView){
   String value=((TextView)view).getText().toString();
   if(exact?value.equals(text):value.contains(text))return (TextView)view;
  }
  if(view instanceof ViewGroup){
   ViewGroup group=(ViewGroup)view;
   for(int i=0;i<group.getChildCount();i++){
    TextView match=findText(group.getChildAt(i),text,exact);
    if(match!=null)return match;
   }
  }
  return null;
 }

 private static final class Gateway implements AutoCloseable{
  final ServerSocket server=new ServerSocket(0,3,InetAddress.getByName("127.0.0.1"));
  final CountDownLatch failFirst=new CountDownLatch(1);
  final BlockingQueue<JSONObject> requests=new LinkedBlockingQueue<>();
  final AtomicReference<Throwable> failure=new AtomicReference<>();
  final Thread worker;

  Gateway()throws Exception{
   worker=new Thread(()->{
    for(int i=0;i<3;i++){
     try(Socket socket=server.accept()){
      socket.setSoTimeout(8000);
      InputStream input=new BufferedInputStream(socket.getInputStream());
      assertEquals("POST /v1/responses HTTP/1.1",line(input));
      int length=0;
      for(String header;(header=line(input)).length()>0;){
       if(header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(header.substring(15).trim());
      }
      byte[] body=new byte[length];
      for(int offset=0;offset<length;){
       int count=input.read(body,offset,length-offset);
       if(count<0)throw new EOFException();
       offset+=count;
      }
      requests.add(new JSONObject(new String(body,StandardCharsets.UTF_8)));
      if(i==0){
       assertTrue(failFirst.await(8,TimeUnit.SECONDS));
       respond(socket,"502 Bad Gateway","text/html","<!doctype html><html><title>Bad gateway</title><body>gateway diagnostic page body</body></html>");
      }else{
       respond(socket,"200 OK","text/event-stream","data: {\"type\":\"response.output_text.delta\",\"delta\":\"fixture answer\"}\n\n"+
        "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n");
      }
     }catch(Throwable error){if(!server.isClosed())failure.set(error);return;}
    }
   });
   worker.start();
  }
  String base(){return "http://127.0.0.1:"+server.getLocalPort()+"/v1";}
  JSONObject next()throws Exception{
   JSONObject value=requests.poll(10,TimeUnit.SECONDS);
   if(failure.get()!=null)throw new AssertionError(failure.get());
   assertNotNull("expected a Responses POST",value);
   return value;
  }
  private static String line(InputStream input)throws Exception{
   ByteArrayOutputStream out=new ByteArrayOutputStream();
   for(int value;(value=input.read())!=-1;){
    if(value=='\n')return out.toString("UTF-8").trim();
    if(value!='\r')out.write(value);
   }
   throw new EOFException();
  }
  private static void respond(Socket socket,String status,String type,String body)throws Exception{
   byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
   socket.getOutputStream().write(("HTTP/1.1 "+status+"\r\nContent-Type: "+type+"\r\n"+
    "CF-Ray: fixture-ray\r\nConnection: close\r\nContent-Length: "+bytes.length+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
   socket.getOutputStream().write(bytes);
  }
  @Override public void close()throws Exception{failFirst.countDown();server.close();worker.join(9000);}
 }
}
