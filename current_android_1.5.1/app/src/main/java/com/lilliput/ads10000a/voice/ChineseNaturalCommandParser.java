package com.lilliput.ads10000a.voice;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic Chinese command-family parser. It produces semantic candidates
 * only; it owns no transport, permit, ledger or transaction capability.
 */
public final class ChineseNaturalCommandParser {
    public enum Status {
        MATCH_QUERY, MATCH_SET, MATCH_ACTION, CLARIFY, REJECT, NO_MATCH
    }

    public static final class Decision {
        public final Status status;
        public final String reason;
        public final Map<String, Object> candidate;
        public final List<Map<String, Object>> evidence;
        public final ChineseNumericNormalizer.Result normalization;

        private Decision(Status status, String reason, Map<String, Object> candidate,
                         List<Map<String, Object>> evidence,
                         ChineseNumericNormalizer.Result normalization) {
            this.status = status;
            this.reason = reason;
            this.candidate = candidate == null ? null
                    : Collections.unmodifiableMap(new LinkedHashMap<>(candidate));
            this.evidence = Collections.unmodifiableList(new ArrayList<>(evidence));
            this.normalization = normalization;
        }

        public String encodedCandidate() {
            return candidate == null ? null : CompactSemanticProtocol.encode(candidate);
        }
    }

    private final ChineseNumericNormalizer normalizer = new ChineseNumericNormalizer();
    private final Map<String, Map> entries = new HashMap<>();
    private final ScpiTableEligibility table;

    static boolean edgeCouplingObject(String text) {
        return text!=null&&text.contains("耦合")&&text.matches("(?is).*(?:边沿触发|触发边沿).*")
                &&!text.matches("(?is).*(?:B\\s*触发|触发\\s*B|B\\s*边沿|N\\s*边沿|脉宽|超时).*");
    }
    static Matcher edgeCouplingSetFrame(String text,String value) {
        String target=value.equalsIgnoreCase("AC")?"交流|AC":value.equalsIgnoreCase("DC")?"直流|DC":value.equalsIgnoreCase("HFRej")?"高频抑制|HFRej":value.equalsIgnoreCase("GND")?"接地|GND":null;
        if(target==null)return null;
        Matcher m=Pattern.compile("(?is)\\s*(?:请|请帮我|帮我|麻烦)?(?:把|将|设置)?(?:A\\s*)?(?:边沿触发|触发边沿)(?:的)?耦合(?:方式)?(?:设为|设成|设置为|改为|改成|调到|[，,:：]\\s*)?(?<target>"+target+")[。.!！]?\\s*").matcher(text);
        return m.matches()?m:null;
    }
    static Matcher edgeCouplingReadFrame(String text) {
        Matcher m=Pattern.compile("(?is)\\s*(?:请|请帮我|帮我|麻烦)?(?:(?:查询|读取|查看|看一下)|我(?:想|需要)知道)?(?:A\\s*)?(?:边沿触发|触发边沿)(?:的)?耦合(?:方式)?(?:(?:现在|当前|目前)(?:的)?)?(?:是(?:什么|哪种)|为哪种)?[。.!！?？]?\\s*").matcher(text);
        return m.matches()?m:null;
    }

    public ChineseNaturalCommandParser(String contract) {
        Map root = (Map) StrictJson.parse(contract);
        table = new ScpiTableEligibility(root);
        for (Object item : (List) ((Map) root.get("catalog")).get("commands")) {
            Map entry = (Map) item;
            entries.put(entry.get("SCPIcommand").toString().toUpperCase(Locale.ROOT), entry);
        }
    }

    public Decision parse(String input) {
        ChineseNumericNormalizer.Result normalized = normalizer.normalize(PcSemanticGuard.chineseScopeSurface(input));
        String raw = PcSemanticGuard.chinesePoliteSurface(normalized.raw);
        if (raw.length() > 512 || raw.matches("(?s).*[;\\r\\n\\x00].*")) {
            return blocked(Status.REJECT, "INVALID_INPUT", normalized);
        }

        List<ChineseNumericNormalizer.Token> channels = tokens(normalized,
                ChineseNumericNormalizer.TokenType.CHANNEL);
        Set<Integer> channelIndexes = new HashSet<>();
        for (ChineseNumericNormalizer.Token channel : channels) {
            if (!channel.valid) return blocked(Status.REJECT, "ILLEGAL_CHANNEL", normalized);
            channelIndexes.add(channel.channelIndex);
        }
        channelIndexes.addAll(PcSemanticGuard.analogSources(input));
        if(channelIndexes.stream().anyMatch(i->i<1||i>4))return blocked(Status.REJECT,"ILLEGAL_CHANNEL",normalized);
        if (channelIndexes.size() > 1) {
            return blocked(Status.REJECT, "CONFLICTING_OBJECTS", normalized);
        }

        if(PcSemanticGuard.explicitConfigurationDisable(raw,"AUTOSet:KEEPcoup","OFF")||PcSemanticGuard.explicitConfigurationDisable(raw,"AUTOSet:OPENch","OFF"))return blocked(Status.NO_MATCH,"EXPLICIT_AUTOSET_SUBSETTING_DISABLE",normalized);
        boolean actualQuery=PcSemanticGuard.requestedQuery(normalized.raw),polite=PcSemanticGuard.chinesePoliteSetting(normalized.raw);
        if(PcSemanticGuard.chineseInformationQuestion(normalized.raw)&&!actualQuery)return blocked(Status.NO_MATCH,"INFORMATION_OR_PERMISSION_NOT_A_CONTROL_REQUEST",normalized);
        boolean query = actualQuery;
        boolean open = !query&&(PcSemanticGuard.naturalDisplay(input)||raw.matches("(?s).*(?:打开|开启|启用|显示).*"));
        boolean close = !query&&(PcSemanticGuard.naturalDisplayOff(input)||raw.matches("(?s).*(?:关闭|关掉|关上|隐藏|禁用).*"));
        if(raw.matches("(?s).*(?:启用|显示)状态.*(?:设为|调成|调到|设置为)关闭.*"))open=false;
        if (open && close) return blocked(Status.REJECT, "CONFLICTING_ACTIONS", normalized);
        if (!actualQuery&&has(normalized, ChineseNumericNormalizer.TokenType.NEGATION) && (open || close
                || raw.matches("(?s).*(?:设置|设为|设成|设在|调到|调整|改为|改成|清除|清空|显示|隐藏|关闭|打开).*$"))) {
            return blocked(Status.REJECT, "PROHIBITED_ACTION", normalized);
        }

        // A separator between the leading index and 通道 can also be a list marker.
        // Do not bind its numbers as a different channel/value pair on the first decode.
        if (raw.matches("(?s)^\\s*[1-4一二三四]\\s*[,，、]\\s*(?:通道|频道).*$")) {
            return blocked(Status.CLARIFY, "AMBIGUOUS_CHANNEL_NUMBER_BOUNDARY", normalized);
        }

        List<ChineseNumericNormalizer.Token> rawNumbers=tokens(normalized,ChineseNumericNormalizer.TokenType.NUMBER);
        List<ChineseNumericNormalizer.Token> numbers = uniquePhysicalNumbers(normalized);
        if(numbers.size()<rawNumbers.size()&&!query&&raw.matches("(?s).*(?:并|且|和|；|;).*(?:设置|设为|打开|关闭|调整).*"))return blocked(Status.CLARIFY,"ORDERED_ACTION_BOUNDARIES_REQUIRED",normalized);
        if (numbers.size() > 1 && raw.matches("(?s).*(?:还是|或者|或|和|与|、|及).*$")) {
            return blocked(Status.REJECT, "CONFLICTING_VALUES", normalized);
        }
        // Edge coupling has its own enum contract; GND belongs only to channel input coupling.
        if(edgeCouplingObject(raw)){
            Set<String> values=new HashSet<>();
            for(String[] alias:new String[][]{{"交流|\\bAC\\b","AC"},{"直流|\\bDC\\b","DC"},{"高频抑制|\\bHFRej\\b","HFRej"},{"接地|\\bGND\\b","GND"}})
                if(Pattern.compile(alias[0],Pattern.CASE_INSENSITIVE).matcher(raw).find())values.add(alias[1]);
            if(values.size()>1)return blocked(Status.CLARIFY,"CONFLICTING_TRIGGER_COUPLING_ENUM_VALUES",normalized);
            if(query){if(!values.isEmpty())return blocked(Status.CLARIFY,"QUERY_WITH_COUPLING_ENUM_VALUE",normalized);if(edgeCouplingReadFrame(raw)==null)return blocked(Status.CLARIFY,"INCOMPLETE_TRIGGER_COUPLING_REQUEST_SPAN",normalized);return match(Status.MATCH_QUERY,"TRIGger:A:EDGE:COUPling?",Collections.emptyMap(),"TRIGGER_COUPLING_QUERY",normalized);}
            if(values.isEmpty())return blocked(Status.CLARIFY,"MISSING_ENUM_VALUE",normalized);
            String value=values.iterator().next();
            if(value.equals("GND"))return blocked(Status.REJECT,"ILLEGAL_TRIGGER_COUPLING_ENUM",normalized);
            if(edgeCouplingSetFrame(raw,value)==null)return blocked(Status.CLARIFY,"INCOMPLETE_TRIGGER_COUPLING_REQUEST_SPAN",normalized);
            return match(Status.MATCH_SET,"TRIGger:A:EDGE:COUPling",value,"TRIGGER_COUPLING_SET",normalized);
        }
        if (containsMultiple(raw, "自动", "单次", "正常")
                || containsMultiple(raw, "上升", "下降")
                || containsMultiple(raw, "直流", "交流", "接地")) {
            return blocked(Status.REJECT, "CONFLICTING_ENUM_VALUES", normalized);
        }

        Integer channel = channelIndexes.isEmpty() ? null : channelIndexes.iterator().next();
        // These qualified objects share words with analog/timebase families, not scope.
        if(raw.matches("(?is).*(?:数学波形|频谱|傅里叶|FFT|放大窗口|缩放视图|外部触发电平|触发电平.*自动|自动.*触发电平|标签显示).*"))return blocked(Status.NO_MATCH,"QUALIFIED_OBJECT_REQUIRES_ITS_OWN_FAMILY",normalized);
        if(raw.matches("(?s).*(?:自动设置过程|自动设置.*(?:保留|自动打开)).*"))return blocked(Status.NO_MATCH,"AUTOSET_CONFIGURATION_SCOPE",normalized);

        if (raw.matches("(?s).*(?:清除|清空|清一下).*(?:错误队列|仪器状态).*")
                || raw.matches("(?s).*(?:错误队列|仪器状态).*(?:清除|清空).*$")) {
            return match(Status.MATCH_ACTION, "*CLS", Collections.emptyMap(),
                    "CLEAR_STATUS_AND_ERROR_QUEUE", normalized);
        }
        if (query && raw.contains("标准事件状态寄存器")) {
            return match(Status.MATCH_QUERY, "*ESR?", Collections.emptyMap(),
                    "STANDARD_EVENT_STATUS_QUERY", normalized);
        }

        if (isVerticalScale(raw, normalized)||raw.matches("(?s).*每格(?:正|负|[+-])?半(?:毫伏|微伏|伏).*")) {
            if (channel == null) return blocked(Status.CLARIFY, "MISSING_CHANNEL", normalized);
            if(query)return match(Status.MATCH_QUERY,"CH"+channel+":SCALe?",Collections.emptyMap(),"VERTICAL_SCALE_QUERY",normalized);
            if (numbers.isEmpty()) return blocked(Status.CLARIFY, "MISSING_VALUE", normalized);
            if (numbers.size() != 1) return blocked(Status.REJECT, "CONFLICTING_VALUES", normalized);
            ChineseNumericNormalizer.Token unit = scaleUnit(normalized, "V/div", "V", raw);
            if (unit == null) return blocked(Status.CLARIFY, "MISSING_OR_CONFLICTING_UNIT", normalized);
            return match(Status.MATCH_SET, "CH" + channel + ":SCALe",
                    scaled(numbers.get(0), unit), "VERTICAL_SCALE_SET", normalized);
        }

        if (raw.contains("垂直位置")) {
            if (channel == null) return blocked(Status.CLARIFY, "MISSING_CHANNEL", normalized);
            if (query) return match(Status.MATCH_QUERY, "CH" + channel + ":POSition?",
                    Collections.emptyMap(), "VERTICAL_POSITION_QUERY", normalized);
            if (numbers.isEmpty()) return blocked(Status.CLARIFY, "MISSING_VALUE", normalized);
            if (numbers.size() != 1) return blocked(Status.REJECT, "CONFLICTING_VALUES", normalized);
            ChineseNumericNormalizer.Token unit = unit(normalized, "div");
            if (unit == null) return blocked(Status.CLARIFY, "MISSING_OR_CONFLICTING_UNIT", normalized);
            return match(Status.MATCH_SET, "CH" + channel + ":POSition",
                    scaled(numbers.get(0), unit), "VERTICAL_POSITION_SET", normalized);
        }

        if ((raw.contains("时基") || raw.contains("水平档位"))&&!raw.matches("(?s).*(?:采样率|记录长度|延迟|滚动).*")) {
            if (query) return match(Status.MATCH_QUERY, "HORizontal:SCAle?",
                    Collections.emptyMap(), "HORIZONTAL_SCALE_QUERY", normalized);
            if (numbers.isEmpty()) return blocked(Status.CLARIFY, "MISSING_VALUE", normalized);
            if (numbers.size() != 1) return blocked(Status.REJECT, "CONFLICTING_VALUES", normalized);
            ChineseNumericNormalizer.Token unit = scaleUnit(normalized, "s/div", "s", raw);
            if (unit == null) return blocked(Status.CLARIFY, "MISSING_OR_CONFLICTING_UNIT", normalized);
            return match(Status.MATCH_SET, "HORizontal:SCAle", scaled(numbers.get(0), unit),
                    "HORIZONTAL_SCALE_SET", normalized);
        }

        if (raw.contains("触发状态")) {
            if (!query) return blocked(Status.CLARIFY, "QUERY_INTENT_REQUIRED", normalized);
            return match(Status.MATCH_QUERY, "TRIGger:STATE?", Collections.emptyMap(),
                    "TRIGGER_STATE_QUERY", normalized);
        }

        if (raw.contains("触发模式")) {
            if (query) return match(Status.MATCH_QUERY, "TRIGger:A:MODe?",
                    Collections.emptyMap(), "TRIGGER_MODE_QUERY", normalized);
            String value = enumValue(raw, new String[][]{{"自动", "AUTO"}, {"正常", "NORMal"},
                    {"普通", "NORMal"}, {"单次", "SINGle"}});
            if (value == null) return blocked(Status.CLARIFY, "MISSING_ENUM_VALUE", normalized);
            return match(Status.MATCH_SET, "TRIGger:A:MODe", value, "TRIGGER_MODE_SET", normalized);
        }

        if (raw.contains("触发源")) {
            if (query) return match(Status.MATCH_QUERY, "TRIGger:A:EDGE:SOUrce?",
                    Collections.emptyMap(), "TRIGGER_SOURCE_QUERY", normalized);
            if (channel == null) return blocked(Status.CLARIFY, "MISSING_CHANNEL", normalized);
            return match(Status.MATCH_SET, "TRIGger:A:EDGE:SOUrce", "CH" + channel,
                    "TRIGGER_SOURCE_SET", normalized);
        }

        if(query&&raw.contains("触发")&&raw.contains("信号源")){
            if(raw.matches("(?is).*N边沿.*"))return match(Status.MATCH_QUERY,"TRIGger:A:NEDGe:SOUrce?",Collections.emptyMap(),"NEDGE_TRIGGER_SOURCE_QUERY",normalized);
            if(raw.contains("边沿触发"))return match(Status.MATCH_QUERY,"TRIGger:A:EDGE:SOUrce?",Collections.emptyMap(),"EDGE_TRIGGER_SOURCE_QUERY",normalized);
        }

        if (raw.contains("触发电平")||raw.contains("触发")&&raw.contains("门限")) {
            if (channel == null) return blocked(Status.CLARIFY, "MISSING_CHANNEL", normalized);
            if (query) return match(Status.MATCH_QUERY, "TRIGger:A:LEVel:CH" + channel + "?",
                    Collections.emptyMap(), "TRIGGER_LEVEL_QUERY", normalized);
            if (numbers.isEmpty()) return blocked(Status.CLARIFY, "MISSING_VALUE", normalized);
            if (numbers.size() != 1) return blocked(Status.REJECT, "CONFLICTING_VALUES", normalized);
            ChineseNumericNormalizer.Token unit = unit(normalized, "V");
            if (unit == null) return blocked(Status.CLARIFY, "MISSING_OR_CONFLICTING_UNIT", normalized);
            return match(Status.MATCH_SET, "TRIGger:A:LEVel:CH" + channel,
                    scaled(numbers.get(0), unit), "TRIGGER_LEVEL_SET", normalized);
        }

        if (raw.contains("触发边沿") || raw.contains("边沿触发")) {
            if (query) return match(Status.MATCH_QUERY, "TRIGger:A:EDGE:SLOpe?",
                    Collections.emptyMap(), "TRIGGER_EDGE_QUERY", normalized);
            String value = enumValue(raw, new String[][]{{"上升", "RISe"}, {"下降", "FALL"}});
            if (value == null) return blocked(Status.CLARIFY, "MISSING_ENUM_VALUE", normalized);
            return match(Status.MATCH_SET, "TRIGger:A:EDGE:SLOpe", value,
                    "TRIGGER_EDGE_SET", normalized);
        }

        if (raw.contains("采集平均次数")) {
            if(query)return match(Status.MATCH_QUERY,"ACQuire:NUMAVg?",Collections.emptyMap(),"ACQUISITION_AVERAGE_COUNT_QUERY",normalized);
            if (numbers.isEmpty()) return blocked(Status.CLARIFY, "MISSING_VALUE", normalized);
            if (numbers.size() != 1) return blocked(Status.REJECT, "CONFLICTING_VALUES", normalized);
            return match(Status.MATCH_SET, "ACQuire:NUMAVg", plain(numbers.get(0).numericValue),
                    "ACQUISITION_AVERAGE_COUNT_SET", normalized);
        }

        if (raw.contains("采集模式") || raw.contains("平均采集") || raw.contains("峰值采集")) {
            if (query) return match(Status.MATCH_QUERY, "ACQuire:MODe?", Collections.emptyMap(),
                    "ACQUISITION_MODE_QUERY", normalized);
            String value = enumValue(raw, new String[][]{{"平均", "AVERage"}, {"高分辨率", "HIRes"},
                    {"峰值", "PEAK"}, {"采样", "SAMPle"}});
            if (value == null) return blocked(Status.CLARIFY, "MISSING_ENUM_VALUE", normalized);
            return match(Status.MATCH_SET, "ACQuire:MODe", value, "ACQUISITION_MODE_SET", normalized);
        }

        if (raw.contains("探头倍率") || raw.contains("探头比")) {
            if (channel == null) return blocked(Status.CLARIFY, "MISSING_CHANNEL", normalized);
            if (numbers.isEmpty()) return blocked(Status.CLARIFY, "MISSING_VALUE", normalized);
            if (numbers.size() != 1) return blocked(Status.REJECT, "CONFLICTING_VALUES", normalized);
            if (unit(normalized, "ratio") == null) {
                return blocked(Status.CLARIFY, "MISSING_OR_CONFLICTING_UNIT", normalized);
            }
            return match(Status.MATCH_SET, "CH" + channel + ":PRObe:GAIN",
                    plain(numbers.get(0).numericValue), "PROBE_GAIN_SET", normalized);
        }

        if (raw.contains("耦合方式") || raw.matches("(?s).*(?:通道|CH).*(?:直流耦合|交流耦合|接地).*$")) {
            if (channel == null) return blocked(Status.CLARIFY, "MISSING_CHANNEL", normalized);
            if (query) return match(Status.MATCH_QUERY, "CH" + channel + ":COUPling?",
                    Collections.emptyMap(), "CHANNEL_COUPLING_QUERY", normalized);
            String value = enumValue(raw, new String[][]{{"直流", "DC"}, {"交流", "AC"},
                    {"接地", "GND"}});
            if (value == null) return blocked(Status.CLARIFY, "MISSING_ENUM_VALUE", normalized);
            return match(Status.MATCH_SET, "CH" + channel + ":COUPling", value,
                    "CHANNEL_COUPLING_SET", normalized);
        }

        if (raw.contains("带宽限制")) {
            if (channel == null) return blocked(Status.CLARIFY, "MISSING_CHANNEL", normalized);
            if (query) return match(Status.MATCH_QUERY, "CH" + channel + ":BANdwidth?",
                    Collections.emptyMap(), "CHANNEL_BANDWIDTH_QUERY", normalized);
            if (close) return match(Status.MATCH_SET, "CH" + channel + ":BANdwidth", "FULl",
                    "CHANNEL_BANDWIDTH_FULL", normalized);
            if (numbers.isEmpty()) return blocked(Status.CLARIFY, "MISSING_VALUE", normalized);
            if (numbers.size() != 1) return blocked(Status.REJECT, "CONFLICTING_VALUES", normalized);
            ChineseNumericNormalizer.Token unit = unit(normalized, "Hz");
            if (unit == null) return blocked(Status.CLARIFY, "MISSING_OR_CONFLICTING_UNIT", normalized);
            return match(Status.MATCH_SET, "CH" + channel + ":BANdwidth",
                    scaled(numbers.get(0), unit), "CHANNEL_BANDWIDTH_SET", normalized);
        }

        int source = sourceIndex(raw);
        if (source == -1) return blocked(Status.REJECT, "ILLEGAL_OBJECT_INDEX", normalized);
        if (source > 0 && (raw.contains("频率") || raw.contains("幅值") || raw.contains("相位"))) {
            String suffix = raw.contains("频率") ? "FREQuency" : raw.contains("幅值") ? "AMPLitude" : "PHASe";
            String expectedUnit = raw.contains("频率") ? "Hz" : raw.contains("幅值") ? "V" : "deg";
            String head = "AFG:SOURce" + source + ":" + suffix;
            if (query) return match(Status.MATCH_QUERY, head + "?", Collections.emptyMap(),
                    "AFG_PARAMETER_QUERY", normalized);
            if (numbers.isEmpty()) return blocked(Status.CLARIFY, "MISSING_VALUE", normalized);
            if (numbers.size() != 1) return blocked(Status.REJECT, "CONFLICTING_VALUES", normalized);
            ChineseNumericNormalizer.Token unit = unit(normalized, expectedUnit);
            if (unit == null) return blocked(Status.CLARIFY, "MISSING_OR_CONFLICTING_UNIT", normalized);
            return match(Status.MATCH_SET, head, scaled(numbers.get(0), unit),
                    "AFG_PARAMETER_SET", normalized);
        }

        if (raw.contains("波特图点数") || raw.contains("测试点数")) {
            if (query) return match(Status.MATCH_QUERY, "FRANalysis:DECade:POINts?",
                    Collections.emptyMap(), "FRA_POINTS_QUERY", normalized);
            if (numbers.isEmpty()) return blocked(Status.CLARIFY, "MISSING_VALUE", normalized);
            if (numbers.size() != 1) return blocked(Status.REJECT, "CONFLICTING_VALUES", normalized);
            ChineseNumericNormalizer.Token unit = unit(normalized, "points");
            if (unit == null) return blocked(Status.CLARIFY, "MISSING_OR_CONFLICTING_UNIT", normalized);
            return match(Status.MATCH_SET, "FRANalysis:DECade:POINts",
                    scaled(numbers.get(0), unit), "FRA_POINTS_SET", normalized);
        }

        if (query && raw.matches("(?s).*(?:通道|频道|CH).*(?:状态|显示状态|开关状态|是否(?:开启|打开|关闭|关掉|启用)).*$")) {
            if (channel == null) return blocked(Status.CLARIFY, "MISSING_CHANNEL", normalized);
            return match(Status.MATCH_QUERY, "SELect:CH" + channel + "?",
                    Collections.emptyMap(), "CHANNEL_STATE_QUERY", normalized);
        }

        if (channel != null && (open || close) && (PcSemanticGuard.naturalDisplay(input)||cleanChannelAction(normalized.nfkc)||polite&&cleanPoliteChannelAction(raw))) {
            return match(Status.MATCH_ACTION, "SELect:CH" + channel, open ? "ON" : "OFF",
                    "CHANNEL_STATE_ACTION", normalized);
        }
        return blocked(Status.NO_MATCH, "NO_UNIQUE_COMMAND_FAMILY", normalized);
    }

    private Decision match(Status status, String command, Object parameter, String reason,
                           ChineseNumericNormalizer.Result normalized) {
        Map entry = entries.get(command.toUpperCase(Locale.ROOT));
        if (entry == null) return blocked(Status.NO_MATCH, "COMMAND_NOT_IN_EXECUTABLE_CATALOG", normalized);
        if (status == Status.MATCH_SET && command.matches("(?i)(?:CH[1-4]:SCALe|HORizontal:SCAle)")) {
            String range = SemanticPolicy.explicitScaleRangeIssue(command, new BigDecimal(parameter.toString()));
            if (range != null) return blocked(Status.REJECT, "AUTHORITATIVE_PARAMETER_REJECTED_" + range, normalized);
        }
        ScpiTableEligibility.Decision qualification = table.qualify(command, parameter);
        if (qualification.status == ScpiTableEligibility.Status.OUT_OF_TABLE_REJECTED
                || qualification.status == ScpiTableEligibility.Status.INJECTION_REJECTED
                || qualification.status == ScpiTableEligibility.Status.PARAMETER_CLARIFICATION_REQUIRED) {
            return blocked(Status.REJECT, "AUTHORITATIVE_PARAMETER_REJECTED_" + qualification.reason,
                    normalized);
        }
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("intent", entry.get("intent").toString());
        candidate.put("SCPIcommand", table.canonicalHead(command));
        candidate.put("SCPIparams", parameter);
        return new Decision(status, reason, candidate, evidence(normalized), normalized);
    }

    private static Decision blocked(Status status, String reason,
                                    ChineseNumericNormalizer.Result normalized) {
        return new Decision(status, reason, null, evidence(normalized), normalized);
    }

    private static boolean isVerticalScale(String raw, ChineseNumericNormalizer.Result result) {
        return raw.matches("(?is).*(?:垂直档位|垂直刻度|通道档位|通道刻度|每格电压|每格伏数|伏每格|毫伏每格|微伏每格).*")
                || raw.matches("(?s).*(?:每格|每一格)(?:对应|代表|表示).*" )
                || unit(result, "V/div") != null;
    }

    private static boolean containsMultiple(String raw, String... values) {
        int count = 0;
        for (String value : values) if (raw.contains(value)) count++;
        return count > 1;
    }

    private static boolean cleanChannelAction(String raw) {
        String residue = raw.replaceAll("(?iu)(?:请|帮我|麻烦|给我|把|将|打开|开启|启用|"
                + "关闭|关掉|关上|禁用|隐藏|显示|通道|频道|CH|[0-9]+|[一二三四]|的|状态|"
                + "开关|一下|吧|[，。！？、,.!?]|\\s)+", "");
        return residue.isEmpty();
    }
    private static boolean cleanPoliteChannelAction(String raw){return raw.replaceAll("(?iu)(?:请|这里|帮我|替它|替我|给我|把|将|打开|开启|启用|关闭|关掉|隐藏|显示|通道|频道|CH|[0-9]+|[一二三四]|模拟|输入|这个|它|的|是|波形|状态|开关|一下|吧|[，。！？、,.!?]|\\s)+","").isEmpty();}

    private static String enumValue(String raw, String[][] values) {
        String found = null;
        for (String[] value : values) if (raw.contains(value[0])) {
            if (found != null && !found.equals(value[1])) return null;
            found = value[1];
        }
        return found;
    }

    /** 0=no source object, -1=explicit invalid source object. */
    private static int sourceIndex(String raw) {
        Matcher matcher = Pattern.compile("(?iu)(?:AFG|信号源|信号源通道|源)\\s*([0-9]+|[零〇一二两三四五六七八九十]+)")
                .matcher(raw);
        if (!matcher.find()) return 0;
        Double value = ParameterSourceGuard.number(matcher.group(1));
        if (value == null || value != Math.rint(value)) return -1;
        int index = value.intValue();
        return index >= 1 && index <= 2 ? index : -1;
    }

    private static String scaled(ChineseNumericNormalizer.Token number,
                                 ChineseNumericNormalizer.Token unit) {
        return plain(number.numericValue.multiply(unit.multiplier));
    }

    private static String plain(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() < 0) normalized = normalized.setScale(0);
        return normalized.toPlainString();
    }

    private static ChineseNumericNormalizer.Token unit(ChineseNumericNormalizer.Result result,
                                                       String canonical) {
        ChineseNumericNormalizer.Token found = null;
        for (ChineseNumericNormalizer.Token token : result.tokens) {
            if (token.type != ChineseNumericNormalizer.TokenType.UNIT
                    || !canonical.equals(token.canonicalUnit)) continue;
            if (found != null) {if(tokens(result,ChineseNumericNormalizer.TokenType.NUMBER).size()>1&&uniquePhysicalNumbers(result).size()==1)continue;return null;}
            found = token;
        }
        return found;
    }

    /** A leading 每格 supplies the denominator only to its adjacent typed physical quantity. */
    private static ChineseNumericNormalizer.Token scaleUnit(ChineseNumericNormalizer.Result result,
                                                            String perDivision, String physical,
                                                            String raw) {
        ChineseNumericNormalizer.Token compound = unit(result, perDivision);
        if (compound != null) return compound;
        ChineseNumericNormalizer.Token scalar = unit(result, physical);
        List<ChineseNumericNormalizer.Token> numbers = uniquePhysicalNumbers(result);
        if (scalar == null || numbers.size() != 1) return null;
        ChineseNumericNormalizer.Token number = numbers.get(0);
        int denominator = raw.indexOf("每格");
        if (denominator < 0 || denominator >= number.rawStart || number.rawEnd > scalar.rawStart
                || !raw.substring(number.rawEnd, scalar.rawStart).trim().isEmpty()) return null;
        return scalar;
    }

    private static boolean has(ChineseNumericNormalizer.Result result,
                               ChineseNumericNormalizer.TokenType type) {
        return !tokens(result, type).isEmpty();
    }

    /** Keep the original tokens/evidence; only the numeric meaning used for one leaf is unique. */
    private static List<ChineseNumericNormalizer.Token> uniquePhysicalNumbers(ChineseNumericNormalizer.Result result){
        Map<String,ChineseNumericNormalizer.Token> meanings=new LinkedHashMap<>();
        for(ChineseNumericNormalizer.Token number:tokens(result,ChineseNumericNormalizer.TokenType.NUMBER)){
            ChineseNumericNormalizer.Token unit=null;for(ChineseNumericNormalizer.Token candidate:result.tokens)if(candidate.type==ChineseNumericNormalizer.TokenType.UNIT&&candidate.nfkcStart>=number.nfkcEnd&&result.nfkc.substring(number.nfkcEnd,candidate.nfkcStart).trim().isEmpty()){unit=candidate;break;}
            BigDecimal value=unit==null?number.numericValue:number.numericValue.multiply(unit.multiplier);String key=(unit==null?"dimensionless":unit.canonicalUnit)+":"+plain(value);meanings.putIfAbsent(key,number);
        }
        return new ArrayList<>(meanings.values());
    }

    private static List<ChineseNumericNormalizer.Token> tokens(
            ChineseNumericNormalizer.Result result, ChineseNumericNormalizer.TokenType type) {
        List<ChineseNumericNormalizer.Token> found = new ArrayList<>();
        for (ChineseNumericNormalizer.Token token : result.tokens) if (token.type == type) found.add(token);
        return found;
    }

    private static List<Map<String, Object>> evidence(ChineseNumericNormalizer.Result result) {
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (ChineseNumericNormalizer.Token token : result.tokens) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", token.type.name());
            item.put("source_text", result.raw.substring(token.rawStart, token.rawEnd));
            item.put("original_asr_text", result.raw);
            item.put("normalized_input_text", result.normalized);
            item.put("source_start", token.rawStart);
            item.put("source_end", token.rawEnd);
            item.put("normalized_source_start", token.normalizedStart);
            item.put("normalized_source_end", token.normalizedEnd);
            item.put("normalized_value", token.numericValue == null
                    ? token.normalizedText : plain(token.numericValue));
            item.put("canonical_unit", token.canonicalUnit == null ? "" : token.canonicalUnit);
            item.put("unit_multiplier", token.multiplier == null ? "" : token.multiplier.toPlainString());
            item.put("rule_id", token.conversionRule);
            item.put("valid", token.valid);
            evidence.add(Collections.unmodifiableMap(item));
        }
        return evidence;
    }
}
