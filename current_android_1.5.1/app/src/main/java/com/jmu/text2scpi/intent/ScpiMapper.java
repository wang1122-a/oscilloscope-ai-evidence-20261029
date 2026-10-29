package com.jmu.text2scpi.intent;
import com.jmu.text2scpi.model.*;
import com.jmu.text2scpi.voice.CommandInput;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;
import kotlinx.serialization.json.*;
/**
 * SCPI 规则校验与序列化。检查设备能力、命令、参数、单位和范围；只为符合规则的结果生成发送预览。
 */
public final class ScpiMapper {
    private final RuleAssets assets;
    private static final Pattern INTENT=Pattern.compile("^[A-Z][A-Z0-9]*_(?:query|set|action)$");
    private static final Pattern CHANNEL=Pattern.compile("(?:^|:)CH([0-9]+)(?::|$)",Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUE=Pattern.compile("^([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*([^\\s]*)$");
    public ScpiMapper(RuleAssets assets) {
        this.assets=assets;
    }
    /** 把模型候选转换为规则结论：核对意图、命令、设备与参数后，才生成可发送文本和可选回读查询。 */
    public RuleDecision evaluate(ModelIntent model,boolean ads10000a) {
        boolean applied=assets.getIntentAliases().containsKey(model.getIntent());
        String intent=assets.getIntentAliases().getOrDefault(model.getIntent(),model.getIntent());
        if(!INTENT.matcher(intent).matches()||!assets.getRegisteredIntents().contains(intent))return rejected(model,"未知 Intent："+model.getIntent());
        String command=RuleAssets.normalizeCommand(model.getScpiCommand()),alias=assets.getCommandAliases().get(RuleAssets.normalizeCommand(command,false));
        if(alias!=null) {
            command=alias+(command.endsWith("?")?"?":"");
            applied=true;
        }
        if(command.contains(";")||command.contains("\r")||command.contains("\n"))return rejected(model,"一次只允许一条 SCPI");
        if(assets.isProtectedCommand(command))return rejected(model,"厂家保护门禁：禁止预设、恢复、校准、文件或系统维护命令");
        // 只有已核验的四通道设备才允许 CH1～CH4；未知能力和越界通道直接拦截。
        Matcher ch=CHANNEL.matcher(command);
        if(ch.find()) {
            long channel;
            try {
                channel=Long.parseLong(ch.group(1));
            }
            catch(NumberFormatException e) {
                channel=-1;
            }
            if(!ads10000a||channel<1||channel>4)return new RuleDecision(RuleDisposition.RUNTIME_BLOCKED,model,null,null,"device_capability_unknown_or_unsupported_channel");
        }
        CommandRule rule=assets.getCatalog().get(command);
        if(rule==null)return rejected(model,"命令不在冻结目录："+command);
        if(!rule.intent.equals(intent))return rejected(model,"Intent 与命令目录不一致");
        String operation=command.endsWith("?")?"query":rule.operation;
        if(!intent.endsWith("_"+operation))return rejected(model,"Intent 与操作类型不一致");
        String reason=assets.capabilityBlockReason(rule,ads10000a);
        if(reason!=null)return new RuleDecision(RuleDisposition.RUNTIME_BLOCKED,model,null,null,reason);
        // 查询必须是空参数对象；设置按命令类型规范化并检查取值。
        JsonElement param;
        if(rule.parameterType.equals("query")) {
            if(!(model.getScpiParams() instanceof JsonObject)||!((JsonObject)model.getScpiParams()).isEmpty())return rejected(model,"查询参数必须为空对象");
            param=model.getScpiParams();
        }
        else {
            param=normalizeParameter(model,rule);
            if(param==null)return rejected(model,"参数不满足 "+rule.parameterType+" 约束");
            if(!param.equals(model.getScpiParams()))applied=true;
        }
        if(param instanceof JsonPrimitive&&assets.isBlockedParameter(rule.command,(JsonPrimitive)param))return rejected(model,"参数值未通过已审计设备兼容表");
        ModelIntent normalized=new ModelIntent(intent,rule.command,param,model.getRaw());
        // 规则通过后才生成单条协议文本，并为可查询设置附上回读命令。
        String wire=serialize(normalized);
        String verification=!wire.contains("?")&&rule.supportsQuery?":"+wire.split(" ",2)[0].replaceFirst("^:","")+"?":null;
        return new RuleDecision(applied?RuleDisposition.SAFELY_CORRECTED:RuleDisposition.VALID_UNCHANGED,normalized,wire,verification,null);
    }
    /** 按命令规则解析枚举、字符串或数值单位；无法满足约束时返回 null。 */
    private JsonPrimitive normalizeParameter(ModelIntent model,CommandRule rule) {
        if(!(model.getScpiParams() instanceof JsonPrimitive))return null;
        JsonPrimitive p=(JsonPrimitive)model.getScpiParams();
        if(!p.isString()) {
            Double value=RuleAssets.number(p);
            return value!=null&&validateNumber(value,rule)?numberPrimitive(value):null;
        }
        String source=p.getContent().trim(),aliased=source;
        JsonElement aliases=assets.getParameterAliases().get(rule.command);
        if(rule.command.matches("(?i)CH[1-4]:COUPLING"))aliased=CommandInput.mode(source);
        else if(aliases instanceof JsonObject&&((JsonObject)aliases).get(source) instanceof JsonPrimitive)aliased=RuleAssets.content(((JsonObject)aliases).get(source));
        for(String value:rule.enumValues)if(value.equalsIgnoreCase(aliased))return JsonElementKt.JsonPrimitive(value);
        if(rule.parameterType.equals("string")) {
            boolean valid=rule.requiresAsciiQuotes?aliased.length()>=2&&aliased.startsWith("\"")&&aliased.endsWith("\"")&&aliased.substring(1,aliased.length()-1).matches("[\\x20-\\x21\\x23-\\x7E]*"):aliased.matches("[\\x20-\\x7E]+");
            return valid?JsonElementKt.JsonPrimitive(aliased):null;
        }
        if(rule.parameterType.equals("enum")||rule.parameterType.equals("boolean"))return null;
        Matcher parsed=VALUE.matcher(aliased);
        if(!parsed.matches())return null;
        double number;
        try {
            number=Double.parseDouble(parsed.group(1));
        }
        catch(NumberFormatException e) {
            return null;
        }
        Double multiplier=unitMultiplier(rule.parameterType,parsed.group(2),rule.allowedUnits);
        return multiplier!=null&&validateNumber(number*multiplier,rule)?numberPrimitive(number*multiplier):null;
    }
    private Double unitMultiplier(String type,String unit,Set<String> allowed) {
        if(unit.trim().isEmpty())return 1.0;
        List<String> categories;
        switch(type) {
            case "time":categories=Arrays.asList("time");
            break;
            case "time_per_div":categories=Arrays.asList("time_per_div","time");
            break;
            case "voltage_per_div":categories=Arrays.asList("voltage_per_div","voltage");
            break;
            case "frequency_or_enum":categories=Arrays.asList("frequency");
            break;
            case "points":case "percentage":case "probe_ratio":case "divisions":categories=Arrays.asList(type);
            break;
            default:categories=Collections.emptyList();
        }
        for(String category:categories) {
            JsonElement table=assets.getUnits().get(category);
            if(table instanceof JsonObject)for(Map.Entry<String,JsonElement> e:((JsonObject)table).entrySet())if(e.getKey().equalsIgnoreCase(unit))return RuleAssets.number(e.getValue());
        }
        for(String value:allowed)if(value.equalsIgnoreCase(unit))return 1.0;
        return null;
    }
    /** 校验有限数值、上下限、离散集合及整数要求，供参数规范化统一调用。 */
    private boolean validateNumber(double value,CommandRule rule) {
        if(!Double.isFinite(value)||(rule.minValue!=null&&value<rule.minValue)||(rule.maxValue!=null&&value>rule.maxValue))return false;
        if(!rule.discreteValues.isEmpty()) {
            boolean found=false;
            for(double v:rule.discreteValues)if(Math.abs(v-value)<=1e-9*Math.max(1,Math.max(Math.abs(v),Math.abs(value))))found=true;
            if(!found)return false;
        }
        return !(Arrays.asList("integer","points","percentage").contains(rule.parameterType)&&value%1!=0);
    }
    /** 把已校验的规范化意图转成单条 SCPI；查询不附带参数，设置追加已验证的参数。 */
    private String serialize(ModelIntent model) {
        String command=model.getScpiCommand();
        if(command.startsWith(":"))throw new IllegalArgumentException("canonical command 不得带根冒号");
        String prefix=command.startsWith("*")?"":":";
        if(command.endsWith("?")||(model.getScpiParams() instanceof JsonObject&&((JsonObject)model.getScpiParams()).isEmpty()))return prefix+command;
        if(!(model.getScpiParams() instanceof JsonPrimitive))throw new IllegalStateException("设置参数不可序列化");
        return prefix+command+" "+((JsonPrimitive)model.getScpiParams()).getContent();
    }
    private static RuleDecision rejected(ModelIntent model,String reason) {
        return new RuleDecision(RuleDisposition.REJECTED_UNSAFE,model,null,null,reason);
    }
    private static JsonPrimitive numberPrimitive(double value) {
        BigDecimal decimal=BigDecimal.valueOf(value).stripTrailingZeros();
        try {
            return JsonElementKt.JsonPrimitive(Long.valueOf(decimal.toPlainString()));
        }
        catch(NumberFormatException e) {
            return JsonElementKt.JsonPrimitive(Double.valueOf(decimal.toPlainString()));
        }
    }
}
