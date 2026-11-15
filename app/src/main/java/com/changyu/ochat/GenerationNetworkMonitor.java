package com.changyu.ochat;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;

/** Exists only while the foreground generation service exists. All state changes run on main. */
final class GenerationNetworkMonitor implements AutoCloseable {
 interface Listener {void changed(GenerationNetworkState.State state);}
 private final ConnectivityManager manager;
 private final Handler main;
 private final Listener listener;
 private final GenerationNetworkState tracker=new GenerationNetworkState();
 private GenerationNetworkState.State published=GenerationNetworkState.State.UNKNOWN;
 private boolean registered,closed;
 private final ConnectivityManager.NetworkCallback callback=new ConnectivityManager.NetworkCallback(){
  @Override public void onAvailable(Network network){dispatch(()->tracker.available(network));}
  @Override public void onLost(Network network){dispatch(()->tracker.lost(network));}
  @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities caps){dispatch(()->capabilities(network,caps));}
  @Override public void onBlockedStatusChanged(Network network,boolean blocked){dispatch(()->tracker.blocked(network,blocked));}
 };
 GenerationNetworkMonitor(Context context,Handler main,Listener listener){
  this.manager=context.getSystemService(ConnectivityManager.class);this.main=main;this.listener=listener;
 }
 void start(){
  if(manager==null)return;
  try{
   manager.registerDefaultNetworkCallback(callback);registered=true;
   Network network=manager.getActiveNetwork();
   if(network==null)tracker.initialOffline();
   else {tracker.available(network);NetworkCapabilities caps=manager.getNetworkCapabilities(network);if(caps!=null)capabilities(network,caps);}
   publish();
  }catch(RuntimeException unavailable){close();} // Network observation must never break generation.
 }
 private void capabilities(Network network,NetworkCapabilities caps){
  tracker.capabilities(network,caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
   caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL));
 }
 private void dispatch(Runnable event){main.post(()->{if(!closed){event.run();publish();}});}
 private void publish(){GenerationNetworkState.State state=tracker.state();if(state!=published){published=state;listener.changed(state);}}
 GenerationNetworkState.State state(){return closed?GenerationNetworkState.State.UNKNOWN:tracker.state();}
 @Override public void close(){closed=true;if(registered){registered=false;try{manager.unregisterNetworkCallback(callback);}catch(RuntimeException ignored){}}}
}
