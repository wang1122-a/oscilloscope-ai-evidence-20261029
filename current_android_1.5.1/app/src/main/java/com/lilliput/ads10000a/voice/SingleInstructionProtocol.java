package com.lilliput.ads10000a.voice;
import java.util.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.util.regex.Pattern;
/** App data-only phase policy. It owns no model, audio, transport or execution capability. */
final class SingleInstructionProtocol {
 static final String PROTOCOL="primitive_three_field_v1";
 static final Set<String> FIELDS=Set.of("intent","SCPIcommand","SCPIparams");
 static final class Config {
  final Map<String,String> templates,hashes,workflowTemplates,workflowHashes;final boolean stopOnJsonComplete;
  private Config(Map<String,String> t,Map<String,String> h,Map<String,String> wt,Map<String,String> wh,boolean stop){templates=Collections.unmodifiableMap(t);hashes=Collections.unmodifiableMap(h);workflowTemplates=Collections.unmodifiableMap(wt);workflowHashes=Collections.unmodifiableMap(wh);stopOnJsonComplete=stop;}
  static Config parse(String json,String zh,String en){
   Map<?,?> c=(Map<?,?>)StrictJson.parse(json);if(!Boolean.TRUE.equals(c.get("single_instruction_mode"))||!PROTOCOL.equals(c.get("protocol"))||!Arrays.asList("zh","en").equals(c.get("languages"))||!(c.get("max_native_commands") instanceof Number)||((Number)c.get("max_native_commands")).intValue()!=1||!SemanticPolicy.ASSISTANT_PREFIX.equals(c.get("assistant_prefix"))||!(c.get("stop_on_json_complete") instanceof Boolean))throw new IllegalArgumentException("single_instruction_config_invalid");
   Map<?,?> prompts=(Map<?,?>)c.get("prompts");if(!prompts.keySet().equals(Set.of("zh","en")))throw new IllegalArgumentException("single_prompt_keys_invalid");Map<String,String> t=new LinkedHashMap<>(),h=new LinkedHashMap<>();
   for(String language:new String[]{"zh","en"}){String template=language.equals("zh")?zh:en;Map<?,?> descriptor=(Map<?,?>)prompts.get(language);String expected="single_instruction_"+language+"_template.txt",hash=digest(template);if(!expected.equals(descriptor.get("asset"))||!hash.equals(descriptor.get("sha256"))||template.indexOf("{{USER_TEXT}}")<0||template.indexOf("{{USER_TEXT}}")!=template.lastIndexOf("{{USER_TEXT}}")||!template.endsWith(SemanticPolicy.ASSISTANT_PREFIX))throw new IllegalArgumentException("single_prompt_identity_invalid");t.put(language,template);h.put(language,hash);}
   if(!Boolean.FALSE.equals(c.get("workflow_v2_enabled_by_default")))throw new IllegalArgumentException("workflow_v2_default_must_be_off");
   return new Config(t,h,Collections.emptyMap(),Collections.emptyMap(),(Boolean)c.get("stop_on_json_complete"));
  }
  static Config parse(String json,String zh,String en,String workflowZh,String workflowEn){
   Config base=parse(json,zh,en);Map<?,?> c=(Map<?,?>)StrictJson.parse(json);Object descriptors=c.get("workflow_v2_prompts");if(!(descriptors instanceof Map)||!((Map<?,?>)descriptors).keySet().equals(Set.of("zh","en")))throw new IllegalArgumentException("workflow_v2_prompt_keys_invalid");Map<String,String> templates=new LinkedHashMap<>(),hashes=new LinkedHashMap<>();
   for(String language:new String[]{"zh","en"}){String asset=language.equals("zh")?workflowZh:workflowEn,template=withoutTerminalLineBreak(asset);Map<?,?> descriptor=(Map<?,?>)((Map<?,?>)descriptors).get(language);String expected="single_instruction_workflow_v2_"+language+"_template.txt",hash=digest(asset);if(!expected.equals(descriptor.get("asset"))||!hash.equals(descriptor.get("sha256"))||template.indexOf("{{USER_TEXT}}")<0||template.indexOf("{{USER_TEXT}}")!=template.lastIndexOf("{{USER_TEXT}}")||!template.endsWith(SemanticPolicy.ASSISTANT_PREFIX))throw new IllegalArgumentException("workflow_v2_prompt_identity_invalid");templates.put(language,template);hashes.put(language,hash);}
   return new Config(base.templates,base.hashes,templates,hashes,base.stopOnJsonComplete);
  }
  String prompt(SemanticLanguage language,String input,boolean workflowV2){if(input==null||input.contains("<|")||input.indexOf(0)>=0||input.length()>4096)throw new IllegalArgumentException("invalid_single_model_input");Map<String,String> selected=workflowV2?workflowTemplates:templates;String template=selected.get(language.id);if(template==null)throw new IllegalStateException("workflow_v2_prompt_not_configured");return template.replace("{{USER_TEXT}}",input);}
  String hash(SemanticLanguage language,boolean workflowV2){return (workflowV2?workflowHashes:hashes).get(language.id);}
 }
 private static String withoutTerminalLineBreak(String text){if(text.endsWith("\r\n"))return text.substring(0,text.length()-2);if(text.endsWith("\n")||text.endsWith("\r"))return text.substring(0,text.length()-1);return text;}
 private static String digest(String text){try{byte[] b=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte value:b)s.append(String.format(Locale.ROOT,"%02X",value&255));return s.toString();}catch(Exception impossible){throw new IllegalStateException(impossible);}}
 static String requestGate(String text,SemanticLanguage language){
  if(text==null||text.trim().isEmpty()||text.length()>512||text.contains("<|")||text.indexOf(0)>=0)return "invalid_single_input";
  String active=language==SemanticLanguage.EN?EnglishRequestCompiler.scopeSurface(text):text;
  if(language==SemanticLanguage.EN&&active.matches("(?is).+\\b(?:export|capture)\\s+(?:a\\s+)?screenshot.*")||language==SemanticLanguage.ZH&&active.matches("(?s).+(?:导出|截图|截屏|截个图)(?:图像|图片|截图)?[。.!！]?"))return "single_instruction_split_required";
  if(language==SemanticLanguage.EN&&active.matches("(?is).+\\b(?:and|then|also|after\\s+that)\\s+(?:please\\s+)?(?:do|perform|execute)\\b.+")||language==SemanticLanguage.ZH&&active.matches("(?s).+(?:再做|另做|另外做|然后做).+"))return "single_instruction_split_required";
  if(language==SemanticLanguage.EN&&EnglishSingleOperationScope.unconsumedSplit(text))return "single_instruction_split_required";
  if(language==SemanticLanguage.ZH&&PcSemanticGuard.chineseDisplayTimebasePair(text))return "single_instruction_split_required";
  if(multipleChannelTargets(active,language))return "single_instruction_split_required";
  if(sameNumericParameterAlternatives(active,language))return "single_parameter_conflict";
  String operationSurface=language==SemanticLanguage.ZH?xyDisplayPropertySurface(active):active;
  if(operationSurface.matches("(?s).*[;；\\r\\n].*")||language==SemanticLanguage.EN&&EnglishSemanticBridge.hasMultipleOperations(text)||language==SemanticLanguage.ZH&&(BilingualModelManifest.orderedChinese(operationSurface)||operationSurface.matches("(?s).*(?:同时|(?:再|并|然后|另外|顺便)(?:且|也|请|帮我)?(?=把|将|执行|运行|启动|打开|关闭|设置|调整|读取|查询|截图|截屏|截个图|导出|保存|停止)).*")))return "single_instruction_split_required";
  if(language==SemanticLanguage.EN){String surface=EnglishRequestCompiler.scopeSurface(text);if(EnglishRequestCompiler.cancelled(surface)||surface.trim().matches("(?is)(?:please\\s+)?cancel\\s+(?:this|the|current)\\s+(?:request|operation|command)[.!?]?"))return "single_instruction_cancelled";String business=EnglishRequestCompiler.derivedIntent(surface);if("MEASURE_ADD".equals(business)||surface.matches("(?is).*\\b(?:save|store)\\b.*")&&!oneSaveConfiguration(surface)&&EnglishRequestCompiler.referenceSave(surface)==null)return "single_instruction_split_required";}
  else {if(PcSemanticGuard.prohibitedRequest(text)&&!PcSemanticGuard.explicitConfigurationDisable(text,"AUTOSet:KEEPcoup","OFF")&&!PcSemanticGuard.explicitConfigurationDisable(text,"AUTOSet:OPENch","OFF")||currentChineseCancellation(text)||text.trim().matches("(?:请|帮我)?取消(?:本次|整个|所有)?(?:操作|请求|命令)[。.!！]?"))return "single_instruction_cancelled";if(text.matches("(?s).*(?:保存|存储).*" )&&!oneSaveConfiguration(text)&&PcSemanticGuard.referenceSaveRequest(text)==null||text.matches("(?s).*(?:添加|新增|增加|创建|加一个).*(?:测量|读数|有效值|频率|相位).*" )||text.matches("(?s).*(?:清除|清空|删除).*(?:全部|所有|整个).*(?:测量|读数).*"))return "single_instruction_split_required";}
  return null;
 }
 private static boolean currentChineseCancellation(String text){return text.trim().matches("(?s)(?:请|帮我)?取消[^，,。!?？；;]{0,80}(?:请求|要求|操作|命令)(?:[，,]\\s*(?:本次|这次|现在)?(?:不要|不|无需)执行)?[。.!！]?" );}
 private static String xyDisplayPropertySurface(String text){return text.replaceAll("(?i)(?:XY\\s*(?:与|和|及|、)\\s*YT|YT\\s*(?:与|和|及|、)\\s*XY)\\s*(?:的)?\\s*同时显示", "XY与YT同显");}
 // Saving configuration is a single leaf. Saving data itself remains a paused workflow.
 static Set<String> saveConfigurationHeads(String text){
  boolean zh=Pattern.compile("\\p{IsHan}").matcher(text).find();Set<String> heads=new HashSet<>();
  if(!Pattern.compile(zh?"保存|存储":"(?i)\\b(?:save|saving|store|storage)\\b").matcher(text).find())return heads;
  boolean waveform=Pattern.compile(zh?"波形":"(?i)\\bwaveforms?\\b").matcher(text).find(),image=Pattern.compile(zh?"图像|图片":"(?i)\\b(?:images?|screenshots?)\\b").matcher(text).find();
  if(Pattern.compile(zh?"格式":"(?i)\\b(?:format|fileformat)\\b").matcher(text).find()){if(waveform)heads.add("SAVE:WAVEFORM:FILEFORMAT");if(image)heads.add("SAVE:IMAGE:FILEFORMAT");}
  if(image&&Pattern.compile(zh?"布局":"(?i)\\blayout\\b").matcher(text).find())heads.add("SAVE:IMAGE:LAYOUT");
  if(Pattern.compile(zh?"(?:文件名)?后缀":"(?i)\\b(?:file(?:name)?\\s+)?suffix\\b").matcher(text).find())heads.add("SAVE:SUFFIX");
  if(Pattern.compile(zh?"(?:保存|存储)(?:的)?类型":"(?i)\\b(?:save|saving|storage)\\s+type\\b").matcher(text).find())heads.add("SAVE:ASSIGN:TYPE");
  if(image&&Pattern.compile(zh?"省墨":"(?i)\\bink\\s*(?:saver|saving)\\b").matcher(text).find())heads.add("SAVE:IMAGE:INKSAVER");
  return heads;
 }
 private static boolean oneSaveConfiguration(String text){
  if(saveConfigurationHeads(text).size()!=1)return false;
  boolean zh=Pattern.compile("\\p{IsHan}").matcher(text).find();
  if(Pattern.compile(zh?"(?:执行|进行|开始|立即)\\s*(?:保存|存储)|(?:保存|存储)(?:下来|到|至|为|成)":"(?i)\\b(?:execute|perform|start)\\s+(?:a\\s+)?(?:save|storage)|^(?:(?:please|could|can|would|will|you)\\s+)*(?:save|store)\\b|\\b(?:to|and|then)\\s+(?:save|store)\\b").matcher(text).find())return false;
  return zh?PcSemanticGuard.requestedQuery(text)||Pattern.compile("设置|设为|设成|设到|调成|改为|选为|选择|选用").matcher(text).find():EnglishRequestCompiler.explicitQuery(text)||EnglishRequestCompiler.action(text);
 }
 private static boolean multipleChannelTargets(String text,SemanticLanguage language){
  // A dual-source existing measurement can still map to one native slot query/remove.
  if(language==SemanticLanguage.EN&&text.matches("(?is).*\\b(?:measurements?|readouts?|readings?)\\b.*")||language==SemanticLanguage.ZH&&text.matches("(?s).*(?:测量|读数).*"))return false;
  Set<Integer> channels=new HashSet<>();if(language==SemanticLanguage.EN){java.util.regex.Matcher addressed=EnglishChannelEvidence.PATTERN.matcher(text);while(addressed.find())if(EnglishChannelEvidence.analog(addressed,text))channels.add(EnglishChannelEvidence.index(addressed));}
  else {for(ChineseNumericNormalizer.Token t:new ChineseNumericNormalizer().normalize(text).tokens)if(t.type==ChineseNumericNormalizer.TokenType.CHANNEL&&t.valid)channels.add(t.channelIndex);}
  return channels.size()>1;
 }
 // Classification of an already-terminal simultaneous request only. No unit is
 // inferred, no candidate is generated, and the untouched model raw is retained.
 private static boolean sameNumericParameterAlternatives(String text,SemanticLanguage language){
  boolean en=language==SemanticLanguage.EN;
  if(!Pattern.compile(en?"(?i)\\bsimultaneously\\b":"同时").matcher(text).find()
    ||!Pattern.compile(en?"(?i)\\band\\b":"和|与|及").matcher(text).find())return false;
  if(Pattern.compile(en?"(?i)\\b(?:instead|not|then|hide|show|enable|disable|start|stop)\\b":"不是|而非|改为|然后|打开|关闭|启动|停止").matcher(text).find())return false;
  java.util.regex.Matcher field=Pattern.compile(en?"(?i)(vertical\\s+scale|volts\\s+per\\s+division)|(time\\s*base)|(trigger\\s+level)|(horizontal\\s+delay)":"(垂直(?:档位|刻度))|(主?时基)|(触发电平)|(水平延迟)").matcher(text);
  Set<Integer> fields=new HashSet<>();while(field.find())for(int i=1;i<=4;i++)if(field.group(i)!=null)fields.add(i);
  if(fields.size()!=1)return false;
  char[] surface=text.toCharArray();
  if(en){java.util.regex.Matcher channels=EnglishChannelEvidence.PATTERN.matcher(text);while(channels.find())if(EnglishChannelEvidence.analog(channels,text))for(int i=channels.start();i<channels.end();i++)surface[i]=' ';}
  else for(ChineseNumericNormalizer.Token token:new ChineseNumericNormalizer().normalize(text).tokens)if(token.type==ChineseNumericNormalizer.TokenType.CHANNEL&&token.valid)for(int i=token.rawStart;i<token.rawEnd;i++)surface[i]=' ';
  String quantities=new String(surface);Set<String> distinct=new HashSet<>();
  if(en){java.util.regex.Matcher numbers=Pattern.compile("(?i)"+EnglishNumberWords.VALUE).matcher(quantities);while(numbers.find()){Double value=EnglishNumberWords.parse(numbers.group());if(value!=null)distinct.add(java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString());}}
  else for(ChineseNumericNormalizer.Token token:new ChineseNumericNormalizer().normalize(quantities).tokens)if(token.type==ChineseNumericNormalizer.TokenType.NUMBER&&token.valid&&token.numericValue!=null)distinct.add(token.numericValue.stripTrailingZeros().toPlainString());
  return distinct.size()>1;
 }
 static String message(String code,SemanticLanguage language){if(code.equals("single_parameter_conflict"))return language==SemanticLanguage.EN?"This parameter has multiple values. Please specify one value and its unit.":"同一参数出现多个数值，请明确一个数值及单位。";if(code.equals("single_instruction_split_required"))return language==SemanticLanguage.EN?"Please split this request into separate single instructions.":"请将此请求拆分为单条指令。";if(code.equals("single_instruction_cancelled"))return language==SemanticLanguage.EN?"The current operation is cancelled; no command is formed.":"当前操作已取消，不生成命令。";return code;}
 static final class RawCheck {final boolean valid,legacySingle,multiple;final String violation;final Map<?,?> object;RawCheck(boolean v,String r,Map<?,?> o,boolean legacy,boolean multi){valid=v;violation=r;object=o;legacySingle=legacy;multiple=multi;}}
 static RawCheck checkRaw(String raw,ScpiTableEligibility table){
  if(raw==null)return new RawCheck(false,"backend_not_used",null,false,false);
  try{Object parsed=StrictJson.parse(raw);if(!(parsed instanceof Map))return new RawCheck(false,"native_three_fields_required",null,false,false);Map<?,?> m=(Map<?,?>)parsed;
   if("MULTI_STEP".equals(m.get("intent"))&&!m.containsKey("steps"))return new RawCheck(false,"multi_step_not_allowed_in_single_mode",m,false,true);
   if(m.containsKey("steps")){Object steps=m.get("steps");if(hiddenCommands(steps))return new RawCheck(false,"hidden_multi_command_not_allowed",m,false,true);boolean one=steps instanceof List&&((List<?>)steps).size()==1;return new RawCheck(false,"steps_not_allowed_in_single_mode",m,one,!one);}
   if(!m.keySet().equals(FIELDS)||!(m.get("intent") instanceof String)||!(m.get("SCPIcommand") instanceof String))return new RawCheck(false,"native_three_fields_required",m,false,false);
   String intent=(String)m.get("intent"),head=(String)m.get("SCPIcommand");Object parameter=m.get("SCPIparams");
   if(hiddenCommands(head)||hiddenCommands(parameter))return new RawCheck(false,"hidden_multi_command_not_allowed",m,false,true);
   if(Set.of("CLARIFY","REJECT").contains(intent))return new RawCheck(head.isEmpty()&&parameter instanceof Map&&((Map<?,?>)parameter).isEmpty(),"native_terminal_shape_invalid",m,false,false);
   ScpiTableEligibility.Decision d=table.qualify(head,parameter);ScpiTableEligibility.NarrationDescriptor descriptor=table.describeForNarration(head);String primitive=descriptor.intent.isEmpty()?"TABLE_"+descriptor.kind:descriptor.intent;if(primitive.equals("TABLE_action")&&head.matches("(?i)BUS:B[12]:COPYTrig"))primitive="BUS_action";
   boolean allowedEnum=descriptor.parameterType.endsWith("_or_enum")&&parameter instanceof String&&descriptor.enumValues.stream().anyMatch(v->v.equalsIgnoreCase((String)parameter));boolean typed=!numeric(descriptor.parameterType)||parameter instanceof Number||allowedEnum;
   boolean valid=intent.equals(primitive)&&typed&&Set.of(ScpiTableEligibility.Status.IN_TABLE_SET,ScpiTableEligibility.Status.IN_TABLE_QUERY,ScpiTableEligibility.Status.IN_TABLE_ACTION).contains(d.status);
   return new RawCheck(valid,valid?"":"native_intent_head_or_parameter_type_invalid",m,false,false);
  }catch(RuntimeException invalid){return new RawCheck(false,"complete_actual_raw_is_not_one_json_object",null,false,false);}
 }
 /** The authority owns a native leaf's set/action label. Normalization cannot
  * change its family, read/write direction, command, or any parameter. The raw
  * checker above remains strict for model-output scoring. */
 static RawCheck checkForExecution(String raw,ScpiTableEligibility table){
  RawCheck strict=checkRaw(raw,table);
  if(strict.valid||strict.multiple||strict.object==null||!strict.object.keySet().equals(FIELDS))return strict;
  Object supplied=strict.object.get("intent"),head=strict.object.get("SCPIcommand");
  if(!(supplied instanceof String)||!(head instanceof String)||((String)head).endsWith("?"))return strict;
  ScpiTableEligibility.NarrationDescriptor descriptor=table.describeForNarration((String)head);
  String intent=(String)supplied,expected=descriptor.intent;
  if(!intent.matches(".+_(?:set|action)")||!expected.matches(".+_(?:set|action)")
    ||!intent.substring(0,intent.lastIndexOf('_')).equals(expected.substring(0,expected.lastIndexOf('_'))))return strict;
  Map<String,Object> normalized=new LinkedHashMap<>();
  for(String key:FIELDS)normalized.put(key,strict.object.get(key));normalized.put("intent",expected);
  // Some authoritative action leaves use a numeric literal as a string enum.
  // Equality is exact, never nearest-value selection or an inferred conversion.
  Object parameter=strict.object.get("SCPIparams");
  if(parameter instanceof Number&&descriptor.parameterType.equals("enum")){
   List<String> matches=new ArrayList<>();
   try{java.math.BigDecimal value=new java.math.BigDecimal(parameter.toString());
    for(String choice:descriptor.enumValues)try{if(value.compareTo(new java.math.BigDecimal(choice))==0)matches.add(choice);}catch(NumberFormatException notNumeric){/* a word enum is never a number */}
   }catch(NumberFormatException notFinite){return strict;}
   if(matches.size()!=1)return strict;normalized.put("SCPIparams",matches.get(0));
  }
  RawCheck checked=checkRaw(CompactSemanticProtocol.encode(normalized),table);
  return checked.valid?checked:strict;
 }
 private static boolean hiddenCommands(Object value){if(value instanceof String){String s=(String)value;return s.indexOf(';')>=0||s.indexOf('；')>=0||s.indexOf('\r')>=0||s.indexOf('\n')>=0;}if(value instanceof Map){for(Object v:((Map<?,?>)value).values())if(hiddenCommands(v))return true;}if(value instanceof List){for(Object v:(List<?>)value)if(hiddenCommands(v))return true;}return false;}
 static boolean numeric(String type){return Arrays.asList("integer","number","float","divisions","time","time_per_div","voltage_per_div","percentage","points","probe_ratio","frequency","voltage").contains(type.replaceFirst("_or_enum$",""));}
 static boolean englishObjectMatches(String text,String command){String surface=EnglishRequestCompiler.scopeSurface(TextSpanMapping.of(text).normalized.toLowerCase(Locale.ROOT)),head=command.replaceFirst("\\?$","");if(EnglishSemanticBridge.singleObjectIssue(surface,head)!=null)return false;String h=head.toUpperCase(Locale.ROOT);if(h.equals("HORIZONTAL:SCALE"))return has(surface,"timebase|time\\s+base|horizontal\\s+scale|seconds?\\s+per\\s+division|time\\s+per\\s+division");if(h.equals("HORIZONTAL:DELAY:TIME"))return has(surface,"delay");if(h.equals("HORIZONTAL:RECORDLENGTH"))return has(surface,"record(?:\\s+length)?|recording\\s+depth");if(h.equals("TRIGGER:A:MODE"))return has(surface,"trigger(?:ing)?")&&has(surface,"mode");if(h.startsWith("TRIGGER:A:LEVEL:"))return has(surface,"trigger(?:ing)?")&&has(surface,"level|threshold|voltage");if(h.equals("TRIGGER:A:TIMEOUT:TIME"))return has(surface,"trigger(?:ing)?")&&has(surface,"timeout|time[- ]out")&&has(surface,"time|duration");if(h.equals("ACQUIRE:MODE"))return has(surface,"mode|processing")&&has(surface,"acquisition|acquire|sampling|capture");return true;}
 private static boolean has(String s,String expression){return Pattern.compile("(?i)\\b(?:"+expression+")\\b").matcher(s).find();}
 private SingleInstructionProtocol(){}
}
