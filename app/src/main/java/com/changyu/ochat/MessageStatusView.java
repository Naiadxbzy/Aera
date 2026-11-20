package com.changyu.ochat;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.drawable.DrawableCompat;

/** Compact, theme-aware status card for reply progress, sources and request failures. */
final class MessageStatusView extends AppCompatTextView {
 private final int backgroundColor, textColor;
 private final Drawable leading;

 MessageStatusView(Context context,int icon,boolean accent){
  super(context);
  ThemePalette palette=ThemePalette.resolve(context);
  backgroundColor=ColorUtils.blendARGB(palette.surface,accent?palette.primary:palette.ink,accent?.07f:.025f);
  textColor=ThemePalette.readable(accent?palette.primary:palette.muted,backgroundColor);
  leading=glyph(icon,18);
  setTextSize(13);
  setTextColor(textColor);
  setGravity(Gravity.CENTER_VERTICAL);
  setPaddingRelative(dp(12),dp(8),dp(12),dp(8));
  setCompoundDrawablePadding(dp(9));
  setMinHeight(dp(36));
  setBackground(UiStyle.surface(context,backgroundColor,12,true));
  setCompoundDrawablesRelative(leading,null,null,null);
 }

 void setAction(Runnable action){
  UiStyle.clickable(this,backgroundColor,12);
  setMinHeight(dp(48));
  setOnClickListener(view->action.run());
  setCompoundDrawablesRelative(leading,null,glyph(R.drawable.ic_status_chevron,16),null);
 }

 void setExpanded(boolean expanded){
  setCompoundDrawablesRelative(leading,null,glyph(expanded?R.drawable.ic_status_chevron_down:R.drawable.ic_status_chevron,16),null);
  if(android.os.Build.VERSION.SDK_INT>=30)setStateDescription(expanded?"已展开":"已折叠");
 }

 private Drawable glyph(int resource,int size){
  Drawable icon=AppCompatResources.getDrawable(getContext(),resource);
  if(icon==null)return null;
  icon=icon.mutate();
  DrawableCompat.setTint(icon,textColor);
  icon.setBounds(0,0,dp(size),dp(size));
  return icon;
 }

 private int dp(int value){return UiStyle.dp(getContext(),value);}
}
