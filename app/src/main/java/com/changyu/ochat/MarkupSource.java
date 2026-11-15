package com.changyu.ochat;

import java.util.Locale;

/** Finds standalone, literal SVG/HTML before prose sanitization. Never parses or executes XML entities. */
final class MarkupSource {
 static final class Block {
  final int start,end;final String language;final boolean closed;
  Block(int start,int end,String language,boolean closed){this.start=start;this.end=end;this.language=language;this.closed=closed;}
 }
 static Block next(String source,int from,int startLimit){
  int cursor=from,prefix=-1,last=from,tick=source.indexOf('`',from);String prefixLanguage="xml";
  while(cursor<startLimit){
   if(tick>=0&&tick<cursor)tick=source.indexOf('`',cursor);
   int tag=source.indexOf('<',cursor);
   if(tick>=0&&tick<startLimit&&(tag<0||tick<tag)){
    int count=1;while(tick+count<source.length()&&source.charAt(tick+count)=='`')count++;
    String marker=source.substring(tick,tick+count);int close=source.indexOf(marker,tick+count);
    if(close>=0){cursor=close+count;prefix=-1;last=cursor;continue;}
    cursor=tick+count;continue;
   }
   if(tag<0||tag>=startLimit)break;
   if(prefix>=0&&!source.substring(last,tag).trim().isEmpty())prefix=-1;
   if(source.startsWith("<!--",tag)){
    cursor=after(source,"-->",tag+4);last=cursor;continue;
   }
   if(starts(source,tag,"<?xml")&&lineStart(source,tag)>=from){
    if(prefix<0)prefix=lineStart(source,tag);
    int close=source.indexOf("?>",tag+5);
    if(close<0)return new Block(prefix,source.length(),"xml",false);
    cursor=close+2;last=cursor;continue;
   }
   if(starts(source,tag,"<!doctype")&&lineStart(source,tag)>=from){
    String lower=source.substring(tag,Math.min(source.length(),tag+24)).toLowerCase(Locale.ROOT);
    if(lower.matches("(?s)<!doctype\\s+(svg|html)(?:\\s|>|$).*")){
     if(prefix<0)prefix=lineStart(source,tag);prefixLanguage=lower.matches("(?s)<!doctype\\s+svg.*")?"svg":"html";
     int end=tagEnd(source,tag);
     if(end<0)return new Block(prefix,source.length(),prefixLanguage,false);
     cursor=end+1;last=cursor;continue;
    }
   }
   String name=name(source,tag);
   if(("svg".equals(name)||"html".equals(name))&&(prefix>=0||lineStart(source,tag)>=from)){
    int end=elementEnd(source,tag,name);return new Block(prefix>=0?prefix:lineStart(source,tag),end<0?source.length():end,name,end>=0);
   }
   prefix=-1;
   int end=tagEnd(source,tag);if(end<0)break;
   if(source.charAt(tag+1)!='/'&&("script".equals(name)||"style".equals(name))&&!selfClosing(source,tag,end)){
    int close=indexOfIgnoreCase(source,"</"+name,end+1);cursor=close<0?source.length():close;
   }else cursor=end+1;
   last=cursor;
  }
  return prefix<0?null:new Block(prefix,source.length(),prefixLanguage,false);
 }
 static String language(String source,String hint){
  String label=hint==null?"代码":hint.trim().toLowerCase(Locale.ROOT);
  if(!label.equals("代码")&&!label.equals("xml")&&!label.equals("svg")&&!label.equals("html")&&!label.equals("xhtml")&&!label.equals("image/svg+xml")&&!label.equals("text/html"))return hint;
  int cursor=0;
  while(cursor<source.length()){
   while(cursor<source.length()&&(Character.isWhitespace(source.charAt(cursor))||source.charAt(cursor)=='\ufeff'))cursor++;
   if(source.startsWith("<!--",cursor)){cursor=after(source,"-->",cursor+4);continue;}
   if(starts(source,cursor,"<?xml")){int end=source.indexOf("?>",cursor+5);if(end<0)break;cursor=end+2;continue;}
   if(starts(source,cursor,"<!doctype")){int end=tagEnd(source,cursor);if(end<0)break;cursor=end+1;continue;}
   String root=name(source,cursor);if("svg".equals(root)||"html".equals(root))return root;break;
  }
  return label.equals("image/svg+xml")?"svg":label.equals("text/html")?"html":hint==null?"代码":hint;
 }
 private static int elementEnd(String source,int start,String root){
  int cursor=start,depth=0;
  while(cursor<source.length()){
   int tag=source.indexOf('<',cursor);if(tag<0)return -1;
   if(source.startsWith("<!--",tag)){cursor=after(source,"-->",tag+4);continue;}
   if(source.startsWith("<![CDATA[",tag)){cursor=after(source,"]]>",tag+9);continue;}
   if(source.startsWith("<?",tag)){cursor=after(source,"?>",tag+2);continue;}
   int end=tagEnd(source,tag);if(end<0)return -1;
   String name=name(source,tag);boolean closing=tag+1<source.length()&&source.charAt(tag+1)=='/';
   if(root.equals(name)){
    if(closing){if(--depth==0)return end+1;}
    else if(!selfClosing(source,tag,end))depth++;
    else if(depth==0)return end+1;
   }
   if(!closing&&("script".equals(name)||"style".equals(name))&&!selfClosing(source,tag,end)){
    int close=indexOfIgnoreCase(source,"</"+name,end+1);if(close<0)return -1;cursor=close;
   }else cursor=end+1;
  }
  return -1;
 }
 private static int lineStart(String source,int position){
  int start=source.lastIndexOf('\n',Math.max(0,position-1))+1;
  for(int i=start;i<position;i++)if(!Character.isWhitespace(source.charAt(i))&&source.charAt(i)!='\ufeff')return -1;
  return start;
 }
 private static int tagEnd(String source,int start){
  char quote=0;int subset=0;boolean declaration=source.startsWith("<!",start);
  for(int i=start+1;i<source.length();i++){
   char c=source.charAt(i);
   if(quote!=0){if(c==quote)quote=0;continue;}
   if(c=='"'||c=='\''){quote=c;continue;}
   if(declaration&&c=='[')subset++;else if(declaration&&c==']'&&subset>0)subset--;
   else if(c=='>'&&subset==0)return i;
  }return -1;
 }
 private static boolean selfClosing(String source,int start,int end){int i=end-1;while(i>start&&Character.isWhitespace(source.charAt(i)))i--;return source.charAt(i)=='/';}
 private static String name(String source,int start){
  if(start<0||start>=source.length()||source.charAt(start)!='<')return "";
  int from=start+1;if(from<source.length()&&source.charAt(from)=='/')from++;
  int end=from;while(end<source.length()){char c=source.charAt(end);if(!Character.isLetterOrDigit(c)&&c!=':'&&c!='-'&&c!='_')break;end++;}
  return source.substring(from,end).toLowerCase(Locale.ROOT);
 }
 private static int after(String source,String marker,int from){int end=source.indexOf(marker,from);return end<0?source.length():end+marker.length();}
 private static int indexOfIgnoreCase(String source,String needle,int from){for(int i=from;i+needle.length()<=source.length();i++)if(source.regionMatches(true,i,needle,0,needle.length()))return i;return -1;}
 private static boolean starts(String source,int from,String needle){return from>=0&&source.regionMatches(true,from,needle,0,needle.length());}
 private MarkupSource(){}
}
