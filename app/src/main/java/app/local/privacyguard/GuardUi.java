package app.local.privacyguard;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class GuardUi {
    static final int BG=0xFFF3F6F3, INK=0xFF172D29, MUTED=0xFF465D56, TEAL=0xFF006959;
    final Activity a;
    GuardUi(Activity activity){a=activity;}
    int dp(int value){return Math.round(value*a.getResources().getDisplayMetrics().density);}
    void pad(View v,int x,int y){v.setPadding(dp(x),dp(y),dp(x),dp(y));}
    LinearLayout col(){LinearLayout c=new LinearLayout(a);c.setOrientation(LinearLayout.VERTICAL);return c;}
    TextView text(String value,int sp,int color,boolean bold){
        TextView t=new TextView(a);t.setText(value);t.setTextSize(sp);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        t.setLineSpacing(dp(2),1);return t;
    }
    GradientDrawable shape(int color,int radius,int border){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));
        if(border!=0)d.setStroke(dp(1),border);return d;
    }
    void add(LinearLayout parent,View child,int top){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);parent.addView(child,p);
    }
    Button action(String label,View.OnClickListener listener){
        Button b=new Button(a,null,android.R.attr.borderlessButtonStyle);b.setText(label);b.setAllCaps(false);
        b.setTextColor(TEAL);b.setTextSize(14);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        b.setMinHeight(dp(46));b.setMinimumHeight(dp(46));b.setMinWidth(0);b.setPadding(0,0,0,0);b.setOnClickListener(listener);return b;
    }
}
