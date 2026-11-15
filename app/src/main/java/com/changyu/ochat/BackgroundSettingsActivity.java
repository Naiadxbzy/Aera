package com.changyu.ochat;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

/** Optional background assistance, with fresh system state rather than a cached success switch. */
public final class BackgroundSettingsActivity extends AppCompatActivity {
 private ThemePalette palette;
 private TextView systemState,shizukuState,result;
 private View apply,remove;
 private ShizukuBackgroundController shizuku;
 @Override protected void onCreate(Bundle state){
  ThemePalette.applyActivityTheme(this);super.onCreate(state);palette=ThemePalette.resolve(this);
  getWindow().setStatusBarColor(palette.page);getWindow().setNavigationBarColor(palette.page);
  getWindow().getDecorView().setSystemUiVisibility(palette.dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
  setContentView(build());
  shizuku=new ShizukuBackgroundController(this,new ShizukuBackgroundController.Listener(){
   public void changed(){refresh();}
   public void result(String message){result.setText(message);result.setVisibility(View.VISIBLE);refresh();}
  });
  refresh();
 }
 private View build(){
  LinearLayout root=column();root.setBackgroundColor(palette.page);
  LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(12),0,dp(18),0);
  TextView back=UiStyle.text(this,"‹",32,palette.ink,false);back.setGravity(Gravity.CENTER);back.setContentDescription("返回");back.setOnClickListener(v->finish());
  bar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));bar.addView(UiStyle.text(this,"后台连接",21,palette.ink,true));
  root.addView(bar,new LinearLayout.LayoutParams(-1,dp(ScreenInsets.TOP_BAR_DP)));
  ScrollView scroll=new ScrollView(this);LinearLayout body=column();body.setPadding(dp(18),dp(8),dp(18),dp(28));
  LinearLayout ordinary=card(body,"普通增强");
  SwitchCompat enabled=new SwitchCompat(this);enabled.setText("后台连接增强");enabled.setTextSize(16);enabled.setTextColor(palette.ink);enabled.setTag("background-enhanced");enabled.setChecked(BackgroundProtection.enabled(this));enabled.setMinHeight(dp(52));
  enabled.setOnCheckedChangeListener((button,checked)->getSharedPreferences("chat",MODE_PRIVATE).edit().putBoolean(BackgroundProtection.PREF,checked).apply());ordinary.addView(enabled,new LinearLayout.LayoutParams(-1,-2));
  detail(ordinary,"生成期间延长静默等待至 10 分钟，监听网络变化；尚未发出的请求会等待网络恢复。修改后对新请求生效。");
  systemState=detail(ordinary,"");systemState.setTag("background-system-state");
  button(ordinary,"省电优化设置",()->open(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
  button(ordinary,"应用后台与电池设置",()->open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));
  button(ordinary,"生成通知设置",()->{
   Intent intent=Build.VERSION.SDK_INT>=26?new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()):new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()));
   open(intent);
  });
  detail(ordinary,"在系统中将 Aera 设为“不受限制”，允许后台运行；部分系统还需允许自启动，并在最近任务中锁定。");
  LinearLayout elevated=card(body,"Shizuku 增强");
  shizukuState=detail(elevated,"");shizukuState.setTag("background-shizuku-state");
  detail(elevated,"授权后仅将 Aera 加入系统省电豁免，减少熄屏暂停联网。不修改其他应用或全局省电策略。");
  apply=button(elevated,"授权并开启省电豁免",()->confirm(true));apply.setTag("background-shizuku-apply");
  remove=button(elevated,"撤销 Aera 添加的豁免",()->confirm(false));remove.setTag("background-shizuku-remove");
  button(elevated,"打开 Shizuku",this::openShizuku);
  button(elevated,"安装与启动指南",()->SafeLinks.open(this,"https://shizuku.rikka.app/guide/setup/"));
  result=detail(elevated,"");result.setTag("background-operation-result");result.setVisibility(View.GONE);
  detail(body,"增强可能增加生成期间的耗电。没有任务时不额外保活；强制停止、实际断网、服务端断开及系统运行时限仍可能中断回复。已生成内容会保存，不自动重发计费请求。");
  scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));ScreenInsets.apply(this,root);return root;
 }
 private void confirm(boolean enabled){
  if(shizuku==null||shizuku.busy())return;
  TextView explanation=UiStyle.text(this,enabled?"将通过 Shizuku 修改 Aera 自身的省电豁免。系统可能弹出授权请求，设置后会重新检查实际状态。":"仅撤销 Aera 此前新增的豁免；熄屏后联网可能再次受到限制。",15,palette.ink,false);
  new PanelDialog(this,enabled?"开启省电豁免":"撤销省电豁免","",explanation).compact()
   .action(AlertDialog.BUTTON_NEGATIVE,"取消",null).action(AlertDialog.BUTTON_POSITIVE,enabled?"开启":"撤销",()->shizuku.apply(enabled)).show();
 }
 private void refresh(){
  if(systemState==null||isDestroyed())return;
  systemState.setText(BackgroundProtection.status(this));
  boolean busy=shizuku!=null&&shizuku.busy();
  shizukuState.setText(getString(R.string.background_shizuku_status,busy?"正在处理…\n":"",ShizukuBackgroundController.state(),BackgroundProtection.exempt(this)?"省电豁免已生效":"省电豁免未生效"));
  apply.setEnabled(!busy);apply.setAlpha(busy?.5f:1f);
  remove.setVisibility(BackgroundProtection.owned(this)?View.VISIBLE:View.GONE);remove.setEnabled(!busy);
 }
 private void open(Intent intent){try{startActivity(intent);}catch(android.content.ActivityNotFoundException|SecurityException failure){result.setText(R.string.background_system_page_missing);result.setVisibility(View.VISIBLE);}}
 private void openShizuku(){
  Intent intent=getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
  if(intent==null){result.setText(R.string.background_shizuku_missing);result.setVisibility(View.VISIBLE);}else open(intent);
 }
 @Override protected void onResume(){super.onResume();refresh();}
 @Override protected void onDestroy(){if(shizuku!=null)shizuku.close();super.onDestroy();}
 private LinearLayout card(LinearLayout body,String title){
  LinearLayout card=column();card.setPadding(dp(16),dp(16),dp(16),dp(16));card.setBackground(UiStyle.surface(this,palette.surface,20,true));card.addView(UiStyle.text(this,title,17,palette.ink,true));
  LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(16);body.addView(card,params);return card;
 }
 private TextView detail(LinearLayout body,String value){TextView view=UiStyle.text(this,value,13,palette.muted,false);view.setPadding(0,dp(10),0,dp(10));body.addView(view,new LinearLayout.LayoutParams(-1,-2));return view;}
 private View button(LinearLayout body,String text,Runnable action){TextView view=UiStyle.text(this,text,14,palette.primary,true);view.setGravity(Gravity.CENTER);view.setPadding(dp(12),dp(12),dp(12),dp(12));view.setMinHeight(dp(48));UiStyle.clickable(view,palette.surface,14);view.setOnClickListener(v->action.run());LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(8);body.addView(view,params);return view;}
 private LinearLayout column(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}
 private int dp(int value){return UiStyle.dp(this,value);}
}
