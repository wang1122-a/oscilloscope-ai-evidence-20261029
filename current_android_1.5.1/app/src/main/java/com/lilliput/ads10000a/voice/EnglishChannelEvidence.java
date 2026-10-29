package com.lilliput.ads10000a.voice;
import java.util.Locale;import java.util.regex.*;
/** Matches explicit channel indices without rewriting the original utterance. */
final class EnglishChannelEvidence {
 static final String EXPRESSION="(?:\\b(?:channel|ch|input|trace|waveform)[ -]*(zero|one|two|three|four|five|six|seven|eight|nine|ten|[0-9]+)\\b|\\b(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|[0-9]+(?:st|nd|rd|th))[ -]+(?:analog[ -]+)?(?:channel|input|trace|waveform)\\b)";
 static final Pattern PATTERN=Pattern.compile(EXPRESSION,Pattern.CASE_INSENSITIVE);
 static boolean analog(Matcher m,String text){return !Pattern.compile("(?i)\\b(?:math(?:ematical)?|reference|ref|digital|logic)[ -]+(?:[a-z0-9]+[ -]+)?$").matcher(text.substring(0,m.start())).find();}
 static boolean nonAnalog(String text){if(Pattern.compile("(?i)\\b(?:math(?:ematical)?|reference|ref|digital|logic)[ -]+(?:waveform|trace|input|channel)\\b").matcher(text).find())return true;Matcher m=PATTERN.matcher(text);while(m.find())if(!analog(m,text))return true;return false;}
 static String objectType(Matcher m){String s=m.group().toLowerCase(Locale.ROOT);return s.contains("input")?"analog_input":s.contains("trace")?"analog_trace":"analog_channel";}
 static int index(Matcher m){String t=(m.group(1)==null?m.group(2):m.group(1)).toLowerCase(Locale.ROOT);if(t.equals("zero"))return 0;String[] cardinal={"one","two","three","four","five","six","seven","eight","nine","ten"},ordinal={"first","second","third","fourth","fifth","sixth","seventh","eighth","ninth","tenth"};for(int i=0;i<10;i++)if(t.equals(cardinal[i])||t.equals(ordinal[i]))return i+1;try{return Integer.parseInt(t.replaceFirst("(?:st|nd|rd|th)$",""));}catch(NumberFormatException e){return -1;}}
 private EnglishChannelEvidence(){}
}
