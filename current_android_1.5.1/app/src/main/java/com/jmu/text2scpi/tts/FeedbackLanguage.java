package com.jmu.text2scpi.tts;
import java.util.Locale;
import java.util.regex.Pattern;
/**
 * 语音反馈语言偏好与自动选择。自动模式根据原始输入判断语言；英文反馈能力不等于英文语音控制能力。
 */
public enum FeedbackLanguage {
    AUTO, CHINESE, ENGLISH;
    public static FeedbackLanguage preference(String value) {
        try {
            return valueOf(value);
        }
        catch(Exception e) {
            return AUTO;
        }
    }
    public static FeedbackLanguage select(FeedbackLanguage preference,String original) {
        if(preference!=AUTO)return preference;
        String text=original==null?"":original;
        if(text.codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN))return CHINESE;
        text=text.replaceAll("(?i)\\bCH\\s*[1-4]\\b"," ").replaceAll("(?i)\\b(?:mV|V|MHz|kHz|Hz|GHz|ms|us|ns|s|div|AC|DC|GND)\\b"," ");
        return Pattern.compile("[A-Za-z]{2,}").matcher(text).find()?ENGLISH:CHINESE;
    }
}
