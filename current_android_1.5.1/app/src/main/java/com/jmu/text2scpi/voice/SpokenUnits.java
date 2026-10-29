package com.jmu.text2scpi.voice;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.*;
/**
 * 规范化口语中的工程单位与量值表达。供确定性输入处理使用，不能替代最终命令范围校验。
 */
public final class SpokenUnits {
    private SpokenUnits() {
    }
    public static String normalize(String text) {
        Matcher m=Pattern.compile("([+-]?[0-9]+(?:\\.[0-9]+)?)\\s*(兆赫兹|兆赫|MHz|千赫兹|千赫|kHz|毫伏|mV|毫秒|ms|微秒|us|纳秒|ns)",Pattern.CASE_INSENSITIVE).matcher(SpokenNumbers.normalize(text));
        StringBuffer out=new StringBuffer();
        while(m.find()) {
            String u=m.group(2).toLowerCase(Locale.ROOT);
            int exponent=u.equals("兆赫兹")||u.equals("兆赫")||u.equals("mhz")?6:u.equals("千赫兹")||u.equals("千赫")||u.equals("khz")?3:u.equals("微秒")||u.equals("us")?-6:u.equals("纳秒")||u.equals("ns")?-9:-3;
            String suffix=u.equals("毫伏")||u.equals("mv")?"V":u.equals("毫秒")||u.equals("ms")||u.equals("微秒")||u.equals("us")||u.equals("纳秒")||u.equals("ns")?"s":"Hz";
            m.appendReplacement(out,Matcher.quoteReplacement(new BigDecimal(m.group(1)).scaleByPowerOfTen(exponent).stripTrailingZeros().toPlainString()+suffix));
        }
        m.appendTail(out);
        return out.toString();
    }
}
