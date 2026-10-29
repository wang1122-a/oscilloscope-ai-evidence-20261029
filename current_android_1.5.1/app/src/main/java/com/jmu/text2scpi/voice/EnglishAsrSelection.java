package com.jmu.text2scpi.voice;
import java.util.*;
import java.util.regex.*;

/** Do not allow word LM selection to change an explicit physical quantity. */
public final class EnglishAsrSelection {
    private EnglishAsrSelection(){}
    private static final Pattern QUANTITY=Pattern.compile("(?i)([+-]?\\d+(?:\\.\\d+)?|(?:(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|point|and|minus|negative)[ -]+)*?(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand))\\s*(millivolts?|milli[ -]+volts?|microvolts?|volts?|milliseconds?|microseconds?|nanoseconds?|seconds?|millwatts?|milliwatts?|watts?|mW|W|mV|uV|µV|V|ms|us|ns|s|[A-Za-zµ]+(?=\\s+(?:per|/)\\s+div))\\b");
    private static List<String> quantities(String text){
        List<String> out=new ArrayList<>();Matcher m=QUANTITY.matcher(text);while(m.find()){
            String unit=m.group(2).toLowerCase(Locale.ROOT).replace("-","").replace(" ","");
            if(unit.matches("millivolts?|mv"))unit="mV";else if(unit.matches("microvolts?|uv|µv"))unit="uV";else if(unit.matches("volts?|v"))unit="V";
            else if(unit.matches("milliseconds?|ms"))unit="ms";else if(unit.matches("microseconds?|us"))unit="us";else if(unit.matches("nanoseconds?|ns"))unit="ns";else if(unit.matches("seconds?|s"))unit="s";else unit="unsupported:"+m.group(2);
            out.add(m.group(1).toLowerCase(Locale.ROOT).replaceAll("[ -]+"," ")+":"+unit);
        }return out;
    }
    public static boolean uncertain(String text){
        for(String q:quantities(text))if(q.contains(":unsupported:"))return true;
        return text.matches("(?is).*\\b(?:vertical|voltage)\\s+(?:scale|sensitivity)\\b.*")&&quantities(text).stream().anyMatch(q->q.matches(".*:(?:ms|us|ns|s)"));
    }
    public static String choose(String raw,String decoded){return decoded;}
    public static boolean maySend(String raw,String decoded){
        if(uncertain(decoded))return false;
        List<String> original=quantities(raw),selected=quantities(decoded);
        if(original.equals(selected)||original.isEmpty())return true;
        boolean ambiguousPower=original.stream().allMatch(q->q.contains(":unsupported:"));
        boolean voltage=selected.size()==original.size()&&selected.stream().allMatch(q->q.endsWith(":mV"));
        boolean vertical=(raw+" "+decoded).matches("(?is).*\\b(?:vertical|voltage)\\s+(?:scale|sensitivity)\\b.*");
        if(!ambiguousPower||!voltage||!vertical)return false;
        for(int i=0;i<original.size();i++)if(!original.get(i).substring(0,original.get(i).indexOf(':')).equals(selected.get(i).substring(0,selected.get(i).indexOf(':'))))return false;
        return true;
    }
}
