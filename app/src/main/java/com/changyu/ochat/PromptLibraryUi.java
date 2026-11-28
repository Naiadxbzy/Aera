package com.changyu.ochat;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONException;
import java.util.*;
import java.util.function.Consumer;

final class PromptLibraryUi {
 private final Context context;
 private final ThemePalette palette;
 private final PromptLibrary store;

 PromptLibraryUi(Context context,SharedPreferences prefs){
  this.context=context;palette=ThemePalette.resolve(context);store=new PromptLibrary(prefs);
 }

 void manage(){list(null);}
 void choose(Consumer<String> insert){list(insert);}

 private void list(Consumer<String> insert){
  boolean manage=insert==null;
  LinearLayout body=new LinearLayout(context);
  body.setOrientation(LinearLayout.VERTICAL);
  EditText search=field("搜索提示词",true);
  search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
  body.addView(search,new LinearLayout.LayoutParams(-1,dp(48)));
  FrameLayout content=new FrameLayout(context);
  body.addView(content,new LinearLayout.LayoutParams(-1,0,1));
  ListView rows=new ListView(context);
  rows.setDivider(null);
  content.addView(rows,new FrameLayout.LayoutParams(-1,-1));
  TextView empty=UiStyle.text(context,"",14,palette.muted,false);
  empty.setGravity(Gravity.CENTER);
  empty.setPadding(dp(16),0,dp(16),0);
  content.addView(empty,new FrameLayout.LayoutParams(-1,-1));

  List<PromptLibrary.Template> all=store.read(),shown=new ArrayList<>();
  BaseAdapter adapter=new BaseAdapter(){
   @Override public int getCount(){return shown.size();}
   @Override public Object getItem(int position){return shown.get(position);}
   @Override public long getItemId(int position){return position;}
   @Override public View getView(int position,View recycled,ViewGroup parent){
    LinearLayout row;
    if(recycled instanceof LinearLayout)row=(LinearLayout)recycled;
    else{
     row=new LinearLayout(context);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(14),dp(12),dp(14),dp(12));
     row.addView(UiStyle.text(context,"",16,palette.ink,true));
     TextView preview=UiStyle.text(context,"",13,palette.muted,false);
     preview.setMaxLines(2);preview.setEllipsize(TextUtils.TruncateAt.END);
     preview.setPadding(0,dp(3),0,0);
     row.addView(preview);
    }
    PromptLibrary.Template template=shown.get(position);
    ((TextView)row.getChildAt(0)).setText(template.title);
    ((TextView)row.getChildAt(1)).setText(template.body.length()<=220?template.body:template.body.substring(0,220)+"…");
    return row;
   }
  };
  rows.setAdapter(adapter);
  Runnable filter=()->{
   String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);
   shown.clear();
   for(PromptLibrary.Template template:all){
    if(query.isEmpty()||template.title.toLowerCase(Locale.ROOT).contains(query)||template.body.toLowerCase(Locale.ROOT).contains(query)){
     shown.add(template);
    }
   }
   adapter.notifyDataSetChanged();
   empty.setText(all.isEmpty()?"还没有提示词模板":"没有匹配的提示词");
   empty.setVisibility(shown.isEmpty()?View.VISIBLE:View.GONE);
  };
  search.addTextChangedListener(new TextWatcher(){
   public void beforeTextChanged(CharSequence s,int start,int count,int after){}
   public void onTextChanged(CharSequence s,int start,int before,int count){filter.run();}
   public void afterTextChanged(Editable text){}
  });
  filter.run();
  PanelDialog dialog=new PanelDialog(context,"提示词库",manage?"":"选择模板并插入输入框",body);
  rows.setOnItemClickListener((parent,view,position,id)->{
   PromptLibrary.Template template=shown.get(position);
   dialog.dismiss();
   if(manage)edit(template);
   else fill(template,insert);
  });
  dialog.action(AlertDialog.BUTTON_NEGATIVE,"关闭",null);
  if(manage)dialog.action(AlertDialog.BUTTON_POSITIVE,"新建模板",()->edit(null));
  else dialog.action(AlertDialog.BUTTON_POSITIVE,"管理模板",this::manage);
  body.setFocusableInTouchMode(true);
  dialog.show();
  body.requestFocus();
 }

 private void edit(PromptLibrary.Template template){
  EditText title=field("模板名称",true),body=field("提示词正文",false);
  body.setGravity(Gravity.TOP);
  body.setMinLines(5);
  if(template!=null){title.setText(template.title);body.setText(template.body);}
  LinearLayout fields=new LinearLayout(context);
  fields.setOrientation(LinearLayout.VERTICAL);
  fields.addView(title,new LinearLayout.LayoutParams(-1,-2));
  LinearLayout.LayoutParams bodyParams=new LinearLayout.LayoutParams(-1,-2);
  bodyParams.topMargin=dp(10);
  fields.addView(body,bodyParams);
  ScrollView scroll=new ScrollView(context);
  scroll.addView(fields);
  PanelDialog dialog=new PanelDialog(context,template==null?"新建提示词":"编辑提示词","用 {{变量名}} 在插入时填写内容",scroll)
   .action(AlertDialog.BUTTON_NEGATIVE,"返回",this::manage)
   .action(AlertDialog.BUTTON_POSITIVE,"保存",null);
  if(template!=null)dialog.action(AlertDialog.BUTTON_NEUTRAL,"删除",null);
  dialog.show();
  dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
   try{
    store.save(template==null?"":template.id,title.getText().toString(),body.getText().toString());
    dialog.dismiss();
    manage();
   }catch(IllegalArgumentException invalid){
    if(title.getText().toString().trim().isEmpty())title.setError(invalid.getMessage());
    else body.setError(invalid.getMessage());
   }catch(JSONException invalid){toast("提示词无法保存。");}
  });
  if(template!=null)dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view->{
   TextView confirmation=UiStyle.text(context,template.title,16,palette.ink,false);
   new PanelDialog(context,"删除提示词模板？","",confirmation).compact()
    .action(AlertDialog.BUTTON_NEGATIVE,"取消",null)
    .action(AlertDialog.BUTTON_POSITIVE,"删除",()->{
     try{store.delete(template.id);dialog.dismiss();manage();}
     catch(JSONException invalid){toast("无法删除提示词。");}
    }).show();
  });
 }

 private void fill(PromptLibrary.Template template,Consumer<String> insert){
  List<String> variables=PromptLibrary.variables(template.body);
  if(variables.isEmpty()){insert.accept(template.body);return;}
  Map<String,EditText> inputs=new LinkedHashMap<>();
  LinearLayout fields=new LinearLayout(context);
  fields.setOrientation(LinearLayout.VERTICAL);
  for(String name:variables){
   TextView label=UiStyle.text(context,name,13,palette.muted,true);
   label.setPadding(0,dp(9),0,dp(4));
   fields.addView(label);
   EditText value=field("填写 "+name,false);
   value.setMaxLines(4);
   fields.addView(value,new LinearLayout.LayoutParams(-1,-2));
   inputs.put(name,value);
  }
  ScrollView scroll=new ScrollView(context);scroll.addView(fields);
  PanelDialog dialog=new PanelDialog(context,template.title,"填写本次使用的变量",scroll)
   .action(AlertDialog.BUTTON_NEGATIVE,"取消",null).action(AlertDialog.BUTTON_POSITIVE,"插入",null);
  dialog.show();
  dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
   Map<String,String> values=new LinkedHashMap<>();
   for(Map.Entry<String,EditText> entry:inputs.entrySet()){
    String value=entry.getValue().getText().toString();
    if(value.trim().isEmpty()){
     entry.getValue().setError("请填写 "+entry.getKey());
     entry.getValue().requestFocus();
     return;
    }
    values.put(entry.getKey(),value);
   }
   String value=PromptLibrary.render(template.body,values);
   InputMethodManager keyboard=context.getSystemService(InputMethodManager.class);
   if(keyboard!=null)keyboard.hideSoftInputFromWindow(view.getWindowToken(),0);
   dialog.dismiss();
   insert.accept(value);
  });
 }

 private EditText field(String hint,boolean line){
  EditText input=EditorDialogs.editor(EditorDialogs.themedContext(context));
  input.setHint(hint);input.setContentDescription(hint);
  input.setTextSize(15);input.setSingleLine(line);
  if(!line)input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
  input.setPadding(dp(14),dp(10),dp(14),dp(10));
  input.setBackground(UiStyle.surface(context,palette.page,12,false));
  return input;
 }

 private int dp(int value){return UiStyle.dp(context,value);}
 private void toast(String text){Toast.makeText(context,text,Toast.LENGTH_SHORT).show();}
}
