package local.dexprobe;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small dark-theme design system ported from the pen.dev "DexScreens" design. Colors live in
 * res/values/colors.xml; everything else is built programmatically to match this codebase's
 * existing style (no XML layouts are used anywhere in this app). */
final class Ui {
    private Ui() {}
    static int dp(Context c,float v) { return Math.round(v*c.getResources().getDisplayMetrics().density); }
    static int sp(Context c,float v) { return Math.round(v*c.getResources().getDisplayMetrics().scaledDensity); }
    static int color(Context c,int resId) { return c.getResources().getColor(resId,c.getTheme()); }
    static GradientDrawable rounded(int fillColor,float radiusDp,Context c) {
        GradientDrawable d=new GradientDrawable(); d.setColor(fillColor); d.setCornerRadius(dp(c,radiusDp)); return d;
    }
    static GradientDrawable roundedStroke(int fillColor,int strokeColor,float radiusDp,float strokeDp,Context c) {
        GradientDrawable d=rounded(fillColor,radiusDp,c); d.setStroke(dp(c,strokeDp),strokeColor); return d;
    }
    static GradientDrawable pillShape(int fillColor) { GradientDrawable d=new GradientDrawable(); d.setColor(fillColor); d.setCornerRadius(999); return d; }
    static GradientDrawable circle(int fillColor) { GradientDrawable d=new GradientDrawable(); d.setShape(GradientDrawable.OVAL); d.setColor(fillColor); return d; }

    /** caption/body/title text with sensible defaults; weight: 0 normal, 1 medium, 2 bold. */
    static TextView text(Context c,String s,float spSize,int colorResId,int weight) { return textRaw(c,s,spSize,color(c,colorResId),weight); }
    static TextView textRaw(Context c,String s,float spSize,int colorInt,int weight) {
        TextView t=new TextView(c); t.setText(s); t.setTextSize(spSize); t.setTextColor(colorInt);
        t.setTypeface(weight==2?Typeface.create("sans-serif",Typeface.BOLD):weight==1?Typeface.create("sans-serif-medium",Typeface.NORMAL):Typeface.create("sans-serif",Typeface.NORMAL));
        return t;
    }
    static TextView caption(Context c,String s,int colorResId) { return caption(c,s,colorResId,11); }
    static TextView caption(Context c,String s,int colorResId,float spSize) { TextView t=text(c,s,spSize,colorResId,1); t.setLetterSpacing(0.08f); t.setAllCaps(true); return t; }

    static ImageView icon(Context c,int drawableRes,int sizeDp,int tintColor) {
        ImageView v=new ImageView(c); v.setImageResource(drawableRes); v.setColorFilter(tintColor);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c,sizeDp),dp(c,sizeDp))); return v;
    }

    /** Small status pill: colored dot + label, translucent rounded background. */
    static LinearLayout pill(Context c,int dotColor,String label,int labelColor,int bgColor) {
        LinearLayout row=new LinearLayout(c); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(pillShape(bgColor)); row.setPadding(dp(c,14),dp(c,9),dp(c,16),dp(c,9));
        View dot=new View(c); dot.setBackground(circle(dotColor)); LinearLayout.LayoutParams dotLp=new LinearLayout.LayoutParams(dp(c,8),dp(c,8)); dotLp.rightMargin=dp(c,10); row.addView(dot,dotLp);
        row.addView(textRaw(c,label,13,labelColor,1));
        return row;
    }

    /** Primary/danger/secondary pill-shaped button with icon + label. */
    static LinearLayout button(Context c,int drawableRes,String label,int bgColor,int fgColor) { return button(c,drawableRes,label,bgColor,fgColor,16,15,18); }
    static LinearLayout button(Context c,int drawableRes,String label,int bgColor,int fgColor,float vPadDp) { return button(c,drawableRes,label,bgColor,fgColor,vPadDp,15,18); }
    static LinearLayout button(Context c,int drawableRes,String label,int bgColor,int fgColor,float vPadDp,float fontSp,float iconDp) {
        LinearLayout row=new LinearLayout(c); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER);
        row.setBackground(rounded(bgColor,12,c)); row.setPadding(dp(c,22),dp(c,vPadDp),dp(c,26),dp(c,vPadDp));
        row.setClickable(true); row.setFocusable(true);
        android.util.TypedValue tv=new android.util.TypedValue(); c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground,tv,true); row.setForeground(c.getDrawable(tv.resourceId));
        if(drawableRes!=0) { ImageView ic=icon(c,drawableRes,Math.round(iconDp),fgColor); LinearLayout.LayoutParams icLp=(LinearLayout.LayoutParams)ic.getLayoutParams(); icLp.rightMargin=dp(c,10); row.addView(ic,icLp); }
        row.addView(textRaw(c,label,fontSp,fgColor,1));
        return row;
    }

    /** Raised card container: dark background, subtle border, rounded corners. */
    static LinearLayout card(Context c,int fillColor,int strokeColor) {
        LinearLayout card=new LinearLayout(c); card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedStroke(fillColor,strokeColor,16,1,c));
        return card;
    }

    /** One LATENCY/FRAME RATE/RESOLUTION-style stat: value on top, dim caption below. Sized small
     * deliberately — this lives inside a dock that must fit a thin letterbox gap, not a spacious panel. */
    static LinearLayout dockStat(Context c,String value,int valueColor,String label) {
        LinearLayout col=new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView v=textRaw(c,value,11,valueColor,1); col.addView(v); col.addView(caption(c,label,R.color.overlay_text_dim,8));
        return col;
    }

    static View divider(Context c,boolean vertical) {
        View v=new View(c); v.setBackgroundColor(color(c,R.color.overlay_border));
        v.setLayoutParams(vertical?new LinearLayout.LayoutParams(dp(c,1),LinearLayout.LayoutParams.MATCH_PARENT):new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(c,1)));
        return v;
    }
}
