package com.changyu.ochat;

import java.io.IOException;

/** Fixed, own-package operations, shared by the privileged adapter and regression tests. */
final class PowerExemption {
 static final String PACKAGE="com.changyu.ochat";
 static final int UNCHANGED=0,ADDED=1,REMOVED=2;
 interface Command {String run(String[] args)throws IOException;}
 static String[] command(char operation){
  if(operation!='+'&&operation!='-'&&operation!='=')throw new IllegalArgumentException("Unsupported operation");
  return new String[]{"/system/bin/dumpsys","deviceidle","whitelist",operation+PACKAGE};
 }
 static boolean query(Command shell)throws IOException{
  String result=shell.run(command('=')).trim();
  if("true".equals(result))return true;
  if("false".equals(result))return false;
  throw new IOException("系统未返回可识别的省电豁免状态："+result);
 }
 static int change(boolean enabled,boolean allowRemoval,Command shell)throws IOException{
  boolean before=query(shell);
  if(enabled==before)return UNCHANGED;
  if(!enabled&&!allowRemoval)throw new IOException("只能撤销由 Aera 添加的豁免，请在系统设置中调整原有配置");
  shell.run(command(enabled?'+':'-'));
  if(query(shell)!=enabled)throw new IOException("系统未应用省电豁免设置，请检查 Shizuku 权限或在系统设置中手动调整");
  return enabled?ADDED:REMOVED;
 }
 private PowerExemption(){}
}
