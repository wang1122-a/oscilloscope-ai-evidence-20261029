package com.lilliput.ads10000a.voice;
import java.util.*;
import java.util.regex.*;
/** Typed source/explicit offline state proofs for relative and composite business steps. */
final class EnglishDerivedPlanBridge {
 /** The only constructor is inside the independent typed derivation; model output never enters it. */
 static final class Proof {
  private final String text,top,kind;private final List<Map<String,Object>> plan,events;private final Map<String,Object> state;
  private Proof(String text,String top,String kind,List<Map<String,Object>> expected,List<Map<String,Object>> evidence,Map<String,Object> fixture){this.text=text;this.top=top;this.kind=kind;plan=(List<Map<String,Object>>)freeze(expected);events=(List<Map<String,Object>>)freeze(evidence);state=(Map<String,Object>)freeze(fixture);}
  private static Object freeze(Object value){if(value instanceof Map){Map<String,Object> copy=new LinkedHashMap<>();for(Map.Entry<?,?> e:((Map<?,?>)value).entrySet())copy.put(String.valueOf(e.getKey()),freeze(e.getValue()));return Collections.unmodifiableMap(copy);}if(value instanceof List){List<Object> copy=new ArrayList<>();for(Object e:(List<?>)value)copy.add(freeze(e));return Collections.unmodifiableList(copy);}return value;}
  boolean matches(String original,String family,String command,Object parameter,int order){if(!text.equals(original)||order<0||order>=plan.size())return false;Map<String,Object> step=plan.get(order);return step.get("intent").equals(family)&&step.get("SCPIcommand").equals(command)&&same(step.get("SCPIparams"),parameter);}
  String top(){return top;}String kind(){return kind;}Map<String,Object> state(){return state;}List<Map<String,Object>> evidence(){return events;}
  private static boolean same(Object a,Object b){return a instanceof Number&&b instanceof Number?Math.abs(((Number)a).doubleValue()-((Number)b).doubleValue())<=Math.max(1e-15,Math.abs(((Number)b).doubleValue())*1e-10):Objects.equals(a,b);}
 }
 private final SemanticPolicy policy;private final Map<String,Object> fixture;private boolean fixtureConflict;
 EnglishDerivedPlanBridge(SemanticPolicy policy,Map<String,Object> fixture){
  this.policy=policy;this.fixture=new LinkedHashMap<>();
  for(Map.Entry<String,Object> e:fixture.entrySet()){
   if(!ValidatedExplicitContext.allowedFields().contains(e.getKey()))throw new IllegalArgumentException("unknown_offline_state_field");
   this.fixture.put(e.getKey(),StrictJson.parse(CompactSemanticProtocol.encode(e.getValue())));
  }
  normalizeFixture();
 }
 private void normalizeFixture(){
  if(this.fixture.containsKey("_vertical_channel")){
   Object addressed=this.fixture.get("_vertical_channel");
   if(!(addressed instanceof Number)||((Number)addressed).doubleValue()!=((Number)addressed).intValue()||((Number)addressed).intValue()<1||((Number)addressed).intValue()>4)fixtureConflict=true;
   else {String key=String.valueOf(((Number)addressed).intValue());Object scale=this.fixture.get("current_vertical_scale_volts");Object values=this.fixture.get("current_vertical_scale_volts_by_channel");
    Map<String,Object> byChannel=new LinkedHashMap<>();if(values instanceof Map)for(Map.Entry<?,?> e:((Map<?,?>)values).entrySet())byChannel.put(String.valueOf(e.getKey()),e.getValue());else if(values!=null)fixtureConflict=true;
    if(scale!=null){if(byChannel.containsKey(key)&&!Objects.equals(byChannel.get(key),scale))fixtureConflict=true;else byChannel.put(key,scale);this.fixture.put("current_vertical_scale_volts_by_channel",byChannel);}
   }
  }
 }
 private EnglishSemanticBridge.Result result(String status,String reason,String backend,String raw,int count){return new EnglishSemanticBridge.Result(status,"",reason,backend,Set.of("clarify","reject").contains(status)?EnglishSemanticBridge.terminalContract(status,reason):raw,count,Collections.emptyList());}
 private static int channel(String token){switch(token.toLowerCase(Locale.ROOT)){case "one":return 1;case "two":return 2;case "three":return 3;case "four":return 4;default:try{return Integer.parseInt(token);}catch(Exception e){return -1;}}}
 private static Double positive(Object value){if(!(value instanceof Number))return null;double n=((Number)value).doubleValue();return Double.isFinite(n)&&n>0?n:null;}
 private static Map<String,Object> step(String intent,String command,Object parameter){Map<String,Object> m=new LinkedHashMap<>();m.put("intent",intent);m.put("SCPIcommand",command);m.put("SCPIparams",parameter);return m;}
 Map<String,Object> stateCopy(){return (Map<String,Object>)StrictJson.parse(CompactSemanticProtocol.encode(fixture));}
 private static final Pattern POSITION_VOLTAGE=Pattern.compile("(?i)("+EnglishNumberWords.VALUE+")\\s+(millivolts?|volts?|mV|V)\\b(?!\\s*(?:/|per))");
 private static Matcher voltagePosition(String text){
  String normalized=TextSpanMapping.of(text).normalized;
  if(!Pattern.compile("(?i)\\b(?:position|offset)\\b").matcher(normalized).find())return null;
  Matcher quantity=POSITION_VOLTAGE.matcher(normalized);return quantity.find()?quantity:null;
 }
 private Double positionScale(int c){
  Object scale=fixture.get("current_vertical_scale_volts");
  if(fixture.containsKey("current_vertical_scale_volts_by_channel")){
   Object byChannel=fixture.get("current_vertical_scale_volts_by_channel");scale=byChannel instanceof Map?((Map<?,?>)byChannel).get(String.valueOf(c)):null;
  }
  return positive(scale);
 }
 EnglishSemanticBridge.Result positionText(String text,int c){
  Matcher quantity=voltagePosition(text);if(quantity==null)return null;
  if(Pattern.compile("(?i)\\b(?:by|up|down|raise|lower|increase|decrease)\\b").matcher(text).find())return result("clarify","relative_position_state_required","java_english_rules","",0);
  Double scale=positionScale(c);if(scale==null)return result("clarify","current_vertical_scale_volts_required","java_english_rules","",0);
  Double voltage=EnglishNumberWords.parse(quantity.group(1));if(voltage==null||!Double.isFinite(voltage))return result("reject","invalid_number","java_english_rules","",0);
  String unit=quantity.group(2);if(unit.equals("MV"))return result("reject","unsupported_megavolt_unit","java_english_rules","",0);
  double value=voltage*(unit.toLowerCase(Locale.ROOT).startsWith("m")?.001:1)/scale;
  if(!Double.isFinite(value))return result("reject","invalid_position_result","java_english_rules","",0);
  Map<String,Object> top=new LinkedHashMap<>();top.put("intent","VERT_SET_OFFSET");top.put("steps",List.of(step("VERTICAL_set","CH"+c+":POSition",value)));
  return evaluate(text,top,(List<?>)top.get("steps"),"java_english_rules",CompactSemanticProtocol.encode(top));
 }
 EnglishSemanticBridge.Result evaluate(String text,Map<?,?> top,List<?> actual,String backend,String raw){
  return evaluate(text,top,actual,backend,raw,false);
 }
 EnglishSemanticBridge.Result compile(String text,String intent){return evaluate(text,Map.of("intent",intent),Collections.emptyList(),"java_english_rules","",true);}
 private EnglishSemanticBridge.Result evaluate(String text,Map<?,?> top,List<?> actual,String backend,String raw,boolean compiling){
  if(fixtureConflict)return result("clarify","explicit_fixture_state_conflict",backend,raw,0);
  String intent=String.valueOf(top.get("intent"));
  Matcher positionQuantity="VERT_SET_OFFSET".equals(intent)?voltagePosition(text):null;
  if(positionQuantity==null&&!Set.of("MEASURE_ADD","MEASURE_REMOVE","MEASURE_QUERY","MEASURE_CLEAN","VERT_ADJUST_SCALE","HORZ_ADJUST_SCALE").contains(intent))return null;
  TextSpanMapping spans=TextSpanMapping.of(text);String surface=EnglishRequestCompiler.scopeSurface(spans.normalized.toLowerCase(Locale.ROOT));Set<Integer> channels=new LinkedHashSet<>();Matcher source=EnglishChannelEvidence.PATTERN.matcher(spans.normalized);
  String missing=EnglishRequestCompiler.missingDataIssue(surface);if(missing!=null)return result("clarify",missing,backend,raw,0);
  if(EnglishRequestCompiler.prohibited(surface)||!EnglishRequestCompiler.action(surface))return result("clarify","request_action_required",backend,raw,0);
  List<Map<String,Object>> evidence=new ArrayList<>();
  while(source.find()){
   if(!EnglishChannelEvidence.analog(source,spans.normalized))return result("clarify","non_analog_source",backend,raw,actual.size());
   int c=EnglishChannelEvidence.index(source);if(c<1||c>4)return result("reject","illegal_channel",backend,raw,actual.size());channels.add(c);
   TextSpanMapping.Span originalSpan=spans.originalSpan(source.start(),source.end());Map<String,Object> proof=new LinkedHashMap<>();proof.put("source_start",originalSpan.start);proof.put("source_end",originalSpan.end);proof.put("source_text",text.substring(originalSpan.start,originalSpan.end));proof.put("normalized_source_text",source.group());proof.put("original_asr_text",text);proof.put("field","channel");proof.put("object_type",EnglishChannelEvidence.objectType(source));proof.put("normalized_value",c);proof.put("model_generated",false);evidence.add(proof);
  }
  List<Map<String,Object>> expected=new ArrayList<>();String derivation;
  if("MEASURE_CLEAN".equals(intent)){
   if(!EnglishRequestCompiler.measurementClean(surface)||!channels.isEmpty()||EnglishRequestCompiler.measurementQuery(surface))return result("clarify","measurement_global_scope_required",backend,raw,actual.size());
   expected.add(step("MEASURE_set","MEASUrement:STATE","OFF"));derivation="measurement_explicit_entire_scope";
  }else if(positionQuantity!=null){
   if(channels.size()!=1)return result("clarify","missing_or_conflicting_channel",backend,raw,actual.size());int c=channels.iterator().next();
   if(surface.matches("(?s).*\\b(?:scale|sensitivity|divisions?|div|seconds?|hertz)\\b.*"))return result("clarify","position_unit_or_object_conflict",backend,raw,actual.size());
   Double scale=positionScale(c);if(scale==null)return result("clarify","current_vertical_scale_volts_required",backend,raw,actual.size());
   Double voltage=EnglishNumberWords.parse(positionQuantity.group(1));if(voltage==null||!Double.isFinite(voltage))return result("reject","invalid_number",backend,raw,actual.size());
   String unit=positionQuantity.group(2);if(unit.equals("MV"))return result("reject","unsupported_megavolt_unit",backend,raw,actual.size());
   double value=voltage*(unit.toLowerCase(Locale.ROOT).startsWith("m")?.001:1)/scale;if(!Double.isFinite(value))return result("reject","invalid_position_result",backend,raw,actual.size());
   TextSpanMapping.Span quantitySpan=spans.originalSpan(positionQuantity.start(),positionQuantity.end());Map<String,Object> quantityProof=new LinkedHashMap<>();quantityProof.put("source_start",quantitySpan.start);quantityProof.put("source_end",quantitySpan.end);quantityProof.put("source_text",text.substring(quantitySpan.start,quantitySpan.end));quantityProof.put("normalized_source_text",positionQuantity.group());quantityProof.put("original_asr_text",text);quantityProof.put("normalized_voltage",voltage*(unit.toLowerCase(Locale.ROOT).startsWith("m")?.001:1));quantityProof.put("normalized_unit","V");quantityProof.put("canonical_unit","div");quantityProof.put("addressed_channel",c);quantityProof.put("current_vertical_scale_volts",scale);quantityProof.put("model_generated",false);quantityProof.put("rule_id","position_voltage_from_explicit_offline_scale");evidence.add(quantityProof);
   if(positionQuantity.find())return result("clarify","position_conflicting_values",backend,raw,actual.size());
   expected.add(step("VERTICAL_set","CH"+c+":POSition",value));derivation="position_voltage_from_explicit_offline_scale";
  }else if(intent.endsWith("ADJUST_SCALE")){
   boolean vertical=intent.startsWith("VERT");if(vertical&&channels.size()!=1)return result("clarify","missing_or_conflicting_channel",backend,raw,actual.size());
   if(!vertical&&!channels.isEmpty())return result("clarify","horizontal_vertical_conflict",backend,raw,actual.size());
   boolean magnification=surface.matches("(?s).*\\b(?:magnification|magnify)\\b.*");
   boolean in=EnglishRequestCompiler.relativeHalf(text)||surface.matches("(?s).*\\b(?:zoom in|smaller|divid(?:e|ed)|magnify|closer|narrow)\\b.*")||surface.matches("(?s).*\\bhalve\\b.*")&&!magnification||magnification&&surface.matches("(?s).*\\b(?:twice|double|triple|increase|more)\\b.*")||!magnification&&surface.matches("(?s).*\\b(?:reduce|reducing|decrease)\\b.*");boolean out=surface.matches("(?s).*\\b(?:zoom out|larger|multipl(?:y|ied)|slower|expand|slow|widen)\\b.*")||surface.matches("(?s).*\\b(?:double|triple)\\b.*")&&!magnification||magnification&&surface.matches("(?s).*\\b(?:halve|half|reduce|reducing|decrease)\\b.*")||!magnification&&surface.matches("(?s).*\\b(?:increase|increasing)\\b.*");
   in|=surface.matches("(?s).*\\b(?:vertical|horizontal|[0-9]+x)\\s+in\\b.*")||surface.matches("(?s).*\\bin\\s+(?:zoom|adjustment|change)\\b.*");out|=surface.matches("(?s).*\\b(?:vertical|horizontal|[0-9]+x)\\s+out\\b.*")||surface.matches("(?s).*\\bout\\s+(?:zoom|adjustment|change)\\b.*");in|=!magnification&&surface.matches("(?s).*\\bhalf\\b.*\\b(?:current|present|existing)\\b.*");out|=!magnification&&surface.matches("(?s).*\\btwice\\b.*\\b(?:current|present|existing)\\b.*");if(in==out)return result("clarify","relative_direction_required",backend,raw,actual.size());
   String factorText=spans.normalized;Matcher addressed=EnglishChannelEvidence.PATTERN.matcher(factorText);while(addressed.find())factorText=factorText.substring(0,addressed.start())+addressed.group().replaceAll("."," ")+factorText.substring(addressed.end());
   Set<Double> factors=new LinkedHashSet<>();Set<String> factorSpans=new HashSet<>();
   for(String expression:new String[]{"(?:by(?: a factor of)?|factor(?: of)?)\\s+("+EnglishNumberWords.VALUE+")(?=\\s*(?:times|[,;.!?]|$))","("+EnglishNumberWords.VALUE+")\\s*(?:times|fold|x)\\b","("+EnglishNumberWords.WORD+")fold\\b"}){Matcher factorMatch=Pattern.compile(expression,Pattern.CASE_INSENSITIVE).matcher(factorText);while(factorMatch.find()){Double value=EnglishNumberWords.parse(factorMatch.group(1));if(value!=null)factors.add(value);String key=factorMatch.start(1)+":"+factorMatch.end(1);if(factorSpans.add(key)){TextSpanMapping.Span span=spans.originalSpan(factorMatch.start(1),factorMatch.end(1));Map<String,Object> proof=new LinkedHashMap<>();proof.put("source_start",span.start);proof.put("source_end",span.end);proof.put("source_text",text.substring(span.start,span.end));proof.put("original_asr_text",text);proof.put("normalized_value",value);proof.put("canonical_field","relative_factor");proof.put("rule_id","explicit_relative_factor");proof.put("model_generated",false);evidence.add(proof);}}}
   if(surface.matches("(?s).*\\b(?:double|halve|twice|half)\\b.*"))factors.add(2.0);if(surface.matches("(?s).*\\btriple\\b.*"))factors.add(3.0);
   if(factors.size()>1)return result("clarify","relative_factor_conflict",backend,raw,actual.size());Double factor=factors.size()==1?factors.iterator().next():null;
   if(factor==null||factor<=0||!Double.isFinite(factor))return result("clarify","relative_factor_required",backend,raw,actual.size());
   int c=vertical?channels.iterator().next():0;Object state=fixture.get(vertical?"current_vertical_scale_volts":"current_timebase_seconds");
   if(vertical&&fixture.get("current_vertical_scale_volts_by_channel") instanceof Map)state=((Map<?,?>)fixture.get("current_vertical_scale_volts_by_channel")).get(String.valueOf(c));
   Double current=positive(state);if(current==null)return result("clarify",vertical?"current_vertical_scale_volts_required":"current_timebase_seconds_required",backend,raw,actual.size());
   java.math.BigDecimal base=java.math.BigDecimal.valueOf(current),ratio=java.math.BigDecimal.valueOf(factor);double value=(in?base.divide(ratio,java.math.MathContext.DECIMAL128):base.multiply(ratio)).doubleValue();if(!Double.isFinite(value)||value<=0)return result("reject","invalid_relative_result",backend,raw,actual.size());
   if(!vertical&&value<5e-10)return result("reject","timebase_below_authority_minimum",backend,raw,actual.size());
   expected.add(step(vertical?"VERTICAL_set":"HORIZONTAL_set",vertical?"CH"+c+":SCALe":"HORizontal:SCAle",value));derivation="relative_scale_from_explicit_offline_fixture";
  }else{
   boolean dual=surface.matches("(?s).*\\b(?:phase|delay)\\b.*");int c=channels.size()==1?channels.iterator().next():-1,second=-1;
   if(dual&&"MEASURE_ADD".equals(intent)){
    Matcher roles=EnglishChannelEvidence.PATTERN.matcher(spans.normalized);Set<Integer> firstRoles=new LinkedHashSet<>(),secondRoles=new LinkedHashSet<>();int count=0;
    while(roles.find()){count++;String after=spans.normalized.substring(roles.end());Matcher role=Pattern.compile("(?i)^\\s+(first|second)\\b").matcher(after);if(!role.find())return result("clarify","measurement_source_roles_required",backend,raw,actual.size());int addressed=EnglishChannelEvidence.index(roles);Set<Integer> targets=role.group(1).equalsIgnoreCase("first")?firstRoles:secondRoles;targets.add(addressed);TextSpanMapping.Span span=spans.originalSpan(roles.start(),roles.end()+role.end());Map<String,Object> roleProof=new LinkedHashMap<>();roleProof.put("canonical_field",role.group(1).equalsIgnoreCase("first")?"SOURCE1":"SOURCE2");roleProof.put("source_start",span.start);roleProof.put("source_end",span.end);roleProof.put("source_text",text.substring(span.start,span.end));roleProof.put("original_asr_text",text);roleProof.put("normalized_value","CH"+addressed);roleProof.put("rule_id","explicit_ordered_measurement_source_role");roleProof.put("model_generated",false);evidence.add(roleProof);}
    if(count!=2||firstRoles.size()!=1||secondRoles.size()!=1)return result("clarify","measurement_source_roles_required",backend,raw,actual.size());c=firstRoles.iterator().next();second=secondRoles.iterator().next();
   }else if(channels.size()!=1||dual)return result("clarify",dual?"measurement_source_roles_required":"missing_or_conflicting_channel",backend,raw,actual.size());
   boolean readoutObject=surface.matches("(?s).*\\b(?:measurements?|measure|readouts?|readings?|items?|value)\\b.*");
   if(surface.matches("(?s).*\\btrigger\\b.*")||!readoutObject&&surface.matches("(?s).*\\b(?:waveform|trace)\\b.*"))return result("clarify","measurement_trace_object_conflict",backend,raw,actual.size());
   String[][] aliases={{"\\brms\\b","RMS"},{"peak[- ]to[- ]peak|\\bvpp\\b","PK2Pk"},{"\\b(?:frequency|freq)\\b","FREQuency"},{"\\bperiod\\b","PERIod"},{"\\bmean\\b","MEAN"},{"\\bmaximum\\b","MAXimum"},{"\\bminimum\\b","MINImum"},{"\\b(?:rise|rising)[- ]+time\\b","RISe"},{"\\b(?:fall|falling)[- ]+time\\b","FALL"},{"\\bphase\\b","PHAse"},{"\\bdelay\\b","DELay"}};
   Set<String> types=new LinkedHashSet<>();
   for(String[] alias:aliases){Matcher named=Pattern.compile(alias[0],Pattern.CASE_INSENSITIVE).matcher(spans.normalized);if(named.find()){
    types.add(alias[1]);TextSpanMapping.Span namedSpan=spans.originalSpan(named.start(),named.end());Map<String,Object> typeProof=new LinkedHashMap<>();typeProof.put("source_start",namedSpan.start);typeProof.put("source_end",namedSpan.end);typeProof.put("source_text",text.substring(namedSpan.start,namedSpan.end));typeProof.put("original_asr_text",text);typeProof.put("normalized_value",alias[1]);typeProof.put("canonical_field","measurement_type");typeProof.put("rule_id","authority_measurement_type_alias");typeProof.put("authority_record_id","measure-r33-set-v0");typeProof.put("model_generated",false);evidence.add(typeProof);
   }}
   String type=types.size()==1?types.iterator().next():null;
   if(type==null)return result("clarify","measurement_type_required",backend,raw,actual.size());
   int slot=-1;
   if("MEASURE_ADD".equals(intent)){
    if(!EnglishRequestCompiler.measurementAdd(surface)||EnglishRequestCompiler.measurementRemove(surface)||EnglishRequestCompiler.measurementQuery(surface))return result("clarify","measurement_operation_conflict",backend,raw,actual.size());
    Object supplied=fixture.get("first_free_measurement_slot");if(supplied instanceof Number&&((Number)supplied).doubleValue()==((Number)supplied).intValue())slot=((Number)supplied).intValue();
    if(slot<1||slot>8)return result("clarify","first_free_measurement_slot_required",backend,raw,actual.size());
    int reused=0;Object existing=fixture.get("measurements");if(existing instanceof List)for(Object row:(List<?>)existing)if(row instanceof Map){Object occupied=((Map<?,?>)row).get("slot");if(occupied instanceof Number&&((Number)occupied).doubleValue()==slot){reused++;if(!"OFF".equals(((Map<?,?>)row).get("state")))return result("clarify","first_free_measurement_slot_conflict",backend,raw,actual.size());}}if(reused>1)return result("clarify","first_free_measurement_slot_conflict",backend,raw,actual.size());
    String prefix="MEASUrement:MEAS"+slot+":";expected.add(step("MEASURE_set",prefix+"SOURCE1","CH"+c));if(dual)expected.add(step("MEASURE_set",prefix+"SOURCE2","CH"+second));expected.add(step("MEASURE_set",prefix+"TYPe",type));expected.add(step("MEASURE_set",prefix+"STATE","ON"));expected.add(step("MEASURE_set","MEASUrement:STATE","ON"));
   }else{
    boolean remove=EnglishRequestCompiler.measurementRemove(surface);boolean query=EnglishRequestCompiler.measurementQuery(surface);
    if(query&&"OFF".equals(fixture.get("measurement_system_state")))return result("clarify","measurement_system_state_required",backend,raw,actual.size());
    if("MEASURE_REMOVE".equals(intent)?!remove||query:!query||remove)return result("clarify","measurement_operation_conflict",backend,raw,actual.size());
    int matches=0;Object list=fixture.get("measurements");if(list instanceof List)for(Object object:(List<?>)list)if(object instanceof Map){Map<?,?> entry=(Map<?,?>)object;if(("CH"+c).equals(entry.get("source"))&&type.equals(entry.get("type"))&&"ON".equals(entry.get("state"))&&entry.get("slot") instanceof Number&&((Number)entry.get("slot")).doubleValue()==((Number)entry.get("slot")).intValue()){slot=((Number)entry.get("slot")).intValue();matches++;}}
    if(matches!=1||slot<1||slot>8)return result("clarify","measurement_slot_required",backend,raw,actual.size());
    int sameSlot=0;if(list instanceof List)for(Object object:(List<?>)list)if(object instanceof Map){Object occupied=((Map<?,?>)object).get("slot");if(occupied instanceof Number&&((Number)occupied).doubleValue()==slot)sameSlot++;}if(sameSlot!=1)return result("clarify","measurement_slot_conflict",backend,raw,actual.size());
    expected.add(step(query&&!remove?"MEASURE_query":"MEASURE_set","MEASUrement:MEAS"+slot+("MEASURE_QUERY".equals(intent)?":VALue?":":STATE"),"MEASURE_QUERY".equals(intent)?Collections.emptyMap():"OFF"));
   }
   derivation="measurement_business_steps_from_typed_source_and_explicit_slot_fixture";
  }
  if(compiling){Map<String,Object> compiled=new LinkedHashMap<>();compiled.put("intent",intent);compiled.put("steps",expected);raw=CompactSemanticProtocol.encode(compiled);actual=expected;}
  if(expected.size()!=actual.size())return result("reject","missing_or_extra_business_step",backend,raw,actual.size());
  evidence.addAll(EnglishRequestCompiler.evidence(text));Proof sourceProof=new Proof(text,intent,derivation,expected,evidence,fixture);List<String> commands=new ArrayList<>();
  for(int i=0;i<expected.size();i++){
   if(!(actual.get(i) instanceof Map))return result("reject","invalid_step_shape",backend,raw,actual.size());Map<?,?> candidate=(Map<?,?>)actual.get(i);Map<String,Object> wanted=expected.get(i);
   if(!candidate.keySet().equals(Set.of("intent","SCPIcommand","SCPIparams"))||!wanted.get("intent").equals(candidate.get("intent"))||!wanted.get("SCPIcommand").equals(candidate.get("SCPIcommand")))return result("reject","business_source_type_slot_or_order_mismatch",backend,raw,actual.size());
   Object a=candidate.get("SCPIparams"),b=wanted.get("SCPIparams");boolean same=a instanceof Number&&b instanceof Number?Math.abs(((Number)a).doubleValue()-((Number)b).doubleValue())<=Math.max(1e-15,Math.abs(((Number)b).doubleValue())*1e-10):Objects.equals(a,b);
   if(!same)return result("reject","business_parameter_mismatch",backend,raw,actual.size());
   SemanticPolicy.Result qualified=policy.evaluateDerivedEnglish(text,CompactSemanticProtocol.encode(candidate),sourceProof,i);
   if(!"preview".equals(qualified.status))return result(qualified.status,qualified.reason,backend,raw,actual.size());
   commands.add(qualified.scpi);evidence.addAll(qualified.parameterEvidence);
  }
  Map<String,Object> proof=new LinkedHashMap<>();proof.put("rule_id",derivation);proof.put("original_asr_text",text);proof.put("source_type","explicit_offline_fixture_and_business_semantics");proof.put("offline_fixture",Collections.unmodifiableMap(fixture));proof.put("model_generated",false);proof.put("instrument_state_queried",false);evidence.add(proof);
  return new EnglishSemanticBridge.Result("preview",String.join("\n",commands),derivation,backend,raw,actual.size(),evidence);
 }
}
