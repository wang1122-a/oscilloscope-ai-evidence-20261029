package com.jmu.text2scpi.voice;
import java.util.*;
import java.util.regex.*;
/**
 * 中文指令的确定性规范化与输入核对。处理通道开关、耦合、数字和单位；否定、取消、缺参及模型语义冲突保持拒绝。
 */
public final class CommandInput {
    private static final Pattern CHANNEL=Pattern.compile("(?:通道|CH)\\s*([1-4])(?![0-9])",Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATIVE=Pattern.compile("不要|不需要|不用|取消|别");
    private CommandInput() {
    }
    /** 把受支持的通道开关、耦合、数字及单位表达统一成模型更稳定的输入形式。 */
    public static String normalize(String text) {
        String source=SpokenNumbers.normalize(text).trim().replaceAll("[。！？.!?]+$","");
        if(NEGATIVE.matcher(source).find()) return source;
        String normalized=CHANNEL.matcher(source).replaceAll("CH$1");
        String target="(CH[1-4])", opening="(打开|开启|开|关闭|关掉|关)";
        Matcher forward=Pattern.compile("^(?:请)?"+opening+"\\s*"+target+"(?:的?显示)?$").matcher(normalized);
        Matcher reverse=Pattern.compile("^(?:请)?(?:把|将)?"+target+"(?:的?显示)?"+opening+"$").matcher(normalized);
        if(forward.matches()) return (forward.group(1).startsWith("关")?"关闭":"打开")+forward.group(2);
        if(reverse.matches()) return (reverse.group(2).startsWith("关")?"关闭":"打开")+reverse.group(1);
        Matcher coupling=Pattern.compile("^(?:请)?(?:把|将)?"+target+"(?:的)?耦合(?:方式)?(?:设置为|设为|设成|设置成|改为|为)(交流|直流|接地|AC|DC|GND|GROUND)$",Pattern.CASE_INSENSITIVE).matcher(normalized);
        if(coupling.matches()) return "设置"+coupling.group(1).toUpperCase(Locale.ROOT)+"耦合为"+mode(coupling.group(2));
        coupling=Pattern.compile("^设置"+target+"(?:的)?耦合(?:方式)?(?:为)?(交流|直流|接地|AC|DC|GND|GROUND)$",Pattern.CASE_INSENSITIVE).matcher(normalized);
        if(coupling.matches()) return "设置"+coupling.group(1).toUpperCase(Locale.ROOT)+"耦合为"+mode(coupling.group(2));
        return SpokenUnits.normalize(normalized);
    }
    public static String mode(String value) {
        String s=value.trim().toUpperCase(Locale.ROOT);
        return s.equals("交流")?"AC":s.equals("直流")?"DC":s.equals("接地")||s.equals("GROUND")?"GND":s;
    }
    /** 推理前检查否定和缺参；传入 scpi 后继续比较耦合或开关语义。返回 null 表示此层未发现拒绝原因。 */
    public static String rejection(String text,String scpi) {
        String input=normalize(text);
        if(NEGATIVE.matcher(input).find()) return "否定或取消表达，未发送";
        if(Pattern.compile("(?:设置为|设为|设成|调到|改为)$").matcher(input).find()) return "设置缺少目标值，请补充后重说";
        boolean query=Pattern.compile("查询|查看|多少|是否|什么").matcher(input).find();
        if(input.contains("耦合")&&!query) {
            Set<String> modes=new HashSet<>();
            Matcher m=Pattern.compile("交流|直流|接地|GROUND|GND|AC|DC",Pattern.CASE_INSENSITIVE).matcher(input);
            while(m.find()) modes.add(mode(m.group()));
            if(modes.size()!=1) return "请明确指定一种耦合方式：交流、直流或接地";
            if(scpi!=null&&(!Pattern.compile("^:CH[1-4]:COUPLING ",Pattern.CASE_INSENSITIVE).matcher(scpi).find()||!mode(scpi.substring(scpi.indexOf(' ')+1)).equals(modes.iterator().next()))) return "模型耦合参数与指定模式不一致，未发送";
        }
        Matcher toggle=Pattern.compile("^(打开|关闭)(CH[1-4])$").matcher(input);
        if(toggle.matches()&&scpi!=null) {
            boolean on=toggle.group(1).equals("打开");
            String ch=toggle.group(2);
            if(!Pattern.compile("^:(?:SELECT:"+ch+"|"+ch+":DISPLAY) (?:"+(on?"ON|1":"OFF|0")+")$",Pattern.CASE_INSENSITIVE).matcher(scpi).matches()) return "模型开关操作与输入不一致，未发送";
        }
        return null;
    }
}
