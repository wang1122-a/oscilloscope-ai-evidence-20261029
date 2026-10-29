package com.lilliput.ads10000a.voice;

import java.util.*;

/** Strict model-authored workflow envelope. It owns no text grammar or execution capability. */
final class WorkflowEnvelopeV2 {
    static final String INTENT = "WORKFLOW_V2";
    static final String SCHEMA = "workflow_envelope_v2";
    static final Set<String> TOP_FIELDS = Set.of("intent", "schema_version", "workflow", "arguments");

    static final class Check {
        final boolean valid;
        final String violation, workflow;
        final Map<String,Object> arguments;

        private Check(boolean valid, String violation, String workflow, Map<String,Object> arguments) {
            this.valid = valid;
            this.violation = violation;
            this.workflow = workflow;
            this.arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        }
    }

    static Check check(String raw, ScpiTableEligibility table) {
        if (raw == null || table == null) return invalid("not_workflow_v2");
        final Object parsed;
        try { parsed = StrictJson.parse(raw); }
        catch (RuntimeException invalid) { return invalid("not_workflow_v2"); }
        if (!(parsed instanceof Map)) return invalid("not_workflow_v2");
        Map<?,?> top = (Map<?,?>) parsed;
        if (!INTENT.equals(top.get("intent"))) return invalid("not_workflow_v2");
        if (!top.keySet().equals(TOP_FIELDS)) return invalid("workflow_v2_top_fields_invalid");
        Object version = top.get("schema_version");
        if (!(version instanceof Number) || ((Number) version).doubleValue() != 2d) return invalid("workflow_v2_version_invalid");
        if (!(top.get("workflow") instanceof String) || !(top.get("arguments") instanceof Map)) return invalid("workflow_v2_shape_invalid");
        String workflow = (String) top.get("workflow");
        Map<?,?> supplied = (Map<?,?>) top.get("arguments");
        if (hidden(supplied)) return invalid("workflow_v2_hidden_command_invalid");
        Map<String,Object> arguments = new LinkedHashMap<>();
        if (workflow.equals("measurement_add") || workflow.equals("measurement_remove")) {
            if (!supplied.keySet().equals(Set.of("source", "measurement_type"))) return invalid(workflow + "_arguments_invalid");
            Object source = supplied.get("source"), type = supplied.get("measurement_type");
            if (!(source instanceof String) || !(type instanceof String)) return invalid(workflow + "_arguments_invalid");
            String canonicalSource = ((String) source).toUpperCase(Locale.ROOT);
            if (!canonicalSource.matches("CH[1-4]")) return invalid(workflow + "_source_invalid");
            if (table.qualify("MEASUrement:MEAS1:SOURCE1", canonicalSource).status != ScpiTableEligibility.Status.IN_TABLE_SET)
                return invalid(workflow + "_source_invalid");
            ScpiTableEligibility.Decision qualifiedType = table.qualify("MEASUrement:MEAS1:TYPe", type);
            if (qualifiedType.status != ScpiTableEligibility.Status.IN_TABLE_SET)
                return invalid(workflow + "_type_invalid");
            // SCPI keyword case is protocol formatting, not a change of measurement meaning.
            String canonicalType = qualifiedType.canonical.substring(qualifiedType.canonical.indexOf(' ') + 1);
            arguments.put("source", canonicalSource);
            arguments.put("measurement_type", canonicalType);
            return new Check(true, "", workflow, arguments);
        }
        if (workflow.equals("measurement_clear_all")) {
            if (!supplied.keySet().equals(Set.of("target_scope")) || !"all".equals(supplied.get("target_scope")))
                return invalid("measurement_clear_all_scope_invalid");
            arguments.put("target_scope", "all");
            return new Check(true, "", workflow, arguments);
        }
        return invalid("workflow_v2_name_invalid");
    }

    /** Explicit representation/quantity adaptation only. Source and quantity
     * remain model-authored. No request text, Gold, slot choice or default is consumed.
     * ACDCRMS is total RMS (DC retained), proven by this instrument's computation.
     * Strict check() still rejects the non-native enum; AC-only is never aliased. */
    static String normalizeForExecution(String raw, ScpiTableEligibility table) {
        if (check(raw, table).valid) return raw;
        Object parsed;
        try { parsed=StrictJson.parse(raw); } catch(RuntimeException e) { return null; }
        if (!(parsed instanceof Map)) return null;
        Map<?,?> top=declaredLegacyQuantity((Map<?,?>)parsed);
        String originalIntent=String.valueOf(top.get("intent"));
        String legacyIntent=originalIntent.toUpperCase(Locale.ROOT);
        if(top.keySet().equals(Set.of("intent","schema_version","measurements"))){
            Map<?,?> entry=singleMeasurementArguments(top);
            if(entry==null)return null;
            top=Map.of("intent",originalIntent,"schema_version",top.get("schema_version"),"workflow","MEASUrement","arguments",entry);
        }
        boolean nativeEnvelope=INTENT.equals(originalIntent);
        boolean remove=nativeEnvelope?"measurement_remove".equals(top.get("workflow")):"MEASURE_REMOVE".equals(legacyIntent);
        if (!top.keySet().equals(TOP_FIELDS) || !(nativeEnvelope||"MEASURE_ADD".equals(legacyIntent)||remove)
            || !(top.get("schema_version") instanceof Number)
            || ((Number)top.get("schema_version")).doubleValue()!=2d
            || !(nativeEnvelope?Set.of("measurement_add","measurement_remove"):
                 remove?Set.of("MEASUREMENT","MEASURE_REMOVE"):Set.of("MEASUREMENT","MEASURE_ADD")).contains(nativeEnvelope?top.get("workflow"):String.valueOf(top.get("workflow")).toUpperCase(Locale.ROOT))) return null;
        Object supplied=top.get("arguments");
        if (supplied instanceof List) { if(nativeEnvelope||((List<?>)supplied).size()!=1)return null; supplied=((List<?>)supplied).get(0); }
        if (!(supplied instanceof Map))return null;
        Map<?,?> args=(Map<?,?>)supplied;
        boolean canonicalKeys=args.keySet().equals(Set.of("source","measurement_type"));
        if (!canonicalKeys&&(nativeEnvelope||!args.keySet().equals(Set.of("source","type"))))return null;
        Object source=args.get("source"),type=args.get(canonicalKeys?"measurement_type":"type");
        if (!(source instanceof String)||!(type instanceof String)||hidden(supplied))return null;
        if("ACDCRMS".equalsIgnoreCase((String)type))type="RMS";
        String normalized=CompactSemanticProtocol.encode(Map.of("intent",INTENT,"schema_version",2,
            "workflow",remove?"measurement_remove":"measurement_add","arguments",Map.of("source",source,"measurement_type",type)));
        return check(normalized,table).valid?normalized:null;
    }
    static Check checkForExecution(String raw,ScpiTableEligibility table) {
        String normalized=normalizeForExecution(raw,table);
        return normalized==null?invalid("not_valid_model_measurement_carrier"):check(normalized,table);
    }
    static boolean legacyRepresentation(String raw) {
        try {Object value=StrictJson.parse(raw);return value instanceof Map&&Set.of("MEASURE_ADD","MEASURE_REMOVE").contains(String.valueOf(((Map<?,?>)value).get("intent")).toUpperCase(Locale.ROOT));}
        catch(RuntimeException e){return false;}
    }

    /** Presentation only. A single measurement intent with an invalid model carrier
     * is not evidence that the user requested several independent operations. */
    static String invalidCarrierPresentation(String text,String raw,String reason,ScpiTableEligibility table){
        if(text==null||table==null||reason==null||!(reason.toLowerCase(Locale.ROOT).contains("split")||reason.startsWith("single_instruction_")))return null;
        String intent=EnglishRequestCompiler.derivedIntent(text);
        if(!Set.of("MEASURE_ADD","MEASURE_REMOVE").contains(intent==null?"":intent)
                ||EnglishSemanticBridge.hasMultipleOperations(text)||EnglishSingleOperationScope.unconsumedSplit(text)
                ||text.indexOf(';')>=0||text.indexOf('；')>=0||text.indexOf('\n')>=0||text.indexOf('\r')>=0
                ||checkForExecution(raw,table).valid)return null;
        return "I couldn't understand that measurement request. Please try again.";
    }

    static Map<String,Object> quantityAliasEvidence(String raw) {
        try {
            Object parsed=StrictJson.parse(raw);if(!(parsed instanceof Map))return Collections.emptyMap();
            Map<?,?> top=declaredLegacyQuantity((Map<?,?>)parsed);Map<?,?> entry=singleMeasurementArguments(top);
            if(!top.keySet().equals(TOP_FIELDS)&&entry==null)return Collections.emptyMap();
            if(!Set.of(INTENT,"MEASURE_ADD","MEASURE_REMOVE").contains(String.valueOf(top.get("intent")).toUpperCase(Locale.ROOT)))return Collections.emptyMap();
            Object args=entry==null?top.get("arguments"):entry;if(args instanceof List){if(((List<?>)args).size()!=1)return Collections.emptyMap();args=((List<?>)args).get(0);}
            if(!(args instanceof Map))return Collections.emptyMap();Map<?,?> values=(Map<?,?>)args;
            Object type=values.get(values.containsKey("measurement_type")?"measurement_type":"type");
            if(!(type instanceof String)||!"ACDCRMS".equalsIgnoreCase((String)type))return Collections.emptyMap();
            return Map.of("original_type",type,"canonical_type","RMS","normalization_reason","total_rms_retains_dc",
                    "normalization_kind","SEMANTICALLY_EQUIVALENT_QUANTITY_ALIAS",
                    "authority_source","Instrument.apk Tek/l0 RMS->VoltageRms; vertical/l squared samples without DC removal; result/c q.e",
                    "authority_apk_sha256","F7DCA5E75840A703845E78583A29A09C1031D1483B9458D79F2DEDC848BBA487",
                    "model_raw_preserved",true,"counts_as_raw_protocol_gain",false);
        } catch(RuntimeException invalid){return Collections.emptyMap();}
    }

    /** Two observed legacy representations of one model-authored quantity.
     * Only exact declared fields are accepted. This never infers values from text,
     * repairs spelling, discards a slot/command or chooses a catalog entry. */
    private static Map<?,?> declaredLegacyQuantity(Map<?,?> top) {
        String intent=String.valueOf(top.get("intent")).toUpperCase(Locale.ROOT);
        if(!Set.of("MEASURE_ADD","MEASURE_REMOVE").contains(intent))return top;
        Object args=null;
        if(top.keySet().equals(Set.of("intent","schema_version","measurement","arguments"))
                && "MEASUREMENT".equals(String.valueOf(top.get("measurement")).toUpperCase(Locale.ROOT)))
            args=top.get("arguments");
        else if(top.keySet().equals(Set.of("intent","schema_version","measurement","source"))
                && top.get("measurement") instanceof String && top.get("source") instanceof String)
            args=Map.of("source",top.get("source"),"measurement_type",top.get("measurement"));
        if(args==null)return top;
        Map<String,Object> canonical=new LinkedHashMap<>();
        canonical.put("intent",top.get("intent"));canonical.put("schema_version",top.get("schema_version"));
        canonical.put("workflow","MEASUrement");canonical.put("arguments",args);
        return canonical;
    }

    /** One declared model-authored add quantity; no slot, command or extra metadata
     * can be stripped to make an invalid carrier look like a valid operation. */
    private static Map<?,?> singleMeasurementArguments(Map<?,?> top){
        if(!top.keySet().equals(Set.of("intent","schema_version","measurements"))
                ||!"MEASURE_ADD".equals(String.valueOf(top.get("intent")).toUpperCase(Locale.ROOT))
                ||!(top.get("schema_version") instanceof Number)
                ||((Number)top.get("schema_version")).doubleValue()!=2d)return null;
        Object supplied=top.get("measurements");
        if(!(supplied instanceof List)||((List<?>)supplied).size()!=1||!(((List<?>)supplied).get(0) instanceof Map))return null;
        Map<?,?> entry=(Map<?,?>)((List<?>)supplied).get(0);
        return entry.keySet().equals(Set.of("source","measurement_type"))?entry:null;
    }

    private static boolean hidden(Object value) {
        if (value instanceof String) {
            String text = (String) value;
            return text.indexOf(';') >= 0 || text.indexOf('；') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0;
        }
        if (value instanceof Map) for (Object item : ((Map<?,?>) value).values()) if (hidden(item)) return true;
        if (value instanceof List) for (Object item : (List<?>) value) if (hidden(item)) return true;
        return false;
    }

    private static Check invalid(String reason) { return new Check(false, reason, "", Collections.emptyMap()); }
    private WorkflowEnvelopeV2() {}
}
