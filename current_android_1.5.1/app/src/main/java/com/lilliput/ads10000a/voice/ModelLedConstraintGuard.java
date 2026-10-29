package com.lilliput.ads10000a.voice;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Model supplies the raw semantics; reliable constraints are checked before admission.
 * Complete current-request proofs may recover typed operands without rewriting the raw. */
final class ModelLedConstraintGuard {
 static final String VERSION="model_led_constraints_v1";
 enum Classification { CONSISTENT, CONFLICT, LEXICAL_INSUFFICIENT, REQUEST_AMBIGUOUS_OR_MISSING }
 private final ScpiTableEligibility table;private final UnitSourceModel units;
 private final String authorityContract;private NativeOperationPlanner adjacentStepPlanner;
 private final ParameterSourceGuard positiveCorrectionSources;
 private final Map<String,List<String>> unitCues=new HashMap<>();
 private final Map<String,Set<BigDecimal>> unitFactors=new HashMap<>();
 private final Map<String,Map<String,Set<BigDecimal>>> factorsByUnitCue=new HashMap<>();
 private static final Pattern CUE_WORDS=Pattern.compile("[A-Za-z]+|[\\p{IsHan}]{1,8}");
 private static final Pattern CUE_HAN=Pattern.compile("\\p{IsHan}");
 private static final Pattern CUE_DIVISION=Pattern.compile("(?:一|每)格.*$");
 private static final Pattern CUE_SKIP=Pattern.compile("per|pre|each|every|a|an|divisions?|div|每|一格|格");
 private static final class CueProgram {final String value;final boolean han;final Set<BigDecimal> factors;CueProgram(String raw,Set<BigDecimal> f){value=raw.toLowerCase(Locale.ROOT);han=CUE_HAN.matcher(value).find();factors=Collections.unmodifiableSet(new LinkedHashSet<>(f));}}
 private final Map<String,List<CueProgram>> cuePrograms=new HashMap<>();
 private static final Pattern EN_NUMBER=Pattern.compile("(?i)(?<![A-Za-z0-9])("+EnglishNumberWords.VALUE+")(?![A-Za-z0-9])");
 private static final Pattern MODEL_CHANNEL=Pattern.compile("(?i)(?:^CH|:CH)([0-9]+)(?=[:?]|$)");
 private static final Pattern SOURCE_INDEX=Pattern.compile("(?i)(?<![A-Za-z0-9])(?:REF|MATH|D|M|B)[0-9]+(?![A-Za-z0-9])");
 private static final Pattern RELATIVE=Pattern.compile("(?i)\\b(?:double|halve|twice|triple|multiply|factor|times)\\b|(?:上移|下移|上挪|下挪|加倍|减半|扩大|缩小)|\\b(?:move|shift|raise|lower)\\b.*\\b(?:by|up|down)\\b");
 private static final Pattern SI_PREFIX=Pattern.compile("(?i)^[\\s,:，：]*(milli|micro|nano|kilo|mega|毫|微|纳|千|兆)");
 // An explicit power unit is a different dimension, not a fuzzy voltage spelling.
 private static final Pattern POWER_UNIT_SUFFIX=Pattern.compile("(?i)^[\\s,:]*((?:(?:pico|nano|micro|milli|kilo|mega|giga)\\s*)?watts?|(?:[pnumkMGgµμ]\\s*)?w)\\b");
 private static final Pattern NON_COMMAND_GREETING=Pattern.compile("(?iu)\\s*(?:hello|hi|hey(?:\\s+OWON)?|good\\s+(?:morning|afternoon|evening|night)|thanks|thank\\s+you|have\\s+a\\s+nice\\s+day|你好|您好|早上好|上午好|下午好|晚上好|晚安|谢谢|小欧小欧)[\\s,.!?!，。！？]*");
 private static final class Quantity {final BigDecimal value;final int start,end;Quantity(BigDecimal v,int a,int b){value=v;start=a;end=b;}}
 private static final class Evidence {
  final List<Map<String,Object>> spans=new ArrayList<>();boolean lexical;
  void add(String text,int a,int b,String field,Object value){TextSpanMapping.Span span=TextSpanMapping.of(text).originalSpan(a,b);Map<String,Object> e=new LinkedHashMap<>();e.put("field",field);e.put("canonical_field",field.equals("explicit_quantity")?"quantity":field.equals("explicit_unit")?"unit":field.equals("explicit_analog_channel")?"channel":field);e.put("source_start",span.start);e.put("source_end",span.end);e.put("source_text",text.substring(span.start,span.end));e.put("original_asr_text",text);e.put("normalized_value",value);e.put("rule_id",VERSION);e.put("model_generated",false);spans.add(e);}
 }
 ModelLedConstraintGuard(Map root,ScpiTableEligibility t){
  table=t;units=new UnitSourceModel(root);positiveCorrectionSources=new ParameterSourceGuard(root,t);authorityContract=CompactSemanticProtocol.encode(root);
  for(Object o:(List)((Map)root.getOrDefault("unit_sources",Collections.emptyMap())).getOrDefault("definitions",Collections.emptyList())){
   Map definition=(Map)o;String canonical=String.valueOf(definition.get("canonical"));String base=canonical.replace("/div","");
   List<String> cues=unitCues.computeIfAbsent(base,k->new ArrayList<>());Set<BigDecimal> factors=unitFactors.computeIfAbsent(base,k->new HashSet<>());
   for(Object form:(List)definition.get("forms")){Map f=(Map)form;String text=String.valueOf(f.get("text"));BigDecimal factor=new BigDecimal(String.valueOf(f.get("factor")));cues.add(text);factors.add(factor);factorsByUnitCue.computeIfAbsent(base,k->new HashMap<>()).computeIfAbsent(text,k->new LinkedHashSet<>()).add(factor);}
  }
  // Full spoken SI names mirror the existing authority-backed exact unit reader.
  for(String[] family:new String[][]{{"V","volt"},{"s","second"},{"Hz","hertz"}}){
   List<String> cues=unitCues.computeIfAbsent(family[0],k->new ArrayList<>());
   for(String prefix:new String[]{"","nano","micro","milli","kilo","mega"}){
    BigDecimal factor=new BigDecimal(prefix.equals("nano")?"1e-9":prefix.equals("micro")?"1e-6":prefix.equals("milli")?"1e-3":prefix.equals("kilo")?"1e3":prefix.equals("mega")?"1e6":"1");
    for(String text:new String[]{prefix+family[1],prefix+family[1]+"s"}){cues.add(text);if(unitFactors.getOrDefault(family[0],Collections.emptySet()).stream().anyMatch(f->f.compareTo(factor)==0))factorsByUnitCue.computeIfAbsent(family[0],k->new HashMap<>()).computeIfAbsent(text,k->new LinkedHashSet<>()).add(factor);}
   }
  }
  for(Map.Entry<String,List<String>> entry:unitCues.entrySet()){List<CueProgram> programs=new ArrayList<>();for(String raw:entry.getValue())programs.add(new CueProgram(raw,factorsByUnitCue.getOrDefault(entry.getKey(),Collections.emptyMap()).getOrDefault(raw,Collections.emptySet())));cuePrograms.put(entry.getKey(),Collections.unmodifiableList(programs));}
 }
 private static String hanMaskedEvidenceSurface(String original){char[] chars=original.toCharArray();for(int i=0;i<original.length();){int cp=original.codePointAt(i),n=Character.charCount(cp);if(Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN)Arrays.fill(chars,i,i+n,' ');i+=n;}return new String(chars);}
 private SemanticPolicy.Result decision(String status,String reason,Classification kind,Evidence e,String wire){
  Map<String,Object> audit=new LinkedHashMap<>();audit.put("constraint_profile",VERSION);audit.put("evidence_classification",kind.name());audit.put("model_owns_semantic_fields",true);audit.put("word_for_word_proof_required",false);audit.put("ASR_original_preserved",true);audit.put("legal_JSON_alone_is_not_permission",true);audit.put("lexical_cue_does_not_select_or_convert_model_parameter",true);
  return new SemanticPolicy.Result(status,wire,reason,e.spans,audit);
 }
 private SemanticPolicy.Result conflict(String reason,Evidence e){return decision("clarify",reason,Classification.CONFLICT,e,"");}
 private SemanticPolicy.Result missing(String reason,Evidence e){return decision("clarify",reason,Classification.REQUEST_AMBIGUOUS_OR_MISSING,e,"");}
 /** Re-admit a deterministic candidate through both positive provenance and this same guard. */
 private SemanticPolicy.Result recovered(String original,Map<?,?> raw,Map<String,Object> candidate,
      SemanticLanguage language,ValidatedExplicitContext context,SemanticPolicy policy,
      SemanticPolicy.Result provenance,String kind){
  if(provenance==null||!"preview".equals(provenance.status)||!Objects.equals(raw.get("intent"),candidate.get("intent")))return null;
  ScpiTableEligibility.Decision qualified=table.qualify((String)candidate.get("SCPIcommand"),candidate.get("SCPIparams"));
  if(qualified.status!=ScpiTableEligibility.Status.IN_TABLE_SET||!qualified.canonical.equalsIgnoreCase(provenance.scpi))return null;
  SemanticPolicy.Result checked=check(original,candidate,language,context,policy);
  if(!"preview".equals(checked.status)||!qualified.canonical.equalsIgnoreCase(checked.scpi))return null;
  List<Map<String,Object>> evidence=new ArrayList<>(provenance.parameterEvidence);evidence.addAll(checked.parameterEvidence);
  Map<String,Object> derivation=new LinkedHashMap<>();derivation.put("canonical_field","deterministic_native_recovery");derivation.put("source_type",kind);derivation.put("source_start",0);derivation.put("source_end",original.length());derivation.put("source_text",original);derivation.put("original_asr_text",original);derivation.put("fixture_lineage",context.snapshot());derivation.put("model_candidate",new LinkedHashMap<>(raw));derivation.put("derived_candidate",candidate);derivation.put("normalized_value",candidate.get("SCPIparams"));derivation.put("rule_id","fully_evidenced_typed_native_recovery");derivation.put("model_generated",false);derivation.put("instrument_state_queried",false);evidence.add(derivation);
  Map<String,Object> audit=new LinkedHashMap<>(checked.resolution);audit.put("model_owns_semantic_fields",false);audit.put("deterministic_recovery",true);audit.put("original_model_raw_preserved",true);audit.put("derived_candidate",candidate);audit.put("derivation_source_type",kind);audit.put("candidate_rechecked_by_shared_policy",true);
  return new SemanticPolicy.Result("preview",checked.scpi,"fully_evidenced_typed_native_recovery",evidence,audit);
 }
 private static boolean equalsNumber(Object value,BigDecimal expected){return value instanceof Number&&expected!=null&&expected.compareTo(new BigDecimal(value.toString()))==0;}
 /** A spoken delta or normalized voltage is an operand; arbitrary wrong numbers are not. */
 private static boolean positionOperand(Map<?,?> raw,SemanticPolicy.PositionRequest bound){
  Object parameter=raw.get("SCPIparams");if(bound.relative&&equalsNumber(parameter,bound.delta))return true;
  Object scales=bound.context.snapshot().get("current_vertical_scale_volts_by_channel"),scale=scales instanceof Map?((Map<?,?>)scales).get(String.valueOf(bound.channel)):null;
  boolean voltage=bound.evidence.stream().anyMatch(e->"unit".equals(e.get("canonical_field"))&&"V".equals(e.get("normalized_unit")));
  return voltage&&scale instanceof Number&&equalsNumber(parameter,bound.delta.multiply(new BigDecimal(scale.toString())));
 }
 private Set<BigDecimal> matchedCueFactors(String suffix,String expected){
  if(UnitSourceModel.unitProgramsReused())return cuePreparedFactors(suffix,expected);
  String base=expected.replace("/div","");List<String> candidates=unitCues.getOrDefault(base,Collections.emptyList());
  Set<BigDecimal> matched=new LinkedHashSet<>();int best=Integer.MAX_VALUE;
  Matcher words=Pattern.compile("[A-Za-z]+|[\\p{IsHan}]{1,8}").matcher(suffix);
  while(words.find())for(String known:candidates){String a=words.group().toLowerCase(Locale.ROOT).replaceFirst("(?:一|每)格.*$",""),b=known.toLowerCase(Locale.ROOT);
   if(a.matches("per|pre|each|every|a|an|divisions?|div|每|一格|格"))continue;
   if(b.length()<2)continue;
   int cost=cueMatchCost(a,b,Pattern.compile("\\p{IsHan}").matcher(a).find(),Pattern.compile("\\p{IsHan}").matcher(b).find());
   Set<BigDecimal> supported=factorsByUnitCue.getOrDefault(base,Collections.emptyMap()).getOrDefault(known,Collections.emptySet());
   if(cost<0||cost>best||supported.isEmpty())continue;if(cost<best){matched.clear();best=cost;}for(BigDecimal factor:supported)matched.add(factor.stripTrailingZeros());
  }
  return matched;
 }
 private Set<BigDecimal> cuePreparedFactors(String suffix,String expected){
  Set<BigDecimal> matched=new LinkedHashSet<>();int best=Integer.MAX_VALUE;
  Matcher words=CUE_WORDS.matcher(suffix);List<CueProgram> programs=cuePrograms.getOrDefault(expected.replace("/div",""),Collections.emptyList());
  while(words.find()){String a=CUE_DIVISION.matcher(words.group().toLowerCase(Locale.ROOT)).replaceFirst("");if(CUE_SKIP.matcher(a).matches())continue;boolean han=CUE_HAN.matcher(a).find();
   for(CueProgram program:programs){String b=program.value;if(b.length()<2)continue;int cost=cueMatchCost(a,b,han,program.han);
    if(cost<0||cost>best||program.factors.isEmpty())continue;if(cost<best){matched.clear();best=cost;}for(BigDecimal factor:program.factors)matched.add(factor.stripTrailingZeros());
   }
  }return matched;
 }
 // Rank spelling evidence before inspecting modelValue. A tied magnitude is
 // uncertainty, never a menu from which the model's number may select a unit.
 private static int cueMatchCost(String a,String b,boolean han,boolean knownHan){
  if(a.equals(b)||knownHan&&a.contains(b)&&b.length()>=2)return 0;
  // A similar English spelling is not source evidence for a physical unit.
  // Choosing a volts factor for an unrecognized word can turn a power-unit
  // recognition error into a voltage write. Explicit authority aliases still
  // pass above; preserve the existing Chinese-only cue policy separately.
  if(!han&&!knownHan)return -1;
  int limit=han?1:Math.max(1,(int)Math.ceil(b.length()*.30));
  if(a.length()<2||Math.abs(a.length()-b.length())>limit)return -1;
  int cost=distance(a,b);return cost<=limit?cost:-1;
 }
 private static int distance(String a,String b){int[] row=new int[b.length()+1];for(int j=0;j<row.length;j++)row[j]=j;for(int i=1;i<=a.length();i++){int[] n=new int[b.length()+1];n[0]=i;for(int j=1;j<=b.length();j++)n[j]=Math.min(Math.min(n[j-1]+1,row[j]+1),row[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));row=n;}return row[b.length()];}
 private UnitSourceModel.Evidence exactUnit(String text,int end){
  UnitSourceModel.Evidence unit=units.at(text,end);if(unit!=null)return unit;
  // Case-fold full unit words only; retain original SI abbreviations (mV is not MV).
  String folded=text.replaceAll("(?i)\\bVOLTS\\b","volts").replaceAll("(?i)\\bVOLT\\b","volt");
  return units.at(folded,end);
 }
 private UnitSourceModel.Evidence precedingUnit(String text,int quantityStart,String expected,boolean terminalRecovery){
  UnitSourceModel.Evidence found=null;for(int i=0;i<quantityStart;i++){UnitSourceModel.Evidence candidate=exactUnit(text,i);if(candidate!=null&&candidate.end<=quantityStart){if(candidate.normalized.equals("div")&&!expected.equals("div")){i=candidate.end-1;continue;}if(terminalRecovery&&!text.substring(candidate.end,quantityStart).matches("[\\s,:=，：]*")){i=candidate.end-1;continue;}if(found!=null&&!found.normalized.equals(candidate.normalized))return null;found=candidate;i=candidate.end-1;}}return found;
 }
 private List<Quantity> quantities(String surface,List<int[]> indices,SemanticLanguage language){
  // Exclude source indices before number tokenization, so 'input four one division'
  // does not become the invalid quantity 'four one'. Offsets stay in the original text.
  List<Quantity> out=new ArrayList<>();
  if(language==SemanticLanguage.ZH){
   // Parse complete number tokens on the untouched surface. Source ownership is
   // checked by interval overlap; never reparse a surviving fragment of a number.
   for(ChineseNumericNormalizer.Token t:new ChineseNumericNormalizer().normalize(surface).tokens)if(t.type==ChineseNumericNormalizer.TokenType.NUMBER&&t.valid&&t.numericValue!=null&&!overlapsSource(t.rawStart,t.rawEnd,indices))out.add(new Quantity(t.numericValue,t.rawStart,t.rawEnd));
   // A known numeral remains reliable when the following unit spelling is not recognized.
   Matcher m=Pattern.compile("(?:正|负|[+-])?(?:半|[零〇一二两三四五六七八九十百千万点]+)").matcher(surface);
   while(m.find())if(!overlapsSource(m.start(),m.end(),indices)&&out.stream().noneMatch(q->m.start()<q.end&&m.end()>q.start))try{out.add(new Quantity(UnitSourceModel.decimal(m.group()),m.start(),m.end()));}catch(RuntimeException notNumeric){/* do not invent a number */}
   out.sort(Comparator.comparingInt(q->q.start));
  }
  else {
   // English number-word groups need source separation. Mask only verified
   // source spans at equal length; all evidence offsets retain the original text.
   char[] masked=surface.toCharArray();for(int[] span:indices)for(int i=span[0];i<span[1];i++)masked[i]=' ';surface=new String(masked);
   Matcher m=EN_NUMBER.matcher(surface);while(m.find())if(!inside(m.start(),m.end(),indices)){Double value=EnglishNumberWords.parse(m.group());if(value!=null&&Double.isFinite(value))out.add(new Quantity(BigDecimal.valueOf(value),m.start(),m.end()));}
   // Supplement only uncovered compact SI spans. Full signed words/fractions retain
   // their original exact English parse rather than acquiring duplicate operands.
   for(ChineseNumericNormalizer.Token t:new ChineseNumericNormalizer().normalize(surface).tokens)if(t.type==ChineseNumericNormalizer.TokenType.NUMBER&&t.valid&&t.numericValue!=null&&!inside(t.rawStart,t.rawEnd,indices)&&out.stream().noneMatch(q->t.rawStart<q.end&&t.rawEnd>q.start)){
    BigDecimal value=t.numericValue;int start=t.rawStart;Matcher sign=Pattern.compile("(?i)\\b(minus|negative|plus|positive)\\s+$").matcher(surface.substring(0,start));if(sign.find()){start=sign.start();if(sign.group(1).matches("(?i)minus|negative"))value=value.negate();}out.add(new Quantity(value,start,t.rawEnd));
   }out.sort(Comparator.comparingInt(q->q.start));
  }
  return out;
 }
 private static boolean inside(int a,int b,List<int[]> spans){for(int[] s:spans)if(a>=s[0]&&b<=s[1])return true;return false;}
 private static boolean overlapsSource(int a,int b,List<int[]> spans){for(int[] s:spans)if(a<s[1]&&b>s[0])return true;return false;}
 private static boolean has(String s,String regex){return Pattern.compile(regex,Pattern.CASE_INSENSITIVE).matcher(s).find();}
 static boolean nativeAutomaticTriggerLevel(String text){return has(text,"自动(?:设置|调整|寻找)[^，。;；]*触发电平|\\bautomatically\\s+(?:set|adjust|find)\\b.{0,40}\\btrigger\\s+level\\b");}
 static boolean explicitExternalTriggerSource(String text){return has(text,"\\b(?:EXT|external)\\b|外部");}
 private static BigDecimal explicitRelativeScaleValue(String text,String head,ValidatedExplicitContext context){
  if(!head.equals("HORIZONTAL:SCALE")&&!head.matches("CH[1-4]:SCALE"))return null;
  Object current=context.snapshot().get("current_timebase_seconds");
  if(head.startsWith("CH")){Object map=context.snapshot().get("current_vertical_scale_volts_by_channel");current=map instanceof Map?((Map<?,?>)map).get(head.substring(2,3)):context.snapshot().get("current_vertical_scale_volts");}
  if(!(current instanceof Number))return null;Set<BigDecimal> ratios=new HashSet<>();
  if(has(text,"\\b(?:double|twice)\\b|加倍"))ratios.add(new BigDecimal("2"));
  if(has(text,"\\btriple\\b"))ratios.add(new BigDecimal("3"));
  if(has(text,"\\b(?:halve|half)\\b|减半|一半"))ratios.add(new BigDecimal("0.5"));
  Matcher m=Pattern.compile("(?:(?:变成|变为|调成|调整为)(?:现在|当前|目前)|(?:放大|增大|扩大)到(?:现值|当前值))(?:的)?([0-9]+(?:\\.[0-9]+)?|[零〇一二两三四五六七八九十百千万点]+)倍").matcher(text);
  while(m.find())try{ratios.add(UnitSourceModel.decimal(m.group(1)).stripTrailingZeros());}catch(RuntimeException ignored){}
  if(ratios.size()!=1)return null;BigDecimal ratio=ratios.iterator().next();return ratio.signum()>0?new BigDecimal(current.toString()).multiply(ratio):null;
 }
 /** Only positive, structurally missing current-request fields. Complete requests return null. */
 static String requestGapReason(String original,SemanticLanguage language,ValidatedExplicitContext context,ScpiTableEligibility table){
  if(original==null||context==null||table==null)return null;
  String s=language==SemanticLanguage.EN?EnglishRequestCompiler.scopeSurface(original):PcSemanticGuard.chineseScopeSurface(original);
  boolean time=has(s,"time\\s*(?:base|per\\s+division)|timebase|horizontal\\s+scale|主时基|横轴|水平档位|每格时间|每格.*时间");
  boolean adjustment=has(s,"\\b(?:increase|decrease|widen|narrow|reduce|expand|double|halve|twice|triple|multiply|divide)\\b|加倍|减半|增大|减小|缩小|扩大");
  if(time&&adjustment&&!has(s,"\\b(?:read|query|what|whether)\\b|查询|读取|是多少")){
   boolean absolute=has(s,"\\bto\\s+(?:"+EnglishNumberWords.VALUE+")\\s*(?:ns|us|ms|s|nanoseconds?|microseconds?|milliseconds?|seconds?)\\b|(?:到|为|成)[0-9一二两三四五六七八九十百千万点.]+(?:纳秒|微秒|毫秒|秒)");
   if(absolute)return null;
   boolean factor=has(s,"\\b(?:double|halve|twice|triple|half)\\b|加倍|减半|一半|[0-9一二两三四五六七八九十]+倍|\\b(?:by|factor|times)\\s+(?:a\\s+factor\\s+of\\s+|of\\s+)?(?:"+EnglishNumberWords.VALUE+")|(?:"+EnglishNumberWords.VALUE+")\\s+times");
   if(!factor&&!(context.snapshot().containsKey("current_timebase_seconds")&&context.snapshot().containsKey("verified_timebase_steps_seconds")))return "current_timebase_and_adjustment_step_required";
   if(!context.snapshot().containsKey("current_timebase_seconds"))return "current_timebase_required";
  }
  if(adjustment&&!time&&has(s,"scale|sensitivity|volts?\\s+per\\s+division|垂直档位|垂直刻度")&&!has(s,"\\b(?:read|query|what|whether)\\b|查询|是多少")){
   boolean absolute=has(s,"\\bto\\s+(?:"+EnglishNumberWords.VALUE+")\\s*(?:volts?|millivolts?|V|mV)\\b|(?:到|为|成)[0-9一二两三四五六七八九十百千万点.]+(?:伏|毫伏)");
   boolean factor=has(s,"\\b(?:double|halve|twice|triple|half)\\b|加倍|减半|一半|倍|\\b(?:factor|times|by)\\s+(?:a\\s+factor\\s+of\\s+|of\\s+)?(?:"+EnglishNumberWords.VALUE+")");
   if(!absolute&&!factor&&!context.snapshot().containsKey("verified_vertical_scale_steps_volts_by_channel"))return "current_vertical_scale_and_adjustment_step_required";
  }
  if(has(s,"trigger\\s+level|触发电平")&&!nativeAutomaticTriggerLevel(s)&&!explicitExternalTriggerSource(s)){
   Set<Integer> sources=new HashSet<>(PcSemanticGuard.analogSources(s));Matcher m=EnglishChannelEvidence.PATTERN.matcher(s);while(m.find())if(EnglishChannelEvidence.analog(m,s))sources.add(EnglishChannelEvidence.index(m));
   if(sources.isEmpty()&&!context.snapshot().containsKey("current_trigger_source"))return "trigger_level_source_required";
  }
  ExistingMeasurementRequest measurement=ExistingMeasurementRequest.parse(original,context,table);
  return measurement==null?null:measurement.issue;
 }
 SemanticPolicy.Result check(String original,Map<?,?> raw,SemanticLanguage language,ValidatedExplicitContext context,SemanticPolicy policy){
  return check(original,raw,language,context,policy,false);
 }
 SemanticPolicy.Result check(String original,Map<?,?> raw,SemanticLanguage language,ValidatedExplicitContext context,SemanticPolicy policy,boolean terminalRecovery){
  Evidence e=new Evidence();String command=(String)raw.get("SCPIcommand");Object parameter=raw.get("SCPIparams");String h=table.canonicalHead(command).toUpperCase(Locale.ROOT);
  if(NON_COMMAND_GREETING.matcher(original).matches())return decision("reject","non_command_greeting",Classification.CONFLICT,e,"");
  ScpiTableEligibility.Decision qualified=table.qualify(command,parameter);
  if(!Set.of(ScpiTableEligibility.Status.IN_TABLE_QUERY,ScpiTableEligibility.Status.IN_TABLE_SET,ScpiTableEligibility.Status.IN_TABLE_ACTION).contains(qualified.status))return decision("reject",qualified.reason,Classification.CONFLICT,e,"");
  if(language==SemanticLanguage.EN&&"AUTOSET".equals(h)&&!EnglishAutosetPrompt.requested(original,language))return missing("explicit_autoset_request_required",e);
  String range=parameter instanceof Number?SemanticPolicy.explicitScaleRangeIssue(command,new BigDecimal(parameter.toString())):null;
  if(range!=null)return decision("reject",range,Classification.CONFLICT,e,"");
  // Catalog-valid no-parameter system actions still need their actual operation source.
  // This reuses the existing positive proof; it does not invent a command from text.
  if(Set.of("*RST","*CLS","*WAI","*OPC").contains(h)&&!ParameterSourceGuard.systemActionSource(original,command))return decision("reject","system_action_source_not_confirmed",Classification.LEXICAL_INSUFFICIENT,e,"");
  if("*OPC?".equals(h)&&!ParameterSourceGuard.operationCompletionQuerySource(original))return missing("system_query_object_not_established",e);
  String gap=requestGapReason(original,language,context,table);if(gap!=null)return missing(gap,e);
  String reliable=ReliableCatalogConflictEvidence.issue(original,command,parameter,language,context,table);
  if(reliable!=null){if(reliable.startsWith("illegal_"))return decision("reject",reliable,Classification.CONFLICT,e,"");if(reliable.startsWith("request_"))return missing(reliable,e);return conflict(reliable,e);}
  String surface=language==SemanticLanguage.EN?EnglishRequestCompiler.scopeSurface(original):PcSemanticGuard.chineseScopeSurface(original);
  surface=correctionSurface(surface,language);
  surface=Normalizer.normalize(surface,Normalizer.Form.NFKC);List<int[]> indices=new ArrayList<>();Set<Integer> channels=new HashSet<>();
  if(has(surface,"^\\s*(?:请)?(?:(?:不要|别|请勿)(?:停用|关闭|打开|设置|查询|读取|执行|启用)|禁止执行)"))return missing("current_request_cancelled",e);
  if(h.matches("CH[1-4]:POSITION")&&has(surface,"\\b(?:raise|lift)\\b.{0,60}\\bdown(?:wards?)?\\b|\\blower\\b.{0,60}\\bup(?:wards?)?\\b"))return conflict("explicit_position_verb_direction_conflict",e);
  {String englishSurface=hanMaskedEvidenceSurface(surface);Matcher m=EnglishChannelEvidence.PATTERN.matcher(englishSurface);while(m.find())if(EnglishChannelEvidence.analog(m,englishSurface)){int c=EnglishChannelEvidence.index(m);e.add(original,m.start(),m.end(),"explicit_analog_channel",c);indices.add(new int[]{m.start(),m.end()});if(c<1||c>4)return decision("reject","explicit_invalid_analog_channel",Classification.CONFLICT,e,"");channels.add(c);}}
  {ChineseNumericNormalizer.Result typed=new ChineseNumericNormalizer().normalize(surface);char[] unclaimed=typed.nfkc.toCharArray();
   for(ChineseNumericNormalizer.Token t:typed.tokens)if(t.type==ChineseNumericNormalizer.TokenType.CHANNEL){indices.add(new int[]{t.rawStart,t.rawEnd});if(!t.valid||t.channelIndex==null||t.channelIndex<1||t.channelIndex>4)return decision("reject","explicit_invalid_analog_channel",Classification.CONFLICT,e,"");channels.add(t.channelIndex);Arrays.fill(unclaimed,t.nfkcStart,t.nfkcEnd,' ');}
   // Aliases inspect only unclaimed source text. A greedy numeral alias must not
   // extend an established channel span into the adjacent spoken quantity.
   Matcher extra=Pattern.compile(PcSemanticGuard.ANALOG_SOURCE).matcher(new String(unclaimed));while(extra.find())indices.add(new int[]{extra.start(),extra.end()});channels.addAll(PcSemanticGuard.analogSources(surface));}
  Matcher named=SOURCE_INDEX.matcher(surface);while(named.find())indices.add(new int[]{named.start(),named.end()});
  if(h.matches("TRIGGER:A:LEVEL:(?:CH[1-4]|EXT)\\??")&&!channels.isEmpty()){
   if(explicitExternalTriggerSource(surface))return missing("multiple_analog_and_external_trigger_sources",e);
   if(h.startsWith("TRIGGER:A:LEVEL:EXT"))return conflict("explicit_analog_vs_external_trigger_source_conflict",e);
  }
  // Only a source-free trigger LEVEL can inherit this separately validated state.
  // An explicit requested source wins; vertical settings cannot borrow trigger state.
  if(channels.isEmpty()&&h.matches("TRIGGER:A:LEVEL:(?:CH[1-4]|EXT)")&&!explicitExternalTriggerSource(surface)){
   Object current=context.snapshot().get("current_trigger_source");
   if(current instanceof String){
    String source=(String)current;
    String levelLeaf=source.equals("EXT/5")?"EXT":source;
    if(!h.endsWith(":"+levelLeaf))return conflict("validated_current_trigger_source_conflict",e);
    Map<String,Object> stateEvidence=new LinkedHashMap<>();stateEvidence.put("canonical_field","trigger_source");stateEvidence.put("normalized_value",source);stateEvidence.put("level_leaf_source",levelLeaf);stateEvidence.put("source_type","VALIDATED_EXPLICIT_CONTEXT");stateEvidence.put("context_field","current_trigger_source");stateEvidence.put("model_generated",false);stateEvidence.put("instrument_state_queried",false);e.spans.add(stateEvidence);
    if(source.startsWith("CH"))channels.add(Integer.parseInt(source.substring(2)));
   }
  }
  ExistingMeasurementRequest measurement=ExistingMeasurementRequest.parse(original,context,table);
  String namedIssue=namedObjectIssue(surface,h,indices);if(namedIssue!=null&&!(namedIssue.equals("request_missing_named_object_index")&&measurement!=null&&measurement.issue==null))return conflict(namedIssue,e);
  String cursorIssue=cursorIndexIssue(surface,h,indices,e,original);if(cursorIssue!=null)return conflict(cursorIssue,e);
  Matcher modelChannel=MODEL_CHANNEL.matcher(h);if(modelChannel.find()){
   int target=Integer.parseInt(modelChannel.group(1));
   if(!channels.isEmpty()&&!channels.contains(target)){
    // Only a single analog scale target may be repaired, and its unchanged action/value
    // must already pass the existing positive source policy and all shared constraints.
    if(channels.size()==1&&h.matches("CH[1-4]:SCALE")&&parameter instanceof Number){
     Map<String,Object> candidate=Map.of("intent","VERTICAL_set","SCPIcommand","CH"+channels.iterator().next()+":SCALe","SCPIparams",parameter);
     SemanticPolicy.Result provenance=policy.evaluate(original,CompactSemanticProtocol.encode(candidate),context);
     SemanticPolicy.Result recovery=recovered(original,raw,candidate,language,context,policy,provenance,"CURRENT_REQUEST_UNIQUE_ANALOG_CHANNEL_RECOVERY");if(recovery!=null)return recovery;
    }
    return conflict("explicit_channel_conflict",e);
   }
   if(channels.size()>1)return missing("multiple_analog_sources_for_single_target",e);
   if(channels.isEmpty())return missing("request_missing_channel",e);
   if(EnglishChannelEvidence.nonAnalog(surface)||has(surface,"(?:数学|参考|数字|逻辑)(?:波形|输入|通道)"))return conflict("explicit_non_analog_object_conflict",e);
  }
  String objectIssue=objectConflict(surface,h);if(objectIssue!=null)return conflict(objectIssue,e);
  // Existing typed frames contribute positive constraints only. Failure to parse is
  // not a veto: a professional-word spelling gap still goes to the model-led path.
  SemanticPolicy.ShortDailyRequest daily=SemanticPolicy.ShortDailyRequest.parse(original);
  if(daily!=null&&daily.issue==null&&!daily.command.isEmpty()){
   ScpiTableEligibility.Decision bound=table.qualify(daily.command,daily.parameter);
   if(!table.canonicalHead(daily.command).equalsIgnoreCase(table.canonicalHead(command)))return conflict("explicit_daily_object_conflict",e);
   if(!bound.canonical.equalsIgnoreCase(qualified.canonical))return conflict("explicit_daily_parameter_conflict",e);
   e.spans.addAll(daily.evidence);
  }
  if(measurement!=null){if(measurement.issue!=null)return missing(measurement.issue,e);if(!measurement.matches(original,command,parameter))return conflict("explicit_measurement_slot_or_action_conflict",e);e.spans.addAll(measurement.evidence);}
  // A full typed position binds its absolute result using trusted state. Its spoken
  // delta must never be compared directly with the model's absolute parameter.
  if(h.matches("CH[1-4]:POSITION")&&SemanticPolicy.PositionRequest.detect(original)){
   SemanticPolicy.PositionRequest bound=SemanticPolicy.PositionRequest.parse(original,context,units);
   if(bound!=null&&bound.issue==null){String wanted=table.qualify(bound.command,bound.value).canonical;if(!qualified.canonical.equalsIgnoreCase(wanted)){
    if(table.canonicalHead(bound.command).equalsIgnoreCase(table.canonicalHead(command))&&positionOperand(raw,bound)){
     Map<String,Object> candidate=Map.of("intent","VERTICAL_set","SCPIcommand",bound.command,"SCPIparams",bound.value);
     SemanticPolicy.Result provenance=policy.evaluatePositionRequest(original,CompactSemanticProtocol.encode(raw),context);
     SemanticPolicy.Result recovery=recovered(original,raw,candidate,language,context,policy,provenance,"CURRENT_REQUEST_AND_VALIDATED_EXPLICIT_CONTEXT_DERIVATION");if(recovery!=null)return recovery;
    }
    return conflict("explicit_position_operand_conflict",e);
   }e.spans.addAll(bound.evidence);return decision("preview","model_position_semantics_checked",Classification.CONSISTENT,e,qualified.canonical);}
   if(bound!=null&&bound.issue!=null&&bound.issue.startsWith("current_"))return missing(bound.issue,e);
  }
  Matcher comparative=Pattern.compile("比(?:当前|现在|目前)(?:的)?(?:位置)?(?<direction>高|低)(?<quantity>[0-9]+(?:\\.[0-9]+)?|[零〇一二两三四五六七八九十百千万点]+)(?<unit>格|毫伏|伏)").matcher(surface);
  if(h.matches("CH[1-4]:POSITION")&&parameter instanceof Number&&comparative.find()){
   String channel=h.substring(2,3);Object positions=context.snapshot().get("current_vertical_position_divisions_by_channel"),current=positions instanceof Map?((Map<?,?>)positions).get(channel):null;
   if(!(current instanceof Number))return missing("current_vertical_position_divisions_by_channel:CH"+channel,e);
   BigDecimal amount=UnitSourceModel.decimal(comparative.group("quantity"));UnitSourceModel.Evidence unit=units.at(comparative.group("unit"),0);BigDecimal delta=amount.multiply(unit.factor);
   if(unit.normalized.equals("V")){Object scales=context.snapshot().get("current_vertical_scale_volts_by_channel"),scale=scales instanceof Map?((Map<?,?>)scales).get(channel):null;if(!(scale instanceof Number))return missing("current_vertical_scale_volts_by_channel:CH"+channel,e);delta=delta.divide(new BigDecimal(scale.toString()),java.math.MathContext.DECIMAL128);}
   if(comparative.group("direction").equals("低"))delta=delta.negate();BigDecimal wanted=new BigDecimal(current.toString()).add(delta);
   if(wanted.compareTo(new BigDecimal(parameter.toString()))!=0)return conflict("explicit_relative_position_conflict",e);
   e.add(original,comparative.start(),comparative.end(),"explicit_current_position_comparison",delta.toPlainString());return decision("preview","model_relative_position_semantics_checked",Classification.CONSISTENT,e,qualified.canonical);
  }
  if(language==SemanticLanguage.EN){EnglishRequestCompiler.Request requested=EnglishRequestCompiler.compile(original);
   if(requested!=null&&"candidate".equals(requested.status)){
    ScpiTableEligibility.Decision bound=table.qualify(requested.command,requested.parameter);
    if(!table.canonicalHead(requested.command).equalsIgnoreCase(table.canonicalHead(command)))return conflict("explicit_typed_operation_object_conflict",e);
    if(!bound.canonical.equalsIgnoreCase(qualified.canonical))return conflict("explicit_typed_parameter_conflict",e);
    EnglishCorrectionScope correction=EnglishCorrectionScope.parse(TextSpanMapping.of(original).normalized);
    if(correction!=null&&correction.inherited){EnglishRequestCompiler.Proof proof=EnglishRequestCompiler.proof(original,command,parameter);ParameterSourceGuard.Check lineage=positiveCorrectionSources.check(original,command,parameter,proof);if(lineage.missing.isEmpty())e.spans.addAll(lineage.evidence);}
   }
  }
  boolean query=h.endsWith("?");
  boolean returnSetting=has(surface,"^\\s*(?:please\\s+)?(?:return|restore)\\b[^.!?]{0,120}\\bto\\b")&&!has(surface,"\\bto\\s+(?:me|us|the operator)\\b");
  boolean explicitSet=returnSetting||has(surface,"\\b(?:set|adjust|change|turn|switch|enable|disable|put|make)\\b|(?:设置|设为|设成|调到|调成|改成|改为|打开|关闭|隐藏|上移|下移)");
  boolean currentReadQuestion=has(surface,"^\\s*(?:what|which)\\b.{0,120}\\b(?:is|are)\\b.{0,120}\\b(?:currently|now|set\\s+to)\\b")&&!has(surface,"^\\s*what\\s+if\\b|\\bshould\\b");
  boolean explicitRead=currentReadQuestion||!returnSetting&&EnglishRequestCompiler.explicitQuery(surface)||has(surface,"\\b(?:what\\s+(?:is|vertical|horizontal)|read|query|read\\s+back|tell\\s+me\\s+(?:the\\s+)?(?:value|current))\\b|(?:查询|查看|帮我查|查一下|读取|读给我|告诉我.*(?:当前|现在|设置)|现在.*多少|当前.*多少|会不会|有没有|打开没有|哪些|是否|打开还是关闭|(?:当前|目前).*是多少|(?:当前|现在|目前)[^。!?！？;；]*是什么)");
  if(has(surface,"^\\s*(?:what|which|how)\\b[^.!?]*\\bshould\\s+be\\b"))return missing("advice_question_not_current_state_query",e);
  if(!query&&!explicitSet&&has(surface,"^\\s*(?:the\\s+)?"+EnglishChannelEvidence.EXPRESSION+"\\s+(?:vertical\\s+)?(?:scale|coupling|position|bandwidth)\\s+is\\b"))return missing("non_executing_current_setting_statement",e);
  if(query&&explicitSet&&!explicitRead)return conflict("explicit_set_vs_query_conflict",e);
  if(!query&&explicitRead&&(currentReadQuestion||!explicitSet||has(surface,"会不会|是否|有没有|哪些|打开还是关闭")))return conflict("explicit_query_vs_set_conflict",e);
  if(!query&&has(surface,"是什么|有什么作用|会造成什么"))return missing("non_executing_definition_question",e);
  if(has(surface,"当前设置是")&&!(query&&explicitRead))return missing("non_executing_current_setting_statement",e);
  if(has(surface,"^\\s*(?:(?:please|could\\s+you|can\\s+you|would\\s+you)\\s+)?(?:if|when|unless|provided\\s+that)\\b|\\b(?:describe|explain)\\b|介绍|解释|如果|假如|\\b(?:already|previously)\\b|(?:已经|曾经)[^，,。;；]{0,24}(?:设置|执行|运行|启用)|\\bcan\\s+be\\s+(?:switched|turned|set)\\b|很有意思|\\bis\\s+interesting\\b|\\bshould\\s+(?:i|we)\\b"))return missing("non_executing_statement_or_condition",e);
  if(query)return decision("preview","model_semantics_authority_checked",e.spans.isEmpty()?Classification.LEXICAL_INSUFFICIENT:Classification.CONSISTENT,e,qualified.canonical);
  boolean relative=RELATIVE.matcher(surface).find()||SemanticPolicy.ChineseScaleRequest.detect(original)||has(surface,"\\bhalf\\b.*\\b(?:current|present|existing)\\b|\\bhalf\\s+the\\s+current|\\b(?:cut|reduce)\\b.*\\bhalf\\b|\\bin\\s+half\\b|(?:现在|当前|目前)(?:的)?一半|减(?:少)?到一半");
  if(relative){
   SemanticPolicy.Result position=policy.evaluatePositionRequest(original,CompactSemanticProtocol.encode(raw),context);
   if(position!=null){if(!"preview".equals(position.status))return missing(position.reason,e);if(!qualified.canonical.equalsIgnoreCase(position.scpi))return conflict("explicit_relative_position_conflict",e);return decision("preview","model_relative_semantics_checked",Classification.CONSISTENT,e,qualified.canonical);}
   String key=h.matches("CH[1-4]:SCALE")?"current_vertical_scale_volts_by_channel":h.equals("HORIZONTAL:SCALE")?"current_timebase_seconds":h.matches("CH[1-4]:POSITION")?"current_vertical_position_divisions_by_channel":"";
   boolean scalarVertical=h.matches("CH[1-4]:SCALE")&&context.snapshot().containsKey("current_vertical_scale_volts")&&!context.snapshot().containsKey("current_vertical_scale_volts_by_channel");
   if(key.isEmpty()||!context.snapshot().containsKey(key)&&!scalarVertical)return missing("relative_current_state_unknown",e);
   if(h.matches("CH[1-4]:SCALE")&&!scalarVertical&&!((Map<?,?>)context.snapshot().get(key)).containsKey(h.substring(2,3)))return missing("relative_current_channel_state_unknown",e);
   BigDecimal ratioValue=explicitRelativeScaleValue(surface,h,context);
   if(ratioValue!=null&&parameter instanceof Number){if(ratioValue.compareTo(new BigDecimal(parameter.toString()))!=0){
    Object current=context.snapshot().get(key);if(current instanceof Map)current=((Map<?,?>)current).get(h.substring(2,3));if(scalarVertical)current=context.snapshot().get("current_vertical_scale_volts");
    if(current instanceof Number&&equalsNumber(parameter,new BigDecimal(current.toString()))){
     Map<String,Object> candidate=Map.of("intent",h.equals("HORIZONTAL:SCALE")?"HORIZONTAL_set":"VERTICAL_set","SCPIcommand",table.canonicalHead(command),"SCPIparams",ratioValue);
     SemanticPolicy.Result provenance;
     if(language==SemanticLanguage.ZH)provenance=policy.evaluate(original,CompactSemanticProtocol.encode(candidate),context);
     else {EnglishSemanticBridge.Result derived=new EnglishSemanticBridge(policy,context.snapshot()).evaluateText(original);provenance=derived.stepCount==1?new SemanticPolicy.Result(derived.status,derived.canonical,derived.reason,derived.evidence):null;}
     SemanticPolicy.Result recovery=recovered(original,raw,candidate,language,context,policy,provenance,"CURRENT_REQUEST_AND_VALIDATED_EXPLICIT_CONTEXT_DERIVATION");if(recovery!=null)return recovery;
    }
    return conflict("explicit_relative_operand_conflict",e);
   }Map<String,Object> state=new LinkedHashMap<>();state.put("canonical_field","relative_result_constraints");state.put("source_type","CURRENT_REQUEST_FACTOR_AND_VALIDATED_EXPLICIT_CONTEXT");state.put("fixture_lineage",context.snapshot());state.put("original_asr_text",original);state.put("model_generated",false);e.spans.add(state);return decision("preview","model_relative_semantics_checked",Classification.CONSISTENT,e,qualified.canonical);}
   if(language==SemanticLanguage.ZH){SemanticPolicy.ChineseScaleRequest derived=SemanticPolicy.ChineseScaleRequest.parse(original,context);if(derived!=null){if(derived.issue!=null)return missing(derived.issue,e);if(!table.qualify(derived.command,derived.value).canonical.equalsIgnoreCase(qualified.canonical))return conflict("explicit_relative_operand_conflict",e);e.spans.addAll(derived.evidence);}}
   else {EnglishSemanticBridge.Result derived=new EnglishSemanticBridge(policy,context.snapshot()).evaluateText(original);if("preview".equals(derived.status)&&!derived.canonical.equalsIgnoreCase(qualified.canonical))return conflict("explicit_relative_operand_conflict",e);if("clarify".equals(derived.status))return missing(derived.reason,e);}
   return decision("preview","model_relative_semantics_checked",Classification.LEXICAL_INSUFFICIENT,e,qualified.canonical);
  }
  // A model's absolute target may be checked against a separately supplied step policy.
  // This reuses the existing typed proof; no next-step default or new NL grammar is invented.
  if(language==SemanticLanguage.EN&&parameter instanceof Number&&h.matches("(?:CH[1-4]:SCALE|HORIZONTAL:SCALE)")&&(context.snapshot().containsKey("verified_vertical_scale_steps_volts_by_channel")||context.snapshot().containsKey("verified_timebase_steps_seconds"))){
   if(adjacentStepPlanner==null)adjacentStepPlanner=new NativeOperationPlanner(policy,authorityContract);
   StateSnapshot snapshot=StateSnapshot.explicitFixture(context,"constraint_check",0,0,0,"explicit_fixture_step_policy_for_pure_constraint_check");
   OperationPlan plan=adjacentStepPlanner.plan(original,language,CompactSemanticProtocol.encode(raw),snapshot,"constraint_check",0,0);
   if(plan.kind==OperationPlan.Kind.SCALE_ADJUSTMENT&&plan.status.equals("preview")&&plan.commands().size()==1&&plan.commands().get(0).equalsIgnoreCase(qualified.canonical)){
    e.spans.addAll(plan.evidence);return decision("preview","model_target_matches_explicit_adjacent_step_policy",Classification.CONSISTENT,e,qualified.canonical);
   }
   if(plan.reason.equals("model_relative_target_conflict")||plan.reason.equals("model_scale_object_conflict"))return conflict(plan.reason,e);
  }
  String dynamicDimension=null;
  if(parameter instanceof Number&&units.expected(command).equals("DYNAMIC")){
   Set<String> currentUnits=new HashSet<>();
   Matcher declarations=Pattern.compile("(?i)(?:(?:当前|本次)[^0-9，。]{0,16}单位(?:为|是)\\s*|\\bcurrent\\s+(?:cursor\\s+)?units?\\s+(?:is|are)\\s+)").matcher(surface);
   while(declarations.find()){UnitSourceModel.Evidence unit=exactUnit(surface,declarations.end());if(unit!=null&&unit.factor.compareTo(BigDecimal.ONE)==0){currentUnits.add(unit.normalized);e.add(original,unit.start,unit.end,"explicit_current_unit",unit.normalized);}}
   if(currentUnits.size()!=1)return missing("dynamic_parameter_current_unit_required",e);
   dynamicDimension=currentUnits.iterator().next();
   if(h.matches("CURSOR:(?:VBARS|HBARS):POSITION[12]")&&has(surface,"提前|后移|前移|后退|\\b(?:advance|retard|move|shift)\\b"))return missing("cursor_relative_position_required",e);
  }
  List<Quantity> quantities=quantities(surface,indices,language);
  if(parameter instanceof Number){
   String expected=dynamicDimension==null?units.expected(command):dynamicDimension;if(expected.isEmpty()&&h.equals("ZOOM:HORIZONTAL:SCALE"))expected="s/div"; // Existing fixed authority: Tek Zoom!H6 / Lai Zoom!E8/F8, already cited by exactZoomScale.
   if(expected.isEmpty()&&h.equals("MAGNIFY:WINDOW:POSITION:VERTICAL"))expected="div"; // Existing integer/div magnifier authority; no fractional or voltage contract promotion.
   BigDecimal modelValue=new BigDecimal(parameter.toString());boolean precise=false;
   final String quantitySurface=surface;
   if(expected.endsWith("/div")&&quantities.size()>1)quantities.removeIf(q->q.value.compareTo(BigDecimal.ONE)==0&&quantitySurface.substring(q.end).startsWith("格"));
   boolean zeroAnchor=h.matches("CH[1-4]:POSITION")&&has(surface,"\\b(?:zero|origin)\\b|零位|零点|回零");
   if(zeroAnchor&&has(surface,"\\b(?:zero|origin)\\s+(?:and|or)\\s+(?:"+EnglishNumberWords.VALUE+")|(?:零位|零点)(?:和|或)[零一二两三四五六七八九0-9]"))return missing("request_ambiguous_position_anchor_and_distance",e);
   boolean zeroHasVoltageUnit=quantities.size()==1&&exactUnit(surface,quantities.get(0).end)!=null&&exactUnit(surface,quantities.get(0).end).normalized.replace("/div","").equals("V");
   if(zeroAnchor&&!zeroHasVoltageUnit&&(quantities.isEmpty()||quantities.size()==1&&quantities.get(0).value.signum()==0)){
    if(modelValue.signum()!=0)return conflict("explicit_position_origin_conflict",e);
    return decision("preview","explicit_position_origin_checked",Classification.CONSISTENT,e,qualified.canonical);
   }
   if(zeroAnchor&&quantities.size()>1)quantities.removeIf(q->q.value.signum()==0);
   if(quantities.isEmpty())return missing("request_missing_value",e);
   for(Quantity q:quantities){
    if(expected.replace("/div","").equals("V")){
     Matcher power=POWER_UNIT_SUFFIX.matcher(surface.substring(q.end));if(power.find()){e.add(original,q.end+power.start(1),q.end+power.end(1),"explicit_incompatible_unit","W");return conflict("explicit_power_unit_for_voltage",e);}
    }
    UnitSourceModel.Evidence unit=exactUnit(surface,q.end);if(unit==null&&quantities.size()==1)unit=precedingUnit(surface,q.start,expected,terminalRecovery);e.add(original,q.start,q.end,"explicit_quantity",q.value.toPlainString());
    if(unit!=null){
     String actual=unit.normalized.replace("/div","");String dimension=expected.replace("/div","");
     if(!dimension.isEmpty()&&!dimension.equals(actual)){
      if(h.matches("CH[1-4]:POSITION")&&actual.equals("V"))return missing("current_vertical_scale_for_voltage_position_unknown",e);
      return conflict("explicit_unit_dimension_conflict",e);
     }
     BigDecimal wanted=q.value.multiply(unit.factor);if(zeroAnchor&&has(surface,"\\bbelow\\b|零(?:位|点)下")&&wanted.signum()>0)wanted=wanted.negate();precise=true;e.add(original,unit.start,unit.end,"explicit_unit",unit.normalized+" x "+unit.factor);
     if(wanted.compareTo(modelValue)!=0)return conflict("explicit_numeric_or_unit_magnitude_conflict",e);
    }else if(expected.isEmpty()||Set.of("points","count","ratio").contains(expected)){if(expected.equals("points")&&surface.substring(q.end).matches("[\\s.。!?！？]*"))return missing("request_missing_count_unit",e);if(q.value.compareTo(modelValue)!=0)return conflict("explicit_numeric_conflict",e);precise=true;}
    else {
     String suffix=surface.substring(q.end,Math.min(surface.length(),q.end+48));
     Set<BigDecimal> factors=matchedCueFactors(suffix,expected);
     if(factors.isEmpty())return missing("request_missing_or_ambiguous_unit",e);
     // A fuzzy spelling only supports the matched form's factor, never every factor
     // of the physical dimension. This checks magnitude without changing model raw.
     e.add(original,q.end,q.end+suffix.length(),"authority_unit_cue_factor_candidates",factors.stream().map(BigDecimal::toPlainString).collect(java.util.stream.Collectors.toList()));
     e.lexical=true;boolean possible=false;
     Matcher prefix=SI_PREFIX.matcher(suffix);if(prefix.find()){
      String p=prefix.group(1).toLowerCase(Locale.ROOT);BigDecimal factor=p.equals("milli")||p.equals("毫")?new BigDecimal("0.001"):p.equals("micro")||p.equals("微")?new BigDecimal("0.000001"):p.equals("nano")||p.equals("纳")?new BigDecimal("0.000000001"):p.equals("kilo")||p.equals("千")?new BigDecimal("1000"):new BigDecimal("1000000");
      e.add(original,q.end+prefix.start(1),q.end+prefix.end(1),"explicit_si_prefix",factor.toPlainString());
      // A separately observed full SI prefix is reliable magnitude evidence even
      // when the following unit word is split (e.g. "micro seconds").
      possible=unitFactors.getOrDefault(expected.replace("/div",""),Collections.emptySet()).stream().anyMatch(f->f.compareTo(factor)==0)&&q.value.multiply(factor).compareTo(modelValue)==0;
     }else {
      if(factors.size()!=1)return missing("unit_cue_magnitude_ambiguous",e);
      BigDecimal factor=factors.iterator().next();possible=q.value.multiply(factor).compareTo(modelValue)==0;
     }
     if(!possible)return conflict("numeric_magnitude_incompatible_with_authority_units",e);
    }
   }
   if(!precise)e.lexical=true;
  }else if(parameter instanceof String){
   String value=((String)parameter).toUpperCase(Locale.ROOT);String enumIssue=enumConflict(surface,h,value);if(enumIssue!=null)return conflict(enumIssue,e);
   if(language==SemanticLanguage.EN&&h.matches("SELECT:CH[1-4]")&&ChannelDisplayStateEvidence.unclearBareTarget(surface))return missing("channel_display_state_unclear",e);
   // The absence of a matched enum spelling is not itself a contradiction.
   if(has(surface,"(?:coupling|mode|耦合|模式)\\s*(?:to|为|成)\\s*[.。!?？]*$"))return missing("request_missing_enum_value",e);
  }
  return decision("preview",e.lexical?"model_semantics_retained_lexical_evidence_insufficient":"model_semantics_authority_and_conflicts_checked",e.lexical||e.spans.isEmpty()?Classification.LEXICAL_INSUFFICIENT:Classification.CONSISTENT,e,qualified.canonical);
 }
 static String correctionSurface(String s,SemanticLanguage language){
  Matcher excludedEnum=Pattern.compile(language==SemanticLanguage.EN?"(?i)(?:not|rather\\s+than)\\s+(?:AC|DC|GND|ground)(?=[,\\s]*(?:but|use|choose|instead))":"(?:不要|不是|而不是)(?:AC|DC|GND|交流|直流|接地)[，,]?\\s*(?=改用|要|选|用|采用)").matcher(s);
  if(excludedEnum.find())s=s.substring(0,excludedEnum.start())+s.substring(excludedEnum.start(),excludedEnum.end()).replaceAll("."," ")+s.substring(excludedEnum.end());
  if(language==SemanticLanguage.EN){Matcher replaced=Pattern.compile("(?i)\\breplace\\s+(?<old>AC|DC|GND|ground)(?:\\s+coupling)?\\s+with\\s+(?:AC|DC|GND|ground)(?:\\s+coupling)?").matcher(s);if(replaced.find())s=s.substring(0,replaced.start("old"))+s.substring(replaced.start("old"),replaced.end("old")).replaceAll("."," ")+s.substring(replaced.end("old"));}
  Matcher discarded=Pattern.compile(language==SemanticLanguage.EN?"(?i)\\b(?:not|instead\\s+of|rather\\s+than)\\s+(?:"+EnglishNumberWords.VALUE+")[^;\\r\\n]*$":"(?:不是|而不是|而非)(?=[0-9半零〇一二两三四五六七八九十百千万])[^；;\\r\\n]*$").matcher(s);
  if(discarded.find())s=s.substring(0,discarded.start())+s.substring(discarded.start()).replaceAll("."," ");
  if(language==SemanticLanguage.ZH){Matcher prior=Pattern.compile("刚才(?:说|用了)?[^，,。!?？]+[，,]\\s*(?=改成|改为|现在要|用)").matcher(s);if(prior.find())s=s.substring(0,prior.start())+s.substring(prior.start(),prior.end()).replaceAll("."," ")+s.substring(prior.end());}
  return s;
 }
 private static int objectIndex(String s){s=s.toLowerCase(Locale.ROOT);String[] ordinals={"zero","first","second","third","fourth","fifth","sixth","seventh","eighth","ninth","tenth","eleventh","twelfth","thirteenth","fourteenth","fifteenth","sixteenth"};for(int i=0;i<ordinals.length;i++)if(s.equals(ordinals[i]))return i;try{return UnitSourceModel.decimal(s).intValueExact();}catch(RuntimeException bad){Double n=EnglishNumberWords.parse(s);return n==null||n!=Math.rint(n)?-1:n.intValue();}}
 private static String cursorIndexIssue(String text,String head,List<int[]> indices,Evidence evidence,String original){
  Matcher target=Pattern.compile("^CURSOR:(?:VBARS|HBARS):(?:POSITION|DIVISION)([12])\\??$").matcher(head);if(!target.matches())return null;
  int expected=Integer.parseInt(target.group(1));Set<Integer> found=new HashSet<>();
  String number="[0-9]+|[一二两三四五六七八九十]+|one|two|three|four|first|second|third|fourth";
  for(String expression:new String[]{"第(?<n>"+number+")(?:个)?(?:光标)?(?:位置|光标)","(?i)(?<n>first|second|third|fourth)\\s+(?:(?:vertical|horizontal)\\s+)?(?:cursor(?:\\s+position)?|position)","(?i)\\b(?:cursor|position)\\s+(?<n>"+number+")(?![A-Za-z0-9])"}){
   Matcher m=Pattern.compile(expression).matcher(text);while(m.find()){int value=objectIndex(m.group("n"));found.add(value);indices.add(new int[]{m.start("n"),m.end("n")});evidence.add(original,m.start("n"),m.end("n"),"explicit_cursor_index",value);}
  }
  return !found.isEmpty()&&(found.size()!=1||!found.contains(expected))?"explicit_cursor_index_conflict":null;
 }
 private static String namedObjectIssue(String text,String head,List<int[]> indices){
  Matcher target=Pattern.compile("(?:^|:)(REF|MATH|FFT|MEAS|D)([0-9]+)(?=[:?]|$)").matcher(head);if(!target.find())return null;
  String family=target.group(1).equals("FFT")?"MATH":target.group(1);int expected=Integer.parseInt(target.group(2));String word=family.equals("REF")?"(?:reference(?:\\s+waveform)?|参考波形)":family.equals("MATH")?"(?:math(?:\\s+waveform)?|数学波形)":family.equals("MEAS")?"(?:measurement(?:\\s+(?:number|slot|item))?|测量项|项测量)":"(?:digital(?:\\s+(?:input|channel))?|数字(?:输入|通道|路))";
  String number="(?:[0-9]+|零|[一二两三四五六七八九十百]+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth|thirteenth|fourteenth|fifteenth|sixteenth)(?![A-Za-z0-9])";
  Set<Integer> found=new HashSet<>();
  for(String expression:new String[]{"(?i)(?<![A-Za-z0-9])"+family+"(?<n>[0-9]+)(?![A-Za-z0-9])","(?i)"+word+"\\s*(?:第)?(?<n>"+number+")(?:路|号|项)?","(?i)(?:第)?(?<n>"+number+")(?:路|号)?\\s*"+word}){
   Matcher m=Pattern.compile(expression).matcher(text);while(m.find()){found.add(objectIndex(m.group("n")));indices.add(new int[]{m.start(),m.end()});}
  }
  if(!found.isEmpty()&&(found.size()!=1||!found.contains(expected)))return "explicit_named_object_index_conflict";
  if(found.isEmpty()&&has(text,word))return "request_missing_named_object_index";
  return null;
 }

 private static String objectConflict(String s,String head){
  boolean trigger=has(s,"trigger|触发"),coupling=has(s,"coupling|耦合"),acquire=has(s,"acquisition|acquire|采集"),time=has(s,"timebase|time\\s+base|main\\s+time|主时基|横轴"),position=has(s,"position|垂直位置"),scale=has(s,"scale|档位|垂直刻度");
  if(trigger&&coupling&&!head.startsWith("TRIGGER:A:EDGE:COUPLING"))return "explicit_trigger_coupling_object_conflict";
  if(coupling&&!trigger&&head.startsWith("TRIGGER:"))return "explicit_channel_coupling_object_conflict";
  if(acquire&&has(s,"mode|模式")&&head.startsWith("TRIGGER:"))return "explicit_acquisition_vs_trigger_object_conflict";
  if(trigger&&has(s,"mode|模式")&&head.startsWith("ACQUIRE:"))return "explicit_trigger_vs_acquisition_object_conflict";
  if(time&&!has(s,"zoom|放大|缩放")&&head.matches("(?:CH[1-4]:.*|ZOOM:.*)"))return "explicit_main_timebase_object_conflict";
  if(position&&head.matches("CH[1-4]:SCALE\\??"))return "explicit_position_vs_scale_conflict";
  if(scale&&!time&&!position&&head.matches("CH[1-4]:POSITION\\??"))return "explicit_scale_vs_position_conflict";
  if(head.matches("CH[1-4]:SCALE\\??")&&has(s,"zoom|缩放视图|放大窗口|缩放窗口|放大倍率"))return "explicit_zoom_vs_main_channel_conflict";
  if(head.matches("TRIGGER:A:LEVEL:CH[1-4]\\??")&&has(s,"upper\\s+threshold|lower\\s+threshold|上阈值|下阈值|高阈值|低阈值"))return "explicit_level_vs_threshold_conflict";
  if(head.startsWith("D")&&head.contains(":THRESHOLD")&&trigger)return "explicit_logic_trigger_vs_LA_conflict";
  if(head.equals("MEASUREMENT:IMMED?")&&has(s,"测量结果|测量值|即时测量结果|readout|measured\\s+value"))return "explicit_measurement_value_leaf_conflict";
  if(head.equals("WAVEFORM:RECORD:OPERATE?")&&has(s,"录制.*(?:启用|开启|使能)状态"))return "explicit_record_state_leaf_conflict";
  if(head.equals("LANGUAGE:FORCE")&&has(s,"界面语言|interface\\s+language")&&!has(s,"强制|force|always"))return "explicit_interface_vs_forced_language_conflict";
  if(head.equals("*ESE?")&&has(s,"记录.*事件|recorded.*events?"))return "explicit_event_log_vs_enable_register_conflict";
  if(head.equals("*OPC")&&has(s,"完成后.*(?:继续|等待)|wait.*(?:completion|finish)")&&!has(s,"完成标志|(?:operation[- ]complete|completion)\\s+flag"))return "explicit_wait_vs_completion_flag_conflict";
  if(head.equals("RECALL:MASK")&&has(s,"创建|create|build"))return "explicit_create_vs_recall_mask_conflict";
  if(Set.of("DATA?","WAVFRM?").contains(head)&&has(s,"波形传输数据")&&!has(s,"配置|前导|采样点|点数组|DATa\\?|WAVFrm\\?"))return "request_ambiguous_waveform_data_object";
  if(head.equals("DISPLAY:INTENSITY?")&&has(s,"哪些显示设置|display\\s+settings")&&!has(s,"亮度|intensit|brightness"))return "explicit_display_settings_vs_intensity_conflict";
  if(head.equals("*WAI")&&has(s,"acquisition|采集"))return "explicit_acquisition_vs_pending_operation_conflict";
  if(head.startsWith("TRIGGER:A:MODE")&&has(s,"video.*line|视频.*行号"))return "explicit_video_line_vs_mode_conflict";
  if(head.equals("HORIZONTAL:SCALE")&&has(s,"XY|zoom|缩放|放大窗口"))return "explicit_main_vs_xy_zoom_conflict";
  return null;
 }
 private static String enumConflict(String s,String head,String value){
  if(head.endsWith(":COUPLING")){
   Set<String> mentioned=new HashSet<>();for(String[] a:new String[][]{{"(?<![A-Za-z])AC(?![A-Za-z])|交流","AC"},{"(?<![A-Za-z])DC(?![A-Za-z])|直流","DC"},{"(?<![A-Za-z])GND(?![A-Za-z])|接地","GND"}})if(has(s,a[0]))mentioned.add(a[1]);
   if(mentioned.size()>1)return "explicit_conflicting_coupling_choices";
   if(mentioned.size()==1&&!mentioned.contains(value))return "explicit_coupling_parameter_conflict";
  }
  if(head.startsWith("NAVIGATE:")&&((has(s,"后方|back(?:ward)?")&&value.equals("NEXT"))||(has(s,"前方|forward")&&value.equals("BACK"))))return "explicit_navigation_direction_conflict";
  if(head.startsWith("SELECT:")){
   int polarity=ChannelDisplayStateEvidence.polarity(s);boolean on=(polarity&ChannelDisplayStateEvidence.ON)!=0,off=(polarity&ChannelDisplayStateEvidence.OFF)!=0;
   if(on&&!off&&!value.equals("ON")||off&&!on&&!value.equals("OFF"))return "explicit_display_polarity_conflict";
  }
  if(value.equals("ON")&&has(s,"disable|禁止|禁用|关闭")&&!has(s,"enable|启用|开启|打开"))return "explicit_enabled_vs_disabled_conflict";
  if(head.equals("ZOOM:MODE")&&value.equals("ON")&&has(s,"hide|exit|收起|退出|停用"))return "explicit_zoom_polarity_conflict";
  if(head.equals("ACQUIRE:STATE")&&has(s,"\\b(?:stop|freeze|pause)\\b|停止|暂停|冻结")&&value.equals("RUN"))return "explicit_acquisition_action_conflict";
  if(head.equals("TRIGGER:A:MODE")){for(String[] mode:new String[][]{{"normal|正常","NORMAL"},{"auto(?:matic)?|自动","AUTO"},{"single|单次","SINGLE"}})if(has(s,mode[0])&&!value.equals(mode[1]))return "explicit_trigger_mode_conflict";}
  return null;
 }
}
