package com.lilliput.ads10000a.voice;
import java.io.ByteArrayOutputStream;import java.nio.charset.StandardCharsets;import java.util.*;
/** Preserves native generation and assistant prefill before any frame extraction or repair. */
final class GeneratedTextEvidence {
 final String prefix;private final boolean stopOnJsonComplete;private final JsonObjectFramer frame=new JsonObjectFramer();private final ByteArrayOutputStream stream=new ByteArrayOutputStream();private boolean callbackRequestedStop;
 GeneratedTextEvidence(String prompt,boolean stop){prefix=prompt.endsWith(SemanticPolicy.ASSISTANT_PREFIX)?SemanticPolicy.ASSISTANT_PREFIX:"";stopOnJsonComplete=stop;frame.accept(prefix.getBytes(StandardCharsets.UTF_8));}
 synchronized boolean accept(byte[] chunk){stream.write(chunk,0,chunk.length);boolean complete=frame.accept(chunk);if(complete&&stopOnJsonComplete)callbackRequestedStop=true;return complete&&stopOnJsonComplete;}
 synchronized boolean frameComplete(){return frame.complete();}
 synchronized String modelRaw(String originalNativeRaw){return prefix+originalNativeRaw;}
 synchronized Map<String,Object> snapshot(String originalNativeRaw){String actual=new String(stream.toByteArray(),StandardCharsets.UTF_8);Map<String,Object> fields=new LinkedHashMap<>();fields.put("native_result_original_raw",originalNativeRaw);fields.put("stream_raw",actual);fields.put("assistant_prefix",prefix);fields.put("model_raw_with_prefix",modelRaw(originalNativeRaw));fields.put("callback_stream_with_prefix",prefix+actual);fields.put("framed_candidate",frame.complete()?frame.text():null);fields.put("framed_candidate_complete",frame.complete());fields.put("stream_matches_native_raw",actual.equals(originalNativeRaw));fields.put("raw_object_extraction_count",0);fields.put("stop_on_json_complete",stopOnJsonComplete);fields.put("callback_requested_stop",callbackRequestedStop);fields.put("model_raw_source","assistant_prefill_plus_complete_native_original_raw_without_extraction");return fields;}
}
