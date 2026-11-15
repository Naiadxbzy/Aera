package com.changyu.ochat;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import rikka.shizuku.Shizuku;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Permission and binding belong to the screen; an already confirmed operation safely finishes off-screen. */
final class ShizukuBackgroundController implements AutoCloseable {
 interface Listener {void changed();void result(String message);}
 private static final int REQUEST=719;
 private static final java.util.concurrent.atomic.AtomicBoolean OPERATION=new java.util.concurrent.atomic.AtomicBoolean();
 private final Context context;
 private final Listener listener;
 private final Handler main=new Handler(Looper.getMainLooper());
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final Shizuku.UserServiceArgs args;
 private boolean closed,binding,running,requesting,busy;
 private boolean enable;
 private final Shizuku.OnBinderReceivedListener received=()->changed();
 private final Shizuku.OnBinderDeadListener dead=()->main.post(()->{
  if(busy&&!running){finish("Shizuku 已断开，请启动后重试");}else changed();
 });
 private final Shizuku.OnRequestPermissionResultListener permission=(code,grant)->main.post(()->{
  if(closed||code!=REQUEST||!requesting)return;
  requesting=false;
  if(grant==PackageManager.PERMISSION_GRANTED)bind();
  else finish("未获得 Shizuku 授权；普通增强仍然可用，可在 Shizuku 中管理授权");
 });
 private final Runnable timeout=()->{if(binding&&!running)finish("连接 Shizuku 服务超时，请检查其运行状态后重试");};
 private final ServiceConnection connection=new ServiceConnection(){
  @Override public void onServiceConnected(ComponentName name,IBinder binder){
   main.post(()->{
    if(closed||!binding||running)return;
    main.removeCallbacks(timeout);running=true;
    worker.execute(()->{
     String message;
     try{
      if(!authorized())throw new SecurityException("Shizuku 授权已失效，请重新授权");
      int result=IBackgroundPowerService.Stub.asInterface(binder).setExempt(enable,BackgroundProtection.owned(context));
      if(result==PowerExemption.ADDED){
       if(!BackgroundProtection.device(context).edit().putBoolean(BackgroundProtection.OWNED,true).commit())throw new IllegalStateException("豁免已应用，但本机撤销记录保存失败，请使用系统省电设置管理");
      }else if(!enable&&!BackgroundProtection.device(context).edit().remove(BackgroundProtection.OWNED).commit()){
       throw new IllegalStateException("豁免已撤销，但本机记录保存失败，请重新打开页面检查");
      }
      boolean verified=BackgroundProtection.exempt(context);
      message=verified==enable?(enable?(result==PowerExemption.ADDED?"已开启 Shizuku 省电豁免":"系统已开启省电豁免，无需重复设置"):"已撤销由 Aera 添加的省电豁免"):"系统状态与操作结果不一致，请在系统省电设置中检查";
     }catch(Exception failure){message="Shizuku 设置失败："+ApiFailure.local(failure);}
     final String result=message;
     main.post(()->{running=false;finish(result);if(closed)worker.shutdown();});
    });
   });
  }
  @Override public void onServiceDisconnected(ComponentName name){main.post(()->{if(busy&&!running)finish("Shizuku 服务已断开，请重试");});}
 };
 ShizukuBackgroundController(Context context,Listener listener){
  this.context=context.getApplicationContext();this.listener=listener;
  args=new Shizuku.UserServiceArgs(new ComponentName(this.context,ShizukuPowerService.class))
   .tag("ochat.background.power."+android.os.Process.myUid()).version(1).daemon(false).processNameSuffix("background_power");
  Shizuku.addBinderReceivedListenerSticky(received);
  Shizuku.addBinderDeadListener(dead);Shizuku.addRequestPermissionResultListener(permission);
 }
 static boolean available(){try{return Shizuku.pingBinder()&&!Shizuku.isPreV11()&&Shizuku.getVersion()>=13;}catch(RuntimeException error){return false;}}
 static boolean authorized(){try{return available()&&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;}catch(RuntimeException error){return false;}}
 static String state(){
  if(!available())return "Shizuku 未运行或版本过旧（需要 v13 及以上）";
  if(!authorized())return "Shizuku 已运行 · 未授权";
  try{return "Shizuku 已授权 · "+(Shizuku.getUid()==0?"Root":"ADB");}catch(RuntimeException error){return "Shizuku 状态不可用";}
 }
 boolean busy(){return busy;}
 void apply(boolean enabled){
  if(closed||busy)return;
  if(!available()){listener.result("请先安装并启动 Shizuku v13 或更新版本");changed();return;}
  if(!enabled&&!BackgroundProtection.owned(context)){listener.result("此豁免不是由 Aera 添加，请在系统省电设置中管理");return;}
  if(!OPERATION.compareAndSet(false,true)){listener.result("另一个后台设置操作正在处理中，请稍后重试");return;}
  enable=enabled;busy=true;changed();
  try{
   if(authorized())bind();
   else if(Shizuku.shouldShowRequestPermissionRationale())finish("Shizuku 授权被拒绝，请在 Shizuku 的已授权应用中允许 Aera 后重试");
   else {requesting=true;Shizuku.requestPermission(REQUEST);}
  }catch(RuntimeException failure){finish("无法请求 Shizuku："+ApiFailure.local(failure));}
 }
 private void bind(){
  if(closed)return;
  if(!authorized()){finish("Shizuku 未运行或授权已失效，请重试");return;}
  try{binding=true;main.postDelayed(timeout,15000);Shizuku.bindUserService(args,connection);}
  catch(RuntimeException failure){finish("无法连接 Shizuku 服务："+ApiFailure.local(failure));}
 }
 private void cleanup(){
  main.removeCallbacks(timeout);
  if(binding){binding=false;try{Shizuku.unbindUserService(args,connection,true);}catch(RuntimeException ignored){}
   try{Shizuku.unbindUserService(args,connection,false);}catch(RuntimeException ignored){}}
 }
 private void finish(String message){cleanup();if(busy)OPERATION.set(false);busy=false;requesting=false;if(!closed){listener.changed();listener.result(message);}}
 private void changed(){if(!closed)main.post(()->{if(!closed)listener.changed();});}
 @Override public void close(){
  closed=true;Shizuku.removeBinderReceivedListener(received);Shizuku.removeBinderDeadListener(dead);Shizuku.removeRequestPermissionResultListener(permission);
  if(!running){finish("");worker.shutdown();} // Do not kill a confirmed command before its ownership record is saved.
 }
}
