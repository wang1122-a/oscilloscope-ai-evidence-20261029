package com.lilliput.ads10000a.voice;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Describes supplied fixture policy; computes no target, command, intent, or model answer. */
public final class ExplicitStepModelGuidance {
 public static final String ID="explicit_adjacent_scale_step_guidance_v1";
 public static final String HINT="Explicit adjacent-scale policy: The supplied verified_vertical_scale_steps_volts_by_channel and verified_timebase_steps_seconds are ordered lists of permitted settings for the addressed property and channel. A bare increase or decrease with no explicit numeric amount, factor, or step count means exactly the next or previous adjacent entry from the supplied current setting. Do not assume doubling or halving, interpolate, keep the current entry, skip entries, or use an unlisted value. An explicitly stated amount, factor, or step count is a different request; do not replace it with this one-adjacent-entry policy. If the addressed current setting, applicable list, exact current entry, or unique adjacent entry is unavailable, ambiguous, or at a list boundary, output CLARIFY. This context is an explicit offline fixture for this request, not an observed device reading.";
 public static final String SHA256=digest(HINT);
 private ExplicitStepModelGuidance(){}
 public static boolean hasValidStepPolicy(ValidatedExplicitContext context){
  if(context==null||!"valid".equals(context.status))return false;
  // Only immutable, separately validated fields can enable the description.
  // Request text, embedded Fake context, model raw and evaluation data are never read.
  Map<String,Object> values=context.snapshot();Object vertical=values.get("verified_vertical_scale_steps_volts_by_channel"),time=values.get("verified_timebase_steps_seconds");
  return vertical instanceof Map&&!((Map<?,?>)vertical).isEmpty()||time instanceof List&&((List<?>)time).size()>=2;
 }
 public static String append(String modelInput){if(modelInput==null)throw new IllegalArgumentException("model_input_required");return modelInput+"\n"+HINT;}
 private static String digest(String text){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte value:bytes)out.append(String.format(Locale.ROOT,"%02X",value&255));return out.toString();}catch(Exception impossible){throw new IllegalStateException(impossible);}}
}
