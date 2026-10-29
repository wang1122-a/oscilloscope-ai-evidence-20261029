package com.jmu.text2scpi.intent;
import android.content.Context;
import com.lilliput.ads10000a.voice.SharedBilingualEngine;
/** One shared bilingual weight. A language change changes only the prompt. */
public final class RkllmIntentEngine implements IntentEngine {
 private final SharedBilingualEngine shared;private volatile String language="zh";private boolean initialized;
 public RkllmIntentEngine(Context c){shared=new SharedBilingualEngine(c);}
 public void setLanguage(String value){language="en".equals(value)?"en":"zh";}
 public synchronized boolean isInitialized(){return initialized;}
 @Override public synchronized void initialize(){try{shared.initialize();initialized=true;}catch(Exception e){throw new IntentEngineException("双语千问加载失败："+e.getMessage());}}
 public synchronized SharedBilingualEngine.Decision process(String text){try{return shared.process(text,language);}catch(Exception e){throw new IntentEngineException("双语指令解析失败："+e.getMessage());}}
 @Override public synchronized String infer(String text){return process(text).raw;}
 @Override public synchronized void release(){shared.close();initialized=false;}
}
