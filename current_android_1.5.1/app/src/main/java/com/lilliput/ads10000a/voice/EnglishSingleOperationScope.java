package com.lilliput.ads10000a.voice;
import java.util.*;import java.util.regex.*;
/** Current-utterance goal plus an explicit detail can describe the same one operation. */
final class EnglishSingleOperationScope {
 private static final String ACTION="(?:make|set|adjust|change|select|choose|enable|disable|turn|switch|show|hide|put|position|halve|double|reduce|increase|decrease|read|query|inspect|report|retrieve)";

 // Exactly three immutable grammar programs; no user strings, Matchers, evidence,
 // model outputs or request KV are retained. Each matcher is newly allocated.
 // Key is the fixed expression + inline flags; scope is this APK class loader.
 // A model reload does not alter these source constants. APK replacement clears them.
 static final String IMMUTABLE_PROGRAM_ID="r15_scope_programs_v1";
 private static final String[] PROGRAM_TEXT={"(?is)^\\s*(?:please\\s+)?lift\\s+(?:the\\s+)?bandwidth\\s+limit\\s+on\\s+(?:the\\s+)?"+EnglishChannelEvidence.EXPRESSION+"\\s*(?<restatement>;\\s*(?:please\\s+)?use\\s+(?:full|complete|unlimited|unrestricted)\\s+bandwidth)[.!?]?\\s*$","(?is)^\\s*(?:this\\s+time\\s+)?(?:i|we)\\s+(?:(?:also|really|now|only)\\s+)?(?:need|want|would\\s+like)\\b(?<goal>.*?)\\s*[.;]\\s*(?<detail>(?:please\\s+)?"+ACTION+"\\b.*)$","(?is)^\\s*(?:the|this)\\s+signal\\s+(?:is|looks|seems)\\s+(?:unstable|noisy|weak|strong|connected|present|ready)(?:\\s+at\\s+the\\s+moment|\\s+now)?[.]\\s*(?<detail>(?:please\\s+)?"+ACTION+"\\b.*)$"};
 private static volatile boolean reuseImmutablePrograms=true;
 private static final class Programs {static final Pattern[] VALUES={Pattern.compile(PROGRAM_TEXT[0]),Pattern.compile(PROGRAM_TEXT[1]),Pattern.compile(PROGRAM_TEXT[2])};}
 static void immutableProgramsEnabled(boolean enabled){reuseImmutablePrograms=enabled;}
 static boolean immutableProgramsReused(){return reuseImmutablePrograms;}
 static Pattern fixedProgram(int index){if(index<0||index>=PROGRAM_TEXT.length)throw new IllegalArgumentException("fixed_scope_program_index");return reuseImmutablePrograms?Programs.VALUES[index]:Pattern.compile(PROGRAM_TEXT[index]);}
 static final class Scope {final String active;final int detailStart;Scope(String a,int s){active=a;detailStart=s;}}
 static Scope parse(String text){
  Matcher bandwidth=fixedProgram(0).matcher(text);
  if(bandwidth.matches()){char[] current=text.toCharArray();Arrays.fill(current,bandwidth.start("restatement"),text.length(),' ');return new Scope(new String(current),bandwidth.start("restatement"));}
  Scope repeated=repeated(text);if(repeated!=null)return repeated;Scope background=background(text);if(background!=null)return background;
  Matcher parts=fixedProgram(1).matcher(text);if(!parts.matches())return null;
  String goal=text.substring(0,parts.start("detail")).replaceFirst("[.;]\\s*$", ""),detail=parts.group("detail");
  if(!fullGoal(goal)||!fullDetail(detail))return null;
  if(detail.matches("(?is)\\s*(?:please\\s+)?(?:read|query|inspect|report|retrieve)\\b.*")){if(goal.matches("(?is).*\\b(?:if|unless|not|never|set|adjust|enable|disable|turn|switch|add|remove)\\b.*")||!goal.matches("(?is).*\\b(?:inspect|read|query|current|existing|present|value|state|status|setting)\\b.*"))return null;Set<Integer> a=channels(goal),b=channels(detail);if(a.size()>1||b.size()>1||!a.isEmpty()&&!b.isEmpty()&&!a.equals(b)||!readObjects(goal).equals(readObjects(detail))||readObjects(goal).isEmpty())return null;return masked(text,parts.start("detail"),goal);}

  if(goal.matches("(?is).*\\b(?:if|when|unless|while|provided|not|never|without|unknown|unavailable|read|query|report|current|value|status|state)\\b.*")||goal.indexOf(';')>=0||EnglishChannelEvidence.nonAnalog(goal)||EnglishChannelEvidence.nonAnalog(detail)||detail.matches("(?is).*[;\\r\\n].*"))return null;
  Set<Integer> before=channels(goal),after=channels(detail);if(before.size()>1||after.size()>1||!before.isEmpty()&&!after.isEmpty()&&!before.equals(after))return null;
  Scope inherited=masked(text,parts.start("detail"),goal);EnglishRequestCompiler.Request explicit=EnglishRequestCompiler.compile(inherited.active);String head=explicit!=null&&"candidate".equals(explicit.status)?explicit.command:null;
  if(head==null){String derived=EnglishRequestCompiler.derivedIntent(detail);if("VERT_ADJUST_SCALE".equals(derived))head="CH"+(after.isEmpty()?before.isEmpty()?0:before.iterator().next():after.iterator().next())+":SCALe";else if("HORZ_ADJUST_SCALE".equals(derived))head="HORizontal:SCAle";else return null;}
  EnglishRequestCompiler.Request wanted=EnglishRequestCompiler.compile(goal);
  if(wanted!=null&&"candidate".equals(wanted.status)){if(explicit==null||!"candidate".equals(explicit.status)||!wanted.command.equals(explicit.command)||!same(wanted.parameter,explicit.parameter))return null;}
  else {if(hasOperand(goal)||!objectMatches(goal,head))return null;if(explicit!=null&&!enumCompatible(goal,head,explicit.parameter))return null;}
  return masked(text,parts.start("detail"),goal);
 }
 private static Scope background(String text){Matcher m=fixedProgram(2).matcher(text);if(!m.matches()||!fullDetail(m.group("detail")))return null;EnglishRequestCompiler.Request request=EnglishRequestCompiler.compile(m.group("detail"));if(request==null||!"candidate".equals(request.status))return null;return masked(text,m.start("detail"),text.substring(0,m.start("detail")));}
 private static Scope repeated(String text){int separator=text.indexOf(';');if(separator<0||text.indexOf(';',separator+1)>=0)return null;String first=text.substring(0,separator),second=text.substring(separator+1).trim();Set<Integer> a=channels(first),b=channels(second);if(a.size()>1||b.size()>1||!a.isEmpty()&&!b.isEmpty()&&!a.equals(b))return null;
  if(!fullMaskGoal(first)||!fullDetail(second))return null;
  // Collapse only independently typed, compatible leaves. No model output participates.
  Scope detailScope=masked(text,separator+1,first);
  EnglishRequestCompiler.Request detail=EnglishRequestCompiler.compile(detailScope.active);
  if(detail!=null&&"candidate".equals(detail.status)&&!second.matches("(?is).*\\b(?:if|unless|when|then|also)\\b.*")){
   EnglishRequestCompiler.Request goal=EnglishRequestCompiler.compile(first);
   if(fullFirst(first)&&goal!=null&&"candidate".equals(goal.status)&&goal.command.equals(detail.command)&&same(goal.parameter,detail.parameter))return detailScope;
   if(!EnglishChannelEvidence.nonAnalog(first)){
    // A passive target description names a coupling mode, rather than a second action.
    if(detail.command.matches("CH[1-4]:COUPling")&&"GND".equals(detail.parameter)&&EnglishChannelEvidence.PATTERN.matcher(first).replaceAll("CHANNEL").matches("(?is)\\s*(?:(?:the|this)\\s+)?CHANNEL\\s+is\\s+(?:the\\s+)?(?:one|input|channel)\\s+to\\s+(?:ground[- ]couple|ground\\s+couple)[.!]?\\s*")&&a.size()==1)return detailScope;
    // Negating the limit is compatible with an explicit full-bandwidth setter only.
    if(detail.command.matches("CH[1-4]:BANdwidth")&&"FULl".equals(detail.parameter)&&a.size()==1&&!hasOperand(first)){
     String constraint=EnglishChannelEvidence.PATTERN.matcher(first).replaceAll("CHANNEL");
     if(constraint.matches("(?is)\\s*(?:please\\s+)?(?:do\\s+not|don't|dont)\\s+limit\\s+(?:the\\s+)?CHANNEL(?:'s)?\\s+bandwidth(?:\\s+(?:this\\s+time|now))?[.!]?\\s*"))return detailScope;
    }
   }
  }
  if(channelSurface(first).matches("(?is)(?:i|we)\\s+no\\s+longer\\s+(?:need|want)\\s+(?:the\\s+)?(?:RMS|mean|frequency|period|rise[- ]time|fall[- ]time|peak[- ]to[- ]peak)(?:\\s+(?:measurement|readout))?\\s+(?:on|for)\\s+(?:the\\s+)?CHANNEL[.!]?")&&second.matches("(?is)(?:please\\s+)?(?:switch|turn)\\s+off\\s+(?:that|the)\\s+(?:measurement|readout)\\b.*")){Matcher type=Pattern.compile("(?i)\\b(?:RMS|mean|frequency|period|rise[- ]time|fall[- ]time|peak[- ]to[- ]peak)\\b").matcher(first);if(a.size()!=1||!type.find())return null;Scope scope=masked(text,separator+1,first);char[] active=scope.active.toCharArray();for(int n=type.start();n<type.end();n++)active[n]=text.charAt(n);return new Scope(new String(active),separator+1);}
  if(first.matches("(?is)\\s*(?:please\\s+)?(?:hide|disable)\\s+(?:the\\s+)?axis\\s+labels?(?:\\s+for\\s+now)?[.!]?\\s*")&&second.matches("(?is)(?:please\\s+)?(?:switch|turn)\\s+(?:their|the\\s+axis\\s+labels?['’]?|the)\\s+display\\s+off[.!?]?")){char[] active=text.toCharArray();Arrays.fill(active,separator,text.length(),' ');return new Scope(new String(active),separator);}
  if(first.matches("(?is)\\s*(?:i|we)\\s+(?:need|want)\\s+(?:the\\s+)?(?:status\\s+registers?(?:\\s+and\\s+error\\s+(?:records?|queue))?|error\\s+(?:records?|queue))\\s+(?:to\\s+be\\s+)?(?:cleared|clearing)[.!]?\\s*")&&second.matches("(?is)(?:please\\s+)?(?:execute|perform)\\s+clear\\s+status[.!?]?"))return masked(text,separator+1,first);
  return null;
 }
 private static Scope masked(String text,int start,String goal){if(!fullMaskGoal(goal)||!fullDetail(text.substring(start)))return null;char[] active=text.toCharArray();Arrays.fill(active,0,start,' ');Matcher sources=EnglishChannelEvidence.PATTERN.matcher(goal);while(sources.find())for(int i=sources.start();i<sources.end();i++)active[i]=text.charAt(i);return new Scope(new String(active),start);}
 private static Set<String> readObjects(String text){String s=text.toLowerCase(Locale.ROOT);Set<String> objects=new HashSet<>();for(String[] pair:new String[][]{{"coupling","coupling|coupled"},{"bandwidth","bandwidth"},{"scale","scale|sensitivity|timebase|volts?\\s+per\\s+division"},{"position","position|offset"},{"recordlength","record\\s+length"},{"graticule","graticule|grid|crosshair"},{"display","display|visible|trace"},{"mode","mode|processing|method"},{"measurement","measurement|readout"}})if(Pattern.compile("(?i)\\b(?:"+pair[1]+")\\b").matcher(s).find())objects.add(pair[0]);return objects;}
 static String active(String text){Scope scope=parse(text);return scope==null?text:scope.active;}
 // Full-span evidence is required before any text is masked. Compiler(candidate) alone
 // cannot establish that a residual predicate or workflow was consumed.
 private static String channelSurface(String text){return EnglishChannelEvidence.PATTERN.matcher(text).replaceAll("CHANNEL").trim();}
 private static boolean fullFirst(String text){
  String s=channelSurface(text),quantity=EnglishNumberWords.VALUE;
  return s.matches("(?is)(?:please\\s+)?(?:set|select|choose|change|adjust)\\s+(?:the\\s+)?CHANNEL\\s+(?:coupling(?:\\s+mode)?\\s+(?:to\\s+)?(?:AC|DC|GND|ground)(?:\\s+coupling)?|(?:vertical\\s+)?scale\\s+to\\s+"+quantity+"\\s+(?:millivolts?|volts?|mV|V)\\s*(?:/|per)\\s*(?:divisions?|div))[.!]?\\s*")
   ||s.matches("(?is)(?:please\\s+)?(?:show|hide|enable|disable)\\s+(?:the\\s+)?CHANNEL[.!]?\\s*")
   ||s.matches("(?is)let\\s+(?:the\\s+)?(?:waveform|trace)\\s+from\\s+(?:the\\s+)?CHANNEL\\s+remain\\s+(?:visible|hidden)[.!]?\\s*");
 }
 private static boolean fullGoal(String text){
  String s=channelSurface(text).replaceFirst("(?is)^(?:this\\s+time\\s+)?(?:i|we)\\s+(?:(?:also|really|now|only)\\s+)?(?:need|want|would\\s+like)\\s+", "");
  return s.matches("(?is)(?:to\\s+)?(?:inspect|read|query)\\s+(?:the\\s+)?(?:current\\s+)?acquisition\\s+(?:mode|method)[.!]?\\s*")||s.matches("(?is)(?:(?:the\\s+)?CHANNEL(?:\\s+(?:visible|hidden|(?:on|off)(?:\\s+(?:the\\s+)?screen)?))?|(?:a\\s+)?(?:clearer\\s+)?CHANNEL\\s+(?:vertical\\s+)?(?:scale|sensitivity)|CHANNEL\\s+(?:position|offset)(?:\\s+shifted)?|(?:the\\s+)?(?:full|complete|unlimited|unrestricted)\\s+bandwidth\\s+(?:on|for)\\s+(?:the\\s+)?CHANNEL|(?:an?\\s+)?(?:enlarged|closer)\\s+view(?:\\s+of\\s+(?:the\\s+)?details)?|(?:horizontal\\s+)?zoom|crosshairs?|(?:the\\s+)?(?:graticule|grid)|(?:averaging|average\\s+acquisition\\s+mode)(?:\\s+for\\s+(?:this|the)\\s+capture)?|coupling\\s+(?:on|for)\\s+(?:the\\s+)?CHANNEL|(?:to\\s+)?(?:inspect|read|query)\\s+(?:the\\s+)?(?:current\\s+)?(?:coupling|bandwidth|scale|position|display\\s+state)\\s+(?:on|for)\\s+(?:the\\s+)?CHANNEL)[.!]?\\s*");
 }
 private static boolean fullMaskGoal(String text){
  String s=channelSurface(text);
  return fullFirst(text)||fullGoal(text)
   ||s.matches("(?is)(?:(?:the|this)\\s+)?CHANNEL\\s+is\\s+(?:the\\s+)?(?:one|input|channel)\\s+to\\s+(?:ground[- ]couple|ground\\s+couple)[.!]?\\s*")
   ||s.matches("(?is)(?:please\\s+)?(?:do\\s+not|don't|dont)\\s+limit\\s+(?:the\\s+)?CHANNEL(?:'s)?\\s+bandwidth(?:\\s+(?:this\\s+time|now))?[.!]?\\s*")
   ||s.matches("(?is)(?:the|this)\\s+signal\\s+(?:is|looks|seems)\\s+(?:unstable|noisy|weak|strong|connected|present|ready)(?:\\s+at\\s+the\\s+moment|\\s+now)?[.]\\s*")
   ||s.matches("(?is)(?:i|we)\\s+no\\s+longer\\s+(?:need|want)\\s+(?:the\\s+)?(?:RMS|mean|frequency|period|rise[- ]time|fall[- ]time|peak[- ]to[- ]peak)(?:\\s+(?:measurement|readout))?\\s+(?:on|for)\\s+(?:the\\s+)?CHANNEL[.!]?\\s*")
   ||s.matches("(?is)(?:please\\s+)?(?:hide|disable)\\s+(?:the\\s+)?axis\\s+labels?(?:\\s+for\\s+now)?[.!]?\\s*")
   ||s.matches("(?is)(?:i|we)\\s+(?:need|want)\\s+(?:the\\s+)?(?:status\\s+registers?(?:\\s+and\\s+error\\s+(?:records?|queue))?|error\\s+(?:records?|queue))\\s+(?:to\\s+be\\s+)?(?:cleared|clearing)[.!]?\\s*");
 }
 private static boolean fullDetail(String text){
  String s=channelSurface(text).replaceAll("(?i)\\b(?:it|that\\s+(?:channel|input|trace)|its)\\b", "CHANNEL").replaceFirst("(?i)^please\\s+", "");
  if(fullFirst(s))return true;
  String q=EnglishNumberWords.VALUE,target="(?:the\\s+)?CHANNEL",setter="(?:set|adjust|change|select|choose|put|make)",on="(?:ON|OFF|visible|hidden)",coupling="(?:AC|DC|GND|ground)",bw="(?:full|complete|unlimited|unrestricted)",property="(?:coupling|bandwidth|scale|position|display\\s+state)";
  String[] patterns={
   "(?:select|choose|set)\\s+"+coupling+"\\s+coupling\\s+(?:for|on)\\s+"+target,
   "(?:show|hide|enable|disable)\\s+"+target,
   "make\\s+"+target+"(?:\\s+(?:trace|waveform))?\\s+"+on,
   "(?:turn|switch)\\s+(?:on|off)\\s+"+target,
   "(?:turn|switch)\\s+"+target+"(?:\\s+display)?\\s+(?:on|off)",
   setter+"\\s+"+target+"\\s+(?:bandwidth\\s+)?to\\s+"+bw+"(?:\\s+bandwidth)?",
   "(?:select|choose|set)\\s+"+bw+"\\s+bandwidth\\s+(?:for|on)\\s+"+target,
   setter+"\\s+"+target+"\\s+bandwidth\\s+to\\s+"+q+"\\s*(?:MHz|kHz|Hz|megahertz|kilohertz|hertz)",
   setter+"\\s+"+target+"\\s+(?:vertical\\s+)?(?:position|offset)\\s+to\\s+"+q+"\\s+(?:divisions?|div)",
   "(?:halve|double)\\s+"+target+"\\s+(?:scale|volts?\\s+per\\s+division)",
   "(?:enable|disable|activate|deactivate|enter|exit)\\s+(?:the\\s+)?(?:horizontal\\s+)?zoom",
   "(?:change|set|select|choose)\\s+(?:the\\s+)?(?:display\\s+)?graticule\\s+(?:to\\s+)?(?:crosshairs?|full|complete|frame|grid)",
   "(?:select|choose|set)\\s+(?:average|averaging|sample|peak|high[- ]resolution)\\s+acquisition\\s+mode",
   "(?:select|choose|set)\\s+(?:auto|automatic|normal|single)\\s+trigger\\s+mode",
   "(?:read|query|inspect|report|retrieve)\\s+"+target+"\\s+(?:current\\s+)?"+property+"(?:\\s+setting)?",
   "(?:read|query|inspect|report|retrieve)\\s+(?:the\\s+)?(?:current\\s+)?"+property+"(?:\\s+setting)?\\s+(?:for|on)\\s+"+target,
   "(?:read|query|inspect|report|retrieve)\\s+(?:current\\s+)?acquisition\\s+(?:mode|method|state)",
   "(?:switch|turn)\\s+off\\s+(?:that|the)\\s+(?:measurement|readout)(?:\\s+(?:for|on)\\s+"+target+")?",
   "(?:switch|turn)\\s+(?:their|the\\s+axis\\s+labels?['’]?|the)\\s+display\\s+off",
   "(?:execute|perform)\\s+clear\\s+status"
  };
  for(String pattern:patterns)if(s.matches("(?is)"+pattern+"[.!?]?\\s*"))return true;return false;
 }
 static boolean unconsumedSplit(String text){return parse(text)==null&&(text.matches("(?is)\\s*(?:this\\s+time\\s+)?(?:i|we)\\s+(?:(?:also|really|now|only)\\s+)?(?:need|want|would\\s+like)\\b.+[.;]\\s*(?:please\\s+)?"+ACTION+"\\b.*")||text.matches("(?is)\\s*(?:the|this)\\s+signal\\s+(?:is|looks|seems)\\s+(?:unstable|noisy|weak|strong|connected|present|ready)(?:\\s+at\\s+the\\s+moment|\\s+now)?[.]\\s*(?:please\\s+)?"+ACTION+"\\b.*"));}
 private static Set<Integer> channels(String text){Set<Integer> result=new HashSet<>();Matcher m=EnglishChannelEvidence.PATTERN.matcher(text);while(m.find())if(EnglishChannelEvidence.analog(m,text))result.add(EnglishChannelEvidence.index(m));return result;}
 private static boolean hasOperand(String text){Matcher m=EnglishChannelEvidence.PATTERN.matcher(text);char[] words=text.toCharArray();while(m.find())Arrays.fill(words,m.start(),m.end(),' ');return Pattern.compile("(?i)(?<![A-Za-z0-9])"+EnglishNumberWords.VALUE+"(?![A-Za-z0-9])").matcher(new String(words)).find();}
 private static boolean same(Object a,Object b){if(a instanceof Number&&b instanceof Number)return Double.compare(((Number)a).doubleValue(),((Number)b).doubleValue())==0;return String.valueOf(a).equalsIgnoreCase(String.valueOf(b));}
 private static boolean objectMatches(String goal,String head){String g=goal.toLowerCase(Locale.ROOT).replaceAll("\\bcrosshairs\\b","crosshair"),h=head.toUpperCase(Locale.ROOT);if(h.startsWith("ZOOM:"))return g.matches("(?s).*\\b(?:zoom|magnification|enlarged\\s+view|closer\\s+view)\\b.*")&&!g.matches("(?s).*\\b(?:graticule|coupling|bandwidth|position|offset|timebase)\\b.*");if(h.equals("DISPLAY:GRATICULE"))return g.matches("(?s).*\\b(?:graticule|grid|crosshair)\\b.*")&&!g.matches("(?s).*\\b(?:zoom|scale|coupling|bandwidth|position|offset)\\b.*");if(h.equals("ACQUIRE:MODE"))return g.matches("(?s).*\\b(?:acquisition|capture|sampling|acquire)\\b.*")&&g.matches("(?s).*\\b(?:mode|processing|average|averaging|sample|peak|resolution)\\b.*");if(EnglishSemanticBridge.singleObjectIssue(g,head)!=null)return false;if(h.startsWith("SELECT:CH"))return g.matches("(?s).*\\b(?:trace|waveform|channel|input|visible|hidden|(?:on|off)\\s+(?:the\\s+)?screen)\\b.*");if(h.matches("CH[1-4]:SCALE"))return g.matches("(?s).*\\b(?:scale|sensitivity|volts?\\s+per\\s+division)\\b.*");if(h.matches("CH[1-4]:POSITION"))return g.matches("(?s).*\\b(?:position|offset)\\b.*");if(h.matches("CH[1-4]:BANDWIDTH"))return g.contains("bandwidth");return false;}
 private static boolean enumCompatible(String goal,String head,Object parameter){String g=goal.toLowerCase(Locale.ROOT);if(head.startsWith("SELect:CH")){if(g.matches("(?s).*\\b(?:hidden|hide|off)\\b.*")&&!"OFF".equals(parameter)||g.matches("(?s).*\\b(?:visible|on)\\b.*")&&!"ON".equals(parameter))return false;}if(head.equals("DISplay:GRAticule")){if(g.matches("(?s).*\\bcrosshairs?\\b.*")&&!"CROSSHair".equals(parameter)||g.matches("(?s).*\\bframe\\b.*")&&!"FRAme".equals(parameter))return false;}return true;}
 private EnglishSingleOperationScope(){}
}
