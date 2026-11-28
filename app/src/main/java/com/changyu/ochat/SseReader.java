package com.changyu.ochat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/** Reads an SSE event as it becomes complete, including gateways that omit blank lines. */
final class SseReader {
 static final int MAX_FRAME=4*1024*1024;
 static final int MAX_TEXT=512*1024;
 private static final int MAX_DEPTH=100;
 private final Reader reader;
 private final char[] input=new char[8192];
 private final StringBuilder frame=new StringBuilder();
 private int available, readAt, lineAt, depth;
 private boolean afterCr, firstLine=true, quoted, escaped, rawFrame;
 private String line, event="";

 SseReader(InputStream input){reader=new InputStreamReader(input,StandardCharsets.UTF_8);}

 static final class Event {
  final String type;
  final JSONObject data;
  Event(String type,JSONObject data){this.type=type;this.data=data;}
 }

 Event next()throws IOException{
  while(true){
   if(line==null||lineAt==line.length()){
    line=readLine();
    if(line==null){
     if(frame.length()>0)throw malformed("连接在事件中途断开");
     return null;
    }
    lineAt=0;
    if(firstLine){
     firstLine=false;
     if(!line.isEmpty()&&line.charAt(0)=='\ufeff')line=line.substring(1);
    }
    if(line.isEmpty()){
     if(frame.length()>0)throw malformed("收到不完整的事件");
     event="";
     continue;
    }
    if(line.startsWith("event:")){
     if(frame.length()>0)throw malformed("新事件到达时上一个事件尚未完成");
     event=line.substring(6).trim();
     line=null;
     continue;
    }
    if(line.startsWith("data:")){
     lineAt=5;
     if(lineAt<line.length()&&line.charAt(lineAt)==' ')lineAt++;
     rawFrame=false;
    }else if(frame.length()==0){
     String trimmed=line.trim();
     if(!trimmed.startsWith("{")&&!trimmed.startsWith("[DONE]")){
      line=null;
      continue;
     }
     rawFrame=true;
    }else if(!rawFrame){
     line=null;
     continue;
    }
    // Whitespace between SSE data lines belongs to JSON syntax. Some gateways split a
    // quoted value over data fields, in which case joining the fragments preserves the value.
    if(frame.length()>0&&!quoted)append('\n');
   }

   while(lineAt<line.length()){
    char c=line.charAt(lineAt++);
    if(frame.length()==0){
     if(Character.isWhitespace(c))continue;
     if(c=='['&&line.startsWith("[DONE]",lineAt-1)){
      lineAt+=5;
      return new Event("",null);
     }
     if(c!='{')throw malformed("事件不是 JSON 对象");
    }
    append(c);
    if(quoted){
     if(escaped)escaped=false;
     else if(c=='\\')escaped=true;
     else if(c=='"')quoted=false;
    }else if(c=='"')quoted=true;
    else if(c=='{'||c=='['){
     if(++depth>MAX_DEPTH)throw malformed("JSON 嵌套过深");
    }else if(c=='}'||c==']'){
     if(--depth==0){
      String complete=frame.toString(), name=event;
      frame.setLength(0);
      event="";
      try{return new Event(name,new JSONObject(complete));}
      catch(JSONException invalid){throw malformed("事件包含无效 JSON");}
     }
    }
   }
  }
 }

 private void append(char c)throws IOException{
  if(frame.length()==MAX_FRAME)throw malformed("单个事件超过大小限制");
  frame.append(c);
 }

 private String readLine()throws IOException{
  StringBuilder value=new StringBuilder();
  for(int c;(c=readChar())!=-1;){
   if(afterCr){
    afterCr=false;
    if(c=='\n')continue;
   }
   if(c=='\n'||c=='\r'){
    afterCr=c=='\r';
    return value.toString();
   }
   if(value.length()==MAX_FRAME)throw malformed("单行事件超过大小限制");
   value.append((char)c);
  }
  return value.length()==0?null:value.toString();
 }

 private int readChar()throws IOException{
  while(readAt==available){
   available=reader.read(input);
   readAt=0;
   if(available<0){
    available=0;
    return -1;
   }
  }
  return input[readAt++];
 }

 static IOException malformed(String reason){return new IOException("invalid_sse："+reason+"。");}
}
