package com.changyu.ochat;

import android.content.Context;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteException;
import androidx.annotation.Keep;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Short-lived Shizuku UserService. Never receives prompts, credentials or arbitrary commands. */
@Keep
public final class ShizukuPowerService extends IBackgroundPowerService.Stub {
 private final int ownerUid;
 @Keep public ShizukuPowerService(){ownerUid=-1;}
 @Keep public ShizukuPowerService(Context context){
  ownerUid=PowerExemption.PACKAGE.equals(context.getPackageName())?context.getApplicationInfo().uid:-1;
 }
 private void enforceOwner(){
  int caller=Binder.getCallingUid();
  if(ownerUid<0||caller!=ownerUid||caller==Process.myUid())throw new SecurityException("Only the owning Aera process may change its exemption");
 }
 @Override public synchronized int setExempt(boolean enabled,boolean allowRemoval)throws RemoteException{
  enforceOwner();
  try{return PowerExemption.change(enabled,allowRemoval,ShizukuPowerService::command);}
  catch(IOException failure){throw new RemoteException(failure.getMessage());}
 }
 private static String command(String[] args)throws IOException{
  java.lang.Process process=new ProcessBuilder(args).redirectErrorStream(true).start();
  try{
   long deadline=android.os.SystemClock.elapsedRealtime()+10000;
   while(true){try{process.exitValue();break;}catch(IllegalThreadStateException running){
    if(android.os.SystemClock.elapsedRealtime()>=deadline)throw new IOException("系统省电设置操作超时");
    Thread.sleep(50);
   }}
   try(InputStream input=process.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
    byte[] buffer=new byte[1024];int count;
    while((count=input.read(buffer))!=-1){if(bytes.size()+count>8192)throw new IOException("系统返回了异常的省电设置结果");bytes.write(buffer,0,count);}
    String result=bytes.toString("UTF-8").trim();
    if(process.exitValue()!=0)throw new IOException("系统拒绝省电设置："+result);
    return result;
   }
  }catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new IOException("省电设置操作已取消",cancelled);}
  finally{process.destroy();}
 }
 @Override public void destroy(){
  int caller=Binder.getCallingUid();
  if(caller!=0&&caller!=2000&&caller!=ownerUid)throw new SecurityException("Not the owner");
  System.exit(0);
 }
}
