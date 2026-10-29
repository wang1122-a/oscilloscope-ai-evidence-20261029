package com.jmu.text2scpi.ui;
import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.FrameLayout;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.jmu.text2scpi.model.StateStore;
import com.jmu.text2scpi.service.OscilloscopeControlService;
/**
 * 应用主入口。申请麦克风与悬浮窗权限，绑定控制服务，并将用户操作转交服务；不在界面线程执行模型推理。
 */
public final class MainActivity extends AppCompatActivity {
    private OscilloscopeControlService service;
    private boolean bound,started;
    private StateStore.Subscription observer;
    private AssistantPanel panel;
    private Uri pendingImport;
    private final ActivityResultLauncher<String[]> resourcePicker=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->{if(uri!=null){if(service!=null)service.importResource(uri);else pendingImport=uri;}});
    private void maybeImport(Intent intent){if(intent!=null&&intent.getBooleanExtra("import_resources",false)){intent.removeExtra("import_resources");new Handler(Looper.getMainLooper()).post(()->resourcePicker.launch(new String[]{"application/zip","application/octet-stream","application/x-zip-compressed"}));}}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);maybeImport(intent);}
    private final ActivityResultLauncher<String> audioPermission=registerForActivityResult(new ActivityResultContracts.RequestPermission(),granted-> {
        if(granted&&service!=null)service.enableVoice();
    }
    );
    private final ActivityResultLauncher<Intent> overlayPermission=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result-> {
        if(Settings.canDrawOverlays(this)&&service!=null) {
            service.showOverlay();service.openOscilloscopeApp();
        }
    }
    );
    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder binder) {
            service=((OscilloscopeControlService.LocalBinder)binder).service();
            subscribe();
            if(pendingImport!=null){Uri uri=pendingImport;pendingImport=null;service.importResource(uri);}
            if(ContextCompat.checkSelfPermission(MainActivity.this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)audioPermission.launch(Manifest.permission.RECORD_AUDIO);
            else service.enableVoice();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            unsubscribe();
            service=null;
        }
    }
    ;
    /** 订阅服务的状态快照，并把变化交给页面渲染；绑定重建时先解除旧订阅。 */
    private void subscribe() {
        unsubscribe();
        if(started&&service!=null)observer=service.getUiState().observe(panel::render);
    }
    /** 解除界面观察者，避免 Activity 已退出后仍被服务持有。 */
    private void unsubscribe() {
        if(observer!=null) {
            observer.close();
            observer=null;
        }
    }
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        panel=new AssistantPanel(this,()->service,()-> {
            if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)audioPermission.launch(Manifest.permission.RECORD_AUDIO);
            else if(service!=null)service.listen();
        }
        ,()-> {
            if(Settings.canDrawOverlays(this)) {
                if(service!=null) {
                    service.showOverlay();service.openOscilloscopeApp();
                }
            }
            else overlayPermission.launch(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));
        }
        ,()-> {
            if(service!=null)service.hideOverlay();finish();
        }
        );
        FrameLayout root=new FrameLayout(this);
        int width=Math.min((int)(520*getResources().getDisplayMetrics().density),getResources().getDisplayMetrics().widthPixels-32);
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(width,-1,Gravity.CENTER);
        p.topMargin=16;
        p.bottomMargin=16;
        root.addView(panel,p);
        setContentView(root);ImmersiveMode.apply(getWindow());
        maybeImport(getIntent());
        Intent intent=new Intent(this,OscilloscopeControlService.class);
        ContextCompat.startForegroundService(this,intent);
        bound=bindService(intent,connection,Context.BIND_AUTO_CREATE);
    }
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)ImmersiveMode.apply(getWindow());}
    @Override protected void onResume(){super.onResume();ImmersiveMode.apply(getWindow());}
    @Override protected void onStart() {
        super.onStart();
        started=true;
        subscribe();
    }
    @Override protected void onStop() {
        started=false;
        unsubscribe();
        super.onStop();
    }
    @Override protected void onDestroy() {
        unsubscribe();
        if(bound)unbindService(connection);
        super.onDestroy();
    }
}
