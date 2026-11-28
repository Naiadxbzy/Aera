package com.changyu.ochat;

import android.os.Handler;
import android.os.Looper;
import java.util.function.Consumer;

/** A lossless producer buffer with a cached immutable view for UI and checkpoints. */
final class StreamingMarkdown {
 private final StringBuffer source;
 private final Runnable changed;
 private final Consumer<String> render;
 private final Updates standalone;
 private boolean sealed;
 private String snapshot;

 StreamingMarkdown(StringBuffer source,Runnable changed){
  this.source=source;
  this.changed=changed;
  render=null;
  standalone=null;
 }

 StreamingMarkdown(StringBuffer source,Consumer<String> render){
  this.source=source;
  this.render=render;
  standalone=new Updates(()->render.accept(snapshot()));
  changed=()->standalone.schedule(source.length());
 }

 void append(String delta){
  if(delta.isEmpty())return;
  synchronized(this){
   if(sealed)return;
   source.append(delta);
   snapshot=null;
  }
  changed.run();
 }

 void replace(String canonical){
  synchronized(this){
   if(sealed||canonical.contentEquals(source))return;
   source.setLength(0);
   source.append(canonical);
   snapshot=null;
  }
  changed.run();
 }

 synchronized String snapshot(){
  if(snapshot==null)snapshot=source.toString();
  return snapshot;
 }

 synchronized String seal(){
  sealed=true;
  if(standalone!=null)standalone.close();
  return snapshot();
 }

 void finish(String canonical){
  synchronized(this){
   source.setLength(0);
   source.append(canonical);
   snapshot=canonical;
   seal();
  }
  if(render!=null)render.accept(canonical);
 }

 /** One delayed callback for both answer and thinking changes in a generation. */
 static final class Updates implements AutoCloseable {
  private final Handler main=new Handler(Looper.getMainLooper());
  private final Runnable update;
  private final Runnable flush=this::deliver;
  private boolean pending,closed;

  Updates(Runnable update){this.update=update;}

  synchronized void schedule(int length){
   if(closed||pending)return;
   pending=true;
   long delay=length<12000?64:length<40000?100:180;
   main.postDelayed(flush,delay);
  }

  private void deliver(){
   synchronized(this){
    pending=false;
    if(closed)return;
   }
   update.run();
  }

  @Override public synchronized void close(){
   closed=true;
   pending=false;
   main.removeCallbacks(flush);
  }
 }
}
