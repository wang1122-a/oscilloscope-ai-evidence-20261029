package com.lilliput.ads10000a.voice;
import java.util.*;import java.util.regex.*;
/** A bounded same-utterance replacement grammar, retaining every original character offset. */
final class EnglishCorrectionScope {
 // Grammar programs only. Every input is still parsed independently with its original spans.
 private static final Map<String,Pattern> PROGRAMS=new java.util.concurrent.ConcurrentHashMap<>();
 private static Pattern grammar(String expression){return PROGRAMS.computeIfAbsent(expression,Pattern::compile);}
 final String surface;final boolean inherited;final int oldNumberEnd,newStart,newEnd;final String replacement,unit,channel;
 private EnglishCorrectionScope(String s,boolean i,int oldEnd,int a,int b,String n,String u,String c){surface=s;inherited=i;oldNumberEnd=oldEnd;newStart=a;newEnd=b;replacement=n;unit=u;channel=c;}
 private static String spaces(int size){char[] blank=new char[size];Arrays.fill(blank,' ');return new String(blank);}
 private static String mask(String text,int start,int end){return text.substring(0,start)+spaces(end-start)+text.substring(end);}
 static EnglishCorrectionScope parse(String text){
  String lower=text.toLowerCase(Locale.ROOT);
  if(!lower.contains("meant")&&!lower.contains("correction")&&!lower.contains("mean")&&!lower.contains("actually")&&!lower.contains("rather")&&!(lower.contains("use")&&lower.contains("instead")))return null;
  String number=EnglishNumberWords.VALUE,unit="(?:millivolts?|volts?|mV|V|nanoseconds?|microseconds?|milliseconds?|seconds?|ns|us|ms|s|megahertz|kilohertz|hertz|MHz|kHz|Hz|divisions?|div|points?|samples?)(?:\\s*(?:/|per)\\s*(?:divisions?|div))?",channel="(?:CH[1-4]|channel\\s+(?:one|two|three|four|[1-4]))";
  Matcher choice=grammar("(?i)^\\s*(?:for\\s+"+EnglishChannelEvidence.EXPRESSION+"\\s+(?:i|we)\\s+meant\\s+"+number+"\\s*"+unit+"|correction\\s+for\\s+(?:the\\s+)?"+EnglishChannelEvidence.EXPRESSION+"\\s*:\\s*(?:AC|DC|GND|ground)\\s+coupling)\\s*,\\s*(?:rather\\s+than\\s+"+number+"\\s*"+unit+"|not\\s+(?:AC|DC|GND|ground))[.!?]?\\s*$").matcher(text);
  if(choice.matches()){Matcher discard=grammar("(?i),\\s*(?:rather\\s+than|not)\\b").matcher(text);if(discard.find())return new EnglishCorrectionScope(mask(text,discard.start(),text.length()),false,-1,-1,-1,"","","");}
  Matcher coupling=grammar("(?i)^\\s*(?:please\\s+)?for\\s+(?:the\\s+)?"+EnglishChannelEvidence.EXPRESSION+"\\s+coupling\\s*,\\s*(?:please\\s+)?(?:use|choose|select)\\s+(?:AC|DC|GND|ground)\\s+(?<discard>rather\\s+than\\s+(?:AC|DC|GND|ground))[.!?]?\\s*$").matcher(text);if(coupling.matches())return new EnglishCorrectionScope(mask(text,coupling.start("discard"),text.length()),false,-1,-1,-1,"","","");
  Matcher local=grammar("(?i)^\\s*(?:please\\s+)?((?:do\\s+not|don't)\\s+)use\\s+("+number+")\\s*("+unit+")\\s+(?:for\\s+|on\\s+)?("+channel+")\\s*(;)\\s*(?:please\\s+)?use\\s+("+number+")\\s+instead\\s*[.!?]?\\s*$").matcher(text);
  if(local.matches()){String s=mask(text,local.start(1),local.end(1));s=mask(s,local.start(2),local.end(2));s=mask(s,local.start(5),local.end(5));return new EnglishCorrectionScope(s,true,local.end(2),local.start(6),local.end(6),local.group(6),local.group(3),local.group(4));}
  Matcher marker=grammar("(?i)[.!?]\\s*(?:correction\\s*:|i\\s+mean\\s*[:,]?|actually\\s*[:,])\\s*").matcher(text);if(!marker.find())return null;int start=marker.end();String prior=text.substring(0,marker.start()),tail=text.substring(start);if(marker.find()||prior.matches("(?is).*\\b(?:and|then|also|followed\\s+by)\\b.*")||prior.indexOf(';')>=0||prior.indexOf('\n')>=0||!tail.matches("(?is)\\s*(?:please\\s+)?(?:use|set|adjust|change|put)\\b.*")||tail.matches("(?is).*\\b(?:and|then|also|do\\s+not|don't|if|unless)\\b.*")||tail.matches("(?s).*[;\\r\\n].*"))return null;
  Matcher addressed=EnglishChannelEvidence.PATTERN.matcher(tail);Set<Integer> targets=new HashSet<>();while(addressed.find()){if(!EnglishChannelEvidence.analog(addressed,tail)||EnglishChannelEvidence.index(addressed)<1||EnglishChannelEvidence.index(addressed)>4)return null;targets.add(EnglishChannelEvidence.index(addressed));}Matcher quantity=grammar("(?i)("+number+")\\s*("+unit+")(?![A-Za-z])").matcher(tail);if(targets.size()!=1||!quantity.find()||quantity.find())return null;
  return new EnglishCorrectionScope(mask(text,0,start),false,-1,-1,-1,"","","");
 }
 static String active(String text){EnglishCorrectionScope parsed=parse(text);if(parsed!=null)return parsed.surface;String lower=text.toLowerCase(Locale.ROOT);
  // An explicit excluded channel at the very end is not a second target. Keep
  // offsets by masking only that complete contrast clause, never a quantity.
  Matcher excluded=grammar("(?i)[,;]\\s*(?:but\\s+)?(?:not|rather\\s+than)\\s+(?:the\\s+)?"+EnglishChannelEvidence.EXPRESSION+"\\s*[.!?]?\\s*$").matcher(text);
  if(excluded.find()){
   String main=text.substring(0,excluded.start());Set<Integer> targets=new HashSet<>();Matcher positive=EnglishChannelEvidence.PATTERN.matcher(main);
   while(positive.find())if(EnglishChannelEvidence.analog(positive,main))targets.add(EnglishChannelEvidence.index(positive));
   Matcher negative=EnglishChannelEvidence.PATTERN.matcher(text);negative.region(excluded.start(),excluded.end());
   if(targets.size()==1&&negative.find()&&EnglishChannelEvidence.analog(negative,text)&&!targets.contains(EnglishChannelEvidence.index(negative))){
    EnglishRequestCompiler.Request request=EnglishRequestCompiler.compile(main);
    if(request!=null&&"candidate".equals(request.status)&&!EnglishSemanticBridge.hasMultipleOperations(main))return mask(text,excluded.start(),text.length());
   }
  }
  if(lower.contains("from")&&lower.contains("to")){Matcher from=grammar("(?i)\\bfrom\\s+("+EnglishNumberWords.VALUE+")(?:\\s*(?:millivolts?|volts?|mV|V|nanoseconds?|microseconds?|milliseconds?|seconds?|ns|us|ms|s|megahertz|kilohertz|hertz|MHz|kHz|Hz|points?|samples?|divisions?|div))?\\s+(?=to\\s+"+EnglishNumberWords.VALUE+")").matcher(text);if(from.find()&&!text.matches("(?is).*\\b(?:range|between|sweep)\\b.*")){String active=mask(text,from.start(),from.end());EnglishRequestCompiler.Request current=EnglishRequestCompiler.compile(active);if(current!=null&&"candidate".equals(current.status)&&current.parameter instanceof Number&&current.command.matches("(?i)(?:CH[1-4]:(?:SCALE|POSITION|BANDWIDTH)|HORIZONTAL:(?:SCALE|RECORDLENGTH|DELAY:TIME)|TRIGGER:A:LEVEL:CH[1-4])"))return active;}}
  if(lower.contains("instead")){Matcher instead=grammar("(?i)\\binstead\\s+of\\s+(?:the\\s+)?(?:AC|DC|GND|ground)(?:\\s+coupling)?\\b").matcher(text);if(instead.find()){String active=mask(text,instead.start(),instead.end());EnglishRequestCompiler.Request current=EnglishRequestCompiler.compile(active);if(current!=null&&"candidate".equals(current.status)&&current.command.matches("(?i)CH[1-4]:COUPLING"))return active;}}
  if(text.indexOf(';')>=0&&lower.contains("accept")){Matcher rejected=grammar("(?is)[;]\\s*(?:i|we)\\s+(?:do\\s+not|don't)\\s+accept\\s+(?:the\\s+)?("+EnglishNumberWords.VALUE+")\\s*(?:MHz|megahertz|kHz|kilohertz|Hz|hertz)\\s+(?:setting|option|alternative)[.!?]?\\s*$").matcher(text);if(rejected.find()){String main=text.substring(0,rejected.start());EnglishRequestCompiler.Request current=EnglishRequestCompiler.compile(main);if(current!=null&&"candidate".equals(current.status)&&current.command.matches("(?i)CH[1-4]:BANDWIDTH")&&current.parameter instanceof Number)return mask(text,rejected.start(),text.length());}}
if((text.indexOf(';')>=0||text.indexOf(',')>=0)&&(lower.contains("disregard")||lower.contains("ignore")||lower.contains("use"))){Matcher inactive=grammar("(?is)[;,]\\s*(?:disregard|ignore|do\\s+not\\s+use|don't\\s+use)\\s+(?:the\\s+)?(?:old|prior|previous|positive|earlier)\\s+(?:value|setting)(?:\\s+(?:i|we)\\s+(?:mentioned|gave|said|used)(?:\\s+(?:before|earlier))?)?\\s*[.!?]?\\s*$").matcher(text);if(inactive.find()){String main=text.substring(0,inactive.start());EnglishRequestCompiler.Request request=EnglishRequestCompiler.compile(main);if(request!=null&&"candidate".equals(request.status)&&!EnglishSemanticBridge.hasMultipleOperations(main))return mask(text,inactive.start(),text.length());}}return text;}
}
