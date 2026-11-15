package com.changyu.ochat;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import androidx.core.app.NotificationManagerCompat;

final class BackgroundProtection {
 static final String PREF="backgroundConnectionEnhanced",DEVICE_PREFS="background_device",OWNED="shizukuExemptionAdded";
 static boolean enabled(Context context){return context.getSharedPreferences("chat",0).getBoolean(PREF,true);}
 static boolean exempt(Context context){
  try{PowerManager power=context.getSystemService(PowerManager.class);return power!=null&&power.isIgnoringBatteryOptimizations(context.getPackageName());}
  catch(RuntimeException unavailable){return false;}
 }
 static SharedPreferences device(Context context){return context.getSharedPreferences(DEVICE_PREFS,0);}
 static boolean owned(Context context){return device(context).getBoolean(OWNED,false);}
 static String summary(Context context){return (enabled(context)?"普通增强已开启":"普通增强已关闭")+" · "+(exempt(context)?"已获省电豁免":"省电豁免未开启");}
 static String status(Context context){
  StringBuilder text=new StringBuilder(exempt(context)?"省电优化：已豁免":"省电优化：未豁免，熄屏后可能暂停联网");
  if(Build.VERSION.SDK_INT>=28){
   ActivityManager manager=context.getSystemService(ActivityManager.class);
   if(manager!=null&&manager.isBackgroundRestricted())text.append("\n后台运行：受系统限制，请在应用信息中设为不受限制");
  }
  if(!NotificationManagerCompat.from(context).areNotificationsEnabled())text.append("\n通知：未开启，后台生成状态不会显示在通知栏");
  PowerManager power=context.getSystemService(PowerManager.class);
  if(power!=null&&power.isPowerSaveMode())text.append("\n系统省电模式：已开启");
  return text.toString();
 }
 private BackgroundProtection(){}
}
