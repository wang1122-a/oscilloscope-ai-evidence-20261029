package com.lilliput.ads10000a.voice;

import java.util.*;

/** Immutable state from a separate explicit fixture parameter, never from text, model output or history. */
public final class ValidatedExplicitContext {
 public enum Origin { EXPLICIT_FIXTURE, VERIFIED_INSTRUMENT_QUERY, UNKNOWN }
 private boolean instrumentOrigin;
 private static final Set<String> FIELDS=Set.of("current_vertical_scale_volts","current_vertical_scale_volts_by_channel","current_vertical_position_divisions_by_channel","_vertical_channel","current_timebase_seconds","first_free_measurement_slot","measurement_system_state","measurements","current_trigger_source","verified_vertical_scale_steps_volts_by_channel","verified_timebase_steps_seconds");
 private static final Set<String> ROW_FIELDS=Set.of("slot","source","source2","type","state");
 public final String status,reason;
 private final Map<String,Object> original,values;
 private ValidatedExplicitContext(String status,String reason,Map<String,Object> original,Map<String,Object> values){this.status=status;this.reason=reason;this.original=freezeMap(original);this.values=freezeMap(values);}
 public boolean usable(){return status.equals("valid")||status.equals("unknown");}
 public Map<String,Object> snapshot(){return values;}
 public Map<String,Object> originalFixture(){return original;}
 /** Recheck values against the current authority without changing their origin.
  * Instrument origin is minted only by fromInstrument, never by a caller's map. */
 ValidatedExplicitContext revalidatePreservingOrigin(ScpiTableEligibility table){
  if(!usable())return this;
  ValidatedExplicitContext checked=validate(original,table);
  if(checked.usable())checked.instrumentOrigin=instrumentOrigin;
  return checked;
 }
 public Origin origin(String field){return values.containsKey(field)?(instrumentOrigin?Origin.VERIFIED_INSTRUMENT_QUERY:Origin.EXPLICIT_FIXTURE):Origin.UNKNOWN;}
 public String canonicalJson(){return CompactSemanticProtocol.encode(values);}
 public String modelInput(String originalText){if(originalText==null)throw new IllegalArgumentException("input_required");return originalText+(values.isEmpty()?"":(instrumentOrigin?"\nVerified instrument state: ":"\nFake context: ")+canonicalJson());}
 /** Structured-looking input text is not the separate trusted fixture; retain its byte positions as spaces. */
 public String semanticInput(String originalText){java.util.regex.Matcher suffix=java.util.regex.Pattern.compile("(?im)(?:^|\\n)[ \\t]*Fake\\s+context\\s*:").matcher(originalText);if(!suffix.find())return originalText;int start=suffix.start();return originalText.substring(0,start)+originalText.substring(start).replaceAll("[^\\r\\n]"," ");}
 public Map<String,Object> receipt(){Map<String,Object> out=new LinkedHashMap<>();out.put("status",status);out.put("reason",reason);out.put("source_type",values.isEmpty()?"UNKNOWN":instrumentOrigin?"VERIFIED_INSTRUMENT_QUERY":"EXPLICIT_FIXTURE_PARAMETER");out.put("original_fixture",safeReceipt(original));out.put("validated_fixture",values);out.put("model_generated",false);out.put("instrument_state_queried",instrumentOrigin);return Collections.unmodifiableMap(out);}
 public static Set<String> allowedFields(){return FIELDS;}
 public static ValidatedExplicitContext validate(Map<String,Object> fixture,ScpiTableEligibility table){
  if(fixture==null||table==null)throw new IllegalArgumentException("explicit_fixture_and_authority_required");
  Map<String,Object> copy;
  try{copy=copyMap(fixture,0);}catch(RuntimeException invalid){return new ValidatedExplicitContext("reject","invalid_explicit_context_shape",Collections.emptyMap(),Collections.emptyMap());}
  try{
   for(String key:copy.keySet())if(!FIELDS.contains(key))throw invalid("unknown_explicit_context_field");
   Map<String,Object> normalized=new TreeMap<>(copy);Object vertical=copy.get("current_vertical_scale_volts"),timebase=copy.get("current_timebase_seconds");
   if(copy.containsKey("current_vertical_scale_volts")){double n=positive(vertical);qualify(table,"CH1:SCALe",n);}
   if(copy.containsKey("current_timebase_seconds")){double n=positive(timebase);qualify(table,"HORizontal:SCAle",n);if(n<5e-10)throw invalid("invalid_current_timebase_range");}
   Map<String,Object> byChannel=new TreeMap<>();
   if(copy.containsKey("current_vertical_scale_volts_by_channel")){
    Object data=copy.get("current_vertical_scale_volts_by_channel");if(!(data instanceof Map))throw invalid("invalid_channel_scale_map");
    for(Map.Entry<?,?> e:((Map<?,?>)data).entrySet()){String key=String.valueOf(e.getKey());if(!key.matches("[1-4]"))throw invalid("invalid_scale_channel");double n=positive(e.getValue());qualify(table,"CH"+key+":SCALe",n);byChannel.put(key,e.getValue());}normalized.put("current_vertical_scale_volts_by_channel",byChannel);
   }
   if(copy.containsKey("current_vertical_position_divisions_by_channel")){
    Object data=copy.get("current_vertical_position_divisions_by_channel");if(!(data instanceof Map))throw invalid("invalid_channel_position_map");Map<String,Object> positions=new TreeMap<>();
    for(Map.Entry<?,?> e:((Map<?,?>)data).entrySet()){String key=String.valueOf(e.getKey());if(!key.matches("[1-4]"))throw invalid("invalid_position_channel");Object value=e.getValue();if(!(value instanceof Number)||!Double.isFinite(((Number)value).doubleValue()))throw invalid("invalid_context_position_number");qualify(table,"CH"+key+":POSition",value);positions.put(key,value);}normalized.put("current_vertical_position_divisions_by_channel",positions);
   }
   if(copy.containsKey("_vertical_channel")){
    int channel=index(copy.get("_vertical_channel"),1,4);if(vertical!=null){String key=String.valueOf(channel);if(byChannel.containsKey(key)&&!equal(byChannel.get(key),vertical))throw conflict("explicit_vertical_scale_conflict");byChannel.put(key,vertical);normalized.put("current_vertical_scale_volts_by_channel",byChannel);}
   }else if(vertical!=null&&!byChannel.isEmpty()){for(Object value:byChannel.values())if(!equal(value,vertical))throw conflict("explicit_vertical_scale_scope_conflict");}
   if(copy.containsKey("measurement_system_state"))state(copy.get("measurement_system_state"));
   if(copy.containsKey("current_trigger_source")){
    Object source=copy.get("current_trigger_source");if(!(source instanceof String))throw invalid("invalid_current_trigger_source");qualify(table,"TRIGger:A:EDGE:SOUrce",source);
   }
   // These are separately supplied fixture policies. Validation proves shape/range,
   // not an observed instrument increment sequence or a hardware capability claim.
   if(copy.containsKey("verified_vertical_scale_steps_volts_by_channel")){
    Object data=copy.get("verified_vertical_scale_steps_volts_by_channel");if(!(data instanceof Map))throw invalid("invalid_vertical_step_policy_map");
    for(Map.Entry<?,?> e:((Map<?,?>)data).entrySet()){String channel=String.valueOf(e.getKey());if(!channel.matches("[1-4]"))throw invalid("invalid_step_policy_channel");steps(table,"CH"+channel+":SCALe",e.getValue(),false);}
   }
   if(copy.containsKey("verified_timebase_steps_seconds"))steps(table,"HORizontal:SCAle",copy.get("verified_timebase_steps_seconds"),true);
   int free=copy.containsKey("first_free_measurement_slot")?index(copy.get("first_free_measurement_slot"),1,8):-1;
   if(copy.containsKey("measurements")){
    Object rows=copy.get("measurements");if(!(rows instanceof List)||((List<?>)rows).size()>8)throw invalid("invalid_measurement_rows");Set<Integer> slots=new HashSet<>();
    for(Object row:(List<?>)rows){if(!(row instanceof Map))throw invalid("invalid_measurement_row");Map<?,?> record=(Map<?,?>)row;for(Object key:record.keySet())if(!ROW_FIELDS.contains(String.valueOf(key)))throw invalid("unknown_measurement_field");if(!record.containsKey("slot"))throw invalid("measurement_slot_required");int slot=index(record.get("slot"),1,8);if(!slots.add(slot))throw conflict("duplicate_measurement_slot");
     for(String source:new String[]{"source","source2"})if(record.containsKey(source)&&(!(record.get(source) instanceof String)||!((String)record.get(source)).matches("CH[1-4]")))throw invalid("invalid_measurement_source");
     if(record.containsKey("type")){if(!(record.get("type") instanceof String))throw invalid("invalid_measurement_type");qualify(table,"MEASUrement:MEAS"+slot+":TYPe",record.get("type"));}
     if(record.containsKey("state"))state(record.get("state"));if(slot==free&&!"OFF".equals(record.get("state")))throw conflict("explicit_free_slot_occupied_or_unknown");
    }
   }
   return new ValidatedExplicitContext(copy.isEmpty()?"unknown":"valid","explicit_context_validated",copy,normalized);
  }catch(ContextIssue issue){return new ValidatedExplicitContext(issue.conflict?"clarify":"reject",issue.getMessage(),copy,Collections.emptyMap());}
 }
 private static final class ContextIssue extends IllegalArgumentException{final boolean conflict;ContextIssue(String reason,boolean conflict){super(reason);this.conflict=conflict;}}
 private static ContextIssue invalid(String reason){return new ContextIssue(reason,false);}private static ContextIssue conflict(String reason){return new ContextIssue(reason,true);}
 private static double positive(Object value){if(!(value instanceof Number))throw invalid("context_number_required");double n=((Number)value).doubleValue();if(!Double.isFinite(n)||n<=0)throw invalid("invalid_context_positive_number");return n;}
 private static int index(Object value,int min,int max){if(!(value instanceof Number))throw invalid("context_index_number_required");double n=((Number)value).doubleValue();if(!Double.isFinite(n)||n!=Math.rint(n)||n<min||n>max)throw invalid("invalid_context_index");return (int)n;}
 private static void state(Object value){if(!(value instanceof String)||!Set.of("ON","OFF").contains(value))throw invalid("invalid_context_state");}
 private static void steps(ScpiTableEligibility table,String command,Object value,boolean timebase){
  if(!(value instanceof List)||((List<?>)value).size()<2||((List<?>)value).size()>32)throw invalid("invalid_step_policy_values");java.math.BigDecimal previous=null;
  for(Object item:(List<?>)value){double n=positive(item);qualify(table,command,item);if(timebase&&n<5e-10)throw invalid("invalid_current_timebase_range");java.math.BigDecimal next=new java.math.BigDecimal(item.toString());if(previous!=null&&next.compareTo(previous)<=0)throw invalid("step_policy_not_strictly_increasing");previous=next;}
 }
 private static boolean equal(Object a,Object b){return a instanceof Number&&b instanceof Number&&Double.compare(((Number)a).doubleValue(),((Number)b).doubleValue())==0;}
 private static void qualify(ScpiTableEligibility table,String command,Object value){ScpiTableEligibility.Decision d=table.qualify(command,value);if(d.status!=ScpiTableEligibility.Status.IN_TABLE_SET)throw invalid("context_value_outside_authority");}
 private static Map<String,Object> copyMap(Map<?,?> input,int depth){if(depth>6||input.size()>32)throw invalid("context_shape_limit");Map<String,Object> out=new TreeMap<>();for(Map.Entry<?,?> e:input.entrySet()){String key=String.valueOf(e.getKey());if(out.containsKey(key))throw conflict("context_key_collision");out.put(key,copyValue(e.getValue(),depth+1));}return out;}
 private static Object copyValue(Object value,int depth){if(depth>6)throw invalid("context_shape_limit");if(value instanceof Map)return copyMap((Map<?,?>)value,depth);if(value instanceof List){if(((List<?>)value).size()>32)throw invalid("context_shape_limit");List<Object> list=new ArrayList<>();for(Object x:(List<?>)value)list.add(copyValue(x,depth+1));return list;}if(value instanceof Number){Class<?> type=value.getClass();if(Set.of(Byte.class,Short.class,Integer.class,Long.class,Float.class,Double.class,java.math.BigDecimal.class,java.math.BigInteger.class).contains(type))return value;return new java.math.BigDecimal(value.toString());}if(value==null||value instanceof Boolean||value instanceof String)return value;throw invalid("context_scalar_type");}
 private static Object safeReceipt(Object value){if(value instanceof Number&&!Double.isFinite(((Number)value).doubleValue()))return "INVALID_NONFINITE:"+value;if(value instanceof Map){Map<String,Object> copy=new TreeMap<>();for(Map.Entry<?,?> e:((Map<?,?>)value).entrySet())copy.put(String.valueOf(e.getKey()),safeReceipt(e.getValue()));return copy;}if(value instanceof List){List<Object> copy=new ArrayList<>();for(Object x:(List<?>)value)copy.add(safeReceipt(x));return copy;}return value;}
 private static Map<String,Object> freezeMap(Map<String,Object> input){Map<String,Object> out=new TreeMap<>();for(Map.Entry<String,Object> e:input.entrySet())out.put(e.getKey(),freeze(e.getValue()));return Collections.unmodifiableMap(out);}
 private static Object freeze(Object value){if(value instanceof Map)return freezeMap((Map<String,Object>)value);if(value instanceof List){List<Object> out=new ArrayList<>();for(Object x:(List<?>)value)out.add(freeze(x));return Collections.unmodifiableList(out);}return value;}
}
