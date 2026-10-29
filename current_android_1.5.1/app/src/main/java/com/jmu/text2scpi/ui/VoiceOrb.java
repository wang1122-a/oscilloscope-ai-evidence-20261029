package com.jmu.text2scpi.ui;
import android.content.Context;
import android.graphics.*;
import android.view.View;
/**
 * 语音状态指示视图。负责绘制语音球及电平动画，显示状态不代表设备指令已成功执行。
 */
public final class VoiceOrb extends View {
    public float level;
    public boolean listening;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader gradient;
    public VoiceOrb(Context context) {
        super(context);
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) {
        float cx=w/2f,cy=h/2f,r=Math.min(w,h)*.36f;
        if(r>0)gradient=new RadialGradient(cx-r*.3f,cy-r*.4f,r*1.8f,new int[] {
            Color.rgb(99,222,245),Color.rgb(130,108,245),Color.rgb(202,91,175)
        }
        ,null,Shader.TileMode.CLAMP);
    }
    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
    @Override protected void onDraw(Canvas canvas) {
        float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(getWidth(),getHeight())*.36f;
        paint.setShader(gradient);
        canvas.drawCircle(cx,cy,r*(1+level*.15f),paint);
        paint.setShader(null);
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(4f*getResources().getDisplayMetrics().density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for(int i=-2;i<=2;i++) {
            float x=cx+i*r*.24f,h=r*(.16f+(2-Math.abs(i))*.11f+level*.3f);
            canvas.drawLine(x,cy-h,x,cy+h,paint);
        }
    }
}
