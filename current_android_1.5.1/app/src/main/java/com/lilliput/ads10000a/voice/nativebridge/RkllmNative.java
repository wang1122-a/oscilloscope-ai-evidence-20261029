package com.lilliput.ads10000a.voice.nativebridge;
import com.jmu.text2scpi.BuildConfig;
import org.json.*;import java.nio.charset.StandardCharsets;

/** Instance lifecycle; synchronous generation belongs on an application worker. */
public final class RkllmNative implements AutoCloseable {
 private static final boolean LOADED;static{boolean b;try{System.loadLibrary("rkllm_bridge");b=true;}catch(UnsatisfiedLinkError e){b=false;}LOADED=b;}
 public interface Callback{boolean onBytes(byte[] utf8Chunk);}
 private volatile long handle;private boolean initialized=false;
 private static native byte[] nativeCreate(byte[] path,int contextTokens,int newTokens);private static native byte[] nativeCreateDiagnostic(byte[] path,int contextTokens,int newTokens,int embedFlash);private static native byte[] nativeGenerate(long handle,byte[] prompt,Callback callback);private static native int nativeCancel(long handle);private static native int nativeDestroy(long handle);private static native int nativePrefixCache(long handle,boolean enabled);private static native byte[] nativePrepareStaticPrefix(long handle,byte[] prefix);
 public static String bridgeStatus(){return LOADED?"RKLLM 1.3.0 JNI loaded":"NOT_LOADED";}
 // Scheduling-only syscalls for the calling TTS worker; these never create/use an RKLLM engine.
 private static native long nativeEnterTtsBigCoreScope();
 private static native int nativeRestoreTtsCpuScope(long original);
 public static long enterTtsBigCoreScope(){return LOADED?nativeEnterTtsBigCoreScope():-38;}
 public static int restoreTtsCpuScope(long original){return LOADED?nativeRestoreTtsCpuScope(original):-38;}
 private JSONObject result(byte[] b)throws Exception{JSONObject o=new JSONObject(new String(b,StandardCharsets.UTF_8));if(o.optInt("code",-1)!=0)throw new IllegalStateException(o.toString());return o;}
 public synchronized JSONObject initialize(String path)throws Exception{return initialize(path,2304,256);}
 public synchronized JSONObject initialize(String path,int contextTokens,int newTokens)throws Exception{if(contextTokens<512||contextTokens>8192||newTokens<64||newTokens>1024||newTokens>=contextTokens)throw new IllegalArgumentException("invalid_token_budget");if(initialized)throw new IllegalStateException("already_initialized_or_closed");if(!LOADED)throw new IllegalStateException("library_not_loaded");byte[] location=path.getBytes(StandardCharsets.UTF_8);JSONObject r=result(RkllmInitializationConfig.cpuOverridden()?nativeCreateDiagnosticCpu(location,contextTokens,newTokens,RkllmInitializationConfig.cpus(),RkllmInitializationConfig.mask()):RkllmInitializationConfig.overridden()?nativeCreateDiagnostic(location,contextTokens,newTokens,RkllmInitializationConfig.embedFlash()):nativeCreate(location,contextTokens,newTokens));handle=r.getLong("handle");initialized=true;return r;}
 /** Only the isolated, input-only run-as diagnostic opts in before constructing its
  * one shared engine. Closing removes the thread-local override; app defaults remain 1. */
 public static AutoCloseable diagnosticInitializationEmbedFlash(int value){return RkllmInitializationConfig.open(BuildConfig.DEBUG&&Boolean.getBoolean("owon.r15.performance_diagnostic"),value);}
 /** One finite SDK CPU-allocation experiment. Default/release initialization stays4/240. */
 public static AutoCloseable diagnosticInitializationCpuAllocation(int cpus,int mask){return RkllmInitializationConfig.openCpu(BuildConfig.DEBUG,Boolean.getBoolean("owon.r15.performance_diagnostic"),cpus,mask);}
 private static native byte[] nativeCreateDiagnosticCpu(byte[] path,int contextTokens,int newTokens,int cpus,int mask);
 public JSONObject generate(String prompt,Callback callback)throws Exception{long h=handle;if(h==0)throw new IllegalStateException("not_initialized_or_released");return new JSONObject(new String(nativeGenerate(h,prompt.getBytes(StandardCharsets.UTF_8),callback),StandardCharsets.UTF_8));}
 /** Compute the static system prefix during resource preparation, never a user request or answer. */
 public JSONObject prepareStaticPrefix(String prefix)throws Exception{long h=handle;if(h==0)throw new IllegalStateException("not_initialized_or_released");return new JSONObject(new String(nativePrepareStaticPrefix(h,prefix.getBytes(StandardCharsets.UTF_8)),StandardCharsets.UTF_8));}
 public int cancel(){long h=handle;return h==0?6104:nativeCancel(h);}
 /** Fixed computation cache diagnostic; never stores or returns a semantic answer. Default off. */
 public int verifiedCleanReuse(boolean enabled){long h=handle;return h==0?6104:nativeSetVerifiedCleanReuse(h,enabled);}
 private static native int nativeSetVerifiedCleanReuse(long handle,boolean enabled);
 private static native int nativeStaticPrefixCapacity(long handle,int slots);
 public int diagnosticStaticPrefixCapacity(int slots){if(!BuildConfig.DEBUG||slots<1||slots>2)throw new SecurityException("DEVELOPMENT_PREFIX_CAPACITY_ONLY");long h=handle;return h==0?6104:nativeStaticPrefixCapacity(h,slots);}
 public int prefixCache(boolean enabled){long h=handle;return h==0?6104:nativePrefixCache(h,enabled);}
 public synchronized int closeWithResult(){long h=handle;if(h==0)return 0;int r=nativeDestroy(h);handle=0;return r;}
 @Override public void close(){closeWithResult();}
}
