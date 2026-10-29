package com.jmu.text2scpi.voice;
import android.content.Context;
import android.util.Log;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Bounded, local text diagnostics; no microphone audio is persisted. */
public final class DiagnosticJournal {
    private final File root;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(128),r->new Thread(r,"voice-diagnostics"),new ThreadPoolExecutor.DiscardOldestPolicy());
    public DiagnosticJournal(Context context){File external=context.getExternalFilesDir(null);root=new File(external!=null?external:context.getFilesDir(),"diagnostics");}
    public void append(String stage,String content){
        if(worker.isShutdown())return;
        worker.execute(()->{try{
            if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Cannot create diagnostics");
            File current=new File(root,"recent.jsonl"),previous=new File(root,"previous.jsonl");
            if(current.length()>524288){if(previous.exists()&&!previous.delete())throw new IOException("Cannot rotate diagnostics");if(!current.renameTo(previous))throw new IOException("Cannot rotate diagnostics");}
            JSONObject row=new JSONObject();row.put("host_device_time_ms",System.currentTimeMillis());row.put("elapsed_ms",android.os.SystemClock.elapsedRealtime());row.put("stage",stage);row.put("content",content);
            try(OutputStream out=new FileOutputStream(current,true)){out.write((row.toString()+"\n").getBytes(StandardCharsets.UTF_8));}
        }catch(Exception e){Log.w("Voice-DIAGNOSTICS","Local diagnostics unavailable",e);}});
    }
    public void close(){worker.shutdown();}
}
