package com.lilliput.ads10000a.voice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Immutable request-bound fixture state. No factory can relabel a fixture as device state. */
public final class StateSnapshot {
    public enum Origin { EXPLICIT_FIXTURE_PARAMETER, VERIFIED_INSTRUMENT_QUERY }
    public final String requestId, sourceId, fingerprint;
    public final long generation, capturedAtNanos, maxAgeNanos;
    public final Origin origin;
    final ValidatedExplicitContext context;
    private final Map<String,Object> receipt;

    private StateSnapshot(ValidatedExplicitContext context,String requestId,long generation,long capturedAtNanos,long maxAgeNanos,String sourceId) {
        if(context==null||requestId==null||requestId.trim().isEmpty()||sourceId==null||sourceId.trim().isEmpty()||generation<0||capturedAtNanos<0||maxAgeNanos<0)
            throw new IllegalArgumentException("state_snapshot_binding_and_source_required");
        this.origin=context.receipt().get("source_type").equals("VERIFIED_INSTRUMENT_QUERY")?Origin.VERIFIED_INSTRUMENT_QUERY:Origin.EXPLICIT_FIXTURE_PARAMETER;this.context=context;this.requestId=requestId;this.generation=generation;this.capturedAtNanos=capturedAtNanos;this.maxAgeNanos=maxAgeNanos;this.sourceId=sourceId;
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("request_id",requestId);data.put("generation",generation);data.put("captured_at_nanos",capturedAtNanos);data.put("max_age_nanos",maxAgeNanos);
        data.put("source_type",origin.name());data.put("source_id",sourceId);data.put("state_status",context.status);data.put("state_reason",context.reason);
        data.put("validated_explicit_context",context.receipt());data.put("values",context.snapshot());
        data.put("model_generated",false);data.put("instrument_state_queried",origin==Origin.VERIFIED_INSTRUMENT_QUERY);data.put("device_state_verified",origin==Origin.VERIFIED_INSTRUMENT_QUERY);data.put("would_send",false);
        data.put("step_policy_scope","EXPLICIT_FIXTURE_POLICY_SHAPE_AND_AUTHORITY_VALIDATED_ONLY");
        fingerprint=digest(CompactSemanticProtocol.encode(data));data.put("snapshot_sha256",fingerprint);receipt=OperationPlan.freezeMap(data);
    }
    public static StateSnapshot explicitFixture(ValidatedExplicitContext context,String requestId,long generation,long capturedAtNanos,long maxAgeNanos,String sourceId) {
        return new StateSnapshot(context,requestId,generation,capturedAtNanos,maxAgeNanos,sourceId);
    }
 public Map<String,Object> values(){return context.snapshot();}
    public String bindingIssue(String expectedRequestId,long expectedGeneration,long nowNanos){
        if(!requestId.equals(expectedRequestId))return "state_snapshot_request_mismatch";
        if(generation!=expectedGeneration)return "state_snapshot_generation_mismatch";
        if(nowNanos<capturedAtNanos)return "state_snapshot_clock_order_invalid";
        if(nowNanos-capturedAtNanos>maxAgeNanos)return "state_snapshot_stale";
        return null;
    }
    public Map<String,Object> toMap(){return receipt;}
    private static String digest(String value){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02X",b&255));return out.toString();}catch(Exception impossible){throw new IllegalStateException(impossible);}}
}
