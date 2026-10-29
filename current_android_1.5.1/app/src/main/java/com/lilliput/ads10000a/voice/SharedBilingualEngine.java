package com.lilliput.ads10000a.voice;
import android.content.Context;
import com.jmu.text2scpi.resources.ResourceStore;
import com.lilliput.ads10000a.voice.nativebridge.RkllmNative;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Adapts the original bilingual policy and its matching RKLLM 1.3.0 JNI. */
public final class SharedBilingualEngine implements AutoCloseable {
 public static final class Decision {
  public final String status,raw,wire,reason,intent,parameters,verification;
  Decision(String s,String r,String w,String why,String i,String p,String v){status=s;raw=r;wire=w;reason=why;intent=i;parameters=p;verification=v;}
 }
 private final Context context;private RkllmNative engine;private BilingualTextPipeline pipeline;private SemanticPolicy policy;private BilingualModelManifest manifest;
 public SharedBilingualEngine(Context c){context=c.getApplicationContext();}
 public synchronized void initialize()throws Exception {
  if(engine!=null)return;
  manifest=BilingualModelManifest.load(new ResourceStore(context).required("semantic"));
  String authority=asset("scpi_contract.json");policy=new SemanticPolicy(authority);
  pipeline=BilingualTextPipeline.singleInstruction(policy,authority,asset("pc_asr_text_rules.json"),asset("single_instruction_runtime_config.json"),manifest.templates.get("zh"),manifest.templates.get("en"));
  RkllmNative candidate=new RkllmNative();
  try{candidate.initialize(manifest.model.getAbsolutePath(),manifest.contextTokens,manifest.newTokens);engine=candidate;}catch(Exception|LinkageError e){candidate.close();throw e;}
  android.util.Log.i("Bilingual-Qwen","READY id="+manifest.modelId+" sha256="+manifest.sha256+" shared_handle=true");
 }
 public synchronized Decision process(String text,String language)throws Exception {
  if(engine==null)throw new IllegalStateException("双语千问尚未加载");
  SemanticLanguage selected="en".equals(language)?SemanticLanguage.EN:SemanticLanguage.ZH;
  BilingualTextPipeline.Prepared prepared=pipeline.prepare(text,selected,Collections.emptyMap(),false,true);String raw=null;
  if(prepared.requiresBackend){String prompt=prepared.singlePrompt();GeneratedTextEvidence generated=new GeneratedTextEvidence(prompt,prepared.stopOnJsonComplete());JSONObject result=engine.generate(prompt,generated::accept);if(result.optInt("code",-1)!=0)throw new IOException("千问推理失败："+result);raw=generated.modelRaw(result.optString("raw",""));}
  BilingualTextPipeline.Result checked=pipeline.complete(prepared,raw);String intent="",parameters="{}",verify=null;
  if(checked.finalContract instanceof Map){Map<?,?> contract=(Map<?,?>)checked.finalContract;intent=String.valueOf(contract.get("intent"));parameters=CompactSemanticProtocol.encode(contract.get("SCPIparams"));String head=String.valueOf(contract.get("SCPIcommand"));if("preview".equals(checked.status)&&!head.endsWith("?"))try{verify=policy.table().requireQuery(head+"?");}catch(IllegalArgumentException unsupported){}}
  String wire="preview".equals(checked.status)?checked.canonical:null;
  android.util.Log.i("Bilingual-Qwen","language="+language+" status="+checked.status+" model="+manifest.modelId+" raw="+raw+" wire="+wire);
  return new Decision(checked.status,raw==null?"":raw,wire,checked.reason,intent,parameters,verify);
 }
 private String asset(String name)throws IOException{try(InputStream in=context.getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);}}
 @Override public synchronized void close(){if(engine!=null){engine.close();engine=null;}}
}
