package com.lilliput.ads10000a.voice;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Post-model planning through existing typed request proofs; owns no grammar or transport. */
public final class NativeOperationPlanner {
    // Exact existing EnglishRequestCompiler numeric grammar/boundaries, not a
    // new request parser. Bare adjacent-step planning consumes no numeric operand.
    private static final Pattern EXISTING_NUMERIC_OPERAND=Pattern.compile("(?i)(?<![A-Za-z0-9])"+EnglishNumberWords.VALUE+"(?![A-Za-z0-9])");
    private final SemanticPolicy policy;
    private final ScpiTableEligibility table;
    private final ParameterSourceGuard sources;
    private final UnitSourceModel units;

    public NativeOperationPlanner(SemanticPolicy policy,String contractJson) {
        if(policy==null||contractJson==null)throw new IllegalArgumentException("shared_policy_and_authority_required");
        this.policy=policy;table=policy.table();Map root=(Map)StrictJson.parse(contractJson);sources=new ParameterSourceGuard(root,table);units=new UnitSourceModel(root);
    }
    public OperationPlan plan(String originalText,SemanticLanguage language,String actualRaw,StateSnapshot snapshot,String requestId,long generation,long nowNanos) {
        if(originalText==null||language==null)throw new IllegalArgumentException("original_request_and_language_required");
        WorkflowEnvelopeV2.Check workflowEnvelope=WorkflowEnvelopeV2.checkForExecution(actualRaw,table);
        if(workflowEnvelope.valid)return workflow(originalText,language,actualRaw,workflowEnvelope,snapshot,requestId,generation,nowNanos);
        SingleInstructionProtocol.RawCheck raw=SingleInstructionProtocol.checkRaw(actualRaw,table);
        if(!raw.valid)return terminal("reject",raw.violation,actualRaw,false,snapshot);
        if(Set.of("CLARIFY","REJECT").contains(raw.object.get("intent")))return terminal("clarify","native_terminal_cannot_authorize_operation_plan",actualRaw,true,snapshot);
        if(snapshot==null)return terminal("clarify","state_snapshot_required",actualRaw,true,null);
        String binding=snapshot.bindingIssue(requestId,generation,nowNanos);
        if(binding!=null)return terminal("clarify",binding,actualRaw,true,snapshot);
        if(!snapshot.context.usable())return terminal(snapshot.context.status,snapshot.context.reason,actualRaw,true,snapshot);
        String text=snapshot.context.semanticInput(originalText);
        if(!originalText.equals(text))return terminal("clarify","embedded_context_not_operation_evidence",actualRaw,true,snapshot);
        String gate=SingleInstructionProtocol.requestGate(text,language);
        String business=language==SemanticLanguage.EN?EnglishRequestCompiler.derivedIntent(text):null;
        boolean workflow=Set.of("MEASURE_ADD","MEASURE_CLEAN").contains(business==null?"":business);
        if(gate!=null&&!(workflow&&gate.equals("single_instruction_split_required")&&!EnglishSemanticBridge.hasMultipleOperations(text)&&!EnglishSingleOperationScope.unconsumedSplit(text)&&text.indexOf(';')<0&&text.indexOf('；')<0&&text.indexOf('\n')<0&&text.indexOf('\r')<0))
            return terminal("clarify",gate,actualRaw,true,snapshot);
        if(PcSemanticGuard.prohibitedRequest(text)||language==SemanticLanguage.EN&&EnglishRequestCompiler.prohibited(EnglishRequestCompiler.scopeSurface(text)))return terminal("clarify","current_request_cancelled_or_nonexecuting",actualRaw,true,snapshot);
        String head=table.canonicalHead(String.valueOf(raw.object.get("SCPIcommand")));
        Object parameter=raw.object.get("SCPIparams");
        if(language==SemanticLanguage.EN&&"MEASURE_ADD".equals(business))return add(text,actualRaw,raw.object,snapshot);
        if(language==SemanticLanguage.EN&&"MEASURE_CLEAN".equals(business))return clear(text,actualRaw,raw.object,snapshot);
        ExistingMeasurementRequest existing=ExistingMeasurementRequest.parse(text,snapshot.context,table);
        if(existing!=null) {
            if(existing.issue!=null)return terminal("clarify",existing.issue,actualRaw,true,snapshot);
            if(!existing.command.endsWith(":STATE"))return terminal("unsupported","read_only_measurement_not_an_operation_plan",actualRaw,true,snapshot);
            if(!existing.matches(text,head,parameter))return terminal("clarify","model_measurement_carrier_conflict",actualRaw,true,snapshot);
            SemanticPolicy.Result proof=policy.evaluate(text,actualRaw,snapshot.context);
            if(!proof.status.equals("preview"))return terminal(proof.status,proof.reason,actualRaw,true,snapshot);
            return ready(OperationPlan.Kind.MEASUREMENT_REMOVE,"DISABLE_READOUT_KEEP_SLOT_CONFIGURATION",actualRaw,snapshot,List.of(new OperationPlan.NativeStep(table,head,parameter,proof.parameterEvidence)),proof.parameterEvidence);
        }
        SemanticPolicy.PositionRequest position=sources.positionRequest(text,snapshot.context);
        if(position!=null) {
            if(position.issue!=null)return terminal("clarify",position.issue,actualRaw,true,snapshot);
            if(!position.matches(text,head,parameter))return terminal("clarify","model_relative_target_conflict",actualRaw,true,snapshot);
            SemanticPolicy.Result proof=policy.evaluatePositionRequest(text,actualRaw,snapshot.context);
            if(proof==null||!proof.status.equals("preview"))return terminal(proof==null?"clarify":proof.status,proof==null?"typed_position_proof_required":proof.reason,actualRaw,true,snapshot);
            return ready(OperationPlan.Kind.POSITION_ADJUSTMENT,"SET_ABSOLUTE_POSITION_FROM_EXPLICIT_CURRENT_AND_DELTA",actualRaw,snapshot,List.of(new OperationPlan.NativeStep(table,head,position.value,proof.parameterEvidence)),proof.parameterEvidence);
        }
        if(head.toUpperCase(Locale.ROOT).startsWith("TRIGGER:A:LEVEL:"))return trigger(text,language,actualRaw,raw.object,snapshot);
        if(language==SemanticLanguage.EN&&(head.matches("(?i)CH[1-4]:SCALe")||head.equalsIgnoreCase("HORizontal:SCAle"))) {
            if("VERT_ADJUST_SCALE".equals(business)||"HORZ_ADJUST_SCALE".equals(business))return relativeScale(text,business,actualRaw,raw.object,snapshot);
            return scaleStep(text,actualRaw,raw.object,snapshot);
        }
        if(language==SemanticLanguage.ZH) {
            SemanticPolicy.ChineseScaleRequest scale=SemanticPolicy.ChineseScaleRequest.parse(text,snapshot.context);
            if(scale!=null) {
                if(scale.issue!=null)return terminal(scale.status,scale.issue,actualRaw,true,snapshot);
                if(!scale.command.equalsIgnoreCase(head)||!same(scale.value,parameter))return terminal("clarify","model_relative_target_conflict",actualRaw,true,snapshot);
                SemanticPolicy.Result proof=policy.evaluate(text,actualRaw,snapshot.context);
                if(!proof.status.equals("preview"))return terminal(proof.status,proof.reason,actualRaw,true,snapshot);
                return ready(OperationPlan.Kind.SCALE_ADJUSTMENT,"SET_ABSOLUTE_SCALE_FROM_EXPLICIT_CURRENT_AND_FACTOR",actualRaw,snapshot,List.of(new OperationPlan.NativeStep(table,head,scale.value,proof.parameterEvidence)),proof.parameterEvidence);
            }
        }
        return terminal("unsupported","no_existing_typed_stateful_operation_proof",actualRaw,true,snapshot);
    }

    private OperationPlan workflow(String originalText,SemanticLanguage language,String raw,WorkflowEnvelopeV2.Check envelope,StateSnapshot snapshot,String requestId,long generation,long nowNanos) {
        if(snapshot==null)return terminalSchema("clarify","state_snapshot_required",raw,true,snapshot,WorkflowEnvelopeV2.SCHEMA);
        String binding=snapshot.bindingIssue(requestId,generation,nowNanos);
        if(binding!=null)return terminalSchema("clarify",binding,raw,true,snapshot,WorkflowEnvelopeV2.SCHEMA);
        if(!snapshot.context.usable())return terminalSchema(snapshot.context.status,snapshot.context.reason,raw,true,snapshot,WorkflowEnvelopeV2.SCHEMA);
        String text=snapshot.context.semanticInput(originalText);
        if(!originalText.equals(text))return terminalSchema("clarify","embedded_context_not_operation_evidence",raw,true,snapshot,WorkflowEnvelopeV2.SCHEMA);
        if(language!=SemanticLanguage.EN)return terminalSchema("unsupported","workflow_v2_language_not_enabled",raw,true,snapshot,WorkflowEnvelopeV2.SCHEMA);
        String gate=SingleInstructionProtocol.requestGate(text,language);
        if(gate!=null&&!(gate.equals("single_instruction_split_required")&&!EnglishSemanticBridge.hasMultipleOperations(text)&&!EnglishSingleOperationScope.unconsumedSplit(text)&&text.indexOf(';')<0&&text.indexOf('；')<0&&text.indexOf('\n')<0&&text.indexOf('\r')<0))
            return terminalSchema("clarify",gate,raw,true,snapshot,WorkflowEnvelopeV2.SCHEMA);
        if(PcSemanticGuard.prohibitedRequest(text)||EnglishRequestCompiler.prohibited(EnglishRequestCompiler.scopeSurface(text)))
            return terminalSchema("clarify","current_request_cancelled_or_nonexecuting",raw,true,snapshot,WorkflowEnvelopeV2.SCHEMA);
        if(envelope.workflow.equals("measurement_add"))return addWorkflow(text,raw,envelope,snapshot);
        if(envelope.workflow.equals("measurement_remove"))return removeWorkflow(text,raw,envelope,snapshot);
        if(envelope.workflow.equals("measurement_clear_all"))return clear(text,raw,Map.of("intent",WorkflowEnvelopeV2.INTENT,"SCPIcommand","MEASUrement:STATE","SCPIparams","OFF"),snapshot,WorkflowEnvelopeV2.SCHEMA);
        return terminalSchema("reject","workflow_v2_name_invalid",raw,false,snapshot,WorkflowEnvelopeV2.SCHEMA);
    }

    private OperationPlan addWorkflow(String text,String raw,WorkflowEnvelopeV2.Check envelope,StateSnapshot snapshot) {
        if(ExistingMeasurementRequest.unsupportedRmsComponent(text))return terminalSchema("clarify","measurement_ac_component_not_supported_by_active_command_set",raw,true,snapshot,WorkflowEnvelopeV2.SCHEMA);
        if(ExistingMeasurementRequest.cycleRmsRequested(text)&&!"CRMs".equals(envelope.arguments.get("measurement_type")))return terminalSchema("clarify","workflow_v2_request_argument_conflict",raw,true,snapshot,WorkflowEnvelopeV2.SCHEMA);
        Object free=snapshot.values().get("first_free_measurement_slot");
        if(!(free instanceof Number))return terminalSchema("clarify","first_free_measurement_slot_required",raw,true,snapshot,WorkflowEnvelopeV2.SCHEMA);
        int slot=((Number)free).intValue();
        EnglishSemanticBridge.Result expected=new EnglishDerivedPlanBridge(policy,snapshot.values()).compile(text,"MEASURE_ADD");
        if(expected==null||!expected.status.equals("preview"))return terminalSchema(expected==null?"clarify":expected.status,expected==null?"existing_typed_measurement_add_proof_required":expected.reason,raw,true,snapshot,WorkflowEnvelopeV2.SCHEMA);
        boolean source=false,type=false;
        for(Map<String,Object> step:decodeCommands(expected.commands)) {
            String head=String.valueOf(step.get("SCPIcommand"));Object value=step.get("SCPIparams");
            if(head.equals("MEASUrement:MEAS"+slot+":SOURCE1")&&Objects.equals(value,envelope.arguments.get("source")))source=true;
            if(head.equals("MEASUrement:MEAS"+slot+":TYPe")&&Objects.equals(value,envelope.arguments.get("measurement_type")))type=true;
        }
        if(!source||!type)return terminalSchema("clarify","workflow_v2_request_argument_conflict",raw,true,snapshot,WorkflowEnvelopeV2.SCHEMA);
        return add(text,raw,Map.of("intent",WorkflowEnvelopeV2.INTENT,"SCPIcommand","MEASUrement:MEAS"+slot+":TYPe","SCPIparams",envelope.arguments.get("measurement_type")),snapshot,WorkflowEnvelopeV2.SCHEMA);
    }

    private OperationPlan removeWorkflow(String text,String raw,WorkflowEnvelopeV2.Check envelope,StateSnapshot snapshot) {
        ExistingMeasurementRequest resolved=ExistingMeasurementRequest.resolveModelRemoval(text,snapshot.context,table,
                String.valueOf(envelope.arguments.get("source")),String.valueOf(envelope.arguments.get("measurement_type")));
        if(resolved==null)return terminalSchema("clarify","model_measurement_remove_action_not_supported_by_request",raw,true,snapshot,WorkflowEnvelopeV2.SCHEMA);
        if(resolved.issue!=null)return terminalSchema("clarify",resolved.issue,raw,true,snapshot,WorkflowEnvelopeV2.SCHEMA);
        List<Map<String,Object>> proof=evidence(resolved.evidence,snapshot,"model_authored_measurement_remove_source_type_and_unique_verified_active_slot");
        return readySchema(OperationPlan.Kind.MEASUREMENT_REMOVE,"DISABLE_READOUT_KEEP_SLOT_CONFIGURATION",raw,snapshot,
                List.of(new OperationPlan.NativeStep(table,resolved.command,resolved.parameter,proof)),proof,WorkflowEnvelopeV2.SCHEMA);
    }

    private OperationPlan add(String text,String raw,Map<?,?> carrier,StateSnapshot snapshot) {
        return add(text,raw,carrier,snapshot,"primitive_three_field_v1");
    }
    private OperationPlan add(String text,String raw,Map<?,?> carrier,StateSnapshot snapshot,String schema) {
        Map<String,Object> state=snapshot.values();Object free=state.get("first_free_measurement_slot");
        if(!(free instanceof Number))return terminalSchema("clarify","first_free_measurement_slot_required",raw,true,snapshot,schema);
        int slot=((Number)free).intValue();Object global=state.get("measurement_system_state");
        if(global==null)return terminalSchema("clarify","measurement_system_state_required",raw,true,snapshot,schema);
        Map<?,?> known=measurementRow(state,slot);
        if(known!=null&&!"OFF".equals(known.get("state")))return terminalSchema("clarify","free_measurement_slot_state_unknown",raw,true,snapshot,schema);
        EnglishSemanticBridge.Result compiled=new EnglishDerivedPlanBridge(policy,state).compile(text,"MEASURE_ADD");
        if(compiled==null||!compiled.status.equals("preview"))return terminalSchema(compiled==null?"clarify":compiled.status,compiled==null?"existing_typed_measurement_add_proof_required":compiled.reason,raw,true,snapshot,schema);
        String prefix="MEASUrement:MEAS"+slot+":";
        List<Map<String,Object>> full=decodeCommands(compiled.commands);
        Map<String,Object> carrierObject=nativeObject(carrier);
        boolean matches=false;for(Map<String,Object> step:full)if(step.get("SCPIcommand").equals(carrierObject.get("SCPIcommand"))&&same(step.get("SCPIparams"),carrierObject.get("SCPIparams")))matches=true;
        if(!matches||!String.valueOf(carrierObject.get("SCPIcommand")).startsWith(prefix))return terminalSchema("clarify","model_measurement_carrier_conflict",raw,true,snapshot,schema);
        List<Map<String,Object>> proof=evidence(compiled.evidence,snapshot,"existing_EnglishDerivedPlanBridge_measurement_add");
        List<OperationPlan.NativeStep> steps=new ArrayList<>();
        for(Map<String,Object> step:full) {
            String head=String.valueOf(step.get("SCPIcommand"));Object value=step.get("SCPIparams");
            if(head.equals("MEASUrement:STATE")&&"ON".equals(global))continue;
            String field=head.endsWith(":SOURCE1")?"source":head.endsWith(":SOURCE2")?"source2":head.endsWith(":TYPe")?"type":null;
            if(field!=null&&known!=null&&same(value,known.get(field)))continue;
            steps.add(new OperationPlan.NativeStep(table,head,value,proof));
        }
        return readySchema(OperationPlan.Kind.MEASUREMENT_ADD,"ADD_CONFIGURE_AND_ENABLE_READOUT",raw,snapshot,steps,proof,schema);
    }
    private OperationPlan clear(String text,String raw,Map<?,?> carrier,StateSnapshot snapshot) {
        return clear(text,raw,carrier,snapshot,"primitive_three_field_v1");
    }
    private OperationPlan clear(String text,String raw,Map<?,?> carrier,StateSnapshot snapshot,String schema) {
        List<Map<String,Object>> typed=EnglishRequestCompiler.evidence(text);
        // Consume existing action evidence. The compiler also names global switch-off
        // as MEASURE_CLEAN; it must not be silently reinterpreted as slot removal.
        boolean clearAction=false;
        for(Map<String,Object> event:typed)if("request_action".equals(event.get("canonical_field"))&&Set.of("clear","clean","empty","remove","drop","delete").contains(String.valueOf(event.get("normalized_source_text")).toLowerCase(Locale.ROOT)))clearAction=true;
        if(!clearAction)return terminalSchema("unsupported","measurement_global_switch_request_is_not_slot_clear",raw,true,snapshot,schema);
        // Family selection alone is not scope proof. Reuse the existing typed
        // workflow admission, which rejects channel/type-qualified clear requests.
        EnglishSemanticBridge.Result scope=new EnglishDerivedPlanBridge(policy,snapshot.values()).compile(text,"MEASURE_CLEAN");
        if(scope==null||!scope.status.equals("preview"))return terminalSchema(scope==null?"clarify":scope.status,scope==null?"existing_global_measurement_clear_scope_proof_required":scope.reason,raw,true,snapshot,schema);
        if(scope.commands.size()!=1||!scope.commands.get(0).equals(table.qualify("MEASUrement:STATE","OFF").canonical))return terminalSchema("clarify","existing_global_measurement_clear_scope_proof_required",raw,true,snapshot,schema);
        if(!table.canonicalHead(String.valueOf(carrier.get("SCPIcommand"))).equalsIgnoreCase("MEASUrement:STATE")||!"OFF".equals(carrier.get("SCPIparams")))return terminalSchema("clarify","model_measurement_carrier_conflict",raw,true,snapshot,schema);
        Map<Integer,Map<?,?>> inventory=new TreeMap<>();Object rows=snapshot.values().get("measurements");
        if(rows instanceof List)for(Object row:(List<?>)rows)if(row instanceof Map){Map<?,?> r=(Map<?,?>)row;Object index=r.get("slot");if(index instanceof Number)inventory.put(((Number)index).intValue(),r);}
        if(inventory.size()!=8)return terminalSchema("clarify","measurement_inventory_complete_1_to_8_required",raw,true,snapshot,schema);
        for(int n=1;n<=8;n++)if(!inventory.containsKey(n)||!Set.of("ON","OFF").contains(String.valueOf(inventory.get(n).get("state"))))return terminalSchema("clarify","measurement_inventory_complete_1_to_8_required",raw,true,snapshot,schema);
        List<Map<String,Object>> proof=evidence(scope.evidence,snapshot,"existing_EnglishDerivedPlanBridge_global_scope_clear_active_readouts");List<OperationPlan.NativeStep> steps=new ArrayList<>();
        for(int n=1;n<=8;n++)if("ON".equals(inventory.get(n).get("state")))steps.add(new OperationPlan.NativeStep(table,"MEASUrement:MEAS"+n+":STATE","OFF",proof));
        return readySchema(OperationPlan.Kind.MEASUREMENT_CLEAR_ACTIVE,"DISABLE_ACTIVE_READOUTS_KEEP_SLOT_CONFIGURATION",raw,snapshot,steps,proof,schema);
    }
    private OperationPlan scaleStep(String text,String raw,Map<?,?> carrier,StateSnapshot snapshot) {
        EnglishRequestCompiler.Request compiled=EnglishRequestCompiler.compileOnce(text).request;
        List<Map<String,Object>> typed=EnglishRequestCompiler.evidence(text);int direction=0;boolean object=false;
        for(Map<String,Object> event:typed) {
            String field=String.valueOf(event.get("canonical_field")),token=String.valueOf(event.get("normalized_source_text")).toLowerCase(Locale.ROOT);
            if(field.equals("request_action")){if(token.equals("increase"))direction=1;if(token.equals("decrease"))direction=-1;}
            if(field.equals("request_object"))object=true;
        }
        if(direction==0||!object||compiled==null||!compiled.status.equals("clarify"))return terminal("unsupported","existing_typed_adjacent_scale_step_proof_required",raw,true,snapshot);
        if(unconsumedStepOperand(text))return terminal("clarify","adjacent_step_request_has_unconsumed_numeric_operand",raw,true,snapshot);
        if(!Set.of("state_required","missing_value").contains(compiled.reason))return terminal("unsupported","existing_typed_adjacent_scale_step_proof_required",raw,true,snapshot);
        String head=table.canonicalHead(String.valueOf(carrier.get("SCPIcommand")));boolean vertical=head.matches("(?i)CH[1-4]:SCALe");String channel=vertical?head.substring(2,3):"";
        boolean sameProperty=false;
        for(Map<String,Object> event:typed)if("request_object".equals(event.get("canonical_field"))) {
            String token=String.valueOf(event.get("normalized_source_text")).toLowerCase(Locale.ROOT).trim();UnitSourceModel.Evidence unit=units.at(token,0);
            if(vertical&&(Set.of("scale","sensitivity").contains(token)||unit!=null&&unit.end==token.length()&&unit.normalized.equals("V/div")))sameProperty=true;
            if(!vertical&&Set.of("time per division","time scale","horizontal division").contains(token))sameProperty=true;
        }
        if(!sameProperty)return terminal("clarify","model_scale_object_conflict",raw,true,snapshot);
        Set<Integer> addressed=new HashSet<>();Matcher match=EnglishChannelEvidence.PATTERN.matcher(TextSpanMapping.of(text).normalized);
        while(match.find())if(EnglishChannelEvidence.analog(match,text))addressed.add(EnglishChannelEvidence.index(match));
        if(vertical&&(addressed.size()!=1||!addressed.contains(Integer.parseInt(channel)))||!vertical&&!addressed.isEmpty())return terminal("clarify","model_scale_object_conflict",raw,true,snapshot);
        Object current=vertical?mapValue(snapshot.values().get("current_vertical_scale_volts_by_channel"),channel):snapshot.values().get("current_timebase_seconds");
        if(!(current instanceof Number))return terminal("clarify",vertical?"current_vertical_scale_volts_by_channel:CH"+channel:"current_timebase_seconds_required",raw,true,snapshot);
        Object values=vertical?mapValue(snapshot.values().get("verified_vertical_scale_steps_volts_by_channel"),channel):snapshot.values().get("verified_timebase_steps_seconds");
        if(!(values instanceof List))return terminal("clarify",vertical?"verified_vertical_scale_steps_volts_by_channel:CH"+channel:"verified_timebase_steps_seconds_required",raw,true,snapshot);
        List<?> sequence=(List<?>)values;int index=-1;for(int n=0;n<sequence.size();n++)if(same(sequence.get(n),current))index=n;
        if(index<0)return terminal("clarify","current_scale_not_in_verified_step_policy",raw,true,snapshot);
        int next=index+direction;if(next<0||next>=sequence.size())return terminal("clarify","verified_step_policy_boundary_reached",raw,true,snapshot);
        Object target=sequence.get(next);if(!same(target,carrier.get("SCPIparams")))return terminal("clarify","model_relative_target_conflict",raw,true,snapshot);
        List<Map<String,Object>> proof=evidence(typed,snapshot,"existing_request_action_and_explicit_adjacent_fixture_step");
        return ready(OperationPlan.Kind.SCALE_ADJUSTMENT,"SET_ABSOLUTE_SCALE_FROM_EXPLICIT_ADJACENT_FIXTURE_POLICY",raw,snapshot,List.of(new OperationPlan.NativeStep(table,head,target,proof)),proof);
    }
    private OperationPlan relativeScale(String text,String business,String raw,Map<?,?> carrier,StateSnapshot snapshot) {
        EnglishSemanticBridge.Result compiled=new EnglishDerivedPlanBridge(policy,snapshot.values()).compile(text,business);
        if(compiled==null||!compiled.status.equals("preview")||compiled.commands.size()!=1)return terminal(compiled==null?"clarify":compiled.status,compiled==null?"existing_typed_relative_scale_proof_required":compiled.reason,raw,true,snapshot);
        Map<String,Object> wanted=decodeCommands(compiled.commands).get(0),model=nativeObject(carrier);
        if(!wanted.get("SCPIcommand").equals(model.get("SCPIcommand"))||!same(wanted.get("SCPIparams"),model.get("SCPIparams")))return terminal("clarify","model_relative_target_conflict",raw,true,snapshot);
        List<Map<String,Object>> proof=evidence(compiled.evidence,snapshot,"existing_EnglishDerivedPlanBridge_relative_scale");
        return ready(OperationPlan.Kind.SCALE_ADJUSTMENT,"SET_ABSOLUTE_SCALE_FROM_EXPLICIT_CURRENT_AND_FACTOR",raw,snapshot,List.of(new OperationPlan.NativeStep(table,String.valueOf(wanted.get("SCPIcommand")),wanted.get("SCPIparams"),proof)),proof);
    }
    private OperationPlan trigger(String text,SemanticLanguage language,String raw,Map<?,?> carrier,StateSnapshot snapshot) {
        Set<Integer> explicit=new HashSet<>(PcSemanticGuard.analogSources(text));Matcher match=EnglishChannelEvidence.PATTERN.matcher(text);while(match.find())if(EnglishChannelEvidence.analog(match,text))explicit.add(EnglishChannelEvidence.index(match));
        boolean external=ModelLedConstraintGuard.explicitExternalTriggerSource(text);
        String source=external?"EXT":explicit.size()==1?"CH"+explicit.iterator().next():null;
        if(explicit.size()>1||external&&!explicit.isEmpty())return terminal("clarify","trigger_level_source_ambiguous",raw,true,snapshot);
        if(source==null){Object value=snapshot.values().get("current_trigger_source");if(!(value instanceof String))return terminal("clarify","trigger_level_source_required",raw,true,snapshot);source=(String)value;}
        String expected=source.matches("CH[1-4]")?"TRIGger:A:LEVel:"+source:source.equals("EXT")||source.equals("EXT/5")?"TRIGger:A:LEVel:EXT":null;
        if(expected==null)return terminal("clarify","trigger_level_source_has_no_unique_level_leaf",raw,true,snapshot);
        if(!expected.equalsIgnoreCase(table.canonicalHead(String.valueOf(carrier.get("SCPIcommand")))))return terminal("clarify","model_trigger_source_conflict",raw,true,snapshot);
        SemanticPolicy.Result proof=policy.evaluateModelConstraints(text,carrier,language,snapshot.context);
        if(!proof.status.equals("preview"))return terminal(proof.status,proof.reason,raw,true,snapshot);
        List<Map<String,Object>> evidence=evidence(proof.parameterEvidence,snapshot,"shared_model_constraint_trigger_source_and_numeric_proof");
        return ready(OperationPlan.Kind.TRIGGER_LEVEL,"SET_LEVEL_FOR_EXPLICIT_OR_TRUSTED_CURRENT_SOURCE",raw,snapshot,List.of(new OperationPlan.NativeStep(table,expected,carrier.get("SCPIparams"),evidence)),evidence);
    }
    private OperationPlan ready(OperationPlan.Kind kind,String effect,String raw,StateSnapshot snapshot,List<OperationPlan.NativeStep> steps,List<Map<String,Object>> evidence) {
        return readySchema(kind,effect,raw,snapshot,steps,evidence,"primitive_three_field_v1");
    }
    private OperationPlan readySchema(OperationPlan.Kind kind,String effect,String raw,StateSnapshot snapshot,List<OperationPlan.NativeStep> steps,List<Map<String,Object>> evidence,String schema) {
        boolean strictValid=!WorkflowEnvelopeV2.SCHEMA.equals(schema)||WorkflowEnvelopeV2.check(raw,table).valid;
        return new OperationPlan(steps.isEmpty()?"noop":"preview",steps.isEmpty()?"explicit_fixture_already_has_no_active_readouts":"typed_operation_plan_authority_and_sources_checked",kind,effect,raw,strictValid,schema,snapshot,steps,evidence);
    }
    private OperationPlan terminal(String status,String reason,String raw,boolean valid,StateSnapshot snapshot) {
        return terminalSchema(status,reason,raw,valid,snapshot,"primitive_three_field_v1");
    }
    private OperationPlan terminalSchema(String status,String reason,String raw,boolean valid,StateSnapshot snapshot,String schema) {
        if(WorkflowEnvelopeV2.SCHEMA.equals(schema))valid=WorkflowEnvelopeV2.check(raw,table).valid;
        return new OperationPlan(Set.of("clarify","reject","unsupported").contains(status)?status:"clarify",reason,OperationPlan.Kind.UNSUPPORTED,"",raw,valid,schema,snapshot,Collections.emptyList(),Collections.emptyList());
    }
    private static Object mapValue(Object map,String key){return map instanceof Map?((Map<?,?>)map).get(key):null;}
    private static boolean unconsumedStepOperand(String original){
        String surface=EnglishRequestCompiler.scopeSurface(TextSpanMapping.of(original).normalized);StringBuilder remainder=new StringBuilder(surface);
        Matcher channels=EnglishChannelEvidence.PATTERN.matcher(surface);
        while(channels.find())if(EnglishChannelEvidence.analog(channels,surface))for(int n=channels.start();n<channels.end();n++)remainder.setCharAt(n,' ');
        return EXISTING_NUMERIC_OPERAND.matcher(remainder).find();
    }
    private static Map<?,?> measurementRow(Map<String,Object> state,int slot){Object rows=state.get("measurements");if(rows instanceof List)for(Object row:(List<?>)rows)if(row instanceof Map&&((Map<?,?>)row).get("slot") instanceof Number&&((Number)((Map<?,?>)row).get("slot")).intValue()==slot)return (Map<?,?>)row;return null;}
    private static boolean same(Object a,Object b){if(a instanceof Number&&b instanceof Number){try{return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()))==0;}catch(RuntimeException invalid){return false;}}return Objects.equals(a,b);}
    private Map<String,Object> nativeObject(Map<?,?> input){String head=table.canonicalHead(String.valueOf(input.get("SCPIcommand")));return Map.of("intent",input.get("intent"),"SCPIcommand",head,"SCPIparams",input.get("SCPIparams"));}
    private List<Map<String,Object>> decodeCommands(List<String> commands){List<Map<String,Object>> out=new ArrayList<>();for(String wire:commands){String[] split=wire.split(" ",2);ScpiTableEligibility.NarrationDescriptor descriptor=table.describeForNarration(split[0]);Object value=split.length==1?Collections.emptyMap():split[1];if(value instanceof String&&SingleInstructionProtocol.numeric(descriptor.parameterType))value=new BigDecimal((String)value);out.add(Map.of("intent",descriptor.intent.isEmpty()?"TABLE_"+descriptor.kind:descriptor.intent,"SCPIcommand",table.canonicalHead(split[0]),"SCPIparams",value));}return out;}
    private static List<Map<String,Object>> evidence(List<Map<String,Object>> typed,StateSnapshot snapshot,String rule){List<Map<String,Object>> out=new ArrayList<>(typed);Map<String,Object> binding=new LinkedHashMap<>();binding.put("rule_id",rule);binding.put("source_type",snapshot.origin==StateSnapshot.Origin.VERIFIED_INSTRUMENT_QUERY?"EXISTING_TYPED_REQUEST_PROOF_AND_VERIFIED_INSTRUMENT_STATE":"EXISTING_TYPED_REQUEST_PROOF_AND_VALIDATED_EXPLICIT_FIXTURE");binding.put("request_id",snapshot.requestId);binding.put("generation",snapshot.generation);binding.put("snapshot_sha256",snapshot.fingerprint);binding.put("fixture_lineage",snapshot.values());binding.put("source_id",snapshot.sourceId);binding.put("model_generated",false);binding.put("instrument_state_queried",snapshot.origin==StateSnapshot.Origin.VERIFIED_INSTRUMENT_QUERY);out.add(binding);return out;}
}
