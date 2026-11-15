package com.changyu.ochat;

import android.content.Context;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BackgroundProtectionRegressionTest {
 private android.widget.TextView text(View view,String value){
  if(view instanceof android.widget.TextView&&value.contentEquals(((android.widget.TextView)view).getText()))return (android.widget.TextView)view;
  if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++){android.widget.TextView found=text(((android.view.ViewGroup)view).getChildAt(i),value);if(found!=null)return found;}
  return null;
 }
 @androidx.test.filters.SdkSuppress(minSdkVersion=29)
 @Test public void ordinarySwitchPersistsAndShizukuCancelDoesNotChangeSystem(){
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  android.content.SharedPreferences prefs=context.getSharedPreferences("chat",0);
  boolean present=prefs.contains(BackgroundProtection.PREF),before=BackgroundProtection.enabled(context),exempt=BackgroundProtection.exempt(context),owned=BackgroundProtection.owned(context);
  try(ActivityScenario<BackgroundSettingsActivity> scenario=ActivityScenario.launch(BackgroundSettingsActivity.class)){
   scenario.onActivity(activity->{
    androidx.appcompat.widget.SwitchCompat toggle=activity.getWindow().getDecorView().findViewWithTag("background-enhanced");
    assertNotNull(toggle);toggle.setChecked(!before);assertEquals(!before,BackgroundProtection.enabled(activity));
    View apply=activity.getWindow().getDecorView().findViewWithTag("background-shizuku-apply");assertNotNull(apply);apply.performClick();
   });
   InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
    android.widget.TextView cancel=null;for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews()){cancel=text(root,"取消");if(cancel!=null)break;}
    assertNotNull(cancel);cancel.performClick();
   });
   scenario.recreate();
   scenario.onActivity(activity->{
    androidx.appcompat.widget.SwitchCompat toggle=activity.getWindow().getDecorView().findViewWithTag("background-enhanced");assertEquals(!before,toggle.isChecked());
    assertEquals(exempt,BackgroundProtection.exempt(activity));assertEquals(owned,BackgroundProtection.owned(activity));
   });
  }finally{if(present)prefs.edit().putBoolean(BackgroundProtection.PREF,before).commit();else prefs.edit().remove(BackgroundProtection.PREF).commit();}
 }
 @Test public void unavailableShizukuFailsWithoutChangingExemption()throws Exception{
  org.junit.Assume.assumeFalse(ShizukuBackgroundController.available());
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  boolean before=BackgroundProtection.exempt(context),owned=BackgroundProtection.owned(context);
  AtomicReference<ShizukuBackgroundController> controller=new AtomicReference<>();AtomicReference<String> result=new AtomicReference<>();CountDownLatch finished=new CountDownLatch(1);
  try{
   InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
    controller.set(new ShizukuBackgroundController(context,new ShizukuBackgroundController.Listener(){
     public void changed(){}public void result(String message){result.set(message);finished.countDown();}
    }));controller.get().apply(true);
   });
   assertTrue(finished.await(5,TimeUnit.SECONDS));assertTrue(result.get().contains("启动 Shizuku"));
   assertEquals(before,BackgroundProtection.exempt(context));assertEquals(owned,BackgroundProtection.owned(context));
  }finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{if(controller.get()!=null)controller.get().close();});}
 }
 @Test public void privilegedServiceRejectsLocalCallsAndUninitializedOwner(){
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();boolean before=BackgroundProtection.exempt(context);
  assertThrows(SecurityException.class,()->new ShizukuPowerService(context).setExempt(true,false));
  assertThrows(SecurityException.class,()->new ShizukuPowerService().setExempt(true,false));
  assertEquals(before,BackgroundProtection.exempt(context));
 }
}
