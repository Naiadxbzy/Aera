package com.changyu.ochat;

import java.util.regex.Pattern;

/** Literal search on the original string keeps match offsets valid for Unicode text. */
final class TextSearch {
 static Pattern pattern(String query,boolean matchCase,boolean wholeWord){
  String literal=Pattern.quote(query);
  if(wholeWord)literal="(?<![\\p{L}\\p{N}\\p{M}_])"+literal+"(?![\\p{L}\\p{N}\\p{M}_])";
  return Pattern.compile(literal,matchCase?0:Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
 }

 static String excerpt(String text,int matchStart,int matchEnd){
  int before=Math.min(22,text.codePointCount(0,matchStart));
  int after=Math.min(65,text.codePointCount(matchEnd,Math.min(text.length(),matchEnd+130)));
  int start=text.offsetByCodePoints(matchStart,-before), end=text.offsetByCodePoints(matchEnd,after);
  return (start>0?"…":"")+text.substring(start,end).replace('\n',' ').replace('\r',' ')+(end<text.length()?"…":"");
 }

 private TextSearch(){}
}
