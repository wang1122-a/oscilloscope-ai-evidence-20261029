package com.jmu.text2scpi.voice;
import java.util.regex.*;
/**
 * 将受支持的中文数字表达规范化为数值文字，为通道识别和参数解析提供一致输入。
 */
public final class SpokenNumbers {
    private static final String DIGITS="零一二三四五六七八九";
    private SpokenNumbers() {
    }
    public static String number(String source) {
        String[] parts=source.replace('两','二').replace('〇','零').split("点",-1);
        String result=Long.toString(integer(parts[0]));
        if(parts.length==2) {
            StringBuilder decimal=new StringBuilder(".");
            for(char c:parts[1].toCharArray()) decimal.append(Math.max(0,DIGITS.indexOf(c)));
            result+=decimal;
        }
        return result;
    }
    private static long integer(String s) {
        if(!Pattern.compile("[十百千万亿]").matcher(s).find()) {
            StringBuilder b=new StringBuilder();
            for(char c:s.toCharArray()) b.append(Math.max(0,DIGITS.indexOf(c)));
            try {
                return Long.parseLong(b.toString());
            }
            catch(NumberFormatException e) {
                return 0;
            }
        }
        long total=0,section=0,digit=0;
        for(char c:s.toCharArray()) {
            int d=DIGITS.indexOf(c);
            if(d>=0) digit=d;
            else {
                long unit=c=='十'?10:c=='百'?100:c=='千'?1000:c=='万'?10000:c=='亿'?100000000:1;
                if(unit<10000) {
                    section+=(digit==0?1:digit)*unit;
                    digit=0;
                }
                else {
                    section+=digit;
                    total=(total+section)*unit;
                    section=0;
                    digit=0;
                }
            }
        }
        return total+section+digit;
    }
    public static String normalize(String text) {
        Matcher m=Pattern.compile("[零〇一二两三四五六七八九十百千万亿]+(?:点[零〇一二三四五六七八九]+)?(?=\\s*(?:毫伏|伏|毫秒|微秒|纳秒|秒|兆赫|千赫|赫兹|倍|格|档|[VA]|$))").matcher(text);
        StringBuffer b=new StringBuffer();
        while(m.find()) m.appendReplacement(b,Matcher.quoteReplacement(number(m.group())));
        m.appendTail(b);
        m=Pattern.compile("(通道|CH)\\s*([一二两三四五六七八九])",Pattern.CASE_INSENSITIVE).matcher(b.toString());
        b=new StringBuffer();
        while(m.find()) m.appendReplacement(b,Matcher.quoteReplacement(m.group(1)+number(m.group(2))));
        m.appendTail(b);
        return b.toString();
    }
}
