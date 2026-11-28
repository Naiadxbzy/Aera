package com.changyu.ochat;

import android.content.SharedPreferences;
import org.json.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local prompt templates. Interpolation is literal and leaves the composer in control of sending. */
final class PromptLibrary {
 private static final String KEY="promptTemplates";
 private static final int MAX_TEMPLATES=120,MAX_TITLE=80,MAX_BODY=20000,MAX_FIELDS=12;
 private static final Pattern VARIABLE=Pattern.compile("\\{\\{([\\p{L}\\p{N}_][\\p{L}\\p{N}_ .-]{0,39})\\}\\}");
 private final SharedPreferences prefs;

 static final class Template {
  final String id,title,body;
  Template(String id,String title,String body){this.id=id;this.title=title;this.body=body;}
 }

 PromptLibrary(SharedPreferences prefs){this.prefs=prefs;}

 List<Template> read(){
  List<Template> templates=new ArrayList<>();
  try{
   JSONArray stored=new JSONArray(prefs.getString(KEY,"[]"));
   for(int i=0;i<stored.length()&&templates.size()<MAX_TEMPLATES;i++){
    JSONObject item=stored.optJSONObject(i);
    if(item==null)continue;
    String id=item.optString("id"),title=item.optString("title"),body=item.optString("body");
    if(!id.isEmpty()&&!title.isEmpty()&&!body.isEmpty())templates.add(new Template(id,title,body));
   }
  }catch(JSONException invalid){android.util.Log.w("Prompts","Unable to read prompt templates",invalid);}
  return templates;
 }

 void save(String id,String title,String body)throws JSONException{
  title=title.trim();
  if(title.isEmpty()||title.length()>MAX_TITLE)throw new IllegalArgumentException("请输入 1–80 字符的模板名称。");
  if(body.trim().isEmpty()||body.length()>MAX_BODY)throw new IllegalArgumentException("请输入 1–20000 字符的提示词。");
  if(variables(body).size()>MAX_FIELDS)throw new IllegalArgumentException("一个模板最多使用 12 个变量。");
  List<Template> templates=read();
  int position=-1;
  for(int i=0;i<templates.size();i++){
   Template entry=templates.get(i);
   if(entry.id.equals(id))position=i;
   else if(entry.title.equalsIgnoreCase(title))throw new IllegalArgumentException("已存在同名模板。");
  }
  if(position<0&&templates.size()>=MAX_TEMPLATES)throw new IllegalArgumentException("最多可保存 120 个模板。");
  Template value=new Template(position<0?UUID.randomUUID().toString():id,title,body);
  if(position<0)templates.add(0,value);else templates.set(position,value);
  write(templates);
 }

 void delete(String id)throws JSONException{
  List<Template> templates=read();
  templates.removeIf(template->template.id.equals(id));
  write(templates);
 }

 private void write(List<Template> templates)throws JSONException{
  JSONArray stored=new JSONArray();
  for(Template entry:templates)stored.put(new JSONObject().put("id",entry.id).put("title",entry.title).put("body",entry.body));
  prefs.edit().putString(KEY,stored.toString()).apply();
 }

 static List<String> variables(String body){
  Set<String> names=new LinkedHashSet<>();
  Matcher matcher=VARIABLE.matcher(body);
  while(matcher.find())names.add(matcher.group(1).trim());
  return new ArrayList<>(names);
 }

 static String render(String body,Map<String,String> values){
  Matcher matcher=VARIABLE.matcher(body);
  StringBuffer expanded=new StringBuffer();
  while(matcher.find()){
   String field=matcher.group(1).trim(),value=values.get(field);
   if(value==null)throw new IllegalArgumentException("请填写「"+field+"」。");
   matcher.appendReplacement(expanded,Matcher.quoteReplacement(value));
  }
  matcher.appendTail(expanded);
  return expanded.toString();
 }
}
