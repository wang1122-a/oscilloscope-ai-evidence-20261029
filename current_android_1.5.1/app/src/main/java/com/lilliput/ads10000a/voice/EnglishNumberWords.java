package com.lilliput.ads10000a.voice;

import java.util.Locale;

/** Bounded exact number words for source-backed English text, never a fuzzy rewrite. */
final class EnglishNumberWords {
    static final String WORD = "(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)";
    static final String DIGIT_WORD="(?:zero|one|two|three|four|five|six|seven|eight|nine)";
    static final String FRACTION = "(?:(?:a|one)\\s+(?:quarter|half)|three\\s+quarters|(?:one|two)\\s+thirds?|(?:one|two|three|four|five)\\s+sixths?|(?:one|two|three|four|five|six|seven)\\s+eighths?|"+DIGIT_WORD+"\\s+tenths?|quarter|half)(?:\\s+of\\s+(?:a|an))?";
    static final String TOKEN="(?:"+WORD+"|hundred|thousand|million|billion)\\b";
    static final String CARDINAL=TOKEN+"(?:[ -]+(?:and[ -]+)?"+TOKEN+"){0,11}";
    static final String DIGIT="[+-]?(?:(?:[0-9]+\\s+)?[0-9]+\\s*/\\s*[0-9]+|(?:[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]+)?|[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)";
    static final String MIXED_SCALE = DIGIT+"\\s+(?:hundred|thousand|million|billion)\\b";

    static final String DECIMAL_WORD=CARDINAL+"\\s+point\\s+"+DIGIT_WORD+"(?:\\s+"+DIGIT_WORD+"){0,8}";
    static final String ARTICLE_CARDINAL="(?:a|an)\\s+(?:hundred|thousand|million|billion)(?:[ -]+(?:and[ -]+)?"+TOKEN+"){0,11}";
    static final String VALUE = "(?:(?:minus|negative|plus|positive)\\s+)?(?:"+FRACTION+"|(?:"+DIGIT+"|"+CARDINAL+")\\s+and\\s+"+FRACTION+"|"+ARTICLE_CARDINAL+"|"+DECIMAL_WORD+"|"+MIXED_SCALE+"|"+DIGIT+"|"+CARDINAL+")";
    private EnglishNumberWords() {}
    static Double parse(String raw) {
        if (raw == null) return null;
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if(text.matches("(?s).*\\s+of\\s+(?:a|an)$")){String prefix=text.replaceFirst("\\s+of\\s+(?:a|an)$","");return prefix.matches("(?s).*\\b(?:quarter|quarters|half|tenths?|thirds?|sixths?|eighths?)$")?parse(prefix):null;}
        if(text.contains(",")){if(!text.matches("[+-]?[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))return null;text=text.replace(",","");}
        if(text.matches("(?:half|quarter)\\s+a"))text=text.substring(0,text.indexOf(' '));
        try { return Double.valueOf(text); } catch (NumberFormatException ignored) {}
        boolean negative = text.startsWith("minus ") || text.startsWith("negative ");
        if (negative || text.startsWith("plus ") || text.startsWith("positive ")) text = text.substring(text.indexOf(' ') + 1).trim();
        if(text.matches("(?:a|an)\\s+(?:hundred|thousand|million|billion)(?:[ -].*)?"))text=text.replaceFirst("^(?:a|an)\\s+","one ");
        java.util.regex.Matcher scaledDigits=java.util.regex.Pattern.compile("("+DIGIT+")\\s+(hundred|thousand|million|billion)").matcher(text);
        if(scaledDigits.matches()){double multiplier=scaledDigits.group(2).equals("hundred")?100:scaledDigits.group(2).equals("thousand")?1000:scaledDigits.group(2).equals("million")?1000000:1000000000;double value=Double.parseDouble(scaledDigits.group(1))*multiplier;return Double.isFinite(value)?(negative?-value:value):null;}
        if(text.contains(" point ")){String[] parts=text.split(" point ",-1);if(parts.length!=2||!parts[1].matches(DIGIT_WORD+"(?:\\s+"+DIGIT_WORD+"){0,8}"))return null;Double whole=parse(parts[0]);if(whole==null||whole<0||whole!=Math.rint(whole))return null;StringBuilder decimal=new StringBuilder(String.valueOf(whole.longValue())).append('.');for(String digit:parts[1].split("\\s+")){Double value=parse(digit);if(value==null||value<0||value>9)return null;decimal.append(value.intValue());}double value=Double.parseDouble(decimal.toString());return negative?-value:value;}
        if(text.matches(DIGIT_WORD+"\\s+tenths?")){Double numerator=parse(text.substring(0,text.indexOf(' ')));return numerator==null?null:(negative?-1:1)*numerator/10;}
        java.util.regex.Matcher spokenFraction=java.util.regex.Pattern.compile("("+DIGIT_WORD+")\\s+(thirds?|sixths?|eighths?)").matcher(text);if(spokenFraction.matches()){Double numerator=parse(spokenFraction.group(1));int denominator=spokenFraction.group(2).startsWith("third")?3:spokenFraction.group(2).startsWith("sixth")?6:8;return numerator==null||numerator<=0||numerator>=denominator?null:(negative?-1:1)*numerator/denominator;}
        java.util.regex.Matcher numericFraction=java.util.regex.Pattern.compile("([+-])?(?:([0-9]+)\\s+)?([0-9]+)\\s*/\\s*([0-9]+)").matcher(text);
        if(numericFraction.matches()){double denominator=Double.parseDouble(numericFraction.group(4));if(denominator==0)return null;double value=(numericFraction.group(2)==null?0:Double.parseDouble(numericFraction.group(2)))+Double.parseDouble(numericFraction.group(3))/denominator;boolean sign=negative^"-".equals(numericFraction.group(1));return Double.isFinite(value)?(sign?-value:value):null;}
        if(text.contains(" and ")){int at=text.lastIndexOf(" and ");Double part=fraction(text.substring(at+5));if(part!=null){Double whole=parse(text.substring(0,at));return whole==null?null:(negative?-1:1)*(whole+part);}}
        Double fraction=fraction(text);if(fraction!=null)return negative?-fraction:fraction;
        if(text.contains(" ")||text.contains("-")){
            double total=0;long previousScale=Long.MAX_VALUE;String[] words=text.split("[ -]+");StringBuilder chunk=new StringBuilder();
            for(String word:words){
                long scale=word.equals("thousand")?1000:word.equals("million")?1000000:word.equals("billion")?1000000000:0;
                if(scale>0){Double section=small(chunk.toString());if(section==null||section<=0||scale>=previousScale)return null;total+=section*scale;chunk.setLength(0);previousScale=scale;continue;}
                if(chunk.length()>0)chunk.append(' ');chunk.append(word);
            }Double remaining=chunk.length()==0?Double.valueOf(0):small(chunk.toString());return remaining==null?null:(negative?-1:1)*(total+remaining);
        }
        try { double numeric=Double.parseDouble(text); return negative?-numeric:numeric; } catch (NumberFormatException ignored) {}
        int value;
        switch (text) {
            case "zero": value = 0; break; case "one": value = 1; break;
            case "two": value = 2; break; case "three": value = 3; break;
            case "four": value = 4; break; case "five": value = 5; break;
            case "six": value = 6; break; case "seven": value = 7; break;
            case "eight": value = 8; break; case "nine": value = 9; break;
            case "ten": value = 10; break; case "eleven": value = 11; break;
            case "twelve": value = 12; break; case "thirteen": value = 13; break;
            case "fourteen": value = 14; break; case "fifteen": value = 15; break;
            case "sixteen": value = 16; break; case "seventeen": value = 17; break;
            case "eighteen": value = 18; break; case "nineteen": value = 19; break;
            case "twenty": value = 20; break; case "thirty": value = 30; break;
            case "forty": value = 40; break; case "fifty": value = 50; break;
            case "sixty": value = 60; break; case "seventy": value = 70; break;
            case "eighty": value = 80; break; case "ninety": value = 90; break;
            default: return null;
        }
        return negative ? -(double) value : (double) value;
    }
    private static Double fraction(String text){switch(text){case "quarter":case "a quarter":case "one quarter":return .25;case "half":case "a half":case "one half":return .5;case "three quarters":return .75;default:return text.matches(DIGIT_WORD+"\\s+(?:thirds?|sixths?|eighths?|tenths?)")?parse(text):null;}}
    private static Double small(String text){String s=text.trim();String[] words=s.split(" +");double hundreds=0;int start=0;if(words.length>=2&&words[1].equals("hundred")){Double leading=parse(words[0]);if(leading==null||leading<1||leading>=100)return null;hundreds=leading*100;start=2;if(start<words.length&&words[start].equals("and"))start++;if(start==words.length)return hundreds;}
        if(words.length-start==1){Double value=parse(words[start]);return value==null||value<0||value>=100?null:hundreds+value;}
        if(words.length-start==2){Double tens=parse(words[start]),ones=parse(words[start+1]);return tens!=null&&ones!=null&&tens>=20&&tens%10==0&&ones>=1&&ones<=9?hundreds+tens+ones:null;}return null;}
}
