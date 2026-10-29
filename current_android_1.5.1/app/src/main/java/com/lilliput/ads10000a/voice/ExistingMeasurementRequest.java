package com.lilliput.ads10000a.voice;
import java.util.*;import java.util.regex.*;
/** Immutable proof derived from the original request and validated explicit measurement rows. */
final class ExistingMeasurementRequest {
 private static final String RMS_NAME="(?:rms|root[\\s-]+mean[\\s-]+square)";
 private static final Pattern ROOT_MEAN_SQUARE=Pattern.compile("(?i)\\b(?:(cycle)[\\s-]+)?root[\\s-]+mean[\\s-]+square(?:\\s+(?:per|of\\s+(?:one|a))\\s+(cycle))?\\b");
 final String text,command,intent,issue;final Object parameter;final ValidatedExplicitContext context;final List<Map<String,Object>> evidence;
 private ExistingMeasurementRequest(String t,String c,String i,Object p,ValidatedExplicitContext v,List<Map<String,Object>> e){text=t;command=c;intent=i;issue=null;parameter=p;context=v;evidence=Collections.unmodifiableList(e);}
 boolean matches(String t,String c,Object p){return issue==null&&text.equals(t)&&command.equals(c)&&Objects.equals(parameter,p);}
 private ExistingMeasurementRequest(String t,ValidatedExplicitContext ctx,String gap){text=t;command="";intent="";issue=gap;parameter=Collections.emptyMap();context=ctx;Map<String,Object> event=new LinkedHashMap<>();event.put("source_start",0);event.put("source_end",t.length());event.put("source_text",t);event.put("original_asr_text",t);event.put("canonical_field","existing_measurement_context_required");event.put("rule_id","typed_current_request_missing_unique_active_measurement_slot");event.put("fixture_lineage",ctx.snapshot());event.put("model_generated",false);evidence=Collections.singletonList(event);}

 /** Prompt selection shares the existing removal/explicit-slot recognizers.
  * A named slot remains the primitive protocol; type-based requests (including
  * ambiguous or absent targets) ask the model for source/type, never a slot. */
 static boolean requiresTypeCarrier(String text,ValidatedExplicitContext context,ScpiTableEligibility table){
  if(text==null||context==null||table==null||!context.usable()||Pattern.compile("\\p{IsHan}").matcher(text).find())return false;
  String surface=EnglishRequestCompiler.scopeSurface(TextSpanMapping.of(text).normalized);
  if(!"MEASURE_REMOVE".equals(EnglishRequestCompiler.derivedIntent(text))||PcSemanticGuard.prohibitedRequest(text)||EnglishRequestCompiler.cancelled(surface))return false;
  return explicitNamedSlot(text,surface,false,context,table)==null&&explicitRemoval(text,surface,false,context,table)==null;
 }

 /** A model-authored source/type selects only a unique active row in the complete
  * request-bound catalog. The model never supplies, guesses or repairs a slot. */
 static ExistingMeasurementRequest resolveModelRemoval(String text,ValidatedExplicitContext context,
   ScpiTableEligibility table,String source,String type){
  if(context==null||!context.usable()||PcSemanticGuard.prohibitedRequest(text))return null;
  String surface=EnglishRequestCompiler.scopeSurface(TextSpanMapping.of(text).normalized);
  if(EnglishRequestCompiler.cancelled(surface)||!"MEASURE_REMOVE".equals(EnglishRequestCompiler.derivedIntent(text)))return null;
  if(unsupportedRmsComponent(text))return new ExistingMeasurementRequest(text,context,"measurement_ac_component_not_supported_by_active_command_set");
  Set<Integer> channels=new HashSet<>();Matcher channel=EnglishChannelEvidence.PATTERN.matcher(surface);
  while(channel.find())if(EnglishChannelEvidence.analog(channel,surface))channels.add(EnglishChannelEvidence.index(channel));
  if(channels.size()>1||!channels.isEmpty()&&!channels.contains(Integer.parseInt(source.substring(2))))
   return new ExistingMeasurementRequest(text,context,"workflow_v2_request_argument_conflict");
  Set<String> namedTypes=new HashSet<>();char[] unclaimed=surface.toCharArray();Matcher rootRms=ROOT_MEAN_SQUARE.matcher(surface);
  // Claim the complete quantity before the independent MEAN alias sees its inner word.
  while(rootRms.find()){namedTypes.add(rootRms.group(1)!=null||rootRms.group(2)!=null?"CRMS":"RMS");Arrays.fill(unclaimed,rootRms.start(),rootRms.end(),' ');}
  String typeSurface=new String(unclaimed);
  for(String[] alias:new String[][]{{"CRMs","\\bcycle[- ]rms\\b"},{"RMS","(?<!cycle[ -])\\brms\\b"},
    {"MEAN","\\bmean\\b"},{"FREQuency","\\b(?:frequency|freq)\\b"},{"PERIod","\\bperiod\\b"},
    {"PK2Pk","\\b(?:peak[- ]to[- ]peak|vpp)\\b"},{"MAXimum","\\bmaximum\\b"},{"MINImum","\\bminimum\\b"},
    {"RISe","\\b(?:rise|rising)[- ]time\\b"},{"FALL","\\b(?:fall|falling)[- ]time\\b"}})
   if(Pattern.compile(alias[1],Pattern.CASE_INSENSITIVE).matcher(typeSurface).find())namedTypes.add(alias[0].toUpperCase(Locale.ROOT));
  if(namedTypes.size()>1||!namedTypes.isEmpty()&&!namedTypes.contains(type.toUpperCase(Locale.ROOT)))
   return new ExistingMeasurementRequest(text,context,"workflow_v2_request_argument_conflict");
  Object inventory=context.snapshot().get("measurements");Map<Integer,Map<?,?>> rows=new TreeMap<>();
  if(inventory instanceof List)for(Object item:(List<?>)inventory)if(item instanceof Map){Map<?,?> row=(Map<?,?>)item;
   Object slot=row.get("slot");if(slot instanceof Number&&((Number)slot).doubleValue()==((Number)slot).intValue())rows.put(((Number)slot).intValue(),row);}
  if(rows.size()!=8)return new ExistingMeasurementRequest(text,context,"measurement_inventory_complete_1_to_8_required");
  Map<?,?> chosen=null;int matches=0;
  for(int slot=1;slot<=8;slot++){Map<?,?> row=rows.get(slot);
   if(row==null||!Set.of("ON","OFF").contains(row.get("state")))
    return new ExistingMeasurementRequest(text,context,"measurement_inventory_complete_1_to_8_required");
   // The collector deliberately omits type/source for disabled rows on remove.
   // OFF proves they cannot compete with an enabled target; do not add needless queries.
   if("OFF".equals(row.get("state")))continue;
   if(!(row.get("source") instanceof String)||!(row.get("type") instanceof String))return new ExistingMeasurementRequest(text,context,"measurement_inventory_complete_1_to_8_required");
   if(source.equalsIgnoreCase(String.valueOf(row.get("source")))&&type.equalsIgnoreCase(String.valueOf(row.get("type")))&&"ON".equals(row.get("state"))){matches++;chosen=row;}}
  if(matches!=1)return new ExistingMeasurementRequest(text,context,matches==0?"measurement_target_not_found":"measurement_target_not_unique");
  int slot=((Number)chosen.get("slot")).intValue();String command="MEASUrement:MEAS"+slot+":STATE";
  if(table.qualify(command,"OFF").status!=ScpiTableEligibility.Status.IN_TABLE_SET)return new ExistingMeasurementRequest(text,context,"measurement_remove_command_outside_authority");
  Map<String,Object> event=new LinkedHashMap<>();event.put("rule_id","model_authored_measurement_source_type_catalog_resolution");
  event.put("source_type","MODEL_ARGUMENTS_AND_REQUEST_BOUND_MEASUREMENT_CATALOG");event.put("original_asr_text",text);
  event.put("measurement_source",source);event.put("measurement_type",type);event.put("selected_slot",slot);
  event.put("derived_command",command);event.put("normalized_value","OFF");event.put("fixture_lineage",context.snapshot());
  event.put("model_generated",false);event.put("semantic_arguments_model_authored",true);
  return new ExistingMeasurementRequest(text,command,"MEASURE_set","OFF",context,Collections.singletonList(event));
 }
 /** Total AC+DC RMS equals the native VoltageRms computation. AC-only and
  * DC-only remain different quantities and may not be replaced by total RMS. */
 static boolean unsupportedRmsComponent(String text){
  String quantity=text.replaceAll("(?i)\\bac\\s*(?:\\+|and|plus)\\s*dc\\b","combined");
  return Pattern.compile("(?i)\\b(?:ac|dc)(?:[- ]only)?\\s*[- ]?"+RMS_NAME+"\\b|\\b"+RMS_NAME+"\\b[^.!?]{0,24}\\b(?:ac|dc)[- ]?(?:only|component)\\b").matcher(quantity).find();
 }
 static boolean cycleRmsRequested(String text){return Pattern.compile("(?i)\\bcycle[\\s-]+"+RMS_NAME+"\\b|\\b"+RMS_NAME+"\\s+(?:per|of\\s+(?:one|a))\\s+cycle\\b").matcher(text).find();}
 static ExistingMeasurementRequest parse(String text,ValidatedExplicitContext context,ScpiTableEligibility table){
  if(context==null||!context.usable()||PcSemanticGuard.prohibitedRequest(text)||EnglishRequestCompiler.cancelled(EnglishRequestCompiler.scopeSurface(text)))return null;boolean zh=Pattern.compile("\\p{IsHan}").matcher(text).find();String s=zh?text:EnglishRequestCompiler.scopeSurface(TextSpanMapping.of(text).normalized);if(!s.matches("(?is).*(?:测量|读数|均方根|有效值|\\brms\\b|\\bmeasurements?\\b|\\bmeasured\\s+values?\\b|\\breadouts?\\b|\\breadings?\\b).*"))return null;
  String object=ReliableCatalogConflictEvidence.currentSurface(text,zh?SemanticLanguage.ZH:SemanticLanguage.EN);if(object.matches("(?is).*(?:\\b(?:counter|frequency\\s+counter|dvm|voltmeter|digital\\s+voltage\\s+meter)\\b|计数器|频率计|数字电压表|万用表).*"))return null;
  ExistingMeasurementRequest explicit=explicitNamedSlot(text,s,zh,context,table);if(explicit!=null)return explicit;explicit=explicitRemoval(text,s,zh,context,table);if(explicit!=null)return explicit;
  boolean query=zh?PcSemanticGuard.requestedQuery(s):EnglishRequestCompiler.explicitQuery(s),remove=zh?s.matches("(?s).*(?:移除|删除|关掉|关闭|不用显示).*" ):EnglishRequestCompiler.measurementRemove(s);if(query==remove||s.matches("(?is).*(?:全部|所有|\\ball\\b|\\bevery\\b).*"))return null;
  if(unsupportedRmsComponent(s))return new ExistingMeasurementRequest(text,context,"measurement_ac_component_not_supported_by_active_command_set");
  Set<Integer> channels=new HashSet<>();if(zh){for(ChineseNumericNormalizer.Token t:new ChineseNumericNormalizer().normalize(s).tokens)if(t.type==ChineseNumericNormalizer.TokenType.CHANNEL&&t.valid)channels.add(t.channelIndex);}else{Matcher m=EnglishChannelEvidence.PATTERN.matcher(s);while(m.find())if(EnglishChannelEvidence.analog(m,s))channels.add(EnglishChannelEvidence.index(m));}if(channels.size()>1)return new ExistingMeasurementRequest(text,context,"measurement_slot_required");
  Set<String> types=new HashSet<>();int typeStart=-1,typeEnd=-1;char[] unclaimed=s.toCharArray();Matcher rootRms=ROOT_MEAN_SQUARE.matcher(s);
  // Equal-length masking preserves original provenance offsets and leaves separate quantities visible.
  while(rootRms.find()){types.add(rootRms.group(1)!=null||rootRms.group(2)!=null?"CRMs":"RMS");typeStart=rootRms.start();typeEnd=rootRms.end();Arrays.fill(unclaimed,typeStart,typeEnd,' ');}
  String typeSurface=new String(unclaimed);
  for(String[] alias:new String[][]{{"CRMs","周期均方根|周期有效值|\\bcycle[- ]rms\\b"},{"RMS","均方根|有效值|(?<!cycle[ -])\\brms\\b"},{"MEAN","平均值|均值|\\bmean\\b"},{"FREQuency","频率|\\bfrequency\\b|\\bfreq\\b"},{"PERIod","周期|\\bperiod\\b"},{"PK2Pk","峰峰|\\bpeak[- ]to[- ]peak\\b|\\bvpp\\b"},{"PWIdth","正脉宽|正脉冲宽度|\\bpositive\\s+pulse[- ]width\\b"},{"NWIdth","负脉宽|负脉冲宽度|\\bnegative\\s+pulse[- ]width\\b"},{"MAXimum","最大值|\\bmaximum\\b"},{"MINImum","最小值|\\bminimum\\b"},{"RISe","上升时间|\\brise[- ]time\\b"},{"FALL","下降时间|\\bfall[- ]time\\b"}}){Matcher m=Pattern.compile(alias[1],Pattern.CASE_INSENSITIVE).matcher(typeSurface);if(m.find()){types.add(alias[0]);typeStart=m.start();typeEnd=m.end();}}
  if(types.size()!=1)return null;String type=types.iterator().next();if(table.qualify("MEASUrement:MEAS1:TYPe",type).status!=ScpiTableEligibility.Status.IN_TABLE_SET)return null;
  Object rows=context.snapshot().get("measurements");
  // A complete source/type request cannot supply the instrument's active slot.
  // Explain the missing state without asking the speaker to repeat known fields.
  if(!zh&&query&&channels.size()==1&&(!(rows instanceof List)||((List<?>)rows).isEmpty()))
   return new ExistingMeasurementRequest(text,context,"measurement_inventory_required");
  Map<?,?> chosen=null;int matches=0;String requestedSource=channels.isEmpty()?null:"CH"+channels.iterator().next();if(rows instanceof List)for(Object row:(List<?>)rows)if(row instanceof Map){Map<?,?> r=(Map<?,?>)row;if((requestedSource==null||requestedSource.equals(r.get("source")))&&type.equals(r.get("type"))&&"ON".equals(r.get("state"))){matches++;chosen=r;}}if(matches!=1||!(chosen.get("slot") instanceof Number))return new ExistingMeasurementRequest(text,context,"measurement_slot_required");if(query&&"OFF".equals(context.snapshot().get("measurement_system_state")))return new ExistingMeasurementRequest(text,context,"measurement_system_state_required");
  int slot=((Number)chosen.get("slot")).intValue();String head="MEASUrement:MEAS"+slot+(query?":VALue?":":STATE");Object parameter=query?Collections.emptyMap():"OFF";List<Map<String,Object>> events=new ArrayList<>();TextSpanMapping spans=TextSpanMapping.of(text);TextSpanMapping.Span span=spans.originalSpan(typeStart,typeEnd);Map<String,Object> e=new LinkedHashMap<>();e.put("source_start",span.start);e.put("source_end",span.end);e.put("source_text",text.substring(span.start,span.end));e.put("original_asr_text",text);e.put("canonical_field","measurement_object_and_slot");e.put("source_type","CURRENT_REQUEST_AND_VALIDATED_EXPLICIT_MEASUREMENT_CONTEXT");e.put("rule_id","unique_existing_measurement_source_type_active_slot");e.put("fixture_lineage",context.snapshot());e.put("selected_slot",slot);e.put("derived_command",head);e.put("normalized_value",parameter);e.put("model_generated",false);events.add(e);return new ExistingMeasurementRequest(text,head,query?"MEASURE_query":"MEASURE_set",parameter,context,events);
 }
 private static ExistingMeasurementRequest explicitNamedSlot(String original,String s,boolean zh,ValidatedExplicitContext context,ScpiTableEligibility table){
  if(!zh)return englishNamedSlot(original,s,context,table);String digit="[1-8一二两三四五六七八]",slot="(?:第)?(?<slot>"+digit+")项测量";
  Matcher off=Pattern.compile("\\s*(?:请|帮我|麻烦)?"+slot+"(?:暂时)?(?:停用|关闭|禁用|关掉)[。.!！?？]?\\s*").matcher(s),read=Pattern.compile("\\s*(?:请|帮我|麻烦)?(?:把|将)?"+slot+"(?:现在|当前|目前)(?:的)?(?:读数|现值|当前值)告诉我[。.!！?？]?\\s*").matcher(s);
  boolean query=read.matches();Matcher matched=query?read:off;if(!query&&!off.matches())return null;String literal=matched.group("slot");int n=literal.matches("[1-8]")?Integer.parseInt(literal):literal.equals("两")?2:"零一二三四五六七八".indexOf(literal);String command="MEASUrement:MEAS"+n+(query?":VALue?":":STATE");Object value=query?Collections.emptyMap():"OFF";
  if(n<1||n>8||table.qualify(command,value).status!=(query?ScpiTableEligibility.Status.IN_TABLE_QUERY:ScpiTableEligibility.Status.IN_TABLE_SET))return null;
  Map<String,Object> e=new LinkedHashMap<>();e.put("source_start",0);e.put("source_end",original.length());e.put("source_text",original);e.put("original_asr_text",original);e.put("canonical_field","measurement_object_and_slot");e.put("source_type","EXPLICIT_CURRENT_UTTERANCE_SLOT_AND_ACTION");e.put("rule_id","full_span_named_measurement_slot_read_or_disable");e.put("selected_slot",n);e.put("derived_command",command);e.put("normalized_value",value);e.put("model_generated",false);return new ExistingMeasurementRequest(original,command,query?"MEASURE_query":"MEASURE_set",value,context,Collections.singletonList(e));
 }
 private static ExistingMeasurementRequest englishNamedSlot(String original,String s,ValidatedExplicitContext context,ScpiTableEligibility table){
  String slot="measurement\\s+(?:(?:number|slot|item)\\s+)?(?<slot>[1-8]|one|two|three|four|five|six|seven|eight)";
  Matcher off=Pattern.compile("(?is)\\s*(?:please\\s+)?(?:turn|switch)\\s+"+slot+"\\s+off[.!?]?\\s*").matcher(s),read=Pattern.compile("(?is)\\s*(?:please\\s+)?(?:tell\\s+me|read|query|report)\\s+(?:the\\s+)?current\\s+(?:reading|value|readout)\\s+(?:in|of|from)\\s+"+slot+"[.!?]?\\s*").matcher(s);
  Matcher offFirst=Pattern.compile("(?is)\\s*(?:please\\s+)?(?:turn|switch)\\s+off\\s+"+slot+"[.!?]?\\s*").matcher(s);
  Matcher remove=Pattern.compile("(?is)\\s*(?:please\\s+)?(?:remove|hide|disable|delete|deactivate)\\s+"+slot+"[.!?]?\\s*").matcher(s);
  boolean query=read.matches();Matcher m=query?read:off.matches()?off:offFirst.matches()?offFirst:remove.matches()?remove:null;
  if(m==null)return null;Double v=EnglishNumberWords.parse(m.group("slot"));if(v==null)return null;int n=v.intValue();String command="MEASUrement:MEAS"+n+(query?":VALue?":":STATE");Object value=query?Collections.emptyMap():"OFF";
  if(table.qualify(command,value).status!=(query?ScpiTableEligibility.Status.IN_TABLE_QUERY:ScpiTableEligibility.Status.IN_TABLE_SET))return null;
  Map<String,Object> e=new LinkedHashMap<>();e.put("source_start",0);e.put("source_end",original.length());e.put("source_text",original);e.put("original_asr_text",original);e.put("canonical_field","measurement_object_and_slot");e.put("source_type","EXPLICIT_CURRENT_UTTERANCE_SLOT_AND_ACTION");e.put("rule_id","full_span_English_named_measurement_slot_read_or_disable");e.put("selected_slot",n);e.put("derived_command",command);e.put("normalized_value",value);e.put("model_generated",false);return new ExistingMeasurementRequest(original,command,query?"MEASURE_query":"MEASURE_set",value,context,Collections.singletonList(e));
 }
 private static ExistingMeasurementRequest explicitRemoval(String original,String s,boolean zh,ValidatedExplicitContext context,ScpiTableEligibility table){
  String slot=zh?"(?:(?:第)?(?<before>[1-8])(?:项|号|条)?测量(?:项)?|测量项\\s*(?<after>[1-8]))":"measurement\\s+(?:(?:slot|item)\\s+)?(?<slot>[1-8])";
  String expression=zh?"\\s*(?:请|帮我|麻烦)?(?:关闭|关掉|禁用|停用|移除|删除)"+slot+"(?:[，,]\\s*(?:谢谢|感谢))?[。.!！?？]?\\s*":"\\s*(?:please\\s+)?(?:(?:i|we)\\s+(?:would\\s+like|want|need)\\s+(?:you\\s+)?to\\s+)?(?:(?:switch|turn)\\s+(?:off\\s+"+slot+"|"+slot.replace("(?<slot>","(?<end>")+"\\s+off)|(?:remove|hide|disable)\\s+"+slot.replace("(?<slot>","(?<remove>")+"(?:\\s+from\\s+(?:the\\s+)?display)?)[.!?]?\\s*";
  Matcher m=Pattern.compile(expression,Pattern.CASE_INSENSITIVE).matcher(s);if(!m.matches())return null;String n=zh?m.group("before")!=null?m.group("before"):m.group("after"):m.group("slot")!=null?m.group("slot"):m.group("end")!=null?m.group("end"):m.group("remove");String command="MEASUrement:MEAS"+n+":STATE";if(table.qualify(command,"OFF").status!=ScpiTableEligibility.Status.IN_TABLE_SET)return null;
  Map<String,Object> event=new LinkedHashMap<>();event.put("source_start",0);event.put("source_end",original.length());event.put("source_text",original);event.put("original_asr_text",original);event.put("canonical_field","measurement_object_and_slot");event.put("source_type","EXPLICIT_CURRENT_UTTERANCE_SLOT_AND_OFF_ACTION");event.put("rule_id","full_span_explicit_measurement_slot_removal_no_analog_source_needed");event.put("selected_slot",Integer.parseInt(n));event.put("derived_command",command);event.put("normalized_value","OFF");event.put("model_generated",false);return new ExistingMeasurementRequest(original,command,"MEASURE_set","OFF",context,Collections.singletonList(event));
 }

}
