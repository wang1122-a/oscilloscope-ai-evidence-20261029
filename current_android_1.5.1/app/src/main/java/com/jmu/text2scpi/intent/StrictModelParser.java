package com.jmu.text2scpi.intent;
import com.jmu.text2scpi.model.ModelIntent;
import java.util.*;
import java.util.regex.Pattern;
import kotlinx.serialization.json.*;
/**
 * 严格解析模型返回的 JSON。字段顺序必须为 intent、SCPIcommand、SCPIparams，拒绝额外说明、思考标签和非法参数类型。
 */
public final class StrictModelParser {
    private static final Pattern ORDERED=Pattern.compile("^\\s*\\{\\s*\"intent\"\\s*:\\s*\"(?:\\\\.|[^\"\\\\])*\"\\s*,\\s*\"SCPIcommand\"\\s*:\\s*\"(?:\\\\.|[^\"\\\\])*\"\\s*,\\s*\"SCPIparams\"\\s*:\\s*.+\\}\\s*$",Pattern.DOTALL);
    /** 模型输出必须是严格三字段对象。参数仅允许字符串、有限数字或空对象；非法输出抛异常而不是猜测修补。 */
    public ModelIntent parse(String output) {
        String text=output.trim(),lower=text.toLowerCase(Locale.ROOT);
        require(!text.isEmpty(),"模型输出为空");
        require(!lower.contains(new String(new char[] {
            96,96,96
        }
        )),"模型输出包含 Markdown");
        require(!lower.contains("<think>")&&!lower.contains("</think>"),"模型输出包含思考标签");
        require(ORDERED.matcher(text).matches(),"模型输出字段必须严格依次为 intent、SCPIcommand、SCPIparams");
        JsonElement parsed=Json.Default.parseToJsonElement(text);
        require(parsed instanceof JsonObject,"模型输出必须为对象");
        JsonObject root=(JsonObject)parsed;
        require(new ArrayList<>(root.keySet()).equals(Arrays.asList("intent","SCPIcommand","SCPIparams")),"模型输出字段非法");
        String intent=string(root.get("intent")),command=string(root.get("SCPIcommand"));
        require(!intent.trim().isEmpty()&&!command.trim().isEmpty(),"intent 或 SCPIcommand 为空");
        JsonElement params=root.get("SCPIparams");
        require(params!=null&&!(params instanceof JsonArray),"SCPIparams 不允许数组");
        if(params instanceof JsonObject) require(((JsonObject)params).isEmpty(),"SCPIparams 对象只能是空对象");
        if(params instanceof JsonPrimitive&&!((JsonPrimitive)params).isString()) {
            String content=((JsonPrimitive)params).getContent();
            require(!content.equals("true")&&!content.equals("false")&&!content.equals("null"),"SCPIparams 只允许字符串、有限数字或空对象");
            try {
                require(Double.isFinite(Double.parseDouble(content)),"SCPIparams 数字非法");
            }
            catch(NumberFormatException e) {
                throw new IllegalArgumentException("SCPIparams 数字非法",e);
            }
        }
        return new ModelIntent(intent,command,params,root);
    }
    private static String string(JsonElement e) {
        return e instanceof JsonPrimitive&&((JsonPrimitive)e).isString()?((JsonPrimitive)e).getContent():"";
    }
    private static void require(boolean ok,String message) {
        if(!ok) throw new IllegalArgumentException(message);
    }
}
