package com.lilliput.ads10000a.voice;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;

/** Per-request provenance only: no history, generated examples or implicit defaults. */
public final class ParameterSourceGuard {
    private final Map<String, Map> entries = new HashMap<>();
    private final Map constraints, aliases, enumAliases, hints, actions;
    private final ScpiTableEligibility table;
    private final UnitSourceModel units;

    public static final class Check {
        public final List<String> missing = new ArrayList<>();
        public final List<Map<String, Object>> evidence = new ArrayList<>();

        public String message() {
            return "请补充" + String.join("、", missing) + "。";
        }
    }

    ParameterSourceGuard(Map root, ScpiTableEligibility t) {
        table = t;
        units = new UnitSourceModel(root);
        for (Object o : (List) ((Map) root.get("catalog")).get("commands")) {
            Map m = (Map) o;
            entries.put(m.get("SCPIcommand").toString().toUpperCase(Locale.ROOT), m);
        }
        constraints = (Map) ((Map) root.get("constraints")).get("commands");
        Map a = (Map) root.get("aliases");
        aliases = (Map) a.getOrDefault("parameter_aliases", Collections.emptyMap());
        enumAliases = (Map) a.getOrDefault("enum_aliases", Collections.emptyMap());
        Map sources = (Map) root.getOrDefault("parameter_sources", Collections.emptyMap());
        hints = (Map) sources.getOrDefault("command_hints", Collections.emptyMap());
        actions = (Map) sources.getOrDefault("actions", Collections.emptyMap());
    }

    private String label(String cmd, String fallback) {
        if(cmd.equalsIgnoreCase("TRIGger:A:MODe"))return "触发模式";
        Object h = hints.get(cmd.toUpperCase(Locale.ROOT));
        return h instanceof Map ? String.valueOf(((Map) h).getOrDefault("label", fallback)) : fallback;
    }

    private static void add(Check c, TextSpanMapping map, String field, int normalizedStart,
                            int normalizedEnd, String value, String ruleId,
                            String sourceType, String canonicalField, boolean modelGenerated) {
        TextSpanMapping.Span span = map.originalSpan(normalizedStart, normalizedEnd);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("field", field);
        e.put("source_text", map.original.substring(span.start, span.end));
        e.put("normalized_source_text", map.normalized.substring(normalizedStart, normalizedEnd));
        e.put("source_start", span.start);
        e.put("source_end", span.end);
        e.put("normalized_source_start", normalizedStart);
        e.put("normalized_source_end", normalizedEnd);
        e.put("original_asr_text", map.original);
        e.put("normalized_input_text", map.normalized);
        e.put("normalized_value", value);
        e.put("rule", ruleId);
        e.put("rule_id", ruleId);
        e.put("source_type", sourceType);
        e.put("canonical_field", canonicalField);
        e.put("model_generated", modelGenerated);
        c.evidence.add(e);
    }

    private static String actionRule(String value) {
        return "ON".equalsIgnoreCase(value) ? "OPEN_TO_ON" : "CLOSE_TO_OFF";
    }

    private static final Pattern NUMBER = Pattern.compile(
            "(?<![A-Za-z0-9,])[+-]?[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]+)?(?![0-9,])|(?i:(?<![A-Za-z0-9])(?:(?:minus|negative|plus|positive)\\s+)?[+-]?(?:[0-9]+\\s+)?[0-9]+\\s*/\\s*[0-9]+(?![0-9./]))|(?:负|[+-])?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?|(?:正|负|[+-])?半(?=毫伏|微伏|伏|格)|[负零〇一二两三四五六七八九十百千万点]+"
                    + "|(?i:(?<![A-Za-z])" + EnglishNumberWords.VALUE + "(?![A-Za-z]))");

    static Double number(String s) {
        if(s.matches("(?:正|负|[+-])?半"))return s.startsWith("负")||s.startsWith("-")?-.5:.5;
        try {
            return Double.valueOf(s);
        } catch (Exception ignored) {
        }
        Double english = EnglishNumberWords.parse(s);
        if (english != null) return english;
        if (s.matches("负(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?")) {
            return -Double.valueOf(s.substring(1));
        }
        try {
            boolean neg = s.startsWith("负");
            if (neg) s = s.substring(1);
            String[] parts = s.split("点", -1);
            long total = 0, section = 0, digit = 0;
            String chars = "零一二三四五六七八九";
            for (char c : parts[0].toCharArray()) {
                if (c == '两') c = '二';
                if (c == '〇') c = '零';
                int d = chars.indexOf(c);
                if (d >= 0) {
                    digit = d;
                    continue;
                }
                int unit = c == '十' ? 10 : c == '百' ? 100 : c == '千' ? 1000 : c == '万' ? 10000 : 0;
                if (unit == 0) return null;
                if (unit == 10000) {
                    section += digit;
                    total += section * unit;
                    section = 0;
                    digit = 0;
                } else {
                    section += (digit == 0 ? 1 : digit) * unit;
                    digit = 0;
                }
            }
            double result = total + section + digit;
            if (parts.length > 1) {
                double factor = .1;
                for (char c : parts[1].toCharArray()) {
                    int d = chars.indexOf(c);
                    if (d < 0) return null;
                    result += d * factor;
                    factor *= .1;
                }
            }
            return neg ? -result : result;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean equal(double a, double b) {
        return Math.abs(a - b) <= Math.max(1e-12, Math.abs(b) * 1e-9);
    }

    private boolean literalSource(Check c, TextSpanMapping map, String text, String cmd,
                                  String value, int index) {
        if (value.isEmpty()) return false;
        // A malformed native carrier may use the action's set label. Its
        // normalized statistics-reset operand still needs independent action
        // and object evidence; the literal spelling "RESet" is not required.
        if(cmd.equalsIgnoreCase("DVM:STATistics")&&value.equalsIgnoreCase("RESet")){
            Matcher current=Pattern.compile("(?i)\\b(?:clear|reset)\\s+(?:the\\s+)?(?:DVM|digital\\s+voltmeter)(?:'s)?\\s+(?:statistics|stats|statistical\\s+results)\\b").matcher(text);
            if(current.find()){add(c,map,"parameter_"+index,current.start(),current.end(),value,"TYPED_STATISTICS_RESET_ACTION_OBJECT","EXPLICIT_CURRENT_UTTERANCE_ACTION_OBJECT","SCPIparams",false);return true;}
        }
        if(PcSemanticGuard.explicitConfigurationDisable(text,cmd,value)){add(c,map,"parameter_"+index,0,text.length(),value,"EXPLICIT_AUTOSET_SUBSETTING_DISABLE","EXPLICIT_CURRENT_UTTERANCE_ACTION_OBJECT","SCPIparams",false);return true;}
        if(cmd.matches("(?i)SELect:CH[1-4]")&&value.equals("ON")&&PcSemanticGuard.naturalDisplay(text)){Set<Integer> selected=PcSemanticGuard.analogSources(text);if(selected.size()==1&&cmd.endsWith(selected.iterator().next().toString())&&!text.matches("(?s).*(?:数字|数学|参考|逻辑).*")){Matcher display=Pattern.compile("出现在屏幕上|亮出来|开启显示|显示").matcher(text);if(display.find()){add(c,map,"parameter_"+index,display.start(),display.end(),value,"COMPLETE_CHINESE_DISPLAY_REQUEST","EXPLICIT_TEXT","SCPIparams",false);return true;}}}
        if(value.matches("CH[1-4]")&&Pattern.compile("\\p{IsHan}").matcher(text).find()&&(cmd.equalsIgnoreCase("DVM:SOUrce")||cmd.equalsIgnoreCase("COUNter:SOURce"))){String object=cmd.toUpperCase(Locale.ROOT).startsWith("DVM:")?"数字电压表|DVM":"计数器|频率计|COUNTER";if(Pattern.compile(object,Pattern.CASE_INSENSITIVE).matcher(text).find()&&Pattern.compile("来源|信源|测量源|作为|源").matcher(text).find()&&Pattern.compile("设置|设为|改用|采用|选择|选|用|作为").matcher(text).find()){List<ChineseNumericNormalizer.Token> addressed=new ArrayList<>();for(ChineseNumericNormalizer.Token t:new ChineseNumericNormalizer().normalize(text).tokens)if(t.type==ChineseNumericNormalizer.TokenType.CHANNEL&&t.valid)addressed.add(t);Set<Integer> channels=new HashSet<>();for(ChineseNumericNormalizer.Token t:addressed)channels.add(t.channelIndex);if(channels.size()==1&&channels.contains(Integer.parseInt(value.substring(2)))){ChineseNumericNormalizer.Token t=addressed.get(0);add(c,map,"parameter_"+index,t.nfkcStart,t.nfkcEnd,value,"TYPED_CHINESE_NAMED_SUBSYSTEM_SOURCE","EXPLICIT_CURRENT_REQUEST_CHANNEL","SCPIparams",false);return true;}}}
        if(cmd.equalsIgnoreCase("TRIGger:A:MODe")&&PcSemanticGuard.dailySetting(map.original)){String target=value.equalsIgnoreCase("AUTO")?"自动":value.equalsIgnoreCase("NORMal")?"正常":value.equalsIgnoreCase("SINGle")?"单次":null;if(target!=null&&text.contains(target)){Matcher m=Pattern.compile(target).matcher(text);if(m.find()){add(c,map,"parameter_"+index,m.start(),m.end(),value,"FULL_SPAN_CHINESE_TRIGGER_MODE_TARGET","EXPLICIT_TEXT","SCPIparams",false);return true;}}}
        if(cmd.equalsIgnoreCase("TRIGger:A:EDGE:COUPling")&&ChineseNaturalCommandParser.edgeCouplingObject(text)){
            Matcher target=ChineseNaturalCommandParser.edgeCouplingSetFrame(text,value);
            if(target!=null){add(c,map,"parameter_"+index,target.start("target"),target.end("target"),value,"FULL_SPAN_CHINESE_EDGE_COUPLING_ENUM","EXPLICIT_TEXT","SCPIparams",false);return true;}
        }
        if(cmd.equalsIgnoreCase("ACQuire:STATE")&&value.equals("STOP")&&PcSemanticGuard.pauseAcquisition(text)){Matcher m=Pattern.compile("暂停").matcher(text);if(m.find()){add(c,map,"parameter_"+index,m.start(),m.end(),value,"FULL_SPAN_CHINESE_PAUSE_ACQUISITION","EXPLICIT_TEXT","SCPIparams",false);return true;}}
        String completeChinese=null;
        if(cmd.equalsIgnoreCase("DISplay:GRAticule")){String target=value.equalsIgnoreCase("FULl")?"完整(?:格线|网格|样式)":value.equalsIgnoreCase("FRAme")?"(?:只有)?(?:边框|框线)":null;if(target!=null&&text.matches("(?:请|帮我|麻烦)?(?:格线|网格)(?:显示)?(?:改成|改为|设为|采用|用)"+target+"(?:[，,]麻烦了)?[。.!！]?"))completeChinese=target;}
        if(cmd.equalsIgnoreCase("TRIGger:A:MODe")){String target=value.equalsIgnoreCase("AUTO")?"自动":value.equalsIgnoreCase("SINGle")?"单次":value.equalsIgnoreCase("NORMal")?"正常":null;if(target!=null&&text.matches("(?:请|帮我|麻烦|我需要)?A触发(?:先)?(?:用)?"+target+"模式[。.!！]?"))completeChinese=target;}
        if(completeChinese!=null){Matcher e=Pattern.compile(completeChinese).matcher(text);if(e.find()){add(c,map,"parameter_"+index,e.start(),e.end(),value,"COMPLETE_CHINESE_ENUM_REQUEST","EXPLICIT_CURRENT_REQUEST_ENUM_OBJECT","SCPIparams",false);return true;}}
        String chinese=chineseEnumSource(text,cmd,value);
        if(chinese!=null){Matcher m=Pattern.compile(chinese,Pattern.CASE_INSENSITIVE).matcher(text);if(m.find()){add(c,map,"parameter_"+index,m.start(),m.end(),value,"TYPED_CHINESE_OBJECT_ACTION_ENUM","EXPLICIT_CURRENT_UTTERANCE_ACTION_OBJECT","SCPIparams",false);return true;}}
        if(cmd.equalsIgnoreCase("TRIGger:A:MODe")&&text.contains("触发")&&!text.contains("采集")&&(Pattern.compile("使用|设置|设定|设一下|设为|改为|改成|切换为|调到|采用|选择|选").matcher(text).find()||text.matches("(?:请|帮我|麻烦)?A?触发模式[，,:：]?(?:正常|自动|单次)[。.!！]?"))){
            String expression=value.equalsIgnoreCase("NORMal")?"正常":value.equalsIgnoreCase("AUTO")?"自动":value.equalsIgnoreCase("SINGle")?"单次":null;
            if(expression!=null){Matcher mode=Pattern.compile(expression).matcher(text);if(mode.find()){add(c,map,"parameter_"+index,mode.start(),mode.end(),value,"EXPLICIT_TRIGGER_MODE_ALIAS","EXPLICIT_CURRENT_UTTERANCE_MODE_OBJECT","SCPIparams",false);return true;}}
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        Matcher literal = Pattern.compile("(?iu)(?<![A-Za-z0-9])" + Pattern.quote(value)
                + "(?![A-Za-z0-9])").matcher(text);
        if (literal.find()) {
            add(c, map, "parameter_" + index, literal.start(), literal.end(), value,
                    "LITERAL_TEXT", "EXPLICIT_TEXT", "SCPIparams", false);
            return true;
        }
        if(cmd.equalsIgnoreCase("DISplay:GRAticule")&&value.equalsIgnoreCase("FULl")||cmd.equalsIgnoreCase("TRIGger:A:MODe")&&value.equalsIgnoreCase("AUTO")){
            Matcher named=Pattern.compile(cmd.equalsIgnoreCase("DISplay:GRAticule")?"(?i)\\bcomplete\\b":"(?i)\\bautomatic\\b").matcher(text);
            if(named.find()){add(c,map,"parameter_"+index,named.start(),named.end(),value,"EXPLICIT_ENGLISH_ENUM_ALIAS","EXPLICIT_TEXT","SCPIparams",false);return true;}
        }
        if(cmd.matches("(?i)CH[1-4]:COUPLING")&&(value.equals("AC")||value.equals("DC"))){
            Matcher current=Pattern.compile(value.equals("AC")?"(?i)\\balternating[- ]current\\b":"(?i)\\bdirect[- ]current\\b").matcher(text);
            if(current.find()&&Pattern.compile("(?i)\\b(?:coupling|coupled|input)\\b").matcher(text).find()){
                add(c,map,"parameter_"+index,current.start(),current.end(),value,"EXPLICIT_ENGLISH_COUPLING_ALIAS","EXPLICIT_TEXT","SCPIparams",false);return true;
            }
        }
        if(cmd.matches("(?i)CH[1-4]:COUPLING")&&value.equals("GND")){Matcher ground=Pattern.compile("(?i)\\bground(?:ed)?\\b").matcher(text);if(ground.find()){add(c,map,"parameter_"+index,ground.start(),ground.end(),value,"EXPLICIT_ENGLISH_GROUND_COUPLING","EXPLICIT_TEXT","SCPIparams",false);return true;}}
        if(cmd.matches("(?i)CH[1-4]:BANDWIDTH")&&value.equalsIgnoreCase("FULl")){Matcher unlimited=Pattern.compile("(?i)\\b(?:unlimited|unrestricted)\\b").matcher(text);if(unlimited.find()){add(c,map,"parameter_"+index,unlimited.start(),unlimited.end(),value,"EXPLICIT_ENGLISH_FULL_BANDWIDTH_ALIAS","EXPLICIT_TEXT","SCPIparams",false);return true;}}
        if(cmd.equalsIgnoreCase("ACQuire:MODe")){String alias=value.equals("HIRes")?"high[- ]resolution|hires":value.equals("PEAK")?"peak(?:\\s+detect(?:ion)?)?":value.equals("SAMPle")?"sample|sampling":value.equals("AVERage")?"average|averaging":null;if(alias!=null){Matcher mode=Pattern.compile("(?i)\\b(?:"+alias+")\\b").matcher(text);if(mode.find()){add(c,map,"parameter_"+index,mode.start(),mode.end(),value,"EXPLICIT_ENGLISH_ACQUISITION_MODE","EXPLICIT_TEXT","SCPIparams",false);return true;}}}
        if(cmd.equalsIgnoreCase("ACQuire:STATE")&&(value.equals("RUN")||value.equals("STOP"))){Matcher action=Pattern.compile(value.equals("RUN")?"(?i)\\b(?:start|run|running|resume|resuming|begin|continue)\\b":"(?i)\\b(?:stop|stopping|freeze|halt)\\b").matcher(text);if(action.find()){add(c,map,"parameter_"+index,action.start(),action.end(),value,"EXPLICIT_ENGLISH_ACQUISITION_ACTION","EXPLICIT_TEXT","SCPIparams",false);return true;}}
        if(cmd.equalsIgnoreCase("AUTOSet")&&value.equals("EXECute")){Matcher auto=Pattern.compile("(?i)\\b(?:auto\\s*set|automatic\\s+waveform\\s+(?:configuration|setup)|waveform\\s+settings\\s+automatically)\\b").matcher(text);if(auto.find()){add(c,map,"parameter_"+index,auto.start(),auto.end(),value,"EXPLICIT_ENGLISH_AUTOSET","EXPLICIT_TEXT","SCPIparams",false);return true;}}
        if (cmd.matches("(?i)SELECT:CH[1-4]") && value.equalsIgnoreCase("ON")) {
            Matcher view=Pattern.compile("(?i)\\bbring\\b.*\\b(?:waveform|trace)\\b.*\\binto\\s+view\\b|\\bvisible\\b|\\bshow\\b.*\\b(?:waveform|trace)\\b").matcher(text);if(view.find()){add(c,map,"parameter_"+index,view.start(),view.end(),value,"EXPLICIT_ANALOG_WAVEFORM_VISIBLE","EXPLICIT_TEXT","SCPIparams",false);return true;}
            Matcher show=Pattern.compile("(?i)\\bshow\\s+(?:the\\s+)?(?:channel|ch)\\s*(?:one|two|three|four|[1-4])\\s+(?:trace|waveform)\\b").matcher(text);
            if(show.find()){add(c,map,"parameter_"+index,show.start(),show.end(),value,"SHOW_TRACE_TO_ON","ACTION_TOKEN_DERIVED","SCPIparams",false);return true;}
        }
        if(cmd.matches("(?i)SELECT:CH[1-4]")&&value.equalsIgnoreCase("OFF")){Matcher hidden=Pattern.compile("(?i)\\b(?:hidden|disabled|mute|remove|stop\\s+showing|off\\s+screen)\\b").matcher(text);if(hidden.find()){add(c,map,"parameter_"+index,hidden.start(),hidden.end(),value,"EXPLICIT_ANALOG_WAVEFORM_HIDDEN","EXPLICIT_TEXT","SCPIparams",false);return true;}}
        String desired = value.equalsIgnoreCase("ON") ? "open" : value.equalsIgnoreCase("OFF") ? "close" : null;
        if (desired != null) {
            Matcher englishAction = Pattern.compile(desired.equals("open")
                    ? "(?i)\\b(?:turn\\s+on|enable|activate|switch\\s+on)\\b"
                    : "(?i)\\b(?:turn\\s+off|disable|deactivate|hide|switch\\s+off)\\b")
                    .matcher(text);
            if (englishAction.find()) {
                add(c, map, "parameter_" + index, englishAction.start(), englishAction.end(), value,
                        actionRule(value), "ACTION_TOKEN_DERIVED", "SCPIparams", false);
                return true;
            }
            for (Object k : actions.keySet()) {
                String source = k.toString();
                if (desired.equals(actions.get(k))) {
                    int start = text.indexOf(source);
                    if (start >= 0) {
                        add(c, map, "parameter_" + index, start, start + source.length(), value,
                                actionRule(value), "ACTION_TOKEN_DERIVED", "SCPIparams", false);
                        return true;
                    }
                }
            }
        }
        if (cmd.equalsIgnoreCase("AUTOSet") && value.equalsIgnoreCase("EXECute")) {
            Matcher autoset = Pattern.compile("(?i)\\b(?:run|perform|execute)\\s+(?:an?\\s+)?autoset\\b")
                    .matcher(text);
            if (autoset.find()) {
                add(c, map, "parameter_" + index, autoset.start(), autoset.end(), value,
                        "EXPLICIT_AUTOSET_ACTION", "EXPLICIT_TEXT", "SCPIparams", false);
                return true;
            }
        }
        for (Map all : new Map[]{aliases, enumAliases}) {
            Object a = all.get(cmd);
            if (a instanceof Map) {
                for (Object k : ((Map) a).keySet()) {
                    String source = k.toString();
                    if (value.equalsIgnoreCase(String.valueOf(((Map) a).get(k)))) {
                        int start = text.indexOf(source);
                        if (start >= 0) {
                            add(c, map, "parameter_" + index, start, start + source.length(), value,
                                    "CONTRACT_ALIAS", "EXPLICIT_TEXT", "SCPIparams", false);
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public Check check(String original, String cmd, Object parameter) {
        Check result = new Check();
        TextSpanMapping spans = TextSpanMapping.of(original);
        String text = PcSemanticGuard.chineseScopeSurface(PcSemanticGuard.chinesePoliteSurface(spans.normalized));
        if(!Pattern.compile("\\p{IsHan}").matcher(text).find())text=EnglishRequestCompiler.scopeSurface(text);
        String h = table.canonicalHead(cmd);
        SemanticPolicy.ShortDailyRequest daily=SemanticPolicy.ShortDailyRequest.parse(original);if(daily!=null&&daily.issue==null&&(!(daily.parameter instanceof Number)||parameter instanceof Number))return checkShortDailyRequest(original,h,parameter,daily);
        if(ChineseNaturalCommandParser.edgeCouplingObject(text)){
            String base=h.replaceFirst("\\?$","");
            if(!base.equalsIgnoreCase("TRIGger:A:EDGE:COUPling")){result.missing.add("边沿触发耦合不能由斜率、通道输入或另一命令对象替代");return result;}
            if(h.endsWith("?")?ChineseNaturalCommandParser.edgeCouplingReadFrame(text)==null
                    :!(parameter instanceof String)||ChineseNaturalCommandParser.edgeCouplingSetFrame(text,(String)parameter)==null){result.missing.add("完整且唯一的边沿触发耦合动作、对象与枚举来源跨度");return result;}
        }
        if(h.matches("(?i)ZOOM:VERTical:SCALe:CH[1-4]\\?")&&Pattern.compile("\\p{IsHan}").matcher(text).find()){
            SemanticPolicy.ShortDailyRequest request=SemanticPolicy.ShortDailyRequest.parse(original);
            if(request==null){result.missing.add("完整且明确的缩放垂直刻度读取对象、通道与跨度");return result;}
            return checkShortDailyRequest(original,h,parameter,request);
        }
        if(h.equalsIgnoreCase("ZOOm:HORizontal:SCALe")&&Pattern.compile("\\p{IsHan}").matcher(text).find())return exactZoomScale(spans,text,h,parameter);
        if(PcSemanticGuard.unconsumedDailyRequest(original)){result.missing.add("完整的当前请求动作/对象/参数跨度");return result;}String dailyRead=PcSemanticGuard.dailyQueryHead(original);if(dailyRead!=null&&!h.equalsIgnoreCase(dailyRead)){result.missing.add("与当前读取请求一致的对象和query head");return result;}
        String exactObject=qualifiedObjectIssue(text,h);if(exactObject!=null){result.missing.add(exactObject);return result;}

        boolean readRequested=Pattern.compile("\\p{IsHan}").matcher(text).find()?PcSemanticGuard.requestedQuery(original):EnglishRequestCompiler.explicitQuery(original);
        if(h.endsWith("?")&&!readRequested){result.missing.add("当前请求明确的读取现值或状态动作，问句语气不能补成查询");return result;}
        if(Pattern.compile("\\p{IsHan}").matcher(text).find()){
            boolean actualQuery=PcSemanticGuard.requestedQuery(original);
            String selected=h.replaceFirst("\\?$","").toUpperCase(Locale.ROOT);String named=selected.startsWith("DVM:")?"数字电压表|DVM":selected.startsWith("COUNTER:")?"计数器|频率计|COUNTER":selected.startsWith("ZOOM:")?"水平放大|放大窗口|水平缩放|缩放窗口|缩放视图":null;if(named!=null&&!Pattern.compile(named,Pattern.CASE_INSENSITIVE).matcher(text).find()){result.missing.add("与当前请求一致的子系统对象");return result;}

            if(h.equalsIgnoreCase("TRIGger:A:MODe")&&text.contains("触发")){Set<String> targets=new HashSet<>();for(String[] a:new String[][]{{"正常","NORMal"},{"自动","AUTO"},{"单次","SINGle"}})if(text.contains(a[0]))targets.add(a[1]);if(targets.size()>1||text.matches("(?s).*(?:尚未(?:决定|选择|确定)|未决定|未选择|不确定|没有确定).*")){result.missing.add("唯一且已选择的触发模式");return result;}}

            if(h.endsWith("?")&&!actualQuery&&(PcSemanticGuard.chinesePoliteSetting(original)||PcSemanticGuard.chineseInformationQuestion(original)||original.matches("(?s).*(?:不要|请勿|不得|不允许|禁止|别).*(?:设置|设为|设在|调整|显示|隐藏|打开|关闭|查询|读取).*"))){result.missing.add("当前请求明确的查询动作；礼貌设置或禁止不能改成查询");return result;}
            if(h.matches("(?i)^REF[0-9]*(?::.*|\\?)")&&original.matches("(?s).*(?:参考输入|参考对象)(?:指的是|是).*模拟.*")&&!original.matches("(?is).*(?:参考波形|\\bREF[0-9]*\\b).*")){result.missing.add("参考波形对象；已声明模拟输入不是REF波形");return result;}
            if(h.matches("(?i)TRIG(?:ger)?:A:.*")&&original.matches("(?is).*(?:B\\s*触发|触发\\s*B).*")){result.missing.add("唯一的A触发对象");return result;}
            if(PcSemanticGuard.chinesePoliteSetting(original)&&h.matches("(?i)(?:CH[1-4]:.*|SELect:CH[1-4]|TRIGger:A:LEVel:CH[1-4])")&&original.matches("(?s).*(?:数字(?:[0-9零〇一二两三四五六七八九十]+(?:号)?)?(?:通道|输入)|数学(?:[0-9零〇一二两三四五六七八九十]+)?(?:波形|通道)|参考波形).*")){result.missing.add("明确的模拟输入来源，不能用数字或参考波形替代");return result;}
        }
        if(h.replaceFirst("\\?$","").matches("(?i)^TRIG(?:ger)?:A:TIMEO(?:ut)?:TIM(?:e)?$")&&Pattern.compile("\\p{IsHan}").matcher(text).find()){
            Matcher objectTime=Pattern.compile("超时(?:触发)?(?:的)?(?:空闲)?时间|超时.{0,8}空闲时间").matcher(text);
            if(!text.contains("触发")||!objectTime.find()||text.matches("(?is).*(?:B\\s*触发|触发\\s*B|脉宽|脉冲宽度|欠幅|超幅).*")){result.missing.add("明确的A超时触发时间对象");return result;}
            boolean query=h.endsWith("?"),requestedQuery=PcSemanticGuard.requestedQuery(text);
            if(query&&!requestedQuery||!query&&(requestedQuery||text.matches("(?s).*(?:不要|禁止|请勿|不得|不允许|已经|曾经|介绍|解释|如何).*" )||text.matches("(?s)^\\s*(?:请|帮我)?别.*")||!text.matches("(?s)^\\s*(?:(?:请|请帮我|帮我|麻烦)\\s*)*(?:把|将|设置|设定|调整|修改|更改|配置).*"))){result.missing.add(query?"明确的超时时间查询动作":"明确的超时时间设置动作");return result;}
            add(result,spans,"request_object",objectTime.start(),objectTime.end(),h,"EXPLICIT_TIMEOUT_TRIGGER_TIME_OBJECT","EXPLICIT_CURRENT_REQUEST_OBJECT","request_object",false);
            result.evidence.get(result.evidence.size()-1).put("authoritative_unit_source","ADS10000A兼容Tek标准SCPI指令表.xlsx:sheet14:row26:G (NR3 seconds)");
        }
        if(Pattern.compile("\\p{IsHan}").matcher(text).find()&&(h.equalsIgnoreCase("TRIGger:A:MODe")||h.equalsIgnoreCase("ACQuire:MODe"))){
            boolean trigger=text.contains("触发"),acquisition=text.contains("采集");if(trigger&&acquisition){result.missing.add("唯一的触发或采集模式对象");return result;}if(h.equalsIgnoreCase("TRIGger:A:MODe")&&acquisition||h.equalsIgnoreCase("ACQuire:MODe")&&trigger){result.missing.add(h.equalsIgnoreCase("TRIGger:A:MODe")?"触发模式对象":"采集模式对象");return result;}
            if(h.equalsIgnoreCase("TRIGger:A:MODe")&&trigger){int modes=0;for(String mode:new String[]{"正常","自动","单次"})if(text.contains(mode))modes++;if(modes>1){result.missing.add("唯一的触发模式参数");return result;}}
        }
        if(!Pattern.compile("\\p{IsHan}").matcher(text).find()&&EnglishChannelEvidence.nonAnalog(text)){result.missing.add("明确模拟输入来源");return result;}
        // A matching quantity cannot justify changing the explicitly named object.
        // English rules share this guard with saved model candidates; Chinese
        // object grammar and the table's NR3 syntax remain unchanged.
        if (h.toUpperCase(Locale.ROOT).matches("CH[1-4]:(?:SCALE|POSITION|BANDWIDTH)")) {
            boolean position = Pattern.compile("(?i)\\b(?:position|offset)\\b").matcher(text).find();
            boolean scale = Pattern.compile("(?i)\\b(?:scale|sensitivity)\\b").matcher(text).find();
            boolean bandwidth = Pattern.compile("(?i)\\b(?:bandwidth|low[- ]pass)\\b").matcher(text).find();
            String expected = h.toUpperCase(Locale.ROOT).endsWith(":POSITION") ? "position"
                    : h.toUpperCase(Locale.ROOT).endsWith(":SCALE") ? "scale" : "bandwidth";
            if (position && !expected.equals("position") || scale && !expected.equals("scale")
                    || bandwidth && !expected.equals("bandwidth")) {
                result.missing.add("与当前话术一致的通道参数对象");
                return result;
            }
        }
        if(h.toUpperCase(Locale.ROOT).matches("SAVE:(?:WAVEFORM:FILEFORMAT|IMAGE:(?:FILEFORMAT|LAYOUT|INKSAVER)|SUFFIX|ASSIGN:TYPE)\\??")){Set<String> objects=SingleInstructionProtocol.saveConfigurationHeads(text);if(objects.size()!=1||!objects.contains(h.replaceFirst("\\?$","").toUpperCase(Locale.ROOT))){result.missing.add("唯一且与当前请求一致的保存配置参数对象");return result;}}
        String numericText = text;
        Matcher object = Pattern.compile("(?i)(CH|INPut|BUS|AFG|SOURce|POSITION|MATH|REF|MEAS|FILTer|WAV)([0-9]+)").matcher(h);
        while (object.find()) {
            String prefix = object.group(1), n = object.group(2);
            String noun = prefix.equalsIgnoreCase("CH") || prefix.equalsIgnoreCase("INPut") ? "通道" : "对象";
            String objectWords = noun.equals("通道") ? "通道|频道"
                    : prefix.equalsIgnoreCase("BUS") ? "总线"
                    : prefix.equalsIgnoreCase("POSITION") ? "光标|位置" : prefix.equalsIgnoreCase("MATH")?"数学波形":prefix.equalsIgnoreCase("REF")?"参考波形":prefix.equalsIgnoreCase("MEAS")?"测量项":prefix.equalsIgnoreCase("FILTer")?"数字滤波器":prefix.equalsIgnoreCase("WAV")?"克隆波形":"信号源|源";
            Pattern op = Pattern.compile("(?i)(?:" + Pattern.quote(prefix) + "|" + objectWords
                    + ")\\s*([0-9]+|[零一二两三四五六七八九十]+)");
            boolean found = false;
            String objectSurface=text;
            if(noun.equals("通道")){
                ChineseNumericNormalizer.Result typed=new ChineseNumericNormalizer().normalize(original);
                for(ChineseNumericNormalizer.Token token:typed.tokens){
                    if(token.type!=ChineseNumericNormalizer.TokenType.CHANNEL||!token.valid||token.channelIndex==null||token.channelIndex!=Integer.parseInt(n))continue;
                    found=true;add(result,spans,noun,token.nfkcStart,token.nfkcEnd,n,token.conversionRule,"EXPLICIT_TEXT","channel",false);
                    char[] remaining=numericText.toCharArray();Arrays.fill(remaining,token.nfkcStart,token.nfkcEnd,' ');numericText=new String(remaining);break;
                }
                objectSurface=PcSemanticGuard.unclaimedAnalogSourceSurface(text);
            }
            Matcher m = op.matcher(objectSurface);
            while (!found&&m.find()) {
                Double v = number(m.group(1));
                if (v != null && equal(v, Double.parseDouble(n))) {
                    found = true;
                    add(result, spans, noun, m.start(), m.end(), n, "EXPLICIT_OBJECT_INDEX",
                            "EXPLICIT_TEXT", noun.equals("通道") ? "channel" : "object", false);
                    numericText = numericText.substring(0, m.start())
                            + m.group().replaceAll(".", " ") + numericText.substring(m.end());
                    break;
                }
            }
            if(!found){String suffix=noun.equals("通道")?"(?:模拟\\s*)?(?:通道|频道)":prefix.equalsIgnoreCase("SOURCE")&&h.startsWith("AFG:")?"(?:路|号)?(?:函数发生器|信号源)":prefix.equalsIgnoreCase("SOURCE")?"信号源":objectWords;Matcher inverted=Pattern.compile("(?:第)?([0-9]+|[零〇一二两三四五六七八九十]+)(?:路|号|条)?(?:"+suffix+")").matcher(text);while(inverted.find()){Double v=number(inverted.group(1));if(v!=null&&equal(v,Double.parseDouble(n))){found=true;add(result,spans,noun,inverted.start(),inverted.end(),n,"EXPLICIT_TYPED_INVERTED_OBJECT","EXPLICIT_TEXT",noun.equals("通道")?"channel":"object",false);numericText=numericText.substring(0,inverted.start())+inverted.group().replaceAll("."," ")+numericText.substring(inverted.end());break;}}}
            if(!found&&noun.equals("通道")&&!original.matches("(?s).*(?:数字|数学|参考|逻辑).*")){Set<Integer> selected=PcSemanticGuard.analogSources(original);if(selected.size()==1&&selected.contains(Integer.parseInt(n))){Matcher analog=Pattern.compile(PcSemanticGuard.ANALOG_SOURCE).matcher(PcSemanticGuard.unclaimedAnalogSourceSurface(text));if(analog.find()){found=true;add(result,spans,noun,analog.start(),analog.end(),n,"EXPLICIT_CHINESE_ANALOG_SOURCE","EXPLICIT_TEXT","channel",false);numericText=numericText.substring(0,analog.start())+analog.group().replaceAll("."," ")+numericText.substring(analog.end());}}}
            if (!found && noun.equals("通道")) {
                Matcher inverted = Pattern.compile(
                        "(?iu)(?<![0-9零〇一二两三四五六七八九十百千万])"
                                + "([0-9]+|[零〇一二两三四五六七八九十百千万]+)\\s*(?:通道|频道)")
                        .matcher(text);
                while (inverted.find()) {
                    Double v = number(inverted.group(1));
                    if (v == null || !equal(v, Double.parseDouble(n))) continue;
                    found = true;
                    add(result, spans, noun, inverted.start(), inverted.end(), n,
                            "EXPLICIT_INVERTED_CHANNEL_INDEX", "EXPLICIT_TEXT", "channel", false);
                    numericText = numericText.substring(0, inverted.start())
                            + inverted.group().replaceAll(".", " ")
                            + numericText.substring(inverted.end());
                    break;
                }
            }
            if (!found && noun.equals("通道")) {
                Matcher english = EnglishChannelEvidence.PATTERN.matcher(text);
                while (english.find()) {
                    if(!EnglishChannelEvidence.analog(english,text))continue;
                    int index=EnglishChannelEvidence.index(english);
                    if (index != Integer.parseInt(n)) continue;
                    found = true;
                    add(result, spans, noun, english.start(), english.end(), n,
                            "EXPLICIT_ENGLISH_CHANNEL_INDEX", "EXPLICIT_TEXT", "channel", false);
                    result.evidence.get(result.evidence.size()-1).put("object_type",EnglishChannelEvidence.objectType(english));
                    numericText = numericText.substring(0, english.start())
                            + english.group().replaceAll(".", " ") + numericText.substring(english.end());
                    break;
                }
            }
            if (!found && noun.equals("通道")) {
                ChineseNumericNormalizer.Result typed =
                        new ChineseNumericNormalizer().normalize(original);
                for (ChineseNumericNormalizer.Token token : typed.tokens) {
                    if (token.type != ChineseNumericNormalizer.TokenType.CHANNEL
                            || !token.valid || token.channelIndex == null
                            || token.channelIndex != Integer.parseInt(n)) continue;
                    found = true;
                    add(result, spans, noun, token.nfkcStart, token.nfkcEnd, n,
                            token.conversionRule, "EXPLICIT_TEXT", "channel", false);
                    numericText = numericText.substring(0, token.nfkcStart)
                            + text.substring(token.nfkcStart, token.nfkcEnd)
                            .replaceAll(".", " ") + numericText.substring(token.nfkcEnd);
                    break;
                }
            }
            if(!found&&prefix.equalsIgnoreCase("MEAS")&&!Pattern.compile("\\p{IsHan}").matcher(text).find()){Set<Integer> slots=explicitMeasurementSlots(text);if(slots.size()==1&&slots.contains(Integer.parseInt(n))){Matcher slot=Pattern.compile("(?i)\\bmeasurement(?:s)?\\s+(?:(?:slot|item)\\s+)?([1-8])\\b").matcher(text);if(slot.find()){found=true;add(result,spans,"object",slot.start(),slot.end(),n,"EXPLICIT_ENGLISH_MEASUREMENT_SLOT","EXPLICIT_TEXT","object",false);}}}
            if (!found) result.missing.add(noun + "编号");
        }

        ScpiTableEligibility.Status kind = table.classify(h);
        if (kind == ScpiTableEligibility.Status.IN_TABLE_QUERY) {if(dailyRead!=null){add(result,spans,"request_action_object",0,spans.original.length(),h,"FULL_SPAN_CHINESE_CURRENT_VALUE_QUERY","EXPLICIT_TEXT","request_object",false);return result;}String issue=chineseQueryObjectIssue(text,h);if(issue!=null)result.missing.add(issue);return result;}
        boolean empty = parameter == null || parameter instanceof Map && ((Map) parameter).isEmpty() || "".equals(parameter);
        if (empty && table.qualify(h, parameter).status == ScpiTableEligibility.Status.IN_TABLE_ACTION) {
            if (!noParameterActionSource(result, spans, text, h)) {
                result.missing.add("动作名称与对象来源");
            }
            return result;
        }
        String name = label(h, "设置参数");
        if (empty) {
            result.missing.add(name);
            return result;
        }
        Map rule = (Map) constraints.get(h);
        if (rule == null) rule = entries.get(h.toUpperCase(Locale.ROOT));
        String unit = units.expected(h);
        if ("DYNAMIC".equals(unit)) {
            Matcher context = Pattern.compile("(?:当前|本次)[^0-9，。]{0,16}单位(?:为|是)\\s*").matcher(text);
            Set<String> explicit = new HashSet<>();
            while (context.find()) {
                UnitSourceModel.Evidence u = units.at(text, context.end());
                if (u != null && u.factor.compareTo(BigDecimal.ONE) == 0) {
                    explicit.add(u.normalized);
                    Map<String, Object> e = u.json(0);
                    addSpanFields(e, spans, u.start, u.end, "EXPLICIT_TEXT",
                            "EXPLICIT_CURRENT_UTTERANCE_UNIT_CONTEXT", "unit", false);
                    result.evidence.add(e);
                }
            }
            if (explicit.size() != 1) {
                result.missing.add(name + "的当前单位（不可沿用历史配置）");
                return result;
            }
            unit = explicit.iterator().next();
        }
        if (parameter instanceof Map || parameter instanceof List) {
            result.missing.add(name + "的明确参数");
            return result;
        }
        if (rule != null && "boolean".equals(rule.get("parameter_type"))) {
            if ("1".equals(String.valueOf(parameter)) || "1.0".equals(String.valueOf(parameter))) parameter = "ON";
            if ("0".equals(String.valueOf(parameter)) || "0.0".equals(String.valueOf(parameter))) parameter = "OFF";
        }
        String[] values = String.valueOf(parameter).split(",", -1);
        int index = 0;
        if (values.length == 1 && !unit.isEmpty()) {
            Set<String> quantities = new HashSet<>();
            Matcher n = NUMBER.matcher(numericText);
            UnitSourceModel.Evidence previous = null;
            boolean dimensionConflict = false;
            while (n.find()) {
                BigDecimal value;
                try {
                    value = UnitSourceModel.decimal(n.group());
                } catch (NumberFormatException ex) {
                    continue;
                }
                UnitSourceModel.Evidence u = units.atForCommand(h, numericText, n.end());
                if (previous != null && previous.end <= n.start()
                        && numericText.substring(previous.end, n.start()).trim()
                        .matches("(?iu)(?:或|或者|和|与|、|及|到|至|or|and)+")
                        && (unit.equals(previous.normalized) || unit.equals(u == null ? "" : u.normalized))) {
                    dimensionConflict = true;
                }
                if (unit.equals(u == null ? "" : u.normalized)) {
                    quantities.add(value.multiply(u.factor).stripTrailingZeros().toPlainString());
                }
                previous = u;
            }
            if (quantities.size() > 1 || dimensionConflict) {
                result.missing.add(name + (dimensionConflict ? "的唯一且匹配的单位" : "的唯一数值（当前话术包含冲突值）"));
                return result;
            }
        }

        for (String part : values) {
            index++;
            String value = part.trim();
            Matcher modelNumber = NUMBER.matcher(value);
            boolean numeric = modelNumber.lookingAt() && number(modelNumber.group()) != null;
            Double wanted = numeric ? number(modelNumber.group()) : null;
            if (!numeric) {
                if (!literalSource(result, spans, text, h, value, index)) {
                    result.missing.add(value.equalsIgnoreCase("ON") || value.equalsIgnoreCase("OFF")
                            ? "开启或关闭动作" : name + "的参数" + (values.length > 1 ? index : ""));
                }
                continue;
            }
            BigDecimal target;
            try {
                target = new BigDecimal(("DYNAMIC".equals(units.expected(h))
                        ? units.normalizeForUnit(unit, value) : units.normalizeModel(h, value)));
            } catch (IllegalArgumentException e) {
                result.missing.add(name + "的正确单位");
                continue;
            }
            boolean found = false, hasNumber = false, conflict = false;
            int usedStart = -1, usedEnd = -1;
            Matcher source = NUMBER.matcher(numericText);
            while (source.find()) {
                if (source.group().equals("一") && numericText.substring(source.end()).startsWith("下")) continue;
                BigDecimal n;
                try {
                    n = UnitSourceModel.decimal(source.group());
                } catch (NumberFormatException e) {
                    continue;
                }
                UnitSourceModel.Evidence u = units.atForCommand(h, numericText, source.end());
                if (n.compareTo(target) == 0) hasNumber = true;
                if (u != null && (units.conflictAfter(numericText, u)
                        || (!unit.isEmpty() && !unit.equals(u.normalized) && n.compareTo(target) == 0))) {
                    conflict = true;
                    continue;
                }
                if (!unit.isEmpty() && (u == null || !unit.equals(u.normalized))) continue;
                if (unit.isEmpty() && u != null) continue;
                BigDecimal actual = u == null ? n : n.multiply(u.factor);
                if (actual.compareTo(target) != 0) continue;
                if (found) continue;
                usedStart = source.start();
                usedEnd = u == null ? source.end() : u.end;
                Map<String, Object> evidence = u == null ? new LinkedHashMap<>() : u.json(index);
                evidence.put("field", "parameter_" + index);
                addSpanFields(evidence, spans, usedStart, usedEnd, "EXPLICIT_TEXT",
                        "CURRENT_UTTERANCE_DECIMAL_UNIT", "SCPIparams", false);
                evidence.put("normalized_value", target.stripTrailingZeros().toPlainString());
                evidence.put("rule", "CURRENT_UTTERANCE_DECIMAL_UNIT");
                evidence.put("rule_id", "CURRENT_UTTERANCE_DECIMAL_UNIT");
                result.evidence.add(evidence);
                found = true;
            }
            if (found && !conflict) {
                numericText = numericText.substring(0, usedStart)
                        + numericText.substring(usedStart, usedEnd).replaceAll(".", " ")
                        + numericText.substring(usedEnd);
            }
            if (conflict || !found) {
                result.missing.add(conflict ? name + "的唯一且匹配的单位"
                        : hasNumber && !unit.isEmpty() ? name + "的单位"
                        : name + (values.length > 1 ? "参数" + index : ""));
            }
        }
        return result;
    }

    /** Fixed s/div source is proven by Tek Zoom!H6 and Lai Zoom!E8/F8, not a changed global unit contract. */
    private Check exactZoomScale(TextSpanMapping spans,String text,String h,Object parameter){Check out=new Check();String n="(?:负|[+-])?(?:[0-9]+(?:\\.[0-9]+)?|[零〇一二两三四五六七八九十百千万点]+)";Matcher m=Pattern.compile("(?s)\\s*(?:请|请帮我|帮我|麻烦)?(?:把|将|设置)?(?:缩放视图|缩放窗口|水平缩放)(?:的)?水平(?:刻度|档位)(?:设为|设置为|调到|调整为|改为|改成)(?<number>"+n+")(?<unit>(?:纳|微|毫)?秒每格)[。.!！?？]?\\s*").matcher(text);
     if(!m.matches()||!(parameter instanceof Number)||PcSemanticGuard.prohibitedRequest(spans.original)){out.missing.add("完整且唯一的缩放水平刻度设置、数值与秒每格单位");return out;}UnitSourceModel.Evidence u=units.at(text,m.end("number"));BigDecimal v=UnitSourceModel.decimal(m.group("number"));if(u==null||!"s/div".equals(u.normalized)||v.multiply(u.factor).compareTo(new BigDecimal(parameter.toString()))!=0){out.missing.add("与请求一致的缩放水平刻度数值及秒每格单位");return out;}
     add(out,spans,"request_object",m.start(),m.end(),h,"FULL_SPAN_ZOOM_HORIZONTAL_SCALE","EXPLICIT_ACTION_OBJECT_PROVENANCE","request_object",false);Map<String,Object> e=u.json(1);addSpanFields(e,spans,m.start("number"),m.end("unit"),"EXPLICIT_TEXT","EXACT_ZOOM_HORIZONTAL_FIXED_S_PER_DIV","SCPIparams",false);e.put("normalized_value",v.multiply(u.factor).stripTrailingZeros().toPlainString());e.put("authoritative_unit_source","Tek SCPI XLSX SHA697D64FFD709CC9146EFCE78FE4BF036B669AE9BBCB36DA612A4D00BA5CDAD85 Zoom!H6; Lai SHA0210479D598DF08D531A66964C598D6C12AAA41CFB5DBE8CA417F3115D5996B5 Zoom!E8/F8 s/div");out.evidence.add(e);return out;
    }
    /** Existing source/quantity/unit verification remains mandatory; typed proof resolves only enum alias drift. */
    Check check(String original,String cmd,Object parameter,EnglishRequestCompiler.Proof proof){
        Check checked=check(original,cmd,parameter);
        EnglishCorrectionScope correction=EnglishCorrectionScope.parse(TextSpanMapping.of(original).normalized);
        if(correction!=null&&correction.inherited&&parameter instanceof Number&&proof!=null&&proof.matches(original,cmd,parameter)){Check replacement=checkCorrectedQuantity(original,cmd,parameter,proof,correction);if(replacement!=null)return replacement;}
        if(proof==null||!proof.matches(original,cmd,parameter))return checked;
        Matcher nominalCount=EnglishRequestCompiler.nominalAcquisitionCount(original);if(nominalCount!=null&&cmd.equals("ACQuire:NUMAVg")&&parameter instanceof Number){Check out=new Check();out.evidence.addAll(proof.evidence());add(out,TextSpanMapping.of(original),"SCPIparams",nominalCount.start("count"),nominalCount.end("count"),String.valueOf(parameter),"FULL_SPAN_ACQUISITION_NOMINAL_AVERAGE_COUNT","EXPLICIT_OBJECT_COUNT","SCPIparams",false);return out;}
        Matcher recordCount=EnglishRequestCompiler.recordCountRequest(original);if(recordCount!=null&&cmd.equals("HORizontal:RECOrdlength")&&"points".equals(units.expected(cmd))&&parameter instanceof Number){Check out=new Check();out.evidence.addAll(proof.evidence());add(out,TextSpanMapping.of(original),"SCPIparams",recordCount.start("count"),recordCount.end("count"),String.valueOf(parameter),"FULL_SPAN_RECORD_LENGTH_TYPED_POINTS_COUNT","EXPLICIT_OBJECT_COUNT","SCPIparams",false);return out;}
        Matcher zero=EnglishRequestCompiler.absoluteZeroPosition(original);if(zero!=null&&cmd.matches("CH[1-4]:POSition")&&parameter instanceof Number){Check out=new Check();for(String missing:checked.missing)if(missing.contains("编号")||missing.contains("对象"))out.missing.add(missing);out.evidence.addAll(checked.evidence);out.evidence.addAll(proof.evidence());add(out,TextSpanMapping.of(original),"SCPIparams",zero.start("quantity"),zero.end("direction"),String.valueOf(parameter),"FULL_SPAN_ABSOLUTE_POSITION_SIGN_FROM_ZERO","EXPLICIT_TEXT","SCPIparams",false);return out;}
        EnglishRequestCompiler.Request reference=EnglishRequestCompiler.referenceSave(original);if(reference!=null&&reference.command.equals(cmd)&&Objects.equals(reference.parameter,parameter)){Check out=new Check();out.evidence.addAll(proof.evidence());out.evidence.addAll(proof.enumSource(original,cmd,parameter));Map<String,Object> e=new LinkedHashMap<>();e.put("source_start",0);e.put("source_end",original.length());e.put("source_text",original);e.put("original_asr_text",original);e.put("canonical_field","reference_slot_and_current_waveform_save_action");e.put("normalized_value",parameter);e.put("derived_command",cmd);e.put("rule_id","full_span_reference_object_front_one_native_save");e.put("model_generated",false);out.evidence.add(e);return out;}
        Matcher beforeUnit=Pattern.compile("(?is)\\s*(?:please\\s+)?(?:set|adjust|change)\\s+(?:the\\s+)?"+EnglishChannelEvidence.EXPRESSION+"\\s+(?<unit>volts?\\s+per\\s+divisions?)\\s+to\\s+(?<number>"+EnglishNumberWords.VALUE+")(?:\\s*(?<voltage>millivolts?|mV|volts?|V))?[.!?]?\\s*").matcher(EnglishRequestCompiler.scopeSurface(original));if(beforeUnit.matches()&&cmd.matches("CH[1-4]:SCALe")&&parameter instanceof Number){Check out=new Check();for(String missing:checked.missing)if(missing.contains("编号")||missing.contains("对象"))out.missing.add(missing);out.evidence.addAll(checked.evidence);out.evidence.addAll(proof.evidence());TextSpanMapping sp=TextSpanMapping.of(original);add(out,sp,"SCPIparams",beforeUnit.start("unit"),beforeUnit.group("voltage")==null?beforeUnit.end("number"):beforeUnit.end("voltage"),String.valueOf(parameter),"FULL_SPAN_UNIT_BEFORE_SCALAR","EXPLICIT_TEXT","SCPIparams",false);return out;}
        Matcher naturalAverage=Pattern.compile("(?is)\\s*(?:please\\s+)?(?:use|choose)\\s+(?<count>"+EnglishNumberWords.VALUE+")\\s+acquisition\\s+averages[.!?]?\\s*").matcher(EnglishRequestCompiler.scopeSurface(original));if(naturalAverage.matches()&&cmd.equals("ACQuire:NUMAVg")&&parameter instanceof Number){Check out=new Check();out.evidence.addAll(proof.evidence());add(out,TextSpanMapping.of(original),"SCPIparams",naturalAverage.start("count"),naturalAverage.end("count"),String.valueOf(parameter),"FULL_SPAN_ACQUISITION_AVERAGE_COUNT","EXPLICIT_TEXT","SCPIparams",false);return out;}
        if(cmd.equalsIgnoreCase("ACQuire:NUMAVg")&&parameter instanceof Number){
            Matcher count=Pattern.compile("(?i)(?<![A-Za-z0-9])("+EnglishNumberWords.VALUE+")\\s+samples?(?![A-Za-z])").matcher(original);
            if(count.find()){Double value=EnglishNumberWords.parse(count.group(1));boolean onlyCount=true;for(String missing:checked.missing)if(missing.contains("对象")||missing.contains("编号")||missing.contains("来源"))onlyCount=false;
                if(value!=null&&equal(value,((Number)parameter).doubleValue())&&!count.find()&&onlyCount){checked.missing.clear();count.reset();count.find();TextSpanMapping spans=TextSpanMapping.of(original);add(checked,spans,"parameter_count",count.start(),count.end(),String.valueOf(parameter),"TYPED_DIMENSIONLESS_AVERAGING_COUNT_SAMPLES","INDEPENDENT_REQUEST_COMPILER_COUNT","SCPIparams",false);}
            }
        }
        if(!checked.missing.isEmpty()&&!(parameter instanceof Number)&&!(parameter instanceof Map)&&!(parameter instanceof List)){
            boolean enumOnly=true;for(String missing:checked.missing)if(missing.contains("编号")||missing.contains("来源")||missing.contains("单位")||missing.contains("对象"))enumOnly=false;
            List<Map<String,Object>> aliases=proof.enumSource(original,cmd,parameter);
            if(enumOnly&&!aliases.isEmpty()){checked.missing.clear();checked.evidence.addAll(aliases);}
        }
        if(checked.missing.isEmpty())checked.evidence.addAll(proof.evidence());
        return checked;
    }

    /** Unit inheritance is confined to an explicit same-utterance "not old; new instead" binding. */
    private Check checkCorrectedQuantity(String original,String cmd,Object parameter,EnglishRequestCompiler.Proof proof,EnglishCorrectionScope correction){
        String normalized=TextSpanMapping.of(original).normalized;UnitSourceModel.Evidence unit=units.atForCommand(cmd,normalized,correction.oldNumberEnd);String expected=units.expected(cmd);Double replacement=EnglishNumberWords.parse(correction.replacement);Matcher channel=EnglishChannelEvidence.PATTERN.matcher(correction.channel);
        if(unit==null||!expected.equals(unit.normalized)||replacement==null||!channel.find()||!EnglishChannelEvidence.analog(channel,correction.channel)||!cmd.equals("CH"+EnglishChannelEvidence.index(channel)+":SCALe")||!"V/div".equals(expected)||!proof.familyMatches("VERTICAL_set")||!equal(((Number)parameter).doubleValue(),java.math.BigDecimal.valueOf(replacement).multiply(unit.factor).doubleValue()))return null;
        Check verified=new Check();TextSpanMapping spans=TextSpanMapping.of(original);add(verified,spans,"SCPIparams",correction.newStart,correction.newEnd,String.valueOf(parameter),"explicit_correction_replacement_value","EXPLICIT_SAME_UTTERANCE_CORRECTION","parameter",false);Map<String,Object> value=verified.evidence.get(verified.evidence.size()-1);TextSpanMapping.Span unitSpan=spans.originalSpan(unit.start,unit.end);value.put("unit_source_start",unitSpan.start);value.put("unit_source_end",unitSpan.end);value.put("unit_source_text",original.substring(unitSpan.start,unitSpan.end));value.put("canonical_unit",expected);value.put("si_multiplier",unit.factor.toPlainString());value.put("unit_inheritance_scope","same_utterance_explicit_replacement_of_negated_quantity");verified.evidence.addAll(proof.evidence());return verified;
    }

    Check checkExistingMeasurement(String original,String cmd,Object parameter,ExistingMeasurementRequest proof){Check result=new Check();ExistingMeasurementRequest independent=proof==null?null:ExistingMeasurementRequest.parse(original,proof.context,table);if(independent==null||!independent.matches(original,cmd,parameter)||!proof.matches(original,cmd,parameter)){result.missing.add("当前请求和明确现有测量槽位的唯一来源");return result;}result.evidence.addAll(independent.evidence);return result;}

    /** Derived quantities are rechecked from current speech operands and explicit fixture lineage. */
    Check checkShortDailyRequest(String original,String cmd,Object parameter,SemanticPolicy.ShortDailyRequest proof){Check out=new Check();SemanticPolicy.ShortDailyRequest independent=SemanticPolicy.ShortDailyRequest.parse(original);boolean magnifierDivision=cmd.equals("MAGNify:WINDow:POSition:VERTical")&&independent!=null&&independent.unit.equals("div");if(independent==null||!independent.matches(original,cmd,parameter)||!proof.matches(original,cmd,parameter)||!cmd.endsWith("?")&&!independent.unit.equals(units.expected(cmd))&&!magnifierDivision||cmd.endsWith("?")&&(!(parameter instanceof Map)||!((Map<?,?>)parameter).isEmpty()||table.classify(cmd)!=ScpiTableEligibility.Status.IN_TABLE_QUERY)){out.missing.add("完整且同对象同单位的日常请求来源");return out;}out.evidence.addAll(independent.evidence);if(magnifierDivision){Map<String,Object> authority=new LinkedHashMap<>();authority.put("rule_id","magnifier_window_vertical_position_documented_div_source");authority.put("authority_source","SCPI_TABLE_AUTHORITY:ADS10000A兼容Tek标准SCPI指令表.xlsx:sheet7:row6");authority.put("authority_unit","div");authority.put("authority_parameter_whitelist_unchanged",true);authority.put("model_generated",false);out.evidence.add(authority);}return out;}

    SemanticPolicy.PositionRequest positionRequest(String original,ValidatedExplicitContext context){return SemanticPolicy.PositionRequest.parse(original,context,units);}
    Check checkPositionRequest(String original,String cmd,Object parameter,SemanticPolicy.PositionRequest proof){
        Check out=new Check();SemanticPolicy.PositionRequest independent=SemanticPolicy.PositionRequest.parse(original,proof.context,units);
        if(independent==null||independent.issue!=null||!independent.matches(original,cmd,parameter)||!proof.matches(original,cmd,parameter)||!"div".equals(units.expected(cmd))){out.missing.add(independent!=null&&independent.issue!=null?independent.issue:"位置参数的独立请求与权威格单位来源");return out;}
        for(Map<String,Object> e:independent.evidence){int a=((Number)e.get("source_start")).intValue(),b=((Number)e.get("source_end")).intValue();if(a<0||b<a||b>original.length()||!original.substring(a,b).equals(e.get("source_text"))||!Boolean.FALSE.equals(e.get("model_generated"))){out.missing.add("位置请求的完整原始来源跨度");return out;}}
        out.evidence.addAll(independent.evidence);Map<String,Object> audit=new LinkedHashMap<>();audit.put("validation_layer","ParameterSourceGuard");audit.put("rule_id",independent.relative?"position_delta_same_channel_fixture_recomputed":"absolute_zero_anchor_direction_distance_recomputed");audit.put("original_asr_text",original);audit.put("addressed_channel",independent.channel);audit.put("relative",independent.relative);audit.put("current_value",independent.current);audit.put("delta_divisions",independent.delta);audit.put("normalized_value",independent.value);audit.put("canonical_unit","div");audit.put("fixture_lineage",independent.relative?independent.context.snapshot():Collections.emptyMap());audit.put("model_generated",false);audit.put("instrument_state_queried",false);out.evidence.add(audit);return out;
    }

    Check checkDerivedChinese(String original,String family,String cmd,Object parameter,SemanticPolicy.ChineseScaleRequest proof){
        Check out=new Check();SemanticPolicy.ChineseScaleRequest independent=proof==null?null:SemanticPolicy.ChineseScaleRequest.parse(original,proof.context);
        if(independent==null||independent.issue!=null||!independent.context.usable()||!independent.matches(original,family,cmd,parameter)||!proof.matches(original,family,cmd,parameter)){out.missing.add(independent!=null&&independent.issue!=null?independent.issue:"当前请求与显式状态的相对刻度来源");return out;}
        String expected=independent.channel>0?"V/div":"s/div";if(!expected.equals(units.expected(cmd))){out.missing.add("相对刻度的权威单位");return out;}
        for(Map<String,Object> e:independent.evidence){int a=((Number)e.get("source_start")).intValue(),b=((Number)e.get("source_end")).intValue();if(a<0||b<a||b>original.length()||!original.substring(a,b).equals(e.get("source_text"))||!Boolean.FALSE.equals(e.get("model_generated"))){out.missing.add("当前请求的原始来源跨度");return out;}}
        out.evidence.addAll(independent.evidence);Map<String,Object> audit=new LinkedHashMap<>();audit.put("validation_layer","ParameterSourceGuard");audit.put("rule_id","Chinese_relative_scale_explicit_context_recomputed");audit.put("original_asr_text",original);audit.put("source_type","INDEPENDENT_REQUEST_AND_VALIDATED_EXPLICIT_CONTEXT");audit.put("fixture_lineage",independent.context.snapshot());audit.put("addressed_channel",independent.channel);audit.put("relative_factor",independent.factor);audit.put("current_value",independent.current);audit.put("divide",independent.divide);audit.put("canonical_unit",expected);audit.put("normalized_value",independent.value);audit.put("derived_command",cmd);audit.put("model_generated",false);audit.put("instrument_state_queried",false);out.evidence.add(audit);return out;
    }

    Check checkDerived(String original,String family,String cmd,Object parameter,EnglishDerivedPlanBridge.Proof proof,int order){
        Check out=new Check();String text=EnglishRequestCompiler.scopeSurface(TextSpanMapping.of(original).normalized.toLowerCase(Locale.ROOT));
        if(proof==null||!proof.matches(original,family,cmd,parameter,order)||Pattern.compile("\\p{IsHan}").matcher(text).find()||EnglishRequestCompiler.prohibited(text)||!EnglishRequestCompiler.action(text)){out.missing.add("明确的当前请求动作");return out;}
        for(Map<String,Object> e:proof.evidence())if(e.get("source_start") instanceof Number){Object end=e.get("source_end");int a=((Number)e.get("source_start")).intValue(),b=end instanceof Number?((Number)end).intValue():-1;if(a<0||b<a||b>original.length()||!original.substring(a,b).equals(e.get("source_text"))||!original.equals(e.get("original_asr_text"))||!Boolean.FALSE.equals(e.get("model_generated"))){out.missing.add("当前话术的原始来源范围");return out;}}
        Set<Integer> channels=new LinkedHashSet<>();Map<String,Set<Integer>> roles=new LinkedHashMap<>();roles.put("first",new LinkedHashSet<>());roles.put("second",new LinkedHashSet<>());Matcher source=EnglishChannelEvidence.PATTERN.matcher(text);int sourceCount=0;
        while(source.find()){sourceCount++;int c=EnglishChannelEvidence.index(source);if(!EnglishChannelEvidence.analog(source,text)||c<1||c>4){out.missing.add("明确模拟输入来源");return out;}channels.add(c);Matcher role=Pattern.compile("^\\s+(first|second)\\b").matcher(text.substring(source.end()));if(role.find())roles.get(role.group(1)).add(c);}
        Map<String,Object> state=proof.state();String top=proof.top();boolean valid=false;
        if(Set.of("VERT_ADJUST_SCALE","HORZ_ADJUST_SCALE").contains(top)){
            boolean vertical=top.startsWith("VERT");if(!top.equals(EnglishRequestCompiler.derivedIntent(text))||vertical&&channels.size()!=1||!vertical&&!channels.isEmpty()||!(parameter instanceof Number)){out.missing.add("明确的倍率对象和来源");return out;}
            int channel=vertical?channels.iterator().next():0;String head=vertical?"CH"+channel+":SCALe":"HORizontal:SCAle";String dimension=units.expected(head);if(!cmd.equals(head)||!dimension.equals(vertical?"V/div":"s/div")){out.missing.add("倍率参数的权威单位");return out;}
            String factorsText=text;Matcher addressed=EnglishChannelEvidence.PATTERN.matcher(factorsText);while(addressed.find())factorsText=factorsText.substring(0,addressed.start())+addressed.group().replaceAll("."," ")+factorsText.substring(addressed.end());Set<Double> factors=new LinkedHashSet<>();
            if(Pattern.compile(vertical?"(?i)\\b(?:seconds?|milliseconds?|microseconds?|nanoseconds?|ms|us|ns)\\b":"(?i)\\b(?:voltage|volts?|millivolts?|mv)\\b").matcher(factorsText).find()){out.missing.add("倍率对象匹配的单位");return out;}
            for(String expression:new String[]{"(?:by(?: a factor of)?|factor(?: of)?)\\s+("+EnglishNumberWords.VALUE+")(?=\\s*(?:times|[,;.!?]|$))","("+EnglishNumberWords.VALUE+")\\s*(?:times|fold|x)\\b","("+EnglishNumberWords.WORD+")fold\\b"}){Matcher m=Pattern.compile(expression,Pattern.CASE_INSENSITIVE).matcher(factorsText);while(m.find()){Double f=EnglishNumberWords.parse(m.group(1));if(f!=null)factors.add(f);}}
            if(text.matches("(?s).*\\b(?:double|halve|twice|half)\\b.*"))factors.add(2.0);if(text.matches("(?s).*\\btriple\\b.*"))factors.add(3.0);if(factors.size()!=1){out.missing.add("唯一倍率数值");return out;}double factor=factors.iterator().next();if(!Double.isFinite(factor)||factor<=0){out.missing.add("有效倍率数值");return out;}
            if(Pattern.compile("(?i)("+EnglishNumberWords.VALUE+")\\s*(?:volts?|millivolts?|mv|v|seconds?|milliseconds?|microseconds?|nanoseconds?|ms|us|ns|s|divisions?|div|hertz|hz)\\b").matcher(factorsText).find()){out.missing.add("无量纲倍率数值");return out;}
            boolean magnification=text.matches("(?s).*\\b(?:magnification|magnify)\\b.*");boolean in=EnglishRequestCompiler.relativeHalf(original)||text.matches("(?s).*\\b(?:zoom in|smaller|divid(?:e|ed)|magnify|closer|narrow)\\b.*")||text.matches("(?s).*\\bhalve\\b.*")&&!magnification||magnification&&text.matches("(?s).*\\b(?:twice|double|triple|increase|more)\\b.*")||!magnification&&text.matches("(?s).*\\b(?:reduce|reducing|decrease)\\b.*");boolean decrease=text.matches("(?s).*\\b(?:zoom out|larger|multipl(?:y|ied)|slower|expand|slow|widen)\\b.*")||text.matches("(?s).*\\b(?:double|triple)\\b.*")&&!magnification||magnification&&text.matches("(?s).*\\b(?:halve|half|reduce|reducing|decrease)\\b.*")||!magnification&&text.matches("(?s).*\\b(?:increase|increasing)\\b.*");
            in|=text.matches("(?s).*\\b(?:vertical|horizontal|[0-9]+x)\\s+in\\b.*")||text.matches("(?s).*\\bin\\s+(?:zoom|adjustment|change)\\b.*");decrease|=text.matches("(?s).*\\b(?:vertical|horizontal|[0-9]+x)\\s+out\\b.*")||text.matches("(?s).*\\bout\\s+(?:zoom|adjustment|change)\\b.*");in|=!magnification&&text.matches("(?s).*\\bhalf\\b.*\\b(?:current|present|existing)\\b.*");decrease|=!magnification&&text.matches("(?s).*\\btwice\\b.*\\b(?:current|present|existing)\\b.*");if(in==decrease){out.missing.add("明确倍率方向");return out;}
            Double current=fixtureScale(state,vertical,channel);if(current==null){out.missing.add(vertical?"current_vertical_scale_volts":"current_timebase_seconds");return out;}
            BigDecimal base=BigDecimal.valueOf(current),ratio=BigDecimal.valueOf(factor);double wanted=(in?base.divide(ratio,java.math.MathContext.DECIMAL128):base.multiply(ratio)).doubleValue();valid=Double.isFinite(wanted)&&wanted>0&&equal(((Number)parameter).doubleValue(),wanted);
        }else if("VERT_SET_OFFSET".equals(top)&&"position_voltage_from_explicit_offline_scale".equals(proof.kind())){
            if(channels.size()!=1||!(parameter instanceof Number)||text.matches("(?s).*\\b(?:by|raise|lower|increase|decrease)\\b.*")){out.missing.add("绝对位置对象和来源");return out;}int channel=channels.iterator().next();if(!cmd.equals("CH"+channel+":POSition")||!"div".equals(units.expected(cmd))){out.missing.add("位置权威单位");return out;}
            Matcher voltage=Pattern.compile("(?i)("+EnglishNumberWords.VALUE+")\\s+(millivolts?|volts?|mV|V)\\b(?!\\s*(?:/|per))").matcher(TextSpanMapping.of(original).normalized);if(!voltage.find()){out.missing.add("位置电压及单位");return out;}Double quantity=EnglishNumberWords.parse(voltage.group(1));String unit=voltage.group(2);if(quantity==null||!Double.isFinite(quantity)||"MV".equals(unit)||voltage.find()){out.missing.add("唯一有效位置电压及单位");return out;}Double scale=fixtureScale(state,true,channel);if(scale==null){out.missing.add("current_vertical_scale_volts");return out;}double wanted=quantity*(unit.toLowerCase(Locale.ROOT).startsWith("m")?.001:1)/scale;valid=Double.isFinite(wanted)&&equal(((Number)parameter).doubleValue(),wanted);
        }else if("MEASURE_CLEAN".equals(top)){
            valid=channels.isEmpty()&&EnglishRequestCompiler.measurementClean(text)&&!EnglishRequestCompiler.measurementQuery(text)&&cmd.equals("MEASUrement:STATE")&&"OFF".equals(parameter);
        }else if(Set.of("MEASURE_ADD","MEASURE_REMOVE","MEASURE_QUERY").contains(top)){
            Set<String> types=new LinkedHashSet<>();for(String[] alias:new String[][]{{"rms","RMS"},{"peak[- ]to[- ]peak|vpp","PK2Pk"},{"frequency|freq","FREQuency"},{"period","PERIod"},{"mean","MEAN"},{"maximum","MAXimum"},{"minimum","MINImum"},{"(?:rise|rising)[- ]+time","RISe"},{"(?:fall|falling)[- ]+time","FALL"},{"phase","PHAse"},{"delay","DELay"}})if(Pattern.compile("(?i)\\b(?:"+alias[0]+")\\b").matcher(text).find())types.add(alias[1]);
            if(types.size()!=1||!top.equals(EnglishRequestCompiler.derivedIntent(text))){out.missing.add("唯一测量对象和动作");return out;}String type=types.iterator().next();boolean dual=Set.of("PHAse","DELay").contains(type);int channel=-1,second=-1;
            if(dual){if(!"MEASURE_ADD".equals(top)||sourceCount!=2||roles.get("first").size()!=1||roles.get("second").size()!=1){out.missing.add("测量来源顺序");return out;}channel=roles.get("first").iterator().next();second=roles.get("second").iterator().next();}else {if(channels.size()!=1){out.missing.add("测量来源");return out;}channel=channels.iterator().next();}
            int slot=-1;Object rows=state.get("measurements");
            if("MEASURE_ADD".equals(top)){Object free=state.get("first_free_measurement_slot");if(free instanceof Number&&((Number)free).doubleValue()==((Number)free).intValue())slot=((Number)free).intValue();int used=0;if(rows instanceof List)for(Object row:(List<?>)rows)if(row instanceof Map&&slotEquals(((Map<?,?>)row).get("slot"),slot)){used++;if(!"OFF".equals(((Map<?,?>)row).get("state"))){out.missing.add("first_free_measurement_slot");return out;}}if(used>1){out.missing.add("first_free_measurement_slot");return out;}}
            else {int matches=0;if(rows instanceof List)for(Object row:(List<?>)rows)if(row instanceof Map){Map<?,?> m=(Map<?,?>)row;if(("CH"+channel).equals(m.get("source"))&&type.equals(m.get("type"))&&"ON".equals(m.get("state"))&&m.get("slot") instanceof Number&&((Number)m.get("slot")).doubleValue()==((Number)m.get("slot")).intValue()){slot=((Number)m.get("slot")).intValue();matches++;}}if(matches!=1||"MEASURE_QUERY".equals(top)&&"OFF".equals(state.get("measurement_system_state"))){out.missing.add("measurement_slot");return out;}int same=0;if(rows instanceof List)for(Object row:(List<?>)rows)if(row instanceof Map&&slotEquals(((Map<?,?>)row).get("slot"),slot))same++;if(same!=1){out.missing.add("measurement_slot");return out;}}
            if(slot<1||slot>8){out.missing.add("MEASURE_ADD".equals(top)?"first_free_measurement_slot":"measurement_slot");return out;}String prefix="MEASUrement:MEAS"+slot+":";
            if("MEASURE_ADD".equals(top))valid=cmd.equals(prefix+"SOURCE1")&&("CH"+channel).equals(parameter)||dual&&cmd.equals(prefix+"SOURCE2")&&("CH"+second).equals(parameter)||cmd.equals(prefix+"TYPe")&&type.equals(parameter)||cmd.equals(prefix+"STATE")&&"ON".equals(parameter)||cmd.equals("MEASUrement:STATE")&&"ON".equals(parameter);
            else valid="MEASURE_REMOVE".equals(top)?cmd.equals(prefix+"STATE")&&"OFF".equals(parameter):cmd.equals(prefix+"VALue?")&&Collections.emptyMap().equals(parameter);
        }
        if(!valid){out.missing.add("当前请求和显式状态推导出的精确参数");return out;}
        out.evidence.addAll(proof.evidence());Map<String,Object> audit=new LinkedHashMap<>();audit.put("rule_id","shared_derived_source_guard");audit.put("validation_layer","ParameterSourceGuard");audit.put("source_type","INDEPENDENT_REQUEST_AND_EXPLICIT_FAKE_DERIVATION");audit.put("original_asr_text",original);audit.put("derived_command",cmd);audit.put("normalized_value",parameter);audit.put("derived_step_index",order);audit.put("derivation",proof.kind());audit.put("fixture_lineage",state);audit.put("model_generated",false);audit.put("instrument_state_queried",false);out.evidence.add(audit);return out;
    }
    private static boolean slotEquals(Object value,int slot){return value instanceof Number&&((Number)value).doubleValue()==slot;}
    private static Double fixtureScale(Map<String,Object> state,boolean vertical,int channel){Object value=state.get(vertical?"current_vertical_scale_volts":"current_timebase_seconds");if(vertical&&state.containsKey("current_vertical_scale_volts_by_channel")){Object by=state.get("current_vertical_scale_volts_by_channel");value=by instanceof Map?((Map<?,?>)by).get(String.valueOf(channel)):null;}if(!(value instanceof Number))return null;double number=((Number)value).doubleValue();return Double.isFinite(number)&&number>0?number:null;}

    /** Exact system action family derived from a current global action/object request, never model tags. */
    static boolean systemActionSource(String original,String command){return systemActionMatch(original,command)!=null;}
    private static Matcher systemActionMatch(String original,String command){String h=command.toUpperCase(Locale.ROOT);if(!Set.of("*RST","*WAI","*CLS","*OPC").contains(h))return null;String text=Pattern.compile("\\p{IsHan}").matcher(original).find()?PcSemanticGuard.chineseScopeSurface(original):EnglishRequestCompiler.scopeSurface(original);
        if(PcSemanticGuard.prohibitedRequest(text)||EnglishRequestCompiler.cancelled(text)||PcSemanticGuard.requestedQuery(text)||EnglishRequestCompiler.explicitQuery(text)||text.matches("(?is).*(?:解释|介绍|说明|讨论|如何|不要|禁止|请勿|\\b(?:explain|describe|how\\s+to|permission)\\b).*"))return null;
        boolean polite=Pattern.compile("\\p{IsHan}").matcher(original).find()?PcSemanticGuard.chinesePoliteSetting(original):EnglishRequestCompiler.politeControlRequest(original);if(!polite&&(text.matches("(?s).*(?:是否|是不是|有没有|能否|可否|吗|么|呢).*" )||text.matches("(?s).*[?？]\\s*$")))return null;
        if(h.equals("*WAI")){Matcher wait=Pattern.compile("(?is)\\s*(?:(?:please|could\\s+you|would\\s+you|can\\s+you)\\s+)?wait\\s+(?:for\\s+)?(?:the\\s+)?(?:(?:scope|oscilloscope|instrument)(?:[\'’]s)\\s+)?(?:pending|current|unfinished|running|ongoing|previous|earlier|preceding)?\\s*operations?(?:\\s+to\\s+(?:finish|complete))?[.!?]?\\s*|\\s*(?:请|帮我|麻烦|(?:能否|可否|能不能|可不可以|可以|能)(?:帮我|替我)?)?(?:等|等待|等候)(?:当前|正在执行|待执行|未完成|前序|此前|前面)?(?:的)?操作(?:完成|结束|执行完毕|执行结束)(?:[，,]\\s*再继续处理)?(?:吗|么|呢)?[。.!！?？]?\\s*|\\s*(?:please\\s+)?wait\\s+until\\s+(?:the\\s+)?current\\s+operation\\s+finishes\\s+before\\s+proceeding[.!?]?\\s*|\\s*(?:请|帮我)?确认(?:当前)?操作完成后再继续[。.!！?？]?\\s*").matcher(text);return wait.matches()?wait:null;}
        if(text.matches("(?is).*(?:通道|频道|总线|信号源|耦合|触发|采集|光标|\\b(?:channel|bus|source|coupling|trigger|acquisition|cursor|ch[1-4])\\b).*"))return null;
        if(h.equals("*OPC")){Matcher explicitFlag=Pattern.compile("(?i)(?:设置|置位|标记)(?:当前|前序)?(?:操作)?完成(?:标志)?|\\b(?:execute|perform)\\s+(?:the\\s+)?operation[- ]complete\\b").matcher(text);if(explicitFlag.find())return explicitFlag;}
        Map<String,String> families=new LinkedHashMap<>();families.put("*RST","(?:恢复|还原)[^，,。;!?？]{0,14}(?:出厂|默认)[^，,。;!?？]{0,8}(?:配置|设置)|(?:执行)?复位|(?:清除|清空)[^，,。;!?？]{0,10}(?:配置|设置)[^，,。;!?？]{0,8}(?:载入|加载)[^，,。;!?？]{0,4}默认(?:配置|设置)|(?:仪器|示波器|设备)(?:的)?(?:各项|所有|全部)?(?:设置|配置)[^，,。;!?？]{0,6}(?:还原|恢复)|(?i:\\b(?:factory\\s+reset|reset\\s+(?:the\\s+)?(?:instrument|device|scope|oscilloscope))\\b)");families.put("*CLS","(?:清除|清空|清掉)[^，,。;!?？]{0,12}(?:状态(?:寄存器)?|错误(?:记录|队列))|(?i:\\bclear\\s+(?:instrument\\s+)?(?:status|error\\s+(?:records?|queue))\\b)");families.put("*OPC","(?:设置|置位|标记)[^，,。;!?？]{0,12}操作[^，,。;!?？]{0,8}完成[^，,。;!?？]{0,6}标志|(?i:\\b(?:set|mark)\\b[^;.!?]{0,20}operation[- ]complete\\s+flag\\b)");families.put("*WAI","(?:等待|等候)[^，,。;!?？]{0,14}操作[^，,。;!?？]{0,12}(?:完成|结束|执行完毕)|(?i:\\bwait\\b[^;.!?]{0,30}operations?\\b[^;.!?]{0,24}(?:finishes|finish|complete|completed)\\b)");
        Set<String> matched=new HashSet<>();Matcher selected=null;for(Map.Entry<String,String> family:families.entrySet()){Matcher m=Pattern.compile(family.getValue(),Pattern.CASE_INSENSITIVE).matcher(text);if(m.find()){matched.add(family.getKey());if(family.getKey().equals(h))selected=m;}}return matched.size()==1&&matched.contains(h)?selected:null;
    }

    private static boolean noParameterActionSource(Check result, TextSpanMapping spans,
                                                   String text, String command) {
        String upper = command.toUpperCase(Locale.ROOT);
        Matcher independentlyBound=systemActionMatch(spans.original,command);if(independentlyBound!=null){add(result,spans,"action",independentlyBound.start(),independentlyBound.end(),command,"CURRENT_REQUEST_TYPED_SYSTEM_ACTION","EXPLICIT_ACTION_OBJECT_PROVENANCE","action",false);return true;}

        if (upper.startsWith("*") && Pattern.compile(
                "(?iu)(?:通道|频道|总线|信号源|\\bchannel\\b|\\bbus\\b|"
                        + "\\bsource\\b|\\bCH(?:[1-4])?\\b|\\bAFG(?:[1-4])?\\b)")
                .matcher(text).find()) return false;
        if (Pattern.compile("(?iu)(?:解释|介绍|说明|分析|评估|讨论|风险|作用|功能|"
                + "如何|什么是|为什么|为何|是否|能否|可以吗|吗|么|呢)").matcher(text).find()) {
            return false;
        }
        String expression;
        switch (upper) {
            case "*CLS":
                expression = "(?:清除|清空)(?:仪器)?(?:状态(?:寄存器)?(?:和|与|及)?错误队列|状态|错误队列)|"
                        + "(?:仪器)?错误队列.{0,2}(?:清除|清空)|"
                        + "clear\\s+(?:instrument\\s+)?(?:status(?:\\s+and\\s+error\\s+queue)?|error\\s+queue)";
                break;
            case "*RST":
                expression = "恢复出厂设置|"
                        + "(?:仪器|设备|示波器).{0,4}(?:复位|重置|恢复默认)|(?:恢复|还原).{0,8}(?:默认设置|各项设置)|(?:将)?(?:仪器|设备|示波器).{0,8}(?:还原|默认设置)|(?:清除现有配置并)?载入默认设置|"
                        + "factory\\s+reset|reset.{0,8}(?:instrument|device)";
                break;
            case "*OPC":
                expression = "(?:等待|确认|标记).{0,12}操作完成|(?:在)?(?:前序)?操作完成后置位操作完成标志|等待前序操作完成并置位完成标志|operation\\s+complete|opc";
                break;
            case "*WAI":
                expression = "等待.{0,8}(?:前序|先前|之前|操作).{0,6}(?:完成|执行完毕)|确认当前操作完成后再继续|"
                        + "wait.{0,12}(?:previous|prior|operation)|wait(?:\\s+for)?\\s+(?:the\\s+)?(?:previous|prior|pending)\\s+operations?(?:\\s+to\\s+(?:finish|complete))?";
                break;
            case "BUS:B1:COPYTRIG":
                if (!uniqueBusObject(text, 1)) return false;
                expression = "(?=.*(?:复制|copy))(?=.*(?:触发|trigger)).*";
                break;
            case "BUS:B2:COPYTRIG":
                if (!uniqueBusObject(text, 2)) return false;
                expression = "(?=.*(?:复制|copy))(?=.*(?:触发|trigger)).*";
                break;
            case "MEASUREMENT:CLEARSNAPSHOT":
                expression = "(?:清除|清空).{0,8}(?:测量)?快照|clear.{0,12}snapshot";
                break;
            case "WAVEFORM:RECORD:FRAMES:MAX":
                expression = "(?:应用|设置|获取).{0,8}(?:最大录制帧|录制最大帧)|"
                        + "(?:波形)?录制(?:的)?(?:帧数)?(?:设置(?:为|到)?|设为|改为)(?:最大(?:录制)?帧数)|"
                        + "maximum.{0,12}(?:recording\\s+)?frames";
                break;
            default:
                expression = Pattern.quote(command);
        }
        String request = "^(?:(?:请|帮我|请帮我|麻烦|现在|立即|给我|把|将|执行|"
                + "please|execute|now)\\s*)*(?:" + expression + ")\\s*"
                + "(?:一下|吧|并立即执行)?[。.!！]?$";
        Matcher match = Pattern.compile(request, Pattern.CASE_INSENSITIVE
                | Pattern.UNICODE_CASE).matcher(text);
        if (!match.matches()) return false;
        add(result, spans, "action", match.start(), match.end(), command,
                "CURRENT_UTTERANCE_NO_PARAMETER_ACTION", "EXPLICIT_ACTION_PROVENANCE",
                "action", false);
        return true;
    }

    private static boolean uniqueBusObject(String text, int expected) {
        Set<Integer> buses = new HashSet<>();
        Matcher objects = Pattern.compile("(?iu)(?:第)?([12一二])(?:号)?总线|总线\\s*([12一二])(?![0-9一二])|"
                + "(?<![A-Za-z0-9])(?:BUS|B)\\s*([12])(?![A-Za-z0-9])").matcher(text);
        while (objects.find()) {
            String token = objects.group(1)!=null?objects.group(1):objects.group(2)!=null?objects.group(2):objects.group(3);
            buses.add("1".equals(token) || "一".equals(token) ? 1 : 2);
        }
        return buses.size() == 1 && buses.contains(expected);
    }

    /** A read act must address this parameter family as well as the already checked object index. */
    /** The requested qualified object is independent of a model-selected SCPI leaf. */
    private static String qualifiedObjectIssue(String text,String command){
        if(PcSemanticGuard.unconsumedSourceCourtesy(text)||PcSemanticGuard.unconsumedDailyRepair(text))return "完整的设置请求跨度，不能预览部分动作";
        String h=command.toUpperCase(Locale.ROOT),base=h.replaceFirst("\\?$","");boolean zh=Pattern.compile("\\p{IsHan}").matcher(text).find();
        if(zh&&text.contains("按每格")&&text.contains("显示")){Matcher scale=PcSemanticGuard.scaleDisplayRequest(text);Set<Integer> channels=PcSemanticGuard.analogSources(text);if(scale==null)return "完整的每格电压显示请求跨度";if(channels.size()!=1||!base.equals("CH"+channels.iterator().next()+":SCALE"))return "每格电压显示请求只能绑定通道档位对象";}
        if(zh&&base.startsWith("DISPLAY:STYLE:")&&text.matches("(?s)\\s*(?:请|帮我|麻烦)?(?:通道[一二两三四0-9]+|[一二两三四0-9]+通道|(?:第)?[一二两三四0-9]+路(?:输入)?)(?:的)?(?:波形|轨迹)显示[，,]?\\s*(?:关掉|关闭|隐藏|打开|开启|启用).*"))return "模拟通道波形开关对象不能替代显示样式参数";
        if(zh&&text.matches("(?s)^\\s*(?:请|帮我|麻烦)?(?:让|使).*(?:波形|轨迹)(?:出现|显示)在屏幕.*")&&!PcSemanticGuard.naturalDisplay(text))return "完整的波形显示请求跨度";
        if(zh&&text.contains("收起")&&!PcSemanticGuard.naturalDisplayOff(text))return "完整的模拟波形屏幕关闭跨度";
        if(zh&&text.contains("半")&&!text.contains("减半")&&base.matches("CH[1-4]:(?:SCALE|POSITION)")){String source="(?:通道[一二两三四0-9]+|[一二两三四0-9]+通道|(?:第)?[一二两三四0-9]+路)",n="(?:正|负|[+-])?半";boolean full=text.matches("(?s)\\s*(?:请|请帮我|帮我|麻烦)?(?:把|将)?"+source+"(?:的)?(?:(?:垂直刻度|垂直档位)(?:设为|设成|调到|调整为|改为|改成)"+n+"(?:毫伏|微伏|伏)每格|每格"+n+"(?:毫伏|微伏|伏)|垂直位置(?:设为|设成|调到|调整为|改为|改成)"+n+"格)[。.!！?？]?\\s*");if(!full&&PcSemanticGuard.scaleDisplayRequest(text)==null)return "完整的有单位半值设置跨度";}
        if(zh&&text.matches("(?s).*(?:而不是|不是|刚才说错了).*" )&&PcSemanticGuard.chineseCorrectionSurface(text).equals(text)&&!(h.endsWith("?")&&h.equalsIgnoreCase(SemanticPolicy.ShortDailyRequest.explicitReadHead(text))))return "完整且可证明的当前局部更正跨度";
        if(base.matches("CH[1-4]:.*|SELECT:CH[1-4]")&&text.matches("(?is).*(?:数学|参考波形|FFT|频谱|\\b(?:math|ref(?:erence)?|fft)\\b).*"))return "明确的模拟通道对象不能替代数学或参考波形";
        if(base.equals("HORIZONTAL:SCALE")&&text.matches("(?is).*(?:XY|缩放视图|放大窗口|\\bzoom\\b).*"))return "明确的主时基对象不能替代XY或zoom对象";
        if(base.matches("CH[1-4]:SCALE")&&text.matches("(?is).*(?:缩放视图|放大窗口|缩放窗口|\\bzoom\\b).*"))return "主通道垂直刻度不能替代缩放垂直刻度对象";
        if(base.matches("CH[1-4]:COUPLING")&&text.matches("(?is).*(?:触发|\\btrigger(?:ing)?\\b).*"))return "通道输入耦合不能替代触发耦合";
        if(base.matches("D[0-9]+:THRESHOLD")&&text.matches("(?is).*(?:触发|\\btrigger\\b).*"))return "数字输入阈值不能替代触发逻辑阈值";
        if(base.matches("TRIGGER:A:LEVEL:CH[1-4]")&&text.matches("(?is).*(?:下阈值|上阈值|低阈值|高阈值|\\b(?:lower|upper)\\s+threshold\\b).*"))return "触发电平不能替代上下阈值";
        if(!zh||!h.endsWith("?"))return null;
        if(Set.of("DATA","WAVFRM").contains(base)&&text.matches("(?s).*波形传输数据.*")&&!text.matches("(?is).*(?:\\bDATa\\?|\\bWAVFrm\\?|前导|数据配置|传输配置|采样点|点数组).*"))return "波形传输数据请求尚未唯一指明数据配置或波形采样数据对象";
        if(base.startsWith("DISPLAY:INTENSITY")&&!text.matches("(?s).*(?:亮度|强度).*"))return "读取显示亮度需要明确亮度对象";
        if(base.equals("MEASUREMENT:IMMED")&&text.matches("(?s).*(?:结果|读数|测量值|现值).*"))return "即时测量结果需要数值叶子对象";
        if(base.equals("WAVEFORM:RECORD:OPERATE")&&text.matches("(?s).*(?:启用|开启|使能)状态.*"))return "录制启用状态不能替代录制操作对象";
        if(base.equals("*ESE")&&!text.matches("(?is).*(?:使能|启用|允许|\\bESE\\b).*"))return "标准事件使能寄存器需要明确使能对象";
        return null;
    }
    static boolean operationCompletionQuerySource(String text) {
        if(text==null)return false;
        return Pattern.compile("(?i)\\*OPC\\?|\\b(?:operations?|commands?|requests?)\\b[^;.!?]{0,60}\\b(?:complete(?:d)?|completion|finished|done)\\b|(?:操作|执行|指令|命令)[^，,。!?？；;]{0,16}(?:完成|结束|做完|执行完)").matcher(text).find();
    }
    private static String chineseQueryObjectIssue(String text,String command){
        boolean zh=Pattern.compile("\\p{IsHan}").matcher(text).find();String h=command.toUpperCase(Locale.ROOT),object=null;
        // These are distinct read objects. A read verb grants no permission to substitute another state family.
        boolean operation=Pattern.compile(zh?"操作[^，,。!?？；;]{0,12}完成":"(?i)\\boperations?\\b[^;.!?]{0,60}\\b(?:complete(?:d)?|completion|finished)\\b").matcher(text).find();
        boolean measurement=Pattern.compile(zh?"测量(?:系统|功能|显示)?[^，,。!?？；;]{0,12}(?:状态|是否(?:开启|启用|打开)|开关)":"(?i)\\bmeasurement(?:s)?\\b[^;.!?]{0,60}\\b(?:state|status|enabled|disabled|on|off)\\b|\\b(?:state|status|enabled|disabled)\\b[^;.!?]{0,60}\\bmeasurement(?:s)?\\b").matcher(text).find();
        boolean acquisition=Pattern.compile(zh?"采集[^，,。!?？；;]{0,12}(?:状态|运行|开始|停止)|正在采集":"(?i)\\b(?:acquisition|acquire|acquiring|capture|sampling)\\b[^;.!?]{0,60}\\b(?:state|status|running|stopped|on|off)\\b|\\b(?:state|status)\\b[^;.!?]{0,60}\\b(?:acquisition|capture|sampling)\\b").matcher(text).find();
        Set<Integer> slots=explicitMeasurementSlots(text);String measurementHead=slots.size()==1?"MEASUREMENT:MEAS"+slots.iterator().next()+":STATE?":"MEASUREMENT:STATE?";
        String family=operation?"*OPC?":measurement?measurementHead:acquisition?"ACQUIRE:STATE?":null;
        if((operation?1:0)+(measurement?1:0)+(acquisition?1:0)>1)return "唯一的读取状态对象；多个状态对象请拆分";
        if(family!=null&&!h.equals(family))return "与读取请求一致的参数对象和命令head";
        boolean literal=text.toUpperCase(Locale.ROOT).contains(h)||text.toUpperCase(Locale.ROOT).contains(h.replaceFirst("\\?$",""));
        if((Set.of("*OPC?","MEASUREMENT:STATE?","ACQUIRE:STATE?").contains(h)||h.matches("MEASUREMENT:MEAS[1-8]:STATE\\?"))&&family==null&&!literal)return "读取状态的明确操作、测量或采集对象";
        if(!zh)return null;
        if(text.toUpperCase(Locale.ROOT).contains(h)||text.toUpperCase(Locale.ROOT).contains(h.replaceFirst("\\?$","")))return null;
        if(h.matches("CH[1-4]:SCALE\\?"))object="刻度|档位|伏.{0,6}每格|每格.{0,6}(?:电压|伏)";
        else if(h.matches("CH[1-4]:POSITION\\?"))object="位置|偏移|偏置";
        else if(h.matches("CH[1-4]:COUPLING\\?"))object="耦合";
        else if(h.matches("CH[1-4]:BANDWIDTH\\?"))object="带宽";
        else if(h.matches("SELECT:CH[1-4]\\?"))object="显示|可见|(?:通道|波形).{0,8}(?:开关|启用|打开|开启|关闭)|通道[0-9零〇一二两三四五六七八九十]*(?:的)?(?:当前|现在)?状态";
        else if(h.equals("HORIZONTAL:SCALE?"))object="时基|水平档位|每格.{0,6}(?:时间|多长)|秒.{0,6}每格";
        else if(h.equals("HORIZONTAL:DELAY:TIME?"))object="延迟|延后";
        else if(h.equals("HORIZONTAL:RECORDLENGTH?"))object="记录长度|记录深度|点数";
        else if(h.equals("ACQUIRE:MODE?"))object="采集.{0,8}(?:模式|方式|处理)";
        else if(h.equals("ACQUIRE:STATE?"))object="采集.{0,8}(?:状态|运行|开始|停止)|正在采集";
        else if(h.equals("ZOOM:MODE?"))object="水平放大|放大窗口|水平缩放|缩放窗口|缩放视图";
        else if(h.equals("DISPLAY:GRATICULE?"))object="格线|网格";
        else if(h.equals("TRIGGER:A:MODE?"))object="触发.{0,8}模式";
        else if(h.equals("TRIGGER:STATE?"))object="触发.{0,8}(?:状态|情况)";
        else if(h.equals("TRIGGER:A:TIMEOUT:TIME?"))object="超时.{0,8}(?:时间|时长)|超时";
        else if(h.equals("DVM:VALUE?"))object="(?:数字电压表|电压表|DVM).{0,16}(?:值|读数)|(?:读取|查询).{0,16}(?:数字电压表|电压表|DVM)";
        else if(h.equals("*IDN?"))object="身份|标识|识别|型号|厂商|设备信息|仪器信息|IDN";
        else if(h.equals("MEASUREMENT:STATE?"))object="测量(?:系统|功能|显示)?[^，,。!?？；;]{0,12}(?:状态|是否(?:开启|启用|打开)|开关)";
        else if(h.equals("*OPC?"))object="操作[^，,。!?？；;]{0,12}完成|OPC";
        else if(h.equals("DISPLAY:XY:WITHYT?"))object="(?:XY\\s*(?:与|和|及|、)\\s*YT|YT\\s*(?:与|和|及|、)\\s*XY).{0,8}(?:同时显示|同显)";
        else if(h.startsWith("COUNTER:")){String leaf=h.substring("COUNTER:".length());if(leaf.equals("STATE?"))object="^(?!.*统计).*(?:启用|开启|开关|状态)";else if(leaf.equals("STATISTICS:STATE?"))object="统计.*(?:启用|开启|开关|状态)";else if(leaf.equals("UNITS?"))object="单位";else if(leaf.equals("SOURCE?"))object="信号源|信源|来源";else if(leaf.equals("VALUE?"))object="^(?!.*(?:单位|状态|启用|开启|开关|统计|信号源|信源|来源)).*(?:读数|当前(?:值|数值)|现值|测量值)";else if(leaf.equals("STATISTICS:MINIMUM?"))object="统计.*最小";else if(leaf.equals("STATISTICS:MAXIMUM?"))object="统计.*最大";}
        return object!=null&&!Pattern.compile(object,Pattern.CASE_INSENSITIVE).matcher(text).find()?"与读取请求一致的参数对象和命令head":null;
    }

    private static Set<Integer> explicitMeasurementSlots(String text){Set<Integer> slots=new HashSet<>();Matcher m=Pattern.compile("(?i)(?:第)?([1-8一二两三四五六七八])(?:号|条)?测量项|测量项\\s*([1-8一二两三四五六七八])|\\bmeasurement(?:s)?\\s+(?:(?:slot|item)\\s+)?([1-8])\\b").matcher(text);while(m.find()){String value=m.group(1)!=null?m.group(1):m.group(2)!=null?m.group(2):m.group(3);Double n=number(value);if(n!=null)slots.add(n.intValue());}return slots;}

    private static String chineseEnumSource(String text,String command,String value){
        if(!Pattern.compile("\\p{IsHan}").matcher(text).find()||PcSemanticGuard.requestedQuery(text)||PcSemanticGuard.prohibitedRequest(text)||text.matches("(?s).*(?:介绍|解释|说明|讨论|如何|为什么).*"))return null;
        String h=command.toUpperCase(Locale.ROOT),v=value.toUpperCase(Locale.ROOT),object=null,action=null;
        if(h.equals("AUTOSET")&&v.equals("EXECUTE")&&text.matches("(?:请|帮我|帮|麻烦)?(?:示波器)?(?:做|进行|执行)(?:一次)?自动(?:调整|配置|设置)[。.!！]?"))return "(?:做|进行|执行)(?:一次)?自动(?:调整|配置|设置)";
        if(h.equals("AUTOSET")){object="自动设置";action=v.equals("EXECUTE")?"运行|执行|启动|开始|做|进行":v.equals("UNDO")?"撤销|取消":null;if(v.equals("EXECUTE")&&text.matches("(?:请|帮我|麻烦)?自动(?:调整|配置)示波器[。.!！]?"))return "自动(?:调整|配置)示波器";}
        else if(h.equals("TRIGGER:A:MODE")&&PcSemanticGuard.dailyRepairCandidate(text)!=null){object="触发";action=v.equals("NORMAL")?"正常":v.equals("AUTO")?"自动":v.equals("SINGLE")?"单次":null;}
        else if(h.matches("CH[1-4]:BANDWIDTH")&&v.equals("FULL")&&PcSemanticGuard.dailyRepairCandidate(text)!=null){object="带宽";action="(?:全|全部|完整)带宽";}
        else if(h.matches("SELECT:CH[1-4]")&&v.equals("OFF")&&PcSemanticGuard.naturalDisplayOff(text)){object="波形|轨迹";action="收起";}
        else if(h.equals("ZOOM:MODE")){object="水平放大|放大窗口|水平缩放|缩放窗口";action=v.equals("OFF")?"停用|关闭|禁用|退出":v.equals("ON")?"开启|启用|打开|进入":null;}
        else if(h.equals("DVM:MODE")){object="数字电压表|DVM";if(Pattern.compile("设置|设为|选择|选|采用").matcher(text).find()){Set<String> requested=new HashSet<>();for(String[] a:new String[][]{{"直流","DC"},{"交流有效值","ACRMS"},{"交直流有效值","DCRMS"}})if(text.contains(a[0]))requested.add(a[1]);if(requested.size()==1&&requested.contains(v))action=v.equals("DC")?"直流":v.equals("ACRMS")?"交流有效值":v.equals("DCRMS")?"交直流有效值":null;}}
        else if(h.equals("ACQUIRE:STATE")&&(v.equals("RUN")||v.equals("STOP"))){object="采集";action=v.equals("RUN")?"采集(?:先|现在|立即|本次|当前)?(?:开始|启动|继续|恢复)|(?:开始|启动|继续|恢复)采集":"采集(?:先|现在|立即|本次|当前)?(?:停止|停下)|(?:停止|停下)采集";}
        else if(h.equals("DISPLAY:GRATICULE")&&!PcSemanticGuard.chineseNominalSetting(text)){object="格线|网格";String target=v.equals("FULL")?"完整格线|完整网格|全部格线":v.equals("CROSSHAIR")?"十字线":v.equals("FRAME")?"框线|边框":v.equals("GRID")?"网格":null;if(target!=null){Matcher chosen=Pattern.compile("(?:设为|改为|改成|换为|换成|选择|使用|用)(完整格线|完整网格|全部格线|十字线|框线|边框|网格)").matcher(text);Set<String> choices=new HashSet<>();while(chosen.find())choices.add(chosen.group(1));if(choices.size()==1&&choices.iterator().next().matches(target))action="(?:设为|改为|改成|换为|换成|选择|使用|用)(?:"+target+")";}}
        else if(h.equals("DISPLAY:GRATICULE")&&PcSemanticGuard.chineseNominalSetting(text)){object="格线|网格";action=v.equals("FULL")?"完整格线|完整网格|全部格线":v.equals("CROSSHAIR")?"十字线":v.equals("FRAME")?"框线|边框":v.equals("GRID")?"网格":null;}
        else if(h.equals("DVM")){object="数字电压表|DVM";action=v.equals("RESET")?"恢复|重置|复位":null;}
        else if(h.equals("DVM:STATISTICS")||h.equals("MEASUREMENT:STATISTICS")){object=h.startsWith("DVM")?"数字电压表|DVM":"测量";action=v.equals("RESET")?"重置|复位|清除":null;}
        else if(h.matches("REF[0-9]+")){object="参考波形|REF[0-9]+";action=v.equals("SAVE")?"保存":v.equals("RECALL")?"调用|加载":null;}
        else if(h.matches("REF[0-9]+:STATE")){object="参考波形|REF[0-9]+";action=v.equals("RESET")?"清除|清空|重置":null;}
        else if(h.equals("NAVIGATE:TIME:POSITION")){Matcher chosen=Pattern.compile("(?s)\\s*(?:请|请帮我|帮我|麻烦)?(?:把|将)?(?:设备(?:的)?)?(?:时间导航|导航时间)(?:的)?(?:波形播放)?位置(?:改成|改为|设成|设为|设置为)(?<target>前(?:方)?|后(?:方)?|下一帧|上一帧|起点|末尾)[。.!！?？]?\\s*").matcher(text);if(!chosen.matches())return null;String target=chosen.group("target"),expected=target.startsWith("后")||target.equals("上一帧")?"BACK":target.startsWith("前")||target.equals("下一帧")?"NEXT":target.equals("起点")?"START":"END";return expected.equals(v)?Pattern.quote(target):null;}
        else if(h.equals("TRIGGER")){object="触发";action=v.equals("FORCE")?"强制(?:执行|产生)?":null;}
        else if(h.equals("TRIGGER:A")){object="触发电平";action=v.equals("SETLEVEL")?"自动(?:设置|设定)|自动|设定":null;}
        else if(h.equals("MASK:TEMPLATE")){object="通过失败检测|模板";action=v.equals("CREATE")?"创建":null;}
        else if(h.equals("WFMCLONE")){object="波形克隆";action=v.equals("EXECUTE")?"执行|运行":null;}
        else if(h.matches("WFMCLONE:WAV[0-9]+")){object="克隆波形";action=v.equals("RECALL")?"调用|加载":null;}
        else if(h.matches("MEASUREMENT:MEAS[1-8]:DELAY:DIRECTION")){object="延迟方向";action=v.equals("BACKWARDS")?"反向":v.equals("FORWARDS")?"正向":null;}
        else if(h.equals("WFMOUTPRE:BYT_OR")){object="字节顺序";action=v.equals("LSB")?"低位字节在前":v.equals("MSB")?"高位字节在前":null;}
        else if(h.matches("CH[1-4]:YUNITS")){object="纵轴单位";action=v.equals("\"A\"")?"安培":v.equals("\"V\"")?"伏特|伏":null;}
        else if(h.matches("SELECT:D[0-9]+")&&v.equals("OFF")){object="数字通道|D[0-9]+";action="取消选中";}
        else if(h.endsWith(":SOURCE")&&v.matches("CH[1-4]")){object=h.startsWith("MASK:")?"通过失败检测.*信号源":h.startsWith("TRIGGER:")?"触发.*信号源":null;action="(?:第)?"+v.substring(2)+"(?:模拟)?通道|(?:第)?"+"零一二三四".charAt(Integer.parseInt(v.substring(2)))+"(?:模拟)?通道";}
        if(object==null||action==null||!Pattern.compile(object,Pattern.CASE_INSENSITIVE).matcher(text).find())return null;
        return action;
    }

    private static void addSpanFields(Map<String, Object> e, TextSpanMapping spans,
                                      int normalizedStart, int normalizedEnd,
                                      String sourceType, String ruleId,
                                      String canonicalField, boolean modelGenerated) {
        TextSpanMapping.Span span = spans.originalSpan(normalizedStart, normalizedEnd);
        e.put("source_text", spans.original.substring(span.start, span.end));
        e.put("normalized_source_text", spans.normalized.substring(normalizedStart, normalizedEnd));
        e.put("source_start", span.start);
        e.put("source_end", span.end);
        e.put("normalized_source_start", normalizedStart);
        e.put("normalized_source_end", normalizedEnd);
        e.put("original_asr_text", spans.original);
        e.put("normalized_input_text", spans.normalized);
        e.put("source_type", sourceType);
        e.put("rule", ruleId);
        e.put("rule_id", ruleId);
        e.put("canonical_field", canonicalField);
        e.put("model_generated", modelGenerated);
    }
}
