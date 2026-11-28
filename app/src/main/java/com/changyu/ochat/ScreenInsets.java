package com.changyu.ochat;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.WindowManager;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.List;

/** Keeps padding synchronized to the keyboard and derives it from one fixed baseline. */
final class ScreenInsets {
 static final int TOP_BAR_DP=72;
 static void apply(Activity activity,View root){
  WindowCompat.setDecorFitsSystemWindows(activity.getWindow(),false);
  activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  activity.getWindow().setStatusBarColor(Color.TRANSPARENT);
  activity.getWindow().setNavigationBarColor(Color.TRANSPARENT);
  boolean light=androidx.core.graphics.ColorUtils.calculateLuminance(ThemePalette.resolve(activity).page)>.5;
  WindowCompat.getInsetsController(activity.getWindow(),activity.getWindow().getDecorView()).setAppearanceLightStatusBars(light);
  WindowCompat.getInsetsController(activity.getWindow(),activity.getWindow().getDecorView()).setAppearanceLightNavigationBars(light);

  class Padding extends WindowInsetsAnimationCompat.Callback {
   final int left=root.getPaddingLeft(),top=root.getPaddingTop(),right=root.getPaddingRight(),bottom=root.getPaddingBottom();
   WindowInsetsCompat target;
   WindowInsetsAnimationCompat keyboard;
   Padding(){super(DISPATCH_MODE_CONTINUE_ON_SUBTREE);}

   void update(WindowInsetsCompat insets){
    Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
    Insets ime=insets.getInsets(WindowInsetsCompat.Type.ime());
    int l=left+bars.left,t=top+bars.top,r=right+bars.right,b=bottom+Math.max(bars.bottom,ime.bottom);
    if(l!=root.getPaddingLeft()||t!=root.getPaddingTop()||r!=root.getPaddingRight()||b!=root.getPaddingBottom())root.setPadding(l,t,r,b);
   }

   @Override public void onPrepare(WindowInsetsAnimationCompat animation){
    if((animation.getTypeMask()&WindowInsetsCompat.Type.ime())!=0)keyboard=animation;
   }

   @Override public WindowInsetsCompat onProgress(WindowInsetsCompat insets,List<WindowInsetsAnimationCompat> running){
    if(keyboard!=null)update(insets);
    return insets;
   }

   @Override public void onEnd(WindowInsetsAnimationCompat animation){
    if(keyboard==animation){
     keyboard=null;
     if(target!=null)update(target);
    }
   }
  }

  Padding padding=new Padding();
  ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{
   padding.target=insets;
   if(padding.keyboard==null)padding.update(insets);
   return insets;
  });
  ViewCompat.setWindowInsetsAnimationCallback(root,padding);
  root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
   @Override public void onViewAttachedToWindow(View view){ViewCompat.requestApplyInsets(view);}
   @Override public void onViewDetachedFromWindow(View view){padding.keyboard=null;}
  });
  if(ViewCompat.isAttachedToWindow(root))ViewCompat.requestApplyInsets(root);
 }
}
