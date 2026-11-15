package com.changyu.ochat;

/** Ignores late callbacks for a network that is no longer the app's default. */
final class GenerationNetworkState {
 enum State {UNKNOWN,CHECKING,CONNECTED,UNVALIDATED,OFFLINE,BLOCKED,CAPTIVE}
 private Object current;
 private State base=State.UNKNOWN;
 private boolean blocked;
 void available(Object network){if(current!=null&&current.equals(network))return;current=network;base=State.CHECKING;blocked=false;}
 void lost(Object network){if(current!=null&&current.equals(network)){current=null;base=State.OFFLINE;blocked=false;}}
 void capabilities(Object network,boolean internet,boolean validated,boolean captive){
  if(current==null||!current.equals(network))return;
  base=captive?State.CAPTIVE:!internet?State.OFFLINE:validated?State.CONNECTED:State.UNVALIDATED;
 }
 void blocked(Object network,boolean value){if(current!=null&&current.equals(network))blocked=value;}
 void initialOffline(){if(current==null)base=State.OFFLINE;}
 State state(){return blocked?State.BLOCKED:base;}
 static boolean mustWait(State state){return state==State.CHECKING||state==State.OFFLINE||state==State.BLOCKED||state==State.CAPTIVE;}
 static String note(State state){
  if(state==State.CHECKING)return "正在确认网络连接…";
  if(state==State.OFFLINE)return "网络已断开 · 等待网络恢复";
  if(state==State.BLOCKED)return "系统限制了联网 · 请检查后台与省电设置";
  if(state==State.CAPTIVE)return "网络需要登录认证";
  return "";
 }
}
