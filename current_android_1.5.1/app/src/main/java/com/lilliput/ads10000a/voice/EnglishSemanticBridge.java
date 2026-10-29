package com.lilliput.ads10000a.voice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit English text preview. No model, audio, transport or stateful command execution. */
final class EnglishSemanticBridge {
    static final class Result {
        final String status, canonical, reason, backend, rawContract;
        final int stepCount;
        final List<String> commands;
        final List<Map<String, Object>> evidence;
        Result(String status, String canonical, String reason, String backend,
               String rawContract, int stepCount, List<Map<String, Object>> evidence) {
            this.status = status;
            boolean terminal="clarify".equals(status)||"reject".equals(status);
            this.canonical = terminal?"":canonical;
            this.reason = reason;
            this.backend = backend;
            this.rawContract = terminal?terminalContract(status,reason):rawContract;
            this.stepCount = terminal?0:stepCount;
            this.commands = this.canonical.isEmpty() ? Collections.emptyList()
                    : Collections.unmodifiableList(Arrays.asList(this.canonical.split("\n")));
            this.evidence = Collections.unmodifiableList(new ArrayList<>(evidence));
        }
    }

    private static final Pattern ENGLISH_COMMAND = Pattern.compile(
            "(?i)\\b(?:turn|enable|disable|activate|hide|set|change|adjust|move|place|put|query|check|show|read|run|perform|execute|autoset|acquisition|increase|decrease|use|select|choose|configure|should|needs|tell|what|add|remove|take)\\b");
    private static final Pattern CHANNEL = EnglishChannelEvidence.PATTERN;
    private static final Pattern VALUE = Pattern.compile("(?i)\\b(?:to|at)\\s*([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+))\\s*(.*)$");
    private static final Set<String> STEP_KEYS = new HashSet<>(Arrays.asList("intent", "SCPIcommand", "SCPIparams"));
    private static final Set<String> TOP_KEYS = new HashSet<>(Arrays.asList("intent", "steps"));
    private final SemanticPolicy policy;private final EnglishDerivedPlanBridge derived;

    EnglishSemanticBridge(SemanticPolicy policy) { this(policy,Collections.emptyMap()); }
    EnglishSemanticBridge(SemanticPolicy policy,Map<String,Object> fixture) { this.policy=policy;this.derived=new EnglishDerivedPlanBridge(policy,fixture); }

    static boolean looksEnglishCommand(String text) {
        if (text == null || text.matches("(?s).*[\\p{IsHan}].*")) return false;
        return ENGLISH_COMMAND.matcher(text).find();
    }

    Result evaluateText(String text) {
        if (text == null || text.trim().isEmpty() || text.length() > 512)
            return result("reject", "invalid_text", "java_english_rules", "", 0);
        List<Clause> clauses = clauses(text);
        if (clauses.size() > 1) {
            if (clauses.size() > 16) return result("reject", "too_many_steps", "java_english_rules", "", 0);
            List<Object> steps = new ArrayList<>();
            List<String> commands=new ArrayList<>();List<Map<String,Object>> evidence=new ArrayList<>();
            Map<String,Object> plannedState=derived.stateCopy();
            SourceAntecedent antecedent=new SourceAntecedent();String singleIntent=null;
            for (Clause clause : clauses) {
                BoundClause bound=antecedent.bind(clause,text);if(bound==null)return result("clarify","same_utterance_unique_antecedent_required","java_english_rules","",0);
                Result part = new EnglishSemanticBridge(policy,plannedState).evaluateText(bound.expanded);
                if (!"preview".equals(part.status)) return result("unsupported".equals(part.status)?"clarify":part.status,
                        "unsupported".equals(part.status) ? "unsupported_multi_step" : part.reason,
                        "java_english_rules", "", clauses.size());
                Map<?,?> partTop=(Map<?,?>)StrictJson.parse(part.rawContract);List<?> block=(List<?>)partTop.get("steps");
                if(singleIntent==null)singleIntent=String.valueOf(partTop.get("intent"));
                boolean restated=Set.of("VERT_SET_DISPLAY","MEASURE_CLEAN").contains(String.valueOf(partTop.get("intent")))&&block.size()==1&&!steps.isEmpty()&&block.get(0).equals(steps.get(steps.size()-1));
                if(!restated){steps.addAll(block);commands.addAll(part.commands);}
                evidence.addAll(bound.evidence(part.evidence,text,steps.size()-block.size()));
                advancePlannedState(plannedState,block);
                if(steps.size()>64)return result("reject","too_many_steps","java_english_rules","",0);
            }
            if(violatesProtectedTimebase(text,commands))return result("clarify","protected_timebase_constraint_conflict","java_english_rules","",0);
            Map<String,Object> top = new LinkedHashMap<>();
            top.put("intent", steps.size()==1?singleIntent:"MULTI_STEP"); top.put("steps", steps);
            return new Result("preview",String.join("\n",commands),"ordered_plan_validated","java_english_rules",CompactSemanticProtocol.encode(top),steps.size(),evidence);
        }
        String s = EnglishRequestCompiler.scopeSurface(TextSpanMapping.of(text).normalized.trim().toLowerCase(Locale.ROOT));
        Result missing=typedMissingRequest(s);if(missing!=null)return missing;
        if(s.matches("(?s).*\\bwhile\\b.*\\b(?:reset(?:ting)?|turn(?:ing)?|set(?:ting)?|start(?:ing)?|stop(?:ping)?|enabl(?:e|ing)|disabl(?:e|ing)|remov(?:e|ing)|add(?:ing)?|read(?:ing)?|query(?:ing)?)\\b.*"))return result("clarify","unsupported_multi_step","java_english_rules","",0);
        if (prohibited(s))return result("clarify", "prohibited_action_requires_clarification", "java_english_rules", "", 0);
        if (s.matches("(?s).*\\bor\\b.*"))
            return result("clarify", alternativeIssue(s), "java_english_rules", "", 0);
        if(EnglishChannelEvidence.nonAnalog(s))return result("clarify","non_analog_source","java_english_rules","",0);
        String business=EnglishRequestCompiler.derivedIntent(s);
        if("MEASURE_CONFLICT".equals(business))return result("clarify","measurement_operation_conflict","java_english_rules","",0);
        if(business!=null)return derived.compile(text,business);
        EnglishRequestCompiler.Compilation compilation=EnglishRequestCompiler.compileOnce(text);EnglishRequestCompiler.Request compiled=compilation.request;
        if(compiled!=null){if(!"candidate".equals(compiled.status))return result(compiled.status,compiled.reason,"java_english_rules","",0);return rule(text,compiled.top,compiled.family,compiled.command,compiled.parameter,compilation);}
        if(s.matches("(?s).*\\b(?:position|offset)\\b.*")&&EnglishRequestCompiler.action(s)){Result position=derived.compile(text,"VERT_SET_OFFSET");if(position!=null)return position;}
        if (!looksEnglishCommand(s)) return result("unsupported", "english_request_rule_not_covered", "java_english_rules", "", 0);
        if (withoutFractionAnd(s).matches("(?s).*\\b(?:and|then|also|followed\\s+by)\\b.*")
                || s.indexOf(';') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0)
            return result("unsupported", "unsupported_multi_step", "java_english_rules", "", 0);
        if (s.matches("(?s).*\\b(?:increase|decrease|raise|lower|double|halve)\\b.*"))
            return result("clarify", "state_required", "java_english_rules", "", 0);
        String core = s.replaceFirst("[.!?]$", "").trim().replaceFirst("^please\\s+", "");
        if (core.matches("set\\s+(?:the\\s+)?(?:display\\s+)?(?:graticule|grid)\\s+to\\s+(?:crosshair|full|grid|frame)")) {
            String value = core.substring(core.lastIndexOf(' ') + 1);
            return rule(text, "DISPLAY_SET_GRATICULE", "DISPLAYANDXY_set", "DISplay:GRAticule",
                    "crosshair".equals(value) ? "CROSSHair" : "full".equals(value) ? "FULl" : "frame".equals(value) ? "FRAme" : "GRId");
        }
        List<Integer> channels = new ArrayList<>();
        Matcher channel = CHANNEL.matcher(core);
        while (channel.find()) {
            if(!EnglishChannelEvidence.analog(channel,core))continue;
            int number = EnglishChannelEvidence.index(channel);
            if (number < 1 || number > 4) return result("reject", "illegal_channel", "java_english_rules", "", 0);
            if (!channels.contains(number)) channels.add(number);
        }
        if (channels.size() > 1) return result("clarify", "conflicting_channels", "java_english_rules", "", 0);
        if (core.matches("(?:run|perform|execute)\\s+(?:an?\\s+)?autoset")) {
            if (!channels.isEmpty()) return result("clarify", "autoset_channel_conflict", "java_english_rules", "", 0);
            return rule(text, "AUTOSET", "AUTOSET_set", "AUTOSet", "EXECute");
        }
        if (channels.isEmpty()) return core.matches("(?s).*(?:scale|position|channel|\\bch\\b).*")
                ? result("clarify", "missing_channel", "java_english_rules", "", 0)
                : result("unsupported", "english_request_rule_not_covered", "java_english_rules", "", 0);
        int ch = channels.get(0);
        Result positionText = derived.positionText(text,ch);
        if(positionText!=null && core.matches("(?s)(?:set|change|adjust|move|place|put)\\b.*"))return positionText;
        String chWord = "(?:channel|ch)\\s*(?:one|two|three|four|[1-4])";
        if (core.matches("(?:query|check|show|read)\\s+(?:(?:the\\s+)?(?:status|state)\\s+of\\s+)?(?:the\\s+)?" + chWord + "(?:\\s+(?:status|state))?"))
            return rule(text, "ANDROID_EN_CHANNEL_QUERY", "VERTICAL_query", "SELect:CH" + ch + "?", Collections.emptyMap());
        Matcher leading = Pattern.compile("(turn\\s+on|turn\\s+off|enable|disable|activate|deactivate|hide|switch\\s+on|switch\\s+off)\\s+(?:the\\s+)?" + chWord).matcher(core);
        Matcher trailing = Pattern.compile("turn\\s+(?:the\\s+)?" + chWord + "\\s+(on|off)").matcher(core);
        if (leading.matches() || trailing.matches()) {
            String action = leading.matches() ? leading.group(1) : trailing.group(1);
            boolean on = action.equals("on") || action.endsWith(" on") || action.equals("enable") || action.equals("activate");
            return rule(text, "VERT_SET_DISPLAY", "VERTICAL_set", "SELect:CH" + ch, on ? "ON" : "OFF");
        }
        if(core.matches("(?s)(?:set|change|adjust)\\b.*\\b(?:bandwidth|low[- ]pass)\\b.*\\b(?:full|unlimited)\\b.*"))
            return rule(text,"VERT_SET_BANDWIDTH","VERTICAL_set","CH"+ch+":BANdwidth","FULl");
        Matcher set = Pattern.compile("(?:set|change|adjust|move|place|put)\\s+(?:the\\s+)?(?:(?:vertical\\s+)?(?:scale|position|offset|bandwidth|low[- ]pass(?:\\s+bandwidth)?)\\s+of\\s+)?"
                + chWord + "(?:\\s+(?:vertical\\s+)?(?:scale|position|offset|bandwidth|low[- ]pass(?:\\s+bandwidth)?))?\\s+(?:to|at)\\s+"
                + "(" + EnglishNumberWords.VALUE + ")\\s+(.+)").matcher(core);
        if (set.matches()) {
            String unit = set.group(2).trim();
            Double numeric = EnglishNumberWords.parse(set.group(1));
            if (numeric == null || !Double.isFinite(numeric))
                return result("reject", "invalid_number", "java_english_rules", "", 0);
            boolean position = core.contains("position") || core.contains("offset"), scale = core.contains("scale");
            if (position && scale) return result("clarify", "conflicting_object", "java_english_rules", "", 0);
            if(core.contains("bandwidth") || core.contains("low-pass") || core.contains("low pass")){
                double multiplier=unit.matches("(?:mhz|megahertz)")?1e6:unit.matches("(?:khz|kilohertz)")?1e3:unit.matches("(?:hz|hertz)")?1:Double.NaN;
                if(!Double.isFinite(multiplier))return result("clarify","missing_or_conflicting_unit","java_english_rules","",0);
                return rule(text,"VERT_SET_BANDWIDTH","VERTICAL_set","CH"+ch+":BANdwidth",numeric*multiplier);
            }
            if (position) {
                if (!unit.matches("(?:div|division|divisions)"))
                    return result("clarify", "missing_or_conflicting_unit", "java_english_rules", "", 0);
                return rule(text, "VERT_SET_OFFSET", "VERTICAL_set", "CH" + ch + ":POSition", numeric);
            }
            if (unit.matches("(?:v\\s*/\\s*(?:div|division|divisions)|volts?\\s+(?:per|each|every)\\s+(?:div|division|divisions))"))
                return rule(text, "VERT_SET_SCALE", "VERTICAL_set", "CH" + ch + ":SCALe", numeric);
            if (unit.matches("(?:mv\\s*/\\s*(?:div|division|divisions)|millivolts?\\s+(?:per|each|every)\\s+(?:div|division|divisions))"))
                return rule(text, "VERT_SET_SCALE", "VERTICAL_set", "CH" + ch + ":SCALe", numeric / 1000.0);
            return result("clarify", "missing_or_conflicting_unit", "java_english_rules", "", 0);
        }
        return result("unsupported", "english_request_rule_not_covered", "java_english_rules", "", 0);
    }

    Result replayContract(String text, String raw) { return convert(text, raw, "pc_saved_result_replay"); }
    Result modelContract(String text, String raw) { return convert(text, raw, "english_rkllm"); }
    static boolean hasMultipleOperations(String text){return clauses(text).size()>1;}
    static String singleObjectIssue(String text,String command){return objectIssue(text,command);}
    static Result selectResult(Result request,Result model){
        if("unsupported".equals(request.status))return model;
        if("preview".equals(request.status)&&"preview".equals(model.status)&&request.canonical.equals(model.canonical))return model;
        return request;
    }

    private static final class Clause {
        final String text; final int start;
        Clause(String text, int start) { this.text=text; this.start=start; }
    }
    private static List<Clause> clauses(String text) {
        List<Clause> out = new ArrayList<>();
        String actions="(?:turn|set|changing|give|put|switch|enable|disable|run|freeze|halt|begin|resume|stop|zoom|add|remove|clear|query|check|read|show|choose|select|hide|slow|reduce|reducing|magnify|reset)";
        Matcher separator = Pattern.compile("(?i)(?:;\\s*(?:(?:and\\s+)?(?:then|next)\\s+)?(?="+actions+"\\b)|,\\s*(?:and\\s+)?(?:finally\\s+)?(?="+actions+"\\b)|\\s+(?:before(?=\\s+"+actions+"\\b|\\s+resuming\\b)|and\\s+then|then|and(?=\\s+(?:finally\\s+)?"+actions+"\\b)|followed\\s+by)\\s+(?:finally\\s+)?)").matcher(EnglishRequestCompiler.scopeSurface(text));
        int start=0;
        while(separator.find()) {if(!EnglishRequestCompiler.action(text.substring(start,separator.start())))continue;out.add(new Clause(text.substring(start,separator.start()).replaceFirst(",\\s*$",""),start)); start=separator.end(); }
        out.add(new Clause(text.substring(start),start)); return out;
    }
    private static String withoutFractionAnd(String text){return text.replaceAll("(?i)\\band\\s+"+EnglishNumberWords.FRACTION+"\\b"," ");}
    /** A bound surface is internal only; every emitted proof is re-anchored to the original request. */
    private static final class BoundClause {
        final Clause clause;final String expanded;final int pronounStart,pronounLength,replacementLength,antecedentStart,antecedentEnd;
        BoundClause(Clause c,String e,int p,int pronounSize,int length,int a,int b){clause=c;expanded=e;pronounStart=p;pronounLength=pronounSize;replacementLength=length;antecedentStart=a;antecedentEnd=b;}
        List<Map<String,Object>> evidence(List<Map<String,Object>> proofs,String original,int step){
            List<Map<String,Object>> out=new ArrayList<>();
            for(Map<String,Object> proof:proofs){Map<String,Object> e=new LinkedHashMap<>(proof);e.put("step_index",step);e.put("original_asr_text",original);
                if(e.get("source_start") instanceof Number){int a=((Number)e.get("source_start")).intValue(),b=((Number)e.get("source_end")).intValue();
                    if(pronounStart>=0&&a>=pronounStart&&b<=pronounStart+replacementLength){a=antecedentStart;b=antecedentEnd;e.put("rule_id","same_utterance_unique_analog_antecedent");e.put("antecedent_scope","same_utterance_only");}
                    else {int delta=replacementLength-pronounLength;if(pronounStart>=0&&a>=pronounStart+replacementLength){a-=delta;b-=delta;}a+=clause.start;b+=clause.start;}
                    e.put("source_start",a);e.put("source_end",b);e.put("source_text",original.substring(a,b));e.remove("normalized_source_start");e.remove("normalized_source_end");e.put("normalized_input_text",TextSpanMapping.of(original).normalized);
                }out.add(e);
            }
            if(pronounStart>=0){Map<String,Object> binding=new LinkedHashMap<>();binding.put("rule_id","same_utterance_unique_analog_antecedent");binding.put("source_start",clause.start+pronounStart);binding.put("source_end",clause.start+pronounStart+pronounLength);binding.put("source_text",original.substring(clause.start+pronounStart,clause.start+pronounStart+pronounLength));binding.put("original_asr_text",original);binding.put("antecedent_start",antecedentStart);binding.put("antecedent_end",antecedentEnd);binding.put("antecedent_scope","same_utterance_only");binding.put("model_generated",false);binding.put("step_index",step);out.add(binding);}return out;
        }
    }
    private static final class SourceAntecedent {
        final Set<Integer> prior=new HashSet<>();int start=-1,end=-1;
        BoundClause bind(Clause clause,String original){
            TextSpanMapping mapping=TextSpanMapping.of(clause.text);Matcher explicit=CHANNEL.matcher(mapping.normalized);Set<Integer> current=new HashSet<>();
            while(explicit.find()){if(!EnglishChannelEvidence.analog(explicit,mapping.normalized))return null;int c=EnglishChannelEvidence.index(explicit);current.add(c);if(start<0){TextSpanMapping.Span span=mapping.originalSpan(explicit.start(),explicit.end());start=clause.start+span.start;end=clause.start+span.end;}}
            Matcher pronoun=Pattern.compile("(?i)\\b(?:its|it(?!\\s+up\\b))\\b").matcher(clause.text);boolean uses=pronoun.find();int p=uses?pronoun.start():-1,size=uses?pronoun.group().length():0;
            if(uses&&current.isEmpty()&&Pattern.compile("(?i)\\b(?:times?\\s+its\\s+current|factor.*its\\s+current)\\b").matcher(clause.text).find())uses=false;
            if(uses&&Pattern.compile("(?i)\\btrigger(?:ing)?\\b.*\\bits\\s+(?:normal|automatic|single|operating|mode)").matcher(clause.text).find())uses=false;
            Set<Integer> available=new HashSet<>(prior);available.addAll(current);
            if(uses&&(available.size()!=1||pronoun.find()))return null;
            prior.addAll(current);
            if(!uses)return new BoundClause(clause,clause.text,-1,0,0,-1,-1);
            String source=original.substring(start,end);String expanded=clause.text.substring(0,p)+source+clause.text.substring(p+size);
            return new BoundClause(clause,expanded,p,size,source.length(),start,end);
        }
    }

    private Result rule(String text, String topIntent, String stepIntent, String command, Object parameter) {
        return rule(text,topIntent,stepIntent,command,parameter,null);
    }
    private Result rule(String text, String topIntent, String stepIntent, String command, Object parameter,EnglishRequestCompiler.Compilation compilation) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("intent", "DISPLAY_SET_GRATICULE".equals(topIntent)&&"DISPLAYANDXY_set".equals(stepIntent)?"DISPLAY_set":stepIntent); step.put("SCPIcommand", command); step.put("SCPIparams", parameter);
        Map<String, Object> top = new LinkedHashMap<>();
        top.put("intent", topIntent); top.put("steps", Collections.singletonList(step));
        SemanticPolicy.ShortDailyRequest daily=SemanticPolicy.ShortDailyRequest.parse(text);if(daily!=null&&daily.matches(text,command,parameter)){SemanticPolicy.Result checked=policy.evaluateShortDailyRequest(text,CompactSemanticProtocol.encode(step));return new Result(checked.status,checked.scpi,checked.reason,"java_english_rules",CompactSemanticProtocol.encode(top),1,checked.parameterEvidence);}
        return convert(text, CompactSemanticProtocol.encode(top), "java_english_rules",compilation);
    }

    private Result convert(String text, String raw, String backend) {
        return convert(text,raw,backend,null);
    }
    private Result convert(String text, String raw, String backend,EnglishRequestCompiler.Compilation compiledRequest) {
        try {
            if (text == null || text.trim().isEmpty() || raw == null || raw.length() > 8192)
                return result("reject", "invalid_contract_input", backend, raw, 0);
            String surface = EnglishRequestCompiler.scopeSurface(TextSpanMapping.of(text).normalized.toLowerCase(Locale.ROOT));
            if(surface.matches("(?s).*\\bwhile\\b.*\\b(?:reset(?:ting)?|turn(?:ing)?|set(?:ting)?|start(?:ing)?|stop(?:ping)?|enabl(?:e|ing)|disabl(?:e|ing)|remov(?:e|ing)|add(?:ing)?|read(?:ing)?|query(?:ing)?)\\b.*"))return result("clarify","unsupported_multi_step",backend,raw,0);
            if(EnglishChannelEvidence.nonAnalog(surface))return result("clarify","non_analog_source",backend,raw,0);
            Set<Integer> mentionedChannels = new HashSet<>();
            Matcher mentioned = CHANNEL.matcher(surface);
            while (mentioned.find()) {
                if(!EnglishChannelEvidence.analog(mentioned,surface))continue;
                int number = EnglishChannelEvidence.index(mentioned);
                if (number < 1 || number > 4) return result("reject", "illegal_channel", backend, raw, 0);
                mentionedChannels.add(number);
            }
            Object parsed = StrictJson.parse(raw);
            if (!(parsed instanceof Map)) return result("reject", "invalid_contract_shape", backend, raw, 0);
            Map<?, ?> top = (Map<?, ?>) parsed;
            Object intent = top.get("intent"), stepsValue = top.get("steps");
            if (!(intent instanceof String) || !(stepsValue instanceof List)) return result("reject", "invalid_contract_shape", backend, raw, 0);
            List<?> steps = (List<?>) stepsValue;
            EnglishRequestCompiler.Proof requestProof=null;
            if ("CLARIFY".equals(intent)) {
                if (steps.isEmpty() && top.size() == 3 && top.get("missing_slots") instanceof List
                        && !((List<?>) top.get("missing_slots")).isEmpty()
                        && ((List<?>)top.get("missing_slots")).stream().allMatch(item->item instanceof String&&!((String)item).isEmpty()))
                    return terminalFromRequest(text,backend);
                return result("reject", "invalid_contract_shape", backend, raw, 0);
            }
            if ("REJECT".equals(intent)) {
                if (steps.isEmpty() && top.size() == 3 && "invalid_parameter".equals(top.get("reason")))
                    return terminalFromRequest(text,backend);
                return result("reject", "invalid_contract_shape", backend, raw, 0);
            }
            if(prohibited(surface))return result("clarify","prohibited_action_requires_clarification",backend,raw,0);
            Result missing=typedMissingRequest(surface);if(missing!=null)return result(missing.status,missing.reason,backend,raw,0);
            if (surface.matches("(?s).*\\bor\\b.*"))
                return result("clarify", alternativeIssue(surface), backend, raw, 0);
            if (!top.keySet().equals(TOP_KEYS) || steps.isEmpty()) return result("reject", "invalid_contract_shape", backend, raw, steps.size());
            for (Object item : steps) {
                if (!(item instanceof Map)) return result("reject", "invalid_step_shape", backend, raw, steps.size());
                Map<?, ?> step = (Map<?, ?>) item;
                if (!step.keySet().equals(STEP_KEYS) || !(step.get("intent") instanceof String)
                        || !(step.get("SCPIcommand") instanceof String)
                        || !String.valueOf(step.get("SCPIcommand")).matches("[A-Za-z0-9_:*?]+"))
                    return result("reject", "invalid_step_shape", backend, raw, steps.size());
            }
            if(clauses(text).size()>1&&!"MULTI_STEP".equals(intent)){
                Result requested=evaluateText(text);if(!"preview".equals(requested.status))return result("unsupported".equals(requested.status)?"clarify":requested.status,requested.reason,backend,raw,0);
                Map<?,?> expectedTop=(Map<?,?>)StrictJson.parse(requested.rawContract);List<?> expected=(List<?>)expectedTop.get("steps");if(expected.size()!=steps.size()||!expectedTop.get("intent").equals(intent))return result("reject","missing_or_extra_business_step",backend,raw,0);
                for(int i=0;i<steps.size();i++){Map<?,?> a=(Map<?,?>)steps.get(i),b=(Map<?,?>)expected.get(i);if(!a.get("intent").equals(b.get("intent"))||!a.get("SCPIcommand").equals(b.get("SCPIcommand"))||!sameParameter(a.get("SCPIparams"),b.get("SCPIparams")))return result("reject","business_source_type_slot_or_order_mismatch",backend,raw,0);}
                return new Result("preview",requested.canonical,"complete_request_plan_validated",backend,raw,steps.size(),requested.evidence);
            }
            if(!EnglishRequestCompiler.action(surface)&&!String.valueOf(intent).startsWith("MEASURE"))return result("clarify","request_action_required",backend,raw,steps.size());
            if(steps.size()==1&&!Set.of("VERT_ADJUST_SCALE","HORZ_ADJUST_SCALE","MEASURE_QUERY","MEASURE_REMOVE").contains(String.valueOf(intent))){Map<?,?> step=(Map<?,?>)steps.get(0);if(!familyMatches((String)intent,(String)step.get("intent"),(String)step.get("SCPIcommand"),step.get("SCPIparams")))return result("reject","intent_steps_mismatch",backend,raw,1);}
            if(steps.size()==1){
                Map<?,?> only=(Map<?,?>)steps.get(0);String command=(String)only.get("SCPIcommand");
                EnglishRequestCompiler.Compilation current=compiledRequest!=null&&Objects.equals(compiledRequest.text,text)?compiledRequest:EnglishRequestCompiler.compileOnce(text);
                requestProof=current.proof(command,only.get("SCPIparams"));
                if(surface.matches("(?s)^\\s*(?:please\\s+)?(?:query|check|read|what\\s+is|tell\\s+me)\\b.*")&&!command.endsWith("?")&&!String.valueOf(intent).startsWith("MEASURE"))return result("reject","action_query_conflict",backend,raw,1);
                EnglishRequestCompiler.Request request=current.request;
                if(request!=null&&!Set.of("VERT_ADJUST_SCALE","HORZ_ADJUST_SCALE","MEASURE_QUERY","MEASURE_REMOVE").contains(String.valueOf(intent))){
                    if(!"candidate".equals(request.status))return result(request.status,request.reason,backend,raw,1);
                    if(!request.top.equals(intent)||!request.command.equals(command)||!sameParameter(request.parameter,only.get("SCPIparams")))return result("reject","request_candidate_mismatch",backend,raw,1);
                }
                String issue=requestProof==null?objectIssue(surface,command):null;if(issue!=null&&!Set.of("VERT_ADJUST_SCALE","HORZ_ADJUST_SCALE").contains(String.valueOf(intent)))return result("clarify",issue,backend,raw,1);
                if(command.matches("CH[1-4]:BANdwidth")){
                    Object parameter=only.get("SCPIparams");
                    Matcher requested=Pattern.compile("(?i)("+EnglishNumberWords.VALUE+")\\s*(megahertz|kilohertz|hertz|MHz|kHz|Hz)\\b").matcher(text);
                    while(requested.find()){
                        Double number=EnglishNumberWords.parse(requested.group(1));String unit=requested.group(2).toLowerCase(Locale.ROOT);
                        double factor=unit.equals("megahertz")||unit.equals("mhz")?1e6:unit.equals("kilohertz")||unit.equals("khz")?1e3:1;
                        if(number!=null&&number*factor!=20000000)return exactBandwidthReject(backend,raw,text);
                    }
                    if(parameter instanceof Number&&((Number)parameter).doubleValue()!=20000000)return exactBandwidthReject(backend,raw,text);
                }
            }
            Result derivedResult=derived.evaluate(text,top,steps,backend,raw);if(derivedResult!=null)return derivedResult;
            if (steps.size() > 1) {
                List<Clause> parts=clauses(text);
                if (steps.size()>64 || parts.size()<2 || !"MULTI_STEP".equals(intent))
                    return result("unsupported", "unsupported_multi_step", backend, raw, steps.size());
                List<String> commands=new ArrayList<>(); List<Map<String,Object>> evidence=new ArrayList<>();
                Map<String,Object> plannedState=derived.stateCopy();
                SourceAntecedent antecedent=new SourceAntecedent();int offset=0;
                for(int i=0;i<parts.size();i++) {
                    BoundClause bound=antecedent.bind(parts.get(i),text);if(bound==null)return result("clarify","same_utterance_unique_antecedent_required",backend,raw,steps.size());
                    EnglishSemanticBridge scoped=new EnglishSemanticBridge(policy,plannedState);Result request=scoped.evaluateText(bound.expanded);
                    if("clarify".equals(request.status)||"reject".equals(request.status))return result(request.status,request.reason,backend,raw,steps.size());
                    int count="preview".equals(request.status)?request.stepCount:1;
                    if(offset+count>steps.size())return result("reject","missing_or_extra_business_step",backend,raw,steps.size());
                    List<?> block=steps.subList(offset,offset+count);
                    Map<String,Object> one=new LinkedHashMap<>();
                    one.put("intent","preview".equals(request.status)?((Map<?,?>)StrictJson.parse(request.rawContract)).get("intent"):inferTop((Map<?,?>)block.get(0))); one.put("steps",block);
                    Result checked=scoped.convert(bound.expanded,CompactSemanticProtocol.encode(one),backend);
                    if(!"preview".equals(checked.status)) return result(checked.status,checked.reason,backend,raw,steps.size());
                    commands.add(checked.canonical);
                    advancePlannedState(plannedState,block);evidence.addAll(bound.evidence(checked.evidence,text,offset));offset+=count;
                }
                if(offset!=steps.size())return result("reject","missing_or_extra_business_step",backend,raw,steps.size());
                if(violatesProtectedTimebase(text,commands))return result("clarify","protected_timebase_constraint_conflict",backend,raw,0);
                return new Result("preview",String.join("\n",commands),"ordered_plan_validated",backend,raw,steps.size(),evidence);
            }
            if (mentionedChannels.size() > 1) return result("clarify", "conflicting_channels", backend, raw, 0);
            if (withoutFractionAnd(surface).matches("(?s).*\\b(?:and|then|also|followed\\s+by)\\b.*") || surface.contains(";"))
                return result("unsupported", "unsupported_multi_step", backend, raw, steps.size());
            if ("MULTI_STEP".equals(intent)) return result("reject", "intent_steps_mismatch", backend, raw, 1);
            Map<?, ?> step = (Map<?, ?>) steps.get(0);
            if (!familyMatches((String) intent, (String) step.get("intent"), (String) step.get("SCPIcommand"), step.get("SCPIparams")))
                return result("reject", "intent_steps_mismatch", backend, raw, 1);
            String command = (String) step.get("SCPIcommand");
            String objectIssue=requestProof==null?objectIssue(surface,command):null;
            if(objectIssue!=null)return result("clarify",objectIssue,backend,raw,1);
            Object parameter = step.get("SCPIparams");
            // PC authority catalog documented_absolute_min_value: 0.5 ns/div; delay remains signed.
            if("HORizontal:SCAle".equals(command)&&parameter instanceof Number
                    &&((Number)parameter).doubleValue()<5e-10)
                return result("reject","timebase_below_authority_minimum",backend,raw,1);
            boolean saysOn = surface.matches("(?s).*\\b(?:turn\\s+on|enable|activate|switch\\s+on)\\b.*")
                    || surface.matches("(?s).*\\bturn\\b.*\\bon\\b(?!\\s+(?:channel|ch|input|trace)\\s*[0-9a-z]).*");
            boolean saysOff = surface.matches("(?s).*\\b(?:turn\\s+off|disable|deactivate|hide|switch\\s+off)\\b.*")
                    || surface.matches("(?s).*\\bturn\\b.*\\boff\\b.*");
            boolean asksQuery = surface.matches("(?s)^\\s*(?:please\\s+)?(?:query|check|read|is)\\b.*") || surface.matches("(?s)^\\s*(?:please\\s+)?show\\b.*\\b(?:status|state)\\b.*");
            if (saysOn && saysOff) return result("clarify", "conflicting_state", backend, raw, 1);
            if (saysOn && "OFF".equals(parameter) || saysOff && "ON".equals(parameter)
                    || asksQuery && !command.endsWith("?")
                    || (saysOn || saysOff) && command.endsWith("?"))
                return result("reject", "action_query_conflict", backend, raw, 1);
            Map<String,Object> internal=new LinkedHashMap<>();for(Map.Entry<?,?> entry:step.entrySet())internal.put(entry.getKey().toString(),entry.getValue());
            if("DISplay:GRAticule".equals(command)&&"DISPLAY_set".equals(internal.get("intent")))internal.put("intent","DISPLAYANDXY_set");
            SemanticPolicy.Result checked = policy.evaluateEnglish(text, CompactSemanticProtocol.encode(internal),requestProof);
            List<Map<String,Object>> events=new ArrayList<>(checked.parameterEvidence);if("preview".equals(checked.status))events.addAll(EnglishRequestCompiler.evidence(text));if("DISPLAY_set".equals(step.get("intent"))&&"DISPLAYANDXY_set".equals(internal.get("intent"))){Map<String,Object> alias=new LinkedHashMap<>();alias.put("rule_id","EN_DISPLAY_INTENT_PROTOCOL_ALIAS");alias.put("semantic_preserving",true);alias.put("from","DISPLAY_set");alias.put("to","DISPLAYANDXY_set");events.add(alias);}
            if("preview".equals(checked.status)&&violatesProtectedTimebase(text,Collections.singletonList(checked.scpi)))return result("clarify","protected_timebase_constraint_conflict",backend,raw,0);
            return new Result(checked.status, checked.scpi, checked.reason, backend, raw, 1, events);
        } catch (RuntimeException e) {
            return result("reject", "invalid_contract_json", backend, raw, 0);
        }
    }

    private static boolean familyMatches(String top, String stepIntent, String command, Object parameter) {
        if ("DISPLAY_SET_GRATICULE".equals(top)) return ("DISPLAYANDXY_set".equals(stepIntent)||"DISPLAY_set".equals(stepIntent))
                && "DISplay:GRAticule".equals(command);
        String expected=coreTop(command);
        if("MEASURE_REMOVE".equals(top)||"MEASURE_CLEAN".equals(top))
            return top.equals(expected)&&"OFF".equals(parameter);
        if(expected!=null&&expected.equals(top))return true; // shared policy binds exact step intent and parameter type
        if ("VERT_SET_DISPLAY".equals(top)) return "VERTICAL_set".equals(stepIntent)
                && command.matches("SELect:CH[1-4]") && ("ON".equals(parameter) || "OFF".equals(parameter));
        if ("ANDROID_EN_CHANNEL_QUERY".equals(top)) return "VERTICAL_query".equals(stepIntent)
                && command.matches("SELect:CH[1-4]\\?") && parameter instanceof Map && ((Map<?, ?>) parameter).isEmpty();
        if ("VERT_SET_SCALE".equals(top)) return "VERTICAL_set".equals(stepIntent)
                && command.matches("CH[1-4]:SCALe") && parameter instanceof Number;
        if ("VERT_SET_OFFSET".equals(top)) return "VERTICAL_set".equals(stepIntent)
                && command.matches("CH[1-4]:POSition") && parameter instanceof Number;
        return "AUTOSET".equals(top) && "AUTOSET_set".equals(stepIntent)
                && "AUTOSet".equals(command) && "EXECute".equals(parameter);
    }
    private static String objectIssue(String text,String command) {
        String c=command.toUpperCase(Locale.ROOT);
        if(c.startsWith("SELECT:CH")&&text.matches("(?s).*\\b(?:bandwidth|scale|position|offset|coupling|trigger)\\b.*"))return "channel_state_parameter_object_conflict";
        if(c.matches("CH[1-4]:(?:SCALE|POSITION|BANDWIDTH)")){
            boolean position=text.matches("(?s).*\\b(?:position|offset)\\b.*"),scale=text.matches("(?s).*\\b(?:scale|sensitivity)\\b.*"),bandwidth=text.matches("(?s).*\\b(?:bandwidth|low[- ]pass)\\b.*");
            if(c.endsWith(":SCALE")&&!position&&!scale&&!text.matches("(?s).*\\b(?:mv|v|millivolts?|volts?)\\s*(?:/|(?:in\\s+)?(?:per|for\\s+each|each|every))\\s*(?:vertical\\s+)?(?:divisions?|div|squares?)\\b.*")&&!text.matches("(?s).*\\bvertical\\s+(?:square|division)\\b.*\\b(?:stand\\s+for|represents?)\\b.*"))return "missing_semantic_object";
            if(c.endsWith(":POSITION")&&text.matches("(?s).*\\b(?:by|up|down|raise|lower|increase|decrease)\\b.*"))return "relative_position_state_required";
            if(position&&!c.endsWith(":POSITION")||scale&&!c.endsWith(":SCALE")||bandwidth&&!c.endsWith(":BANDWIDTH"))return "vertical_parameter_object_conflict";
            if(c.endsWith(":BANDWIDTH")&&!bandwidth)return "missing_semantic_object";
        }
        if(c.startsWith("SELECT:CH")&&text.matches("(?s).*\\b(?:measurements?|readouts?|rms|peak[- ]to[- ]peak|vpp|frequency|period)\\b.*"))return "measurement_trace_object_conflict";
        if(c.matches("CH[1-4]:COUPLING")&&!text.matches("(?s).*\\b(?:coupling|coupled|couple|input)\\b.*"))return "missing_semantic_object";
        if(text.matches("(?s).*\\b(?:increase|decrease|raise|lower|double|halve)\\b.*"))return "state_required";
        if(c.equals("DISPLAY:GRATICULE")&&(!text.matches("(?s).*\\b(?:grid|graticule|crosshair)\\b.*")||text.matches("(?s).*\\bzoom\\b.*")))return "graticule_zoom_conflict";
        if(c.startsWith("ZOOM:")&&!text.matches("(?s).*\\bzoom\\b.*"))return "graticule_zoom_conflict";
        if(c.startsWith("TRIGGER:")&&!text.matches("(?s).*\\b(?:trigger|triggering)\\b.*"))return "missing_semantic_object";
        if(c.startsWith("HORIZONTAL:")&&!text.matches("(?s).*\\b(?:horizontal|timebase|time\\s+base|delay|record\\s+length|record|recording\\s+depth)\\b.*"))return "missing_semantic_object";
        if(c.startsWith("ACQUIRE:")&&!text.matches("(?s).*\\b(?:acquisition|acquire|acquiring|sampling|sample|capture|run|stop)\\b.*"))return "missing_semantic_object";
        return null;
    }
    /** Terminal candidates supply no action authority; recompile the current request once. */
    private Result terminalFromRequest(String text,String backend){
        String s=TextSpanMapping.of(text).normalized.toLowerCase(Locale.ROOT);Result request;
        if(prohibited(s))request=result("clarify","prohibited_action_requires_clarification","java_english_rules","",0);
        else if(s.matches("(?s).*\\bor\\b.*"))request=result("clarify","conflicting_alternatives","java_english_rules","",0);
        else {String business=EnglishRequestCompiler.derivedIntent(s);if("MEASURE_CONFLICT".equals(business))request=result("clarify","measurement_operation_conflict","java_english_rules","",0);
            else if(business!=null)request=derived.compile(text,business);
            else {EnglishRequestCompiler.Request compiled=EnglishRequestCompiler.compile(text);if(compiled==null)request=result("clarify","request_operation_required","java_english_rules","",0);else if(!"candidate".equals(compiled.status))request=result(compiled.status,compiled.reason,"java_english_rules","",0);else request=rule(text,compiled.top,compiled.family,compiled.command,compiled.parameter);}}
        String finalContract="preview".equals(request.status)?request.rawContract:terminalContract(request.status,request.reason);
        List<Map<String,Object>> evidence=new ArrayList<>(request.evidence);Map<String,Object> proof=new LinkedHashMap<>();proof.put("rule_id","terminal_decision_from_independent_request_evidence");proof.put("original_asr_text",text);proof.put("model_generated",false);proof.put("offline_fixture",derived.stateCopy());proof.put("instrument_state_queried",false);evidence.add(proof);
        return new Result(request.status,request.canonical,request.reason,backend,finalContract,request.stepCount,evidence);
    }
    private static Result exactBandwidthReject(String backend,String raw,String text){
        Map<String,Object> event=new LinkedHashMap<>();event.put("rule_id","exact_requested_bandwidth_not_representable");event.put("source_type","request_semantics_and_authority_rounding");event.put("original_asr_text",text);event.put("authority_syntax","FULl|NR3");event.put("syntax_narrowed",false);event.put("model_generated",false);
        return new Result("reject","","exact_requested_bandwidth_not_representable",backend,terminalContract("reject","exact_requested_bandwidth_not_representable"),1,Collections.singletonList(event));
    }
    private static void advancePlannedState(Map<String,Object> state,List<?> steps){
        for(Object item:steps){
            Map<?,?> step=(Map<?,?>)item;String command=String.valueOf(step.get("SCPIcommand"));Object value=step.get("SCPIparams");
            if(command.equals("MEASUrement:STATE")&&("OFF".equals(value)||"ON".equals(value)))state.put("measurement_system_state",value);
            if(command.equals("AUTOSet")){
                state.remove("current_vertical_scale_volts");state.remove("current_vertical_scale_volts_by_channel");state.remove("current_timebase_seconds");
            }else if(command.matches("CH[1-4]:SCALe")&&value instanceof Number){
                Map<String,Object> byChannel=new LinkedHashMap<>();Object previous=state.get("current_vertical_scale_volts_by_channel");
                if(previous instanceof Map)for(Map.Entry<?,?> entry:((Map<?,?>)previous).entrySet())byChannel.put(String.valueOf(entry.getKey()),entry.getValue());
                byChannel.put(command.substring(2,3),value);state.put("current_vertical_scale_volts_by_channel",byChannel);state.remove("current_vertical_scale_volts");
            }else if(command.equals("HORizontal:SCAle")&&value instanceof Number)state.put("current_timebase_seconds",value);
        }
        for(int i=0;i+3<steps.size();i++){
            Map<?,?> source=(Map<?,?>)steps.get(i);String head=String.valueOf(source.get("SCPIcommand"));
            if(head.matches("MEASUrement:MEAS[1-8]:SOURCE1")){String prefix=head.substring(0,head.length()-7);
                Map<?,?> second=(Map<?,?>)steps.get(i+1);boolean dual=(prefix+"SOURCE2").equals(second.get("SCPIcommand"));int typeIndex=i+(dual?2:1);if(typeIndex+2>=steps.size())continue;Map<?,?> type=(Map<?,?>)steps.get(typeIndex),on=(Map<?,?>)steps.get(typeIndex+1),global=(Map<?,?>)steps.get(typeIndex+2);
                if((prefix+"TYPe").equals(type.get("SCPIcommand"))&&(prefix+"STATE").equals(on.get("SCPIcommand"))&&"ON".equals(on.get("SCPIparams"))&&"MEASUrement:STATE".equals(global.get("SCPIcommand"))&&"ON".equals(global.get("SCPIparams"))){
                    int slot=Integer.parseInt(head.substring(16,17));List<Object> readings=new ArrayList<>();Object previous=state.get("measurements");if(previous instanceof List)for(Object old:(List<?>)previous){if(old instanceof Map&&((Map<?,?>)old).get("slot") instanceof Number&&((Number)((Map<?,?>)old).get("slot")).doubleValue()==slot&&"OFF".equals(((Map<?,?>)old).get("state")))continue;readings.add(old);}Map<String,Object> added=new LinkedHashMap<>();added.put("slot",slot);added.put("source",source.get("SCPIparams"));if(dual)added.put("source2",second.get("SCPIparams"));added.put("type",type.get("SCPIparams"));added.put("state","ON");readings.add(added);state.put("measurements",readings);state.remove("first_free_measurement_slot");
                }
            }
        }
        for(Object item:steps){Map<?,?> step=(Map<?,?>)item;String head=String.valueOf(step.get("SCPIcommand"));if(head.matches("MEASUrement:MEAS[1-8]:STATE")&&"OFF".equals(step.get("SCPIparams"))&&state.get("measurements") instanceof List){int slot=Integer.parseInt(head.substring(16,17));List<Object> changed=new ArrayList<>();for(Object reading:(List<?>)state.get("measurements")){if(reading instanceof Map&&((Map<?,?>)reading).get("slot") instanceof Number&&((Number)((Map<?,?>)reading).get("slot")).doubleValue()==slot){Map<String,Object> row=new LinkedHashMap<>();for(Map.Entry<?,?> entry:((Map<?,?>)reading).entrySet())row.put(String.valueOf(entry.getKey()),entry.getValue());row.put("state","OFF");changed.add(row);}else changed.add(reading);}state.put("measurements",changed);}}
    }
    private static boolean sameParameter(Object a,Object b){return a instanceof Number&&b instanceof Number?Math.abs(((Number)a).doubleValue()-((Number)b).doubleValue())<=Math.max(1e-15,Math.abs(((Number)a).doubleValue())*1e-10):Objects.equals(a,b);}
    private static boolean violatesProtectedTimebase(String text,List<String> commands){if(!EnglishRequestCompiler.timebaseProtected(TextSpanMapping.of(text).normalized))return false;for(String command:commands)if(command.matches("(?i)HORizontal:SCAle\\s+.*"))return true;return false;}
    private static String coreTop(String command) {
        if(command.equals("HORizontal:SCAle?"))return "HORZ_QUERY_SCALE";
        if(command.matches("REF[1-4]"))return "REFERENCE_SAVE";
        if(command.matches("CH[1-4]:COUPling"))return "VERT_SET_COUPLING";
        if(command.matches("CH[1-4]:BANdwidth"))return "VERT_SET_BANDWIDTH";
        if(command.equals("HORizontal:SCAle"))return "HORZ_SET_SCALE";
        if(command.equals("HORizontal:DELay:TIMe"))return "HORZ_SET_DELAY";
        if(command.equals("HORizontal:RECOrdlength"))return "HORZ_SET_REC_LEN";
        if(command.equals("ZOOm:MODe"))return "HORZ_SET_ZOOM";
        if(command.matches("TRIGger:A:LEVel:CH[1-4]"))return "TRIG_SET_LEVEL";
        if(command.equals("TRIGger:A:MODe"))return "TRIG_SET_MODE";
        if(command.equals("ACQuire:STATE"))return "ACQ_SET_STATE";
        if(command.equals("ACQuire:MODe"))return "ACQ_SET_MODE";
        if(command.equals("ACQuire:NUMAVg"))return "ACQ_SET_AVERAGING_COUNT";
        if(command.equals("AUTOSet"))return "AUTOSET";
        if(command.matches("MEASUrement:MEAS[0-9]+:STATE"))return "MEASURE_REMOVE";
        if(command.equals("MEASUrement:STATE"))return "MEASURE_CLEAN";
        return null;
    }

    private static String inferTop(Map<?,?> step) {
        String command=String.valueOf(step.get("SCPIcommand"));
        if(command.matches("SELect:CH[1-4]"))return "VERT_SET_DISPLAY";
        if(command.matches("SELect:CH[1-4]\\?"))return "ANDROID_EN_CHANNEL_QUERY";
        if(command.matches("CH[1-4]:SCALe"))return "VERT_SET_SCALE";
        if(command.matches("CH[1-4]:POSition"))return "VERT_SET_OFFSET";
        if(command.equals("DISplay:GRAticule"))return "DISPLAY_SET_GRATICULE";
        if(command.equals("AUTOSet"))return "AUTOSET";
        if(coreTop(command)!=null)return coreTop(command);
        return "UNSUPPORTED";
    }

    private static int channelNumber(String token) {
        switch (token.toLowerCase(Locale.ROOT)) {
            case "one": return 1; case "two": return 2; case "three": return 3;
            case "four": return 4; case "five": return 5;
            default: try { return Integer.parseInt(token); } catch (NumberFormatException e) { return -1; }
        }
    }

    private static Result result(String status, String reason, String backend, String raw, int count) {
        if(Set.of("clarify","reject").contains(status))raw=terminalContract(status,reason);
        return new Result(status, "", reason, backend, raw, count, Collections.emptyList());
    }
    static String terminalContract(String status,String reason){Map<String,Object> terminal=new LinkedHashMap<>();terminal.put("intent","reject".equals(status)?"REJECT":"CLARIFY");terminal.put("steps",Collections.emptyList());if("reject".equals(status))terminal.put("reason","invalid_parameter");else {String slot=reason.contains("first_free_measurement_slot")?"first_free_measurement_slot":reason.contains("measurement_source2")?"source2":reason.contains("measurement_slot")?"measurement_slot":reason.contains("vertical_position")?"current_vertical_position_divisions":reason.contains("vertical_scale")?"current_vertical_scale_volts":reason.contains("timebase_seconds")?"current_timebase_seconds":reason.contains("measurement_type")?"type":reason.contains("coupling")?"coupling":reason.contains("mode")?"mode":reason.contains("channel")||reason.contains("source")||reason.contains("通道")?"source":reason.contains("unit")||reason.contains("单位")?"unit":reason.contains("value")||reason.contains("number")||reason.contains("factor")||reason.contains("参数")?"value":reason.contains("state")?"state":"operation";terminal.put("missing_slots",Collections.singletonList(slot));}return CompactSemanticProtocol.encode(terminal);}
    private Result typedMissingRequest(String s){
        String declared=EnglishRequestCompiler.missingDataIssue(s);if(declared!=null)return result("clarify",declared,"java_english_rules","",0);
        if(s.matches("(?s).*\\b(?:scale|sensitivity|position|offset|timebase|bandwidth|level)\\b.*\\b(?:have|has)\\s+not\\s+(?:supplied|provided|given|stated)\\s+(?:an?\\s+)?(?:number|value)\\b.*"))return result("clarify","missing_value","java_english_rules","",0);
        if(s.matches("(?s).*\\b(?:position|offset)\\b.*\\b(?:scale|volts[- ]per[- ]division)\\b[^;]*\\b(?:unknown|unavailable)\\b.*"))return result("clarify","current_vertical_scale_volts_required","java_english_rules","",0);
        if(s.matches("(?s).*\\bbandwidth\\b.*\\bwithout\\s+(?:an?\\s+)?target\\b.*")||s.matches("(?s).*\\b(?:timebase|time\\s+base)\\b.*\\bno\\s+time\\s+per\\s+division\\b.*"))return result("clarify","missing_value","java_english_rules","",0);
        int background=s.indexOf(';');
        if(background>=0&&s.matches("(?s).*\\b(?:position|offset)\\b.*")&&s.substring(background+1).matches("(?s)\\s*only\\b[^;]*\\b(?:scale|sensitivity)\\b[^;]*\\bknown\\b\\s*[.!?]?")){
            Set<Integer> requestSources=new HashSet<>(),stateSources=new HashSet<>();Matcher addressed=CHANNEL.matcher(s.substring(0,background));while(addressed.find())requestSources.add(EnglishChannelEvidence.index(addressed));Matcher known=CHANNEL.matcher(s.substring(background+1));while(known.find())stateSources.add(EnglishChannelEvidence.index(known));
            if(requestSources.size()==1&&stateSources.size()==1&&!requestSources.equals(stateSources))return result("clarify","current_vertical_scale_volts_required","java_english_rules","",0);
        }
        if(s.matches("(?s).*\\b(?:position|offset)\\b.*\\b(?:by|up|down|raise|lower|increase|decrease)\\b.*")||s.matches("(?s).*\\b(?:raise|lower|increase|decrease)\\b.*\\b(?:position|offset)\\b.*"))return result("clarify","current_vertical_position_divisions_required","java_english_rules","",0);
        if(s.matches("(?s).*\\b(?:position|offset)\\b.*")&&s.matches("(?s).*\\b(?:no|unknown|without)\\b.*\\b(?:scale|volts[- ]per[- ]division)\\b.*"))return result("clarify","current_vertical_scale_volts_required","java_english_rules","",0);
        if(s.matches("(?s).*\\bmagnification\\b.*\\b(?:unknown|without)\\b.*"))return result("clarify",s.matches("(?s).*\\b(?:horizontal|timebase)\\b.*")?"current_timebase_seconds_required":"current_vertical_scale_volts_required","java_english_rules","",0);
        return null;
    }
    private static String alternativeIssue(String s){
        Set<Integer> sources=new HashSet<>();Matcher channel=CHANNEL.matcher(s);while(channel.find())sources.add(EnglishChannelEvidence.index(channel));if(sources.size()>1)return "conflicting_channels";
        if(s.matches("(?s).*\\b(?:coupling|coupled|couple)\\b.*"))return "coupling_required";
        if(s.matches("(?s).*\\b(?:graticule|grid)\\b.*"))return "graticule_mode_required";
        return s.matches("(?s).*\\b(?:readouts?|measurements?)\\b.*")?"measurement_type_required":s.matches("(?s).*\\b(?:on|off|display)\\b.*")?"display_state_required":s.matches("(?s).*\\b(?:trigger|triggering|mode)\\b.*")?"trigger_mode_required":s.matches("(?s).*\\b(?:mv|v|millivolts?|volts?)\\b.*")?"conflicting_values":"conflicting_channels";
    }
    private static boolean prohibited(String text){return EnglishRequestCompiler.prohibited(text);}
}
