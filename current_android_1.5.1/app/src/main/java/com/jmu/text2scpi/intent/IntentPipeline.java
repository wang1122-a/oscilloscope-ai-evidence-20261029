package com.jmu.text2scpi.intent;
import com.jmu.text2scpi.model.*;
import com.lilliput.ads10000a.voice.SharedBilingualEngine;
import kotlinx.serialization.json.Json;
/** Shared semantic policy -> original confirmation and transport result. */
public final class IntentPipeline {
 private final RkllmIntentEngine engine;private volatile boolean ads10000a;
 public IntentPipeline(RkllmIntentEngine e){engine=e;}
 public boolean getAds10000a(){return ads10000a;}
 public void setAds10000a(boolean v){ads10000a=v;}
 public PipelineResult process(String id,String text){
  SharedBilingualEngine.Decision d=engine.process(text);boolean preview="preview".equals(d.status);
  RuleDisposition disposition=preview&&ads10000a?RuleDisposition.VALID_UNCHANGED:"clarify".equals(d.status)?RuleDisposition.CLARIFICATION_REQUIRED:RuleDisposition.REJECTED_UNSAFE;
  String reason=preview&&!ads10000a?"示波器尚未连接，未发送":preview?null:d.reason;
  return new PipelineResult(id,text,d.raw,preview?IntentStatus.UNDERSTOOD:"clarify".equals(d.status)?IntentStatus.NEED_CLARIFICATION:IntentStatus.NOT_UNDERSTOOD,disposition,d.intent,Json.Default.parseToJsonElement(d.parameters),preview?d.wire:null,d.verification,reason);
 }
}
