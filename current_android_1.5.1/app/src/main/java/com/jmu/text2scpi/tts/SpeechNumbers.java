package com.jmu.text2scpi.tts;
import java.math.*;
/**
 * 将数值与单位转换成适合反馈朗读的中英文文本，供反馈模板使用。
 */
public final class SpeechNumbers {
    private static final String[] SMALL= {
        "zero","one","two","three","four","five","six","seven","eight","nine","ten","eleven","twelve","thirteen","fourteen","fifteen","sixteen","seventeen","eighteen","nineteen"
    }
    ;
    private static final String[] TENS= {
        "","","twenty","thirty","forty","fifty","sixty","seventy","eighty","ninety"
    }
    ;
    private static final String DIGITS="零一二三四五六七八九";
    private SpeechNumbers() {
    }
    public static String number(BigDecimal value,FeedbackLanguage language) {
        boolean en=language==FeedbackLanguage.ENGLISH;
        String sign=value.signum()<0?(en?"minus ":"负"):"";
        String[] parts=value.abs().stripTrailingZeros().toPlainString().split("\\.",-1);
        long integer=Long.parseLong(parts[0]);
        String whole=en?english(integer):chinese(integer);
        if(parts.length==1)return sign+whole;
        StringBuilder result=new StringBuilder(sign+whole+(en?" point ":"点"));
        for(char c:parts[1].toCharArray()) {
            if(en&&result.charAt(result.length()-1)!=' ')result.append(' ');
            result.append(en?SMALL[c-'0']:DIGITS.substring(c-'0',c-'0'+1));
        }
        return result.toString();
    }
    private static String english(long n) {
        if(n<20)return SMALL[(int)n];
        if(n<100)return TENS[(int)n/10]+(n%10==0?"":" "+english(n%10));
        if(n<1000)return english(n/100)+" hundred"+(n%100==0?"":" "+english(n%100));
        long[] scales= {
            1000000000000L,1000000000,1000000,1000
        }
        ;
        String[] names= {
            "trillion","billion","million","thousand"
        }
        ;
        for(int i=0;i<scales.length;i++)if(n>=scales[i])return english(n/scales[i])+" "+names[i]+(n%scales[i]==0?"":" "+english(n%scales[i]));
        throw new IllegalArgumentException("number");
    }
    private static String chinese(long n) {
        if(n==0)return "零";
        if(n>=100000000) {
            long r=n%100000000;
            return chinese(n/100000000)+"亿"+(r==0?"":r<10000000?"零"+chinese(r):chinese(r));
        }
        if(n>=10000) {
            long r=n%10000;
            return chinese(n/10000)+"万"+(r==0?"":r<1000?"零"+chinese(r):chinese(r));
        }
        StringBuilder out=new StringBuilder();
        int[] divisors= {
            1000,100,10,1
        }
        ;
        String[] units= {
            "千","百","十",""
        }
        ;
        boolean zero=false;
        for(int i=0;i<4;i++) {
            int d=(int)n/divisors[i];
            n%=divisors[i];
            if(d==0) {
                if(out.length()>0&&n>0)zero=true;
                continue;
            }
            if(zero) {
                out.append('零');
                zero=false;
            }
            if(!(d==1&&divisors[i]==10&&out.length()==0))out.append(DIGITS.charAt(d));
            out.append(units[i]);
        }
        return out.toString();
    }
    /** Engineering scale and bounded spoken precision. Never reads protocol exponent notation aloud. */
    public static String quantity(String raw,String kind,FeedbackLanguage language) {
        BigDecimal value;
        try {
            value=new BigDecimal(raw.trim());
        }
        catch(Exception e) {
            return null;
        }
        if(value.abs().compareTo(new BigDecimal("1e30"))>=0)return null;
        boolean en=language==FeedbackLanguage.ENGLISH;
        BigDecimal abs=value.abs();
        int shift=0;
        String zh="",english="";
        switch(kind) {
            case "voltage":case "voltage_per_div":
            if(abs.signum()!=0&&abs.compareTo(BigDecimal.ONE)<0) {
                shift=3;
                zh="毫伏";
                english="millivolts";
            }
            else {
                zh="伏";
                english="volts";
            }
            break;
            case "time":
            if(abs.signum()==0||abs.compareTo(BigDecimal.ONE)>=0) {
                zh="秒";
                english="seconds";
            }
            else if(abs.compareTo(new BigDecimal(".001"))>=0) {
                shift=3;
                zh="毫秒";
                english="milliseconds";
            }
            else if(abs.compareTo(new BigDecimal(".000001"))>=0) {
                shift=6;
                zh="微秒";
                english="microseconds";
            }
            else {
                shift=9;
                zh="纳秒";
                english="nanoseconds";
            }
            break;
            case "frequency":
            if(abs.compareTo(new BigDecimal("1000000000"))>=0) {
                shift=-9;
                zh="吉赫兹";
                english="gigahertz";
            }
            else if(abs.compareTo(new BigDecimal("1000000"))>=0) {
                shift=-6;
                zh="兆赫兹";
                english="megahertz";
            }
            else if(abs.compareTo(new BigDecimal("1000"))>=0) {
                shift=-3;
                zh="千赫兹";
                english="kilohertz";
            }
            else {
                zh="赫兹";
                english="hertz";
            }
            break;
            case "ratio":zh="倍";
            english="times";
            break;
            default:return null;
        }
        BigDecimal scaled=value.scaleByPowerOfTen(shift),rounded=scaled.round(new MathContext(6,RoundingMode.HALF_UP));
        if(rounded.abs().compareTo(new BigDecimal("1e15"))>=0||rounded.abs().compareTo(new BigDecimal("1e-9"))<0&&rounded.signum()!=0)return null;
        String prefix=rounded.compareTo(scaled)!=0?(en?"about ":"约"):"";
        if(en&&rounded.abs().compareTo(BigDecimal.ONE)==0&&english.endsWith("s"))english=english.substring(0,english.length()-1);
        String suffix=kind.equals("voltage_per_div")?(en?" per division":"每格"):"";
        return prefix+number(rounded,language)+(en?" "+english:zh)+suffix;
    }
}
