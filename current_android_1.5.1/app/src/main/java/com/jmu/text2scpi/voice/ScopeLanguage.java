package com.jmu.text2scpi.voice;
import java.util.Locale;
/** Accept only a known language value returned by the instrument. */
public final class ScopeLanguage {
    private ScopeLanguage(){}
    public static String parse(String response){
        if(response==null)return null;String value=response.trim().replace("\"","").toUpperCase(Locale.ROOT).replaceAll("[ _-]","");
        if(value.equals("ENGLISH")||value.equals("EN")||value.equals("ENG"))return "en";
        if(value.equals("CHINESE")||value.equals("SIMPLIFIEDCHINESE")||value.equals("TRADITIONALCHINESE")||value.equals("CHS")||value.equals("CHT")||value.equals("ZH")||value.equals("中文")||value.equals("简体中文")||value.equals("繁體中文"))return "zh";
        return null;
    }
}
