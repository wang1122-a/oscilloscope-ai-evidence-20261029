package com.jmu.text2scpi.voice;
import java.util.*;
import java.util.regex.*;
/**
 * 原始语音意图与最终 SCPI 的一致性核对。重点保护通道及操作语义，防止规则合法但与输入不一致的结果直接发送。
 */
public final class VoiceIntentGuard {
    private VoiceIntentGuard() {
    }
    /** 比较输入文字与最终 SCPI 的通道和数值单位；返回拒绝原因或 null，防止合法命令偏离原意。 */
    public static String rejection(String text,String scpi) {
        if(Pattern.compile("不要|别(?:给|把|开|关|设|调)|不需要|不用|取消").matcher(text).find()) return "检测到否定或取消表达，请重新说出单条操作";
        Set<String> channels=new HashSet<>();
        Matcher m=Pattern.compile("(?:CH|通道)\\s*([1-9])",Pattern.CASE_INSENSITIVE).matcher(SpokenNumbers.normalize(text));
        while(m.find()) channels.add(m.group(1));
        m=Pattern.compile("(?:^|:)CH([1-9])(?:[:?]|$)",Pattern.CASE_INSENSITIVE).matcher(scpi);
        String target=m.find()?m.group(1):null;
        if(channels.size()>1||(channels.size()==1&&target!=null&&!channels.contains(target))) return "识别通道与模型命令不一致，请重说";
        boolean query=scpi.split(" ",2)[0].endsWith("?");
        if(Pattern.compile("查询|查看|多少|是否|了吗|什么").matcher(text).find()&&!query) return "查询意图与设置命令不一致，请重说";
        if(Pattern.compile("设置|设为|设成|调到").matcher(text).find()&&query) return "设置意图与查询命令不一致，请重说";
        if(!query) {
            m=Pattern.compile("([+-]?[0-9]+(?:\\.[0-9]+)?)\\s*(毫伏|伏|mV|V|兆赫兹|兆赫|MHz|千赫兹|千赫|kHz|赫兹|Hz|毫秒|微秒|纳秒|秒|ms|us|ns|s)(?:每格|/div)?",Pattern.CASE_INSENSITIVE).matcher(SpokenNumbers.normalize(text));
            int count=0;
            double expected=0;
            while(m.find()) {
                count++;
                String u=m.group(2).toLowerCase(Locale.ROOT);
                double multiplier=u.equals("毫伏")||u.equals("mv")||u.equals("毫秒")||u.equals("ms")?1e-3:u.equals("微秒")||u.equals("us")?1e-6:u.equals("纳秒")||u.equals("ns")?1e-9:u.equals("兆赫兹")||u.equals("兆赫")||u.equals("mhz")?1e6:u.equals("千赫兹")||u.equals("千赫")||u.equals("khz")?1e3:1;
                expected=Double.parseDouble(m.group(1))*multiplier;
            }
            if(count==1&&scpi.contains(" ")) {
                try {
                    double actual=Double.parseDouble(scpi.substring(scpi.indexOf(' ')+1));
                    if(Math.abs(expected-actual)>1e-6*Math.max(Math.abs(expected),1e-12)) return "识别数值或单位与模型参数不一致，请重说";
                }
                catch(NumberFormatException ignored) {
                }
            }
        }
        return null;
    }
}
