package com.lilliput.ads10000a.voice;
import java.util.regex.*;

/** Evidence only. Never chooses a command/channel or rewrites the model's state. */
final class ChannelDisplayStateEvidence {
 static final int UNKNOWN=0,ON=1,OFF=2;
 private static final Pattern POSITIVE=Pattern.compile("(?iu)\\b(?:turn\\s+on|switch\\s+on|open(?:ed)?|enable(?:d)?|show(?:n)?|displayed|unhide|unhidden|reveal(?:ed)?|activate(?:d)?)\\b|\\bdisplay\\s+(?:the\\s+)?(?:channel|ch|input|trace)\\b|\\bon\\s*(?:please\\s*)?[.!?]*$|打开|显示|开启");
 private static final Pattern NEGATIVE=Pattern.compile("(?iu)\\b(?:turn\\s+off|switch\\s+off|close(?:d)?|disable(?:d)?|hide|hidden|conceal(?:ed)?|deactivate(?:d)?|blank(?:ed)?|invisible|disappear)\\b|\\boff\\s*(?:please\\s*)?[.!?]*$|关闭|隐藏|关掉");
 private static final Pattern VISIBLE=Pattern.compile("(?iu)\\bvisible\\b"),NOT_VISIBLE=Pattern.compile("(?iu)\\bnot\\s+(?:currently\\s+)?visible\\b");
 private static final Pattern DESCRIPTIVE_PREFIX=Pattern.compile("(?iu)\\b(?:[a-z]+ed|shown|(?:un)?hidden|visible|invisible)\\s*$");
 static int polarity(String text){
  // An adjective preceding the explicit target describes its old state, not
  // the requested change. Mask only that adjective for this evidence check;
  // keep the channel span and never feed this surface to quantity parsing.
  Matcher channel=EnglishChannelEvidence.PATTERN.matcher(text);StringBuilder evidence=null;
  while(channel.find()){Matcher prefix=DESCRIPTIVE_PREFIX.matcher(text.substring(0,channel.start()));if(prefix.find()){if(evidence==null)evidence=new StringBuilder(text);for(int i=prefix.start();i<prefix.end();i++)evidence.setCharAt(i,' ');}}
  String state=evidence==null?text:evidence.toString();boolean notVisible=NOT_VISIBLE.matcher(state).find();return (POSITIVE.matcher(state).find()||VISIBLE.matcher(state).find()&&!notVisible?ON:0)|(NEGATIVE.matcher(state).find()||notVisible?OFF:0);
 }
 static boolean unclearBareTarget(String text){
  if(polarity(text)!=UNKNOWN)return false;
  Matcher m=EnglishChannelEvidence.PATTERN.matcher(text);if(!m.find()||!EnglishChannelEvidence.analog(m,text))return false;
  int start=m.start(),end=m.end();if(m.find())return false;
  // Inspect the two intact source ranges independently. No damaged text is fed
  // to number/unit parsing and no channel token is rediscovered by another regex.
  String before=text.substring(0,start).replaceAll("[.,!?;:]"," ").trim();
  String after=text.substring(end).replaceAll("[.,!?;:]"," ").trim();
  return before.isEmpty()&&after.isEmpty()
    || before.matches("[A-Za-z]+")&&after.isEmpty()
    || before.isEmpty()&&after.matches("[A-Za-z]+")
    // A switch verb and target do not supply the requested state. Preserve
    // unknown/repeated tail words; never reinterpret them as ON or OFF.
    || before.matches("(?i)(?:please\\s+)?(?:turn|switch|set)(?:\\s+the)?");
 }
 private ChannelDisplayStateEvidence(){}
}
