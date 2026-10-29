package com.lilliput.ads10000a.voice;

import java.util.*;

/** Immutable DryRun sidecar. It never changes the model schema or grants execution. */
public final class OperationPlan {
    public enum Kind {UNSUPPORTED,SCALE_ADJUSTMENT,POSITION_ADJUSTMENT,TRIGGER_LEVEL,MEASUREMENT_ADD,MEASUREMENT_REMOVE,MEASUREMENT_CLEAR_ACTIVE}
    public final String status,reason,effect,modelRaw,canonical;
    public final Kind kind;
    public final boolean wouldSend=false,executionAllowed=false;
    public final List<NativeStep> steps;
    public final List<Map<String,Object>> evidence;
    private final List<String> commands;
    private final Map<String,Object> receipt;

    OperationPlan(String status,String reason,Kind kind,String effect,String raw,boolean rawValid,StateSnapshot snapshot,List<NativeStep> steps,List<Map<String,Object>> evidence) {
        this(status,reason,kind,effect,raw,rawValid,"primitive_three_field_v1",snapshot,steps,evidence);
    }
    OperationPlan(String status,String reason,Kind kind,String effect,String raw,boolean rawValid,String modelSchema,StateSnapshot snapshot,List<NativeStep> steps,List<Map<String,Object>> evidence) {
        if(!Set.of("preview","noop","clarify","reject","unsupported").contains(status))throw new IllegalArgumentException("invalid_operation_plan_status");
        if(!Set.of("preview","noop").contains(status)&&!steps.isEmpty()||status.equals("noop")&&!steps.isEmpty()||status.equals("preview")&&steps.isEmpty())throw new IllegalArgumentException("operation_plan_status_step_conflict");
        this.status=status;this.reason=reason;this.kind=kind;this.effect=effect;modelRaw=raw;
        this.steps=Collections.unmodifiableList(new ArrayList<>(steps));this.evidence=freezeEvents(evidence);
        List<String> wires=new ArrayList<>();for(NativeStep step:steps)wires.add(step.canonical);commands=Collections.unmodifiableList(wires);canonical=String.join("\n",commands);
        Map<String,Object> data=new LinkedHashMap<>();data.put("status",status);data.put("reason",reason);data.put("kind",kind.name());data.put("effect",effect);
        Map<String,Object> quantityAlias=WorkflowEnvelopeV2.SCHEMA.equals(modelSchema)?WorkflowEnvelopeV2.quantityAliasEvidence(raw):Collections.emptyMap();
        data.put("scope","TYPED_INTERNAL_DRYRUN_OPERATION_SIDECAR");data.put("model_raw",raw);data.put("model_protocol_valid",rawValid&&!WorkflowEnvelopeV2.legacyRepresentation(raw));data.put("workflow_protocol_representation_normalized",WorkflowEnvelopeV2.SCHEMA.equals(modelSchema)&&(WorkflowEnvelopeV2.legacyRepresentation(raw)||!quantityAlias.isEmpty()));data.put("model_raw_preserved",true);
        data.put("measurement_quantity_alias_normalized",!quantityAlias.isEmpty());data.put("measurement_quantity_alias_evidence",quantityAlias);
        data.put("model_schema",modelSchema);data.put("native_step_count",steps.size());data.put("canonical_steps",commands);
        List<Map<String,Object>> nativeSteps=new ArrayList<>();for(NativeStep step:steps)nativeSteps.add(step.toMap());data.put("native_steps",nativeSteps);
        data.put("source_evidence",this.evidence);data.put("state_snapshot",snapshot==null?Collections.emptyMap():snapshot.toMap());
        data.put("would_send",false);data.put("execution_allowed",false);data.put("instrument_io",0);data.put("scpi_dispatch",0);data.put("device_state_verified",snapshot!=null&&snapshot.origin==StateSnapshot.Origin.VERIFIED_INSTRUMENT_QUERY);
        data.put("model_generation_required",true);data.put("model_generation_receipt_required_from_caller",true);data.put("replaces_single_native_final",false);
        receipt=freezeMap(data);
    }
    public static final class NativeStep {
        public final String intent,command,canonical,authorityId;
        public final Object parameter;
        public final List<Map<String,Object>> sourceEvidence;
        private final Map<String,Object> object,receipt;
        NativeStep(ScpiTableEligibility table,String command,Object parameter,List<Map<String,Object>> evidence) {
            ScpiTableEligibility.Decision decision=table.qualify(command,parameter);
            if(decision.status!=ScpiTableEligibility.Status.IN_TABLE_SET)throw new IllegalArgumentException("native_operation_step_not_authority_set");
            ScpiTableEligibility.NarrationDescriptor descriptor=table.describeForNarration(command);
            this.command=table.canonicalHead(command);this.parameter=freeze(parameter);canonical=decision.canonical;authorityId=descriptor.templateId;
            intent=descriptor.intent.isEmpty()?"TABLE_"+descriptor.kind:descriptor.intent;sourceEvidence=freezeEvents(evidence);
            if(sourceEvidence.isEmpty())throw new IllegalArgumentException("native_step_source_evidence_required");
            object=freezeMap(Map.of("intent",intent,"SCPIcommand",this.command,"SCPIparams",this.parameter));
            if(!SingleInstructionProtocol.checkRaw(CompactSemanticProtocol.encode(object),table).valid)throw new IllegalArgumentException("native_operation_step_protocol_invalid");
            receipt=freezeMap(Map.of("native",object,"canonical",canonical,"authority_id",authorityId,"source_evidence",sourceEvidence,"would_send",false));
        }
        public Map<String,Object> nativeObject(){return object;}
        public Map<String,Object> toMap(){return receipt;}
    }
    public List<String> commands(){return commands;}
    public Map<String,Object> toMap(){return receipt;}
    static List<Map<String,Object>> freezeEvents(List<Map<String,Object>> input){List<Map<String,Object>> output=new ArrayList<>();for(Map<String,Object> event:input)output.add(freezeMap(event));return Collections.unmodifiableList(output);}
    static Map<String,Object> freezeMap(Map<String,Object> input){Map<String,Object> output=new LinkedHashMap<>();for(Map.Entry<String,Object> e:input.entrySet())output.put(e.getKey(),freeze(e.getValue()));return Collections.unmodifiableMap(output);}
    @SuppressWarnings("unchecked") private static Object freeze(Object value){if(value instanceof Map)return freezeMap((Map<String,Object>)value);if(value instanceof List){List<Object> out=new ArrayList<>();for(Object x:(List<?>)value)out.add(freeze(x));return Collections.unmodifiableList(out);}return value;}
}
