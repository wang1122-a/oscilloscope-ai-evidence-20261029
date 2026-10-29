package com.jmu.text2scpi.service;
import android.app.AppOpsManager;
import android.content.*;
import android.graphics.PixelFormat;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import com.jmu.text2scpi.model.ControlUiState;
import com.jmu.text2scpi.ui.*;
import java.util.Objects;
/**
 * 管理悬浮球与展开面板的窗口生命周期。订阅服务状态；收起、关闭和再次展开通过会话代次隔离旧事件。
 */
public final class OverlayController {
    private final Context context;
    private final OscilloscopeControlService service;
    private final WindowManager wm;
    private final AppOpsManager ops;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final OverlayEpoch epoch=new OverlayEpoch();
    private final SharedPreferences preferences;
    private final AppOpsManager.OnOpChangedListener permissionListener;
    private View window;
    private AssistantPanel panel;
    private WindowManager.LayoutParams panelParams;
    private boolean enabled;
    private ControlUiState latest=new ControlUiState();
    private String completion;
    private int x,y;
    public OverlayController(Context context,OscilloscopeControlService service) {
        this.context=context;
        this.service=service;
        wm=context.getSystemService(WindowManager.class);
        ops=context.getSystemService(AppOpsManager.class);
        preferences=context.getSharedPreferences("voice_overlay",Context.MODE_PRIVATE);
        x=preferences.getInt("x",24);
        y=preferences.getInt("y",96);
        permissionListener=(op,pkg)-> {
            if(context.getPackageName().equals(pkg))handler.post(()-> {
                if(!Settings.canDrawOverlays(context)) {
                    service.pauseVoice();closeCompletely();
                }
            }
            );
        }
        ;
        ops.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,context.getPackageName(),permissionListener);
    }
    private int dp(int n) {
        return (int)(n*context.getResources().getDisplayMetrics().density);
    }
    private void remove() {
        epoch.replaced();
        if(window!=null)try {
            wm.removeView(window);
        }
        catch(Exception ignored) {
        }
        window=null;
        panel=null;panelParams=null;
    }
    /** 将当前悬浮窗口替换为可拖动的小球；点击小球展开面板并进入监听。 */
    public boolean showButton() {
        if(!Settings.canDrawOverlays(context))return false;
        enabled=true;
        remove();
        VoiceOrb button=new VoiceOrb(context);
        button.setContentDescription(UiText.choose(latest.asrLanguage,"小欧语音助手，点击说话","XiaoOu voice assistant, tap to speak"));
        button.setOnClickListener(v-> {
            showPanel();service.listen();
        }
        );
        WindowManager.LayoutParams p=params(dp(60),dp(60),true);
        float[] start=new float[2];
        int[] origin=new int[2];
        button.setOnTouchListener((v,e)-> {
            switch(e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:start[0]=e.getRawX();start[1]=e.getRawY();origin[0]=p.x;origin[1]=p.y;return true;
                case MotionEvent.ACTION_MOVE:
                p.x=clamp((int)(origin[0]+e.getRawX()-start[0]),context.getResources().getDisplayMetrics().widthPixels-p.width);
                p.y=clamp((int)(origin[1]+e.getRawY()-start[1]),context.getResources().getDisplayMetrics().heightPixels-p.height);
                try {
                    wm.updateViewLayout(button,p);
                }
                catch(Exception ignored) {
                }
                return true;
                case MotionEvent.ACTION_UP:
                x=p.x;y=p.y;preferences.edit().putInt("x",x).putInt("y",y).apply();
                if(Math.abs(e.getRawX()-start[0])+Math.abs(e.getRawY()-start[1])<dp(10))button.performClick();return true;
                default:return false;
            }
        }
        );
        try {
            wm.addView(button,p);
            window=button;
            return true;
        }
        catch(Exception e) {
            return false;
        }
    }
    private static int clamp(int n,int maximum) {
        return Math.max(0,Math.min(n,Math.max(0,maximum)));
    }
    private WindowManager.LayoutParams params(int w,int h,boolean noFocus) {
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(w,h,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|(noFocus?WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE:0),PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.START;
        p.x=clamp(x,context.getResources().getDisplayMetrics().widthPixels-w);
        p.y=clamp(y,context.getResources().getDisplayMetrics().heightPixels-h);
        p.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        return p;
    }
    private void showPanel() {
        if(!enabled||!Settings.canDrawOverlays(context))return;
        remove();
        AssistantPanel view=new AssistantPanel(context,()->service,service::listen,service::collapseCurrentSession,service::hideOverlay);
        WindowManager.LayoutParams p=params(Math.min(dp(430),context.getResources().getDisplayMetrics().widthPixels-dp(24)),Math.min(dp(510),context.getResources().getDisplayMetrics().heightPixels-dp(40)),true);
        try {
            view.setOverlayInputActivation(()->{try{p.flags&=~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;wm.updateViewLayout(view,p);}catch(Exception ignored){}});
            wm.addView(view,p);panelParams=p;
            window=view;
            panel=view;
            view.render(latest);
        }
        catch(Exception ignored) {
        }
    }
    /** 接收服务状态，刷新已展开的面板，并根据新的监听会话决定是否展开悬浮窗。 */
    public void render(ControlUiState state) {
        latest=state;
        if(window instanceof VoiceOrb)window.setContentDescription(UiText.choose(state.asrLanguage,"小欧语音助手，点击说话","XiaoOu voice assistant, tap to speak"));
        if(epoch.newListening(state.voiceSession,state.listening)&&enabled&&panel==null)showPanel();
        if(panel!=null){
            if((state.listening||state.processing)&&panelParams!=null&&(panelParams.flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)==0){panel.finishTextInput();panelParams.flags|=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;try{wm.updateViewLayout(panel,panelParams);}catch(Exception ignored){}}
            panel.render(state);
        }
        if(!state.continuousListening&&state.response!=null&&state.error==null&&!state.processing&&!Objects.equals(completion,state.requestId)) {
            completion=state.requestId;
            String id=state.requestId;
            long generation=epoch.getGeneration();
            handler.postDelayed(()->collapseWhenIdle(id,generation),5000);
        }
    }
    private void collapseWhenIdle(String id,long generation) {
        if(latest.continuousListening||!enabled||!epoch.timerCurrent(generation)||!Objects.equals(latest.requestId,id)||latest.listening||latest.processing||latest.awaitingConfirmation||latest.error!=null)return;
        if(service.isFeedbackBusy()) {
            handler.postDelayed(()->collapseWhenIdle(id,generation),1000);
            return;
        }
        service.collapseCurrentSession();
    }
    public void closeCompletely() {
        enabled=false;
        handler.removeCallbacksAndMessages(null);
        remove();
    }
    public void release() {
        closeCompletely();
        ops.stopWatchingMode(permissionListener);
    }
}
