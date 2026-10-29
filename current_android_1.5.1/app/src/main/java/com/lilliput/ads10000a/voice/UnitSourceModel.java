package com.lilliput.ads10000a.voice;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Immutable unit definitions from the authoritative contract. No device state or history. */
final class UnitSourceModel {
 static final class Evidence {
  final Map definition;final String raw,normalized;final BigDecimal factor;final int start,end;
  Evidence(Map d,String r,BigDecimal f,int s,int e){definition=d;raw=r;normalized=d.get("canonical").toString();factor=f;start=s;end=e;}
  Map<String,Object> json(int slot){Map<String,Object> m=new LinkedHashMap<>();m.put("raw_unit",raw);m.put("normalized_unit",normalized);m.put("dimension",definition.get("dimension"));m.put("si_multiplier",factor.toPlainString());m.put("numerator",definition.get("numerator"));m.put("denominator",definition.get("denominator"));for(String flag:new String[]{"per_div","ratio","angle","logarithmic","count","dynamic","implicit_denominator"})m.put(flag,Boolean.TRUE.equals(definition.get(flag)));m.put("source_start",start);m.put("source_end",end);m.put("parameter_slot",slot);return m;}
 }
 private static final class Form {final String text;final Map definition;final BigDecimal factor;final Pattern compound;Form(String t,Map d,String f){text=t;definition=d;factor=new BigDecimal(f);compound=t.contains("/")?Pattern.compile(Pattern.quote(t).replace("/","\\E\\s*(?:/|per|each|every)\\s*\\Q")):null;}}
 private final Map<String,Map> definitions=new HashMap<>();private final List<Form> forms=new ArrayList<>();private final Map slots,coverage;private final List<Map> templates=new ArrayList<>();
 UnitSourceModel(Map root){Map config=(Map)root.getOrDefault("unit_sources",Collections.emptyMap());slots=(Map)config.getOrDefault("slots",Collections.emptyMap());coverage=(Map)config.getOrDefault("coverage",Collections.emptyMap());for(Object o:(List)config.getOrDefault("definitions",Collections.emptyList())){Map d=(Map)o;definitions.put(d.get("canonical").toString(),d);for(Object a:(List)d.get("forms")){Map f=(Map)a;forms.add(new Form(f.get("text").toString(),d,f.get("factor").toString()));}}forms.sort((a,b)->Integer.compare(b.text.length(),a.text.length()));for(Object o:(List)config.getOrDefault("template_slots",Collections.emptyList())){Map t=new HashMap((Map)o);t.put("pattern",Pattern.compile(t.get("regex").toString(),Pattern.CASE_INSENSITIVE));templates.add(t);}}
 Evidence align(String command,Evidence e){if(e==null)return null;String expected=expected(command);Map slot=(Map)slots.get(command.toUpperCase(Locale.ROOT));if(slot!=null&&Boolean.TRUE.equals(slot.get("denominator_omittable"))&&expected.equals(e.normalized+"/div")){Map def=new HashMap(definitions.get(expected));def.put("implicit_denominator",true);return new Evidence(def,e.raw,e.factor,e.start,e.end);}return e;}
 String classification(String unit){return String.valueOf(coverage.get(unit==null?"None":unit));}
 String expected(String command){Object s=slots.get(command.replaceFirst("^:","").toUpperCase(Locale.ROOT));if(s instanceof Map)return ((Map)s).get("unit").toString();Set<String> found=new HashSet<>();for(Map t:templates)if(((Pattern)t.get("pattern")).matcher(command).matches())found.add(t.get("unit").toString());if(found.isEmpty()&&command.toUpperCase(Locale.ROOT).matches("^TRIGGER:A:LEVEL:CH[1-4]$"))return "V";/* Existing authority: ADS10000A Tek SCPI sheet14 row26, NR3 timeout time is seconds; XLSX SHA697D64FFD709CC9146EFCE78FE4BF036B669AE9BBCB36DA612A4D00BA5CDAD85. Unit extraction omitted this family; explicit spoken time units still required. */if(found.isEmpty()&&command.matches("(?i)^TRIG(?:ger)?:A:TIMEO(?:ut)?:TIM(?:e)?$"))return "s";return found.size()==1?found.iterator().next():found.isEmpty()?"":"AMBIGUOUS";}
 // Exactly eight fixed source expressions. Cache only immutable compiled programs;
 // Matchers, transcripts, state, model raw and business answers are never retained.
 // Identity: expression bytes + inline flags + this APK class loader. No model KV.
 static final String UNIT_PROGRAM_VERSION="r15_unit_source_fixed_programs_v1";
 private static volatile boolean reuseUnitPrograms=false;
 static void unitProgramsEnabled(boolean enabled){reuseUnitPrograms=enabled;}
 static boolean unitProgramsReused(){return reuseUnitPrograms;}
 private static final String[] UNIT_PROGRAM_TEXT={"\\p{IsHan}","兆赫(?:兹)?|千赫(?:兹)?","(?i)(nanoseconds?|microseconds?|milliseconds?|seconds?)(?![A-Za-z]|\\s*(?:/|每))","(?i)samples?(?![A-Za-z])","(?i)millivolts?(?![A-Za-z])","(?i)millivolts?\\s*(?:per|/)\\s*(?:divisions?|div)(?![A-Za-z])","(?i)(megahertz|kilohertz)(?![A-Za-z])","((?i:millivolts?|volts?|nanoseconds?|microseconds?|milliseconds?|seconds?)|[A-Za-zµμ]+)\\s*(?i:/|(?:in\\s+)?(?:per|for\\s+(?:each|every)|each|every))\\s*(?i:(?:(?:vertical|horizontal)\\s+)?(?:divisions?|div|squares?))(?![A-Za-z])"};
 private static final class UnitPrograms {static final Pattern[] VALUES=create();private static Pattern[] create(){Pattern[] p=new Pattern[UNIT_PROGRAM_TEXT.length];for(int i=0;i<p.length;i++)p[i]=Pattern.compile(UNIT_PROGRAM_TEXT[i]);return p;}}
 private static Pattern unitProgram(int index){return reuseUnitPrograms?UnitPrograms.VALUES[index]:Pattern.compile(UNIT_PROGRAM_TEXT[index]);}
 Evidence at(String text,int from){int at=from;while(at<text.length()&&Character.isWhitespace(text.charAt(at)))at++;if(at<text.length()&&text.charAt(at)=='-'&&!unitProgram(0).matcher(text).find()){at++;while(at<text.length()&&Character.isWhitespace(text.charAt(at)))at++;}if(text.regionMatches(true,at,"a ",0,2))at+=2;Matcher chineseFrequency=unitProgram(1).matcher(text).region(at,text.length());if(chineseFrequency.lookingAt())return new Evidence(definitions.get("Hz"),text.substring(at,chineseFrequency.end()),new BigDecimal(chineseFrequency.group().startsWith("兆")?"1000000":"1000"),at,chineseFrequency.end());Evidence englishRatio=englishRatioAt(text,at);if(englishRatio!=null)return englishRatio;Matcher time=unitProgram(2).matcher(text).region(at,text.length());if(time.lookingAt()){String spoken=time.group(1).toLowerCase(Locale.ROOT);String multiplier=spoken.startsWith("nano")?"0.000000001":spoken.startsWith("micro")?"0.000001":spoken.startsWith("milli")?"0.001":"1";return new Evidence(definitions.get("s"),text.substring(at,time.end()),new BigDecimal(multiplier),at,time.end());}Matcher samples=unitProgram(3).matcher(text).region(at,text.length());if(samples.lookingAt())return new Evidence(definitions.get("points"),text.substring(at,samples.end()),BigDecimal.ONE,at,samples.end());Matcher englishVoltage=unitProgram(4).matcher(text).region(at,text.length());if(englishVoltage.lookingAt())return new Evidence(definitions.get("V"),text.substring(at,englishVoltage.end()),new BigDecimal("0.001"),at,englishVoltage.end());Matcher englishMilli=unitProgram(5).matcher(text).region(at,text.length());if(englishMilli.lookingAt())return new Evidence(definitions.get("V/div"),text.substring(at,englishMilli.end()),new BigDecimal("0.001"),at,englishMilli.end());Matcher englishFrequency=unitProgram(6).matcher(text).region(at,text.length());if(englishFrequency.lookingAt())return new Evidence(definitions.get("Hz"),text.substring(at,englishFrequency.end()),new BigDecimal(englishFrequency.group(1).equalsIgnoreCase("megahertz")?"1000000":"1000"),at,englishFrequency.end());for(Form f:forms){int end;if(text.startsWith(f.text,at))end=at+f.text.length();else {if(f.compound==null)continue;Matcher compound=f.compound.matcher(text).region(at,text.length());if(!compound.lookingAt())continue;end=compound.end();}int boundary=end;while(boundary<text.length()&&Character.isWhitespace(text.charAt(boundary)))boundary++;if(boundary<text.length()&&(text.charAt(boundary)=='/'||text.startsWith("每",boundary)))continue;if(end<text.length()){char c=text.charAt(end);if(c=='/'||c=='×'||c=='°'||c>='A'&&c<='Z'||c>='a'&&c<='z'||text.startsWith("每",end))continue;}return new Evidence(f.definition,text.substring(at,end),f.factor,at,end);}return null;}
 private Evidence englishRatioAt(String text,int at){
  Matcher ratio=unitProgram(7).matcher(text).region(at,text.length());
  if(!ratio.lookingAt())return null;
  String literal=ratio.group(1);if(!literal.matches("(?i)millivolts?|volts?|nanoseconds?|microseconds?|milliseconds?|seconds?")){Form chosen=null;for(Form f:forms)if(f.text.equals(literal+"/div")&&Set.of("V/div","s/div").contains(f.definition.get("canonical"))){if(chosen!=null&&(!chosen.definition.equals(f.definition)||chosen.factor.compareTo(f.factor)!=0))return null;chosen=f;}return chosen==null?null:new Evidence(chosen.definition,text.substring(at,ratio.end()),chosen.factor,at,ratio.end());}
  String spoken=literal.toLowerCase(Locale.ROOT);boolean voltage=spoken.contains("volt")||spoken.equals("v")||spoken.equals("mv");
  String factor=spoken.startsWith("milli")||spoken.equals("ms")||spoken.equals("mv")?"0.001":spoken.startsWith("micro")||spoken.equals("us")?"0.000001":spoken.startsWith("nano")||spoken.equals("ns")?"0.000000001":"1";
  return new Evidence(definitions.get(voltage?"V/div":"s/div"),text.substring(at,ratio.end()),new BigDecimal(factor),at,ratio.end());
 }
 Evidence atForCommand(String command,String text,int from){
  Evidence explicit=at(text,from);
  if(explicit!=null)return align(command,explicit);
  if(!"Hz".equals(expected(command)))return null;
  int start=from;
  while(start<text.length()&&Character.isWhitespace(text.charAt(start)))start++;
  if(start>=text.length()||text.charAt(start)!='兆')return null;
  int end=start+1;
  if(end<text.length()&&!Character.isWhitespace(text.charAt(end))
    &&"，。！？、,.!?吧呀啦".indexOf(text.charAt(end))<0)return null;
  return new Evidence(definitions.get("Hz"),"兆",new BigDecimal("1000000"),start,end);
 }
 boolean conflictAfter(String text,Evidence first){int at=first.end;while(at<text.length()&&(Character.isWhitespace(text.charAt(at))||"或和与、".indexOf(text.charAt(at))>=0))at++;Evidence other=at(text,at);return other!=null&&!other.normalized.equals(first.normalized);}
   static BigDecimal decimal(String s){String n=Normalizer.normalize(s,Normalizer.Form.NFKC);if(n.matches("(?:正|负|[+-])?半"))return new BigDecimal(n.startsWith("负")||n.startsWith("-")?"-0.5":"0.5");try{return new BigDecimal(n);}catch(NumberFormatException e){Double english=EnglishNumberWords.parse(n);if(english!=null)return BigDecimal.valueOf(english);if(n.matches("负(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?"))return new BigDecimal("-"+n.substring(1));if(!n.matches("负?[零〇一二两三四五六七八九十百千万]+(?:点[零〇一二两三四五六七八九]+)?"))throw e;Double value=ParameterSourceGuard.number(n);if(value==null||!Double.isFinite(value))throw e;return BigDecimal.valueOf(value);}}
 static final Pattern VALUE=Pattern.compile("^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
 String normalizeModel(String command,String raw){return normalize(command,expected(command),raw);}
 String normalizeForUnit(String unit,String raw){return normalize(null,unit,raw);}
 private String normalize(String command,String expected,String raw){if(expected.isEmpty()||raw.indexOf(',')>=0)return raw;Matcher n=VALUE.matcher(raw.trim());if(!n.find())return raw;String value=raw.trim();BigDecimal number=decimal(n.group());String tail=value.substring(n.end()).trim();if(tail.isEmpty())return number.stripTrailingZeros().toPlainString();Evidence u=at(value,n.end());if(command!=null)u=align(command,u);if(u==null||u.end!=value.length()||!u.normalized.equals(expected)&&!expected.equals("DYNAMIC"))throw new IllegalArgumentException("MODEL_UNIT_DIMENSION_MISMATCH");return number.multiply(u.factor).stripTrailingZeros().toPlainString();}
}
