package com.jmu.text2scpi.ui;
import android.view.*;
public final class ImmersiveMode {
    private ImmersiveMode(){}
    public static void apply(Window window){
        window.setDecorFitsSystemWindows(false);
        window.getDecorView().post(()->{
            WindowInsetsController bars=window.getInsetsController();
            if(bars!=null){bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);bars.hide(WindowInsets.Type.systemBars());}
        });
    }
}
