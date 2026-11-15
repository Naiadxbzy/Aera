package com.changyu.ochat;

import android.content.Context;
import androidx.core.graphics.ColorUtils;

/** One palette for message code cards and the preview's source tab, including custom themes. */
final class CodePalette {
 final int background,text,muted,accent,border,scrollTrack;
 final int[] tokens;
 static CodePalette resolve(Context context){return new CodePalette(ThemePalette.resolve(context));}
 private CodePalette(ThemePalette theme){
  boolean dark=ColorUtils.calculateLuminance(theme.surface)<.35;
  background=ColorUtils.blendARGB(theme.surface,theme.ink,dark?.035f:.025f);
  text=ThemePalette.readable(theme.ink,background);muted=ThemePalette.readable(theme.muted,background);accent=ThemePalette.readable(theme.primary,background);
  border=ColorUtils.blendARGB(background,text,dark?.16f:.08f);scrollTrack=ColorUtils.blendARGB(background,text,dark?.16f:.10f);
  int[] requested=dark?new int[]{0xff9db1bf,0xffb6d69c,0xff8dcbe6,0xffe8c079,0xffd3b4f2,0xffa9bcff}
   :new int[]{0xff66737d,0xff3b7846,0xffb03945,0xff83600d,0xff8050a0,0xff355eaa};
  tokens=new int[requested.length];for(int i=0;i<requested.length;i++)tokens[i]=ThemePalette.readable(requested[i],background);
 }
}
