package com.changyu.ochat;

import android.content.Context;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Explicitly opt-in emulator integration; never changes a normal user's system during the default suite. */
@RunWith(AndroidJUnit4.class)
public class ShizukuBackgroundIntegrationTest {
 private Context prepare(){
  org.junit.Assume.assumeTrue("Run only with -e shizukuIntegration true","true".equals(InstrumentationRegistry.getArguments().getString("shizukuIntegration")));
  org.junit.Assume.assumeTrue(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
  org.junit.Assume.assumeTrue(ShizukuBackgroundController.authorized());
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  org.junit.Assume.assumeFalse(BackgroundProtection.exempt(context));org.junit.Assume.assumeFalse(BackgroundProtection.owned(context));return context;
 }
 private String apply(Context context,boolean enable)throws Exception{
  CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();AtomicReference<ShizukuBackgroundController> controller=new AtomicReference<>();
  try{
   InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
    controller.set(new ShizukuBackgroundController(context,new ShizukuBackgroundController.Listener(){
     public void changed(){}public void result(String message){result.set(message);done.countDown();}
    }));controller.get().apply(enable);
   });
   assertTrue("Shizuku operation timed out",done.await(40,TimeUnit.SECONDS));return result.get();
  }finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{if(controller.get()!=null)controller.get().close();});}
 }
 @Test public void realAdbUserServiceAddsVerifiesAndRemovesOnlyOwnExemption()throws Exception{
  Context context=prepare();
  try{
   String result=apply(context,true);assertTrue(result,result.contains("已开启"));assertTrue(BackgroundProtection.exempt(context));assertTrue(BackgroundProtection.owned(context));
   result=apply(context,false);assertTrue(result,result.contains("已撤销"));assertFalse(BackgroundProtection.exempt(context));assertFalse(BackgroundProtection.owned(context));
  }finally{if(BackgroundProtection.owned(context))apply(context,false);}
 }
 @Test public void confirmedCommandFinishesOwnershipRecordAfterScreenCloses()throws Exception{
  Context context=prepare();AtomicReference<ShizukuBackgroundController> controller=new AtomicReference<>();
  CountDownLatch bound=new CountDownLatch(1);
  try{
   // Close immediately after the worker starts, while the first dumpsys command is still running.
   InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
    controller.set(new ShizukuBackgroundController(context,new ShizukuBackgroundController.Listener(){public void changed(){}public void result(String message){bound.countDown();}}));
    controller.get().apply(true);
   });
   long deadline=android.os.SystemClock.elapsedRealtime()+20000;
   java.lang.reflect.Field running=ShizukuBackgroundController.class.getDeclaredField("running");running.setAccessible(true);
   java.util.concurrent.atomic.AtomicBoolean started=new java.util.concurrent.atomic.AtomicBoolean();
   while(!started.get()&&android.os.SystemClock.elapsedRealtime()<deadline){
    InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{if(running.getBoolean(controller.get())){controller.get().close();started.set(true);}}catch(Exception failure){throw new AssertionError(failure);}});
    if(bound.getCount()==0)break;Thread.sleep(10);
   }
   assertTrue("Command never began",started.get());
   while(!BackgroundProtection.owned(context)&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(25);
   assertTrue(BackgroundProtection.owned(context));assertTrue(BackgroundProtection.exempt(context));
   assertTrue(apply(context,false).contains("已撤销"));assertFalse(BackgroundProtection.exempt(context));
  }finally{
   InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{if(controller.get()!=null)controller.get().close();});
   if(BackgroundProtection.owned(context))apply(context,false);
  }
 }
}
