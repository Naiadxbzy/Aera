package com.changyu.ochat;

import android.text.*;
import android.text.style.ClickableSpan;
import android.text.method.ArrowKeyMovementMethod;
import android.text.method.LinkMovementMethod;
import android.view.*;
import android.widget.TextView;

/** Native selection owns touch. The observer opens a span only on a short, stationary tap. */
final class SelectableSpanTouch implements View.OnTouchListener {
 static final LinkMovementMethod MOVEMENT=new LinkMovementMethod(){
  @Override public boolean onTouchEvent(TextView view,Spannable text,MotionEvent event){
   return ArrowKeyMovementMethod.getInstance().onTouchEvent(view,text,event);
  }
 };
 private ClickableSpan down;
 private float x,y;
 private long time;
 private boolean moved,selected;

 private ClickableSpan hit(TextView view,MotionEvent event){
  if(!(view.getText() instanceof Spanned)||view.getLayout()==null)return null;
  float px=event.getX()-view.getTotalPaddingLeft()+view.getScrollX();
  float py=event.getY()-view.getTotalPaddingTop()+view.getScrollY();
  Layout layout=view.getLayout();
  if(py<0||py>=layout.getHeight())return null;
  int line=layout.getLineForVertical((int)py);
  if(px<layout.getLineLeft(line)||px>layout.getLineRight(line))return null;
  int offset=layout.getOffsetForHorizontal(line,px);
  Spanned text=(Spanned)view.getText();
  for(ClickableSpan span:text.getSpans(offset,offset,ClickableSpan.class)){
   if(offset>=text.getSpanStart(span)&&offset<text.getSpanEnd(span))return span;
  }
  return null;
 }

 @Override public boolean onTouch(View target,MotionEvent event){
  TextView view=(TextView)target;
  switch(event.getActionMasked()){
   case MotionEvent.ACTION_DOWN:
    down=hit(view,event);x=event.getX();y=event.getY();time=event.getEventTime();
    moved=false;selected=view.hasSelection();
    break;
   case MotionEvent.ACTION_MOVE:
    if(Math.hypot(event.getX()-x,event.getY()-y)>ViewConfiguration.get(view.getContext()).getScaledTouchSlop())moved=true;
    break;
   case MotionEvent.ACTION_UP:
    ClickableSpan action=down;
    down=null;
    if(action!=null&&!selected&&!view.hasSelection()&&!moved
      &&event.getEventTime()-time<ViewConfiguration.getLongPressTimeout()&&hit(view,event)==action){
     action.onClick(view);
     return true;
    }
    break;
   case MotionEvent.ACTION_POINTER_DOWN:
   case MotionEvent.ACTION_CANCEL:
    down=null;
    break;
  }
  return false;
 }
}
