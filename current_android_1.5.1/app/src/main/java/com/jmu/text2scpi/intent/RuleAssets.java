package com.jmu.text2scpi.intent;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import kotlin.Pair;
import kotlinx.serialization.json.*;
/**
 * 读取随应用提供的 SCPI 命令目录、约束、别名等规则资产，构建规则查询所需的数据结构。
 */
public final class RuleAssets {
    private final JsonObject units,parameterAliases,baseCapabilities,adsCapabilities;
    private final Map<String,String> intentAliases,commandAliases;
    private final Set<String> protectedExactCommands,protectedCommandPrefixes,registeredIntents;
    private final Map<String,Set<String>> blockedParameterValues=new LinkedHashMap<>();
    private final Set<Pair<String,String>> equivalentParameterTypes=new LinkedHashSet<>();
    private final Map<String,CommandRule> catalog=new LinkedHashMap<>();
    /** 从 assets/rules 读取命令目录和配套规则，合并 ADS10000A 能力并建立按命令查询的索引。 */
    public RuleAssets(Context context) {
        JsonObject aliases=readObject(context,"rules/scpi_aliases.json"),compat=readObject(context,"rules/scpi_compatibility.json");
        units=readObject(context,"rules/scpi_units.json");
        parameterAliases=object(aliases.get("parameter_aliases"));
        intentAliases=stringMap(object(aliases.get("intent_aliases")));
        commandAliases=new LinkedHashMap<>();
        for(Map.Entry<String,String> e:stringMap(object(compat.get("command_aliases"))).entrySet()) commandAliases.put(normalizeCommand(e.getKey()),normalizeCommand(e.getValue()));
        protectedExactCommands=commands(compat.get("protected_exact_commands"));
        protectedCommandPrefixes=commands(compat.get("protected_command_prefixes"));
        for(Map.Entry<String,JsonElement> e:object(compat.get("blocked_parameter_values")).entrySet()) blockedParameterValues.put(normalizeCommand(e.getKey(),false),upper(strings(e.getValue())));
        baseCapabilities=object(readObject(context,"rules/device_profile.json").get("capabilities"));
        Map<String,JsonElement> capabilities=new LinkedHashMap<>(baseCapabilities);
        capabilities.putAll(object(object(compat.get("ads10000a_profile")).get("capabilities")));
        adsCapabilities=new JsonObject(capabilities);
        for(JsonElement e:array(compat.get("equivalent_parameter_types"))) {
            JsonArray a=(JsonArray)e;
            equivalentParameterTypes.add(new Pair<>(content(a.get(0)),content(a.get(1))));
        }
        for(JsonElement e:array(readObject(context,"rules/scpi_command_catalog.json").get("commands"))) {
            JsonObject o=object(e);
            String command=content(o.get("SCPIcommand"));
            Set<Double> discrete=new LinkedHashSet<>();
            for(JsonElement n:array(o.get("discrete_values"))) {
                Double value=number(n);
                if(value!=null)discrete.add(value);
            }
            CommandRule rule=new CommandRule(command,content(o.get("intent")),content(o.get("query_or_set")),content(o.get("parameter_type")),strings(o.get("allowed_units")),upper(strings(o.get("enum_values"))),discrete,number(o.get("min_value")),number(o.get("max_value")),bool(o.get("supports_query")),object(o.get("capability_requirements")),bool(o.get("capability_check_required")),bool(o.get("requires_ascii_quotes")));
            if(catalog.put(normalizeCommand(command),rule)!=null) throw new IllegalStateException("规则目录存在重复命令："+command);
        }
        registeredIntents=new LinkedHashSet<>();
        for(CommandRule rule:catalog.values())registeredIntents.add(rule.intent);
    }
    public JsonObject getUnits() {
        return units;
    }
    public JsonObject getParameterAliases() {
        return parameterAliases;
    }
    public Map<String,String> getIntentAliases() {
        return Collections.unmodifiableMap(intentAliases);
    }
    public Map<String,String> getCommandAliases() {
        return Collections.unmodifiableMap(commandAliases);
    }
    public Map<String,CommandRule> getCatalog() {
        return Collections.unmodifiableMap(catalog);
    }
    public Set<String> getRegisteredIntents() {
        return Collections.unmodifiableSet(registeredIntents);
    }
    public Set<Pair<String,String>> getEquivalentParameterTypes() {
        return Collections.unmodifiableSet(equivalentParameterTypes);
    }
    public boolean isProtectedCommand(String command) {
        String normalized=normalizeCommand(command);
        if(protectedExactCommands.contains(normalized))return true;
        for(String prefix:protectedCommandPrefixes)if(normalized.startsWith(prefix))return true;
        return false;
    }
    public boolean isBlockedParameter(String command,JsonPrimitive parameter) {
        Set<String> blocked=blockedParameterValues.get(normalizeCommand(command,false));
        return blocked!=null&&blocked.contains(parameter.getContent().toUpperCase(Locale.ROOT));
    }
    /** 检查当前设备是否满足命令能力要求；不满足时返回原因，通过时返回 null。 */
    public String capabilityBlockReason(CommandRule rule,boolean ads10000a) {
        if(!rule.capabilityCheckRequired)return null;
        if(!ads10000a)return "device_capability_unknown";
        for(Map.Entry<String,JsonElement> e:rule.capabilityRequirements.entrySet()) {
            String key=e.getKey();
            if(key.equals("requires_la")) {
                if(bool(e.getValue())&&!bool(baseCapabilities.get("has_la")))return "requires_la";
            }
            else if(key.equals("min_channel_count")) {
                Double required=number(e.getValue()),actual=number(adsCapabilities.get("channel_count"));
                if(required==null)return "invalid_capability_requirement";
                if(actual==null||actual<required)return "channel_count";
            }
            else {
                JsonElement actual=adsCapabilities.get(key);
                if(actual==null)return "device_capability_unknown:"+key;
                if(!content(actual).equals(content(e.getValue())))return "device_capability_not_available:"+key;
            }
        }
        return null;
    }
    public static String normalizeCommand(String command) {
        return normalizeCommand(command,true);
    }
    public static String normalizeCommand(String command,boolean keepQuery) {
        String v=command.trim();
        if(v.startsWith(":"))v=v.substring(1);
        v=v.toUpperCase(Locale.ROOT);
        if(!keepQuery&&v.endsWith("?"))v=v.substring(0,v.length()-1);
        return v;
    }
    private static JsonObject readObject(Context context,String path) {
        try(InputStream in=context.getAssets().open(path)) {
            return (JsonObject)Json.Default.parseToJsonElement(AssetInstaller.readUtf8(in));
        }
        catch(IOException e) {
            throw new IllegalStateException("规则读取失败："+path,e);
        }
    }
    static String content(JsonElement e) {
        return e instanceof JsonPrimitive?((JsonPrimitive)e).getContent():"";
    }
    static Double number(JsonElement e) {
        try {
            return Double.valueOf(content(e));
        }
        catch(NumberFormatException ex) {
            return null;
        }
    }
    static boolean bool(JsonElement e) {
        return content(e).equals("true");
    }
    private static JsonObject object(JsonElement e) {
        return e instanceof JsonObject?(JsonObject)e:new JsonObject(Collections.emptyMap());
    }
    private static JsonArray array(JsonElement e) {
        return e instanceof JsonArray?(JsonArray)e:new JsonArray(Collections.emptyList());
    }
    private static Set<String> strings(JsonElement e) {
        Set<String> s=new LinkedHashSet<>();
        for(JsonElement v:array(e))if(v instanceof JsonPrimitive&&v!=JsonNull.INSTANCE)s.add(content(v));
        return s;
    }
    private static Set<String> upper(Set<String> input) {
        Set<String>s=new LinkedHashSet<>();
        for(String v:input)s.add(v.toUpperCase(Locale.ROOT));
        return s;
    }
    private static Set<String> commands(JsonElement e) {
        Set<String>s=new LinkedHashSet<>();
        for(String v:strings(e))s.add(normalizeCommand(v));
        return s;
    }
    private static Map<String,String> stringMap(JsonObject object) {
        Map<String,String>m=new LinkedHashMap<>();
        for(Map.Entry<String,JsonElement>e:object.entrySet())m.put(e.getKey(),content(e.getValue()));
        return m;
    }
}
