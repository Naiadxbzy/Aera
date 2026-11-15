package com.changyu.ochat;

import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.*;
import android.webkit.WebView;
import android.widget.*;
import androidx.core.graphics.ColorUtils;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SvgDisplayRegressionTest {
 static final String SVG="<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 180\" width=\"800\" height=\"700\" role=\"img\" aria-labelledby=\"title desc\">\n<title id=\"title\">Pelican riding a bicycle</title>\n<desc id=\"desc\">A cheerful white pelican</desc>\n<circle cx=\"100\" cy=\"90\" r=\"70\" fill=\"#4aa3df\"/>\n</svg>";
 private <T extends View>T find(View view,Class<T> type){
  if(type.isInstance(view))return type.cast(view);
  if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){T match=find(((ViewGroup)view).getChildAt(i),type);if(match!=null)return match;}return null;
 }
 private TextView text(View view,String value){
  if(view instanceof TextView&&value.contentEquals(((TextView)view).getText()))return (TextView)view;
  if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView match=text(((ViewGroup)view).getChildAt(i),value);if(match!=null)return match;}return null;
 }
 private TextView source(CodeBlockView block){TextView view=findSource(block);assertNotNull(view);return view;}
 private TextView findSource(View view){
  if(view instanceof TextView&&android.text.TextUtils.equals("代码内容",view.getContentDescription()))return (TextView)view;
  if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView match=findSource(((ViewGroup)view).getChildAt(i));if(match!=null)return match;}return null;
 }
 @Test public void rawSvgStreamsIntoSameCardAndCopiesLiteralMarkup()throws Exception{
  try(RegressionPreferences state=new RegressionPreferences()){
   state.prefs.edit().putString("themeMode","light").commit();
   try(ActivityScenario<MainActivity> screen=ActivityScenario.launch(MainActivity.class)){
    screen.onActivity(activity->{
     MarkdownMessageView message=new MarkdownMessageView(activity,MarkdownRenderer.create(activity));CodeBlockView first=null;
     for(int end=4;end<=SVG.length();end+=13){
      String snapshot=SVG.substring(0,end);message.setMarkdown(snapshot);CodeBlockView block=find(message,CodeBlockView.class);assertNotNull(block);
      if(first==null){first=block;block.setExpanded(true);}else assertSame(first,block);
      assertEquals(snapshot,block.source());
     }
     message.setMarkdown(SVG);assertSame(first,find(message,CodeBlockView.class));assertEquals(1,message.getChildCount());assertEquals(SVG,first.source());assertTrue(first.isExpanded());
     assertEquals(SVG,source(first).getText().toString());assertNotNull(text(first,"svg"));assertEquals(View.VISIBLE,text(first,"预览").getVisibility());
     text(first,"复制").performClick();
     ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);assertNotNull(clipboard);ClipData clip=clipboard.getPrimaryClip();assertNotNull(clip);assertEquals(SVG,clip.getItemAt(0).getText().toString());
     message.setMarkdown("说明\n\n~~~xml\n"+SVG+"\n~~~\n结尾");CodeBlockView xml=find(message,CodeBlockView.class);assertNotNull(xml);assertNotNull(text(xml,"svg"));assertEquals(View.VISIBLE,text(xml,"预览").getVisibility());
    });
   }
  }
 }
 @Test public void sourceColorsFollowLightDarkAndCustomSurfaceWithoutChangingCode()throws Exception{
  try(RegressionPreferences state=new RegressionPreferences()){
   for(String mode:new String[]{"light","dark","custom"}){
    state.prefs.edit().putString("themeMode","custom".equals(mode)?"dark":mode).remove("themeSurface").commit();
    if("custom".equals(mode))state.prefs.edit().putString("themeSurface","#ffffff").commit();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
     CodePalette palette=CodePalette.resolve(state.context);
     if("dark".equals(mode))assertTrue(ColorUtils.calculateLuminance(palette.background)<.15);else assertTrue(ColorUtils.calculateLuminance(palette.background)>.85);
     assertTrue(ColorUtils.calculateContrast(palette.text,palette.background)>=4.5);
     Set<Integer> colors=new HashSet<>();for(int color:palette.tokens){assertTrue(ColorUtils.calculateContrast(color,palette.background)>=4.5);colors.add(color);}assertTrue(colors.size()>=4);
     CodeBlockView block=new CodeBlockView(state.context);block.setSource(SVG,"svg");TextView code=source(block);
     assertEquals(palette.text,code.getCurrentTextColor());assertEquals(SVG,code.getText().toString());
     assertEquals(android.graphics.Typeface.MONOSPACE,code.getTypeface());assertTrue(code.getTextSize()<15*state.context.getResources().getDisplayMetrics().scaledDensity);
     assertTrue(((Spanned)code.getText()).getSpans(0,code.length(),ForegroundColorSpan.class).length>=6);
    });
   }
  }
 }
 private void capture(Context context,String name)throws Exception{
  if(!"true".equals(InstrumentationRegistry.getArguments().getString("svgScreenshots")))return;
  InstrumentationRegistry.getInstrumentation().waitForIdleSync();Bitmap image=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(image);
  java.io.File directory=context.getExternalFilesDir("svg-review");assertNotNull(directory);
  try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(directory,name+".png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));}finally{image.recycle();}
 }
 @Test public void savedBareSvgUsesModernCardAfterReloadAndRecreation()throws Exception{
  try(RegressionPreferences state=new RegressionPreferences()){
   state.prefs.edit().putString("themeMode","light").commit();
   JSONObject session=new JSONObject().put("id","svg-history").put("title","SVG history").put("messages",new JSONArray()
    .put(new JSONObject().put("id","u").put("user",true).put("text","Generate an SVG").put("timestamp",1))
    .put(new JSONObject().put("id","a").put("user",false).put("text",SVG).put("timestamp",2)));
   ConversationBranches.sync(session);SessionRepository.get(state.prefs).replace(new JSONArray().put(session));state.prefs.edit().putString("currentSessionId","svg-history").commit();
   try(ActivityScenario<MainActivity> screen=ActivityScenario.launch(new Intent(state.context,MainActivity.class).putExtra("resumeGeneration",true))){
    screen.onActivity(activity->{try{java.lang.reflect.Method restore=MainActivity.class.getDeclaredMethod("restoreSession",JSONObject.class);restore.setAccessible(true);restore.invoke(activity,session);}catch(Exception error){throw new AssertionError(error);}
     CodeBlockView block=find(activity.getWindow().getDecorView(),CodeBlockView.class);assertNotNull(block);assertEquals(SVG,block.source());
    });
    capture(state.context,"svg-card-light");
    screen.onActivity(activity->find(activity.getWindow().getDecorView(),CodeBlockView.class).setExpanded(true));
    screen.recreate();InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    screen.onActivity(activity->{CodeBlockView block=find(activity.getWindow().getDecorView(),CodeBlockView.class);assertNotNull(block);assertEquals(SVG,block.source());assertTrue(block.isExpanded());});
   }
  }
 }
 @Test public void actualSvgCanvasRendersAndSourceTabMatchesTheme()throws Exception{
  try(RegressionPreferences state=new RegressionPreferences()){
   state.prefs.edit().putString("themeMode","light").commit();
   try(ActivityScenario<MainActivity> screen=ActivityScenario.launch(MainActivity.class)){
    PanelDialog[] dialog=new PanelDialog[1];WebView[] web=new WebView[1];
    try{
     screen.onActivity(activity->{dialog[0]=CodePreview.show(activity,SVG,"xml");web[0]=find(dialog[0].getWindow().getDecorView(),WebView.class);assertNotNull(web[0]);assertFalse(web[0].getSettings().getJavaScriptEnabled());assertFalse(web[0].getSettings().getAllowFileAccess());assertFalse(web[0].getSettings().getAllowContentAccess());assertFalse(web[0].getSettings().getBlockNetworkLoads());});
     long end=android.os.SystemClock.elapsedRealtime()+15000;AtomicBoolean ready=new AtomicBoolean();
     while(!ready.get()&&android.os.SystemClock.elapsedRealtime()<end){screen.onActivity(activity->ready.set("preview-ready".equals(web[0].getTag())));Thread.sleep(50);}
     assertTrue("WebView did not render",ready.get());capture(state.context,"svg-canvas");Rect bounds=new Rect();screen.onActivity(activity->assertTrue(web[0].getGlobalVisibleRect(bounds)));
     Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(screenshot);
     try{int matches=0;for(int y=bounds.top;y<Math.min(bounds.bottom,screenshot.getHeight());y+=3)for(int x=bounds.left;x<Math.min(bounds.right,screenshot.getWidth());x+=3){int color=screenshot.getPixel(x,y);if(Math.abs(Color.red(color)-0x4a)<8&&Math.abs(Color.green(color)-0xa3)<8&&Math.abs(Color.blue(color)-0xdf)<8)matches++;}assertTrue("SVG must paint a blue circle, not only title/description text",matches>1000);}finally{screenshot.recycle();}
     screen.onActivity(activity->{
      View root=dialog[0].getWindow().getDecorView();assertNotNull(text(root,"SVG 预览"));text(root,"源码").performClick();
      ScrollView source=root.findViewWithTag("preview-source");assertEquals(View.VISIBLE,source.getVisibility());
      assertEquals(CodePalette.resolve(activity).background,((android.graphics.drawable.ColorDrawable)source.getBackground()).getColor());
      TextView code=find(source,TextView.class);assertEquals(SVG,code.getText().toString());assertEquals(CodePalette.resolve(activity).text,code.getCurrentTextColor());
      dialog[0].getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick();assertTrue(dialog[0].isShowing());
     });
     capture(state.context,"svg-source-light");
    }finally{screen.onActivity(activity->{if(dialog[0]!=null)dialog[0].dismiss();});}
   }
  }
 }
}
