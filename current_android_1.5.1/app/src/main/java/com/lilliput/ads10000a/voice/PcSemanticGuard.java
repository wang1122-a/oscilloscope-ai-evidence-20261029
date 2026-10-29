package com.lilliput.ads10000a.voice;
import java.util.*;import java.util.regex.*;import java.text.Normalizer;
/** Direct Java port of frozen PC v0.7.10 semantic_guard. Never generates a command. */
public final class PcSemanticGuard {
 static final String ANALOG_SOURCE="(?<![哪几])(?:第)?([零〇一二两三四五六七八九十0-9]+)(?:路|号输入|号波形)|(?:通道|模拟输入|模拟波形)([零〇一二两三四五六七八九十0-9]+)";
 static Set<Integer> analogSources(String input){
  Set<Integer> out=new HashSet<>();ChineseNumericNormalizer.Result typed=new ChineseNumericNormalizer().normalize(input);char[] additional=typed.nfkc.toCharArray();
  // Preserve typed source spans before aliases: a quantity after 一通道 is not 通道2.
  for(ChineseNumericNormalizer.Token token:typed.tokens)if(token.type==ChineseNumericNormalizer.TokenType.CHANNEL){if(token.valid&&token.channelIndex!=null)out.add(token.channelIndex);Arrays.fill(additional,token.nfkcStart,token.nfkcEnd,' ');}
  Matcher m=Pattern.compile(ANALOG_SOURCE).matcher(new String(additional));while(m.find()){String v=m.group(1)!=null?m.group(1):m.group(2);try{out.add(Integer.parseInt(v));}catch(NumberFormatException e){int i=v.length()==1?"零一二三四五六七八九十".indexOf(v):-1;if(v.equals("两"))i=2;if(i>=0)out.add(i);}}return out;
 }
 static String unclaimedAnalogSourceSurface(String input){ChineseNumericNormalizer.Result typed=new ChineseNumericNormalizer().normalize(input);char[] remaining=typed.nfkc.toCharArray();for(ChineseNumericNormalizer.Token t:typed.tokens)if(t.type==ChineseNumericNormalizer.TokenType.CHANNEL)Arrays.fill(remaining,t.nfkcStart,t.nfkcEnd,' ');return new String(remaining);}
 static Matcher scaleDisplayRequest(String input){Matcher m=Pattern.compile("(?is)\\s*(?:请|请帮我|帮我|麻烦)?(?:我(?:想|要|需要))?(?:把|将|让|使)?(?<source>通道[一二两三四1-4]|(?:第)?[一二两三四1-4]通道|CH[1-4]|(?:第)?[一二两三四1-4]路(?:输入)?)(?:的)?(?:波形)?按每格(?<quantity>半|[0-9]+(?:\\.[0-9]+)?|[零〇一二两三四五六七八九十百点]+)(?<unit>毫伏|微伏|伏)(?:来)?显示[。.!！?？]?\\s*").matcher(java.text.Normalizer.normalize(input,java.text.Normalizer.Form.NFKC));return m.matches()?m:null;}
 static boolean naturalDisplay(String input){if(scaleDisplayRequest(input)!=null)return false;if(input.matches("(?s)\\s*(?:请|帮我|麻烦)?(?:让|使)(?:通道[一二两三四0-9]+|[一二两三四0-9]+通道|(?:第)?[一二两三四0-9]+路(?:输入)?)(?:的)?(?:波形|轨迹)(?:出现|显示)在屏幕(?:上)?[。.!！?？]?\\s*"))return true;return input.matches("(?s)\\s*(?:(?:能否|能不能)(?:帮我)?|请|帮我|麻烦)?(?:把|将)?(?:"+ANALOG_SOURCE+")(?:的)?(?:波形)?(?:亮出来|显示|开启显示)[。.!！?？]?\\s*");}
 static boolean naturalDisplayOff(String input){return input.matches("(?s)\\s*(?:请|帮我|麻烦)?(?:把|将)?(?:"+ANALOG_SOURCE+")(?:的)?(?:波形|轨迹)(?:先|暂时)?(?:从屏幕上)?收起[。.!！?？]?\\s*");}
 static Matcher referenceSaveRequest(String text){
  String reference="(?:REF[1-4]|参考波形[一二两三四1-4]|(?:第)?[一二两三四1-4](?:号)?参考波形)";
  // A complete reference object and save act exempt one native REF<n> SAVE from workflow splitting.
  // The backend candidate still passes the ordinary object, parameter-source and authority checks.
  // Configuration/arrangement and execution verbs modify this one complete save act.
  // A second object/action or unmatched operand cannot fit the anchored frame.
  String saveAct="(?:(?:配置|安排)\\s*)?(?:(?:执行|进行)\\s*)?(?:保存|存储)(?:一下|下来|起来|好)?";
  Matcher m=Pattern.compile("(?is)\\s*(?:请|请帮我|帮我|麻烦)?(?:把|将|为|给)?(?:(?<source>"+reference+")(?:的)?(?:当前波形)?"+saveAct+"|(?:现在|当前)(?:的)?波形(?:存进|存入|保存到|保存进)(?:第)?[一二两三四1-4]参考波形)[。.!！?？]?\\s*").matcher(text);return m.matches()?m:null;
 }
 static Map<String,Object> dailyRepairCandidate(String text){String source="(?:通道[一二两三四1-4]|(?:第)?[一二两三四1-4]通道|CH[1-4]|(?:第)?[一二两三四1-4]路(?:输入)?)",prefix="\\s*(?:请|请帮我|帮我|麻烦)?(?:我(?:想|要|需要))?(?:把|将|让|使)?",end="[。.!！?？]?\\s*";Set<Integer> channels=analogSources(text);String head=null,value=null,family="VERTICAL_set";
  if(channels.size()==1&&text.matches("(?is)"+prefix+source+"(?:的)?(?:波形|轨迹)显示[，,]?\\s*(?:关掉|关闭|隐藏)"+end)){head="SELect:CH"+channels.iterator().next();value="OFF";}
  else if(channels.size()==1&&text.matches("(?is)"+prefix+source+"(?:的)?(?:用|使用|采用)(?:全|全部|完整)带宽"+end)){head="CH"+channels.iterator().next()+":BANdwidth";value="FULl";}
  else {Matcher trigger=Pattern.compile("(?is)\\s*(?:请|请帮我|帮我|麻烦)?(?:我(?:想|要|需要))?(?:把|将)?A触发(?:模式|方式)?(?:能|可以|能否|能不能)?(?:用|使用|改成|设为|调成)(?<mode>自动|正常|单次)(?:模式)?(?:吗|么|呢)?[。.!！?？]?\\s*").matcher(text);if(trigger.matches()){head="TRIGger:A:MODe";value=trigger.group("mode").equals("正常")?"NORMal":trigger.group("mode").equals("自动")?"AUTO":"SINGle";family="TRIGGERBASEANDEDGE_set";}}
  return head==null?null:Map.of("intent",family,"SCPIcommand",head,"SCPIparams",value);
 }
 static boolean unconsumedDailyRepair(String text){if(dailyRepairCandidate(text)!=null)return false;String source="(?:通道[一二两三四1-4]|(?:第)?[一二两三四1-4]通道|CH[1-4]|(?:第)?[一二两三四1-4]路(?:输入)?)",prefix="\\s*(?:请|请帮我|帮我|麻烦)?(?:我(?:想|要|需要))?(?:把|将|让|使)?";return text.matches("(?is)"+prefix+source+"(?:的)?(?:(?:波形|轨迹)显示[，,]?\\s*(?:关掉|关闭|隐藏)|(?:用|使用|采用)(?:全|全部|完整)带宽).*" )||text.matches("(?is)\\s*(?:请|请帮我|帮我|麻烦)?A触发(?:模式|方式)?(?:能|可以|能否|能不能)?(?:用|使用|改成|设为|调成)(?:自动|正常|单次).*" );}
 private static final String DAILY_SOURCE="(?:通道[一二两三四0-9]+|[一二两三四0-9]+通道|(?:第)?[一二两三四0-9]+路(?:输入)?)",DAILY_NUMBER="(?:负|[+-])?(?:[0-9]+(?:\\.[0-9]+)?|[零〇一二两三四五六七八九十百千万点]+)",DAILY_COURTESY="(?:能否|可否|能不能|可不可以|可以|能)(?:帮我|替我)?";
 static boolean dailySetting(String input){return input.matches("(?s)\\s*(?:"+DAILY_SOURCE+DAILY_COURTESY+"(?:换到|改到|调到|设为|调整为)每格"+DAILY_NUMBER+"(?:毫伏|微伏|伏)|"+DAILY_COURTESY+"给"+DAILY_SOURCE+"(?:加上|设置|设定)"+DAILY_NUMBER+"(?:兆赫(?:兹)?|千赫(?:兹)?|赫兹)(?:的)?带宽限制|"+DAILY_COURTESY+"(?:把|将)?A触发(?:方式|模式)(?:换成|改成|设为|设置为)(?:自动|正常|单次))(?:吗|么|呢)?[。.!！?？]?\\s*");}
 static boolean pauseAcquisition(String input){return input.matches("(?s)\\s*(?:请|帮我|麻烦)?(?:(?:波形)?采集[，,]?\\s*(?:先|现在)?暂停|(?:先|现在)?暂停(?:波形)?采集)[。.!！?？]?\\s*");}
 static String dailyQueryHead(String input){String q="\\s*(?:请|帮我|麻烦)?(?:我(?:想|需要)知道|我(?:想|需要)了解)",end="[。.!！?？]?\\s*";
  if(input.matches("(?s)"+q+"(?:现在|当前|目前)(?:横轴|水平轴)(?:一|每)格(?:表示|对应)(?:多长|多少)时间"+end))return "HORizontal:SCAle?";
  if(input.matches("(?s)\\s*"+DAILY_COURTESY+"(?:看一下|查看|读取)(?:示波器|设备)?(?:现在|当前|目前)(?:选的|选择的|采用的)?采集(?:模式|方式)(?:吗|么|呢)?"+end))return "ACQuire:MODe?";
  if(input.matches("(?s)"+q+DAILY_SOURCE+"(?:的)?(?:波形|轨迹)?显示开关(?:现在|当前|目前)(?:开着没有|是否(?:开启|打开)|是开还是关)"+end)){Set<Integer> c=analogSources(input);return c.size()==1?"SELect:CH"+c.iterator().next()+"?":null;}return null;
 }
 static boolean unconsumedDailyRequest(String input){String setting="(?s)^\\s*(?:"+DAILY_SOURCE+DAILY_COURTESY+"(?:换到|改到|调到|设为|调整为)每格|"+DAILY_COURTESY+"给"+DAILY_SOURCE+"(?:加上|设置|设定)|"+DAILY_COURTESY+"(?:把|将)?A触发(?:方式|模式)(?:换成|改成|设为|设置为))";String query="(?s)^\\s*(?:(?:请|帮我|麻烦)?我(?:想|需要)(?:知道|了解)(?=(?:现在|当前|目前)(?:横轴|水平轴)|"+DAILY_SOURCE+")|"+DAILY_COURTESY+"(?:看一下|查看|读取)(?:示波器|设备)?(?:现在|当前|目前)(?:选的|选择的|采用的)?采集(?:模式|方式))";return Pattern.compile(setting).matcher(input).find()&&!dailySetting(input)||Pattern.compile(query).matcher(input).find()&&dailyQueryHead(input)==null||input.matches("(?s)^\\s*(?:请|帮我|麻烦)?(?:波形)?采集[，,]?\\s*(?:先|现在)?暂停.*")&&!pauseAcquisition(input);}
 static boolean chineseWaitCourtesy(String input){return input.matches("(?s)\\s*(?:执行\\s*)?(?:能否|可否|能不能|可不可以|可以|能)(?:帮我|替我)?(?:等|等待|等候)(?:当前|正在执行|待执行|未完成)?(?:的)?操作(?:完成|结束|执行完毕)(?:吗|么|呢)?[。.!！?？]?\\s*");}
 static boolean chineseDisplayTimebasePair(String input){String num="[零〇一二两三四五六七八九十0-9]+",source="(?:通道"+num+"|"+num+"(?:号)?通道|(?:第)?"+num+"路)";String display="(?:(?:把|将)?"+source+"(?:的)?(?:波形)?(?:显示出来|亮出来|显示|开启显示)|(?:开启|打开|启用|显示)"+source+"(?:波形)?(?:显示)?)";String timebase="(?:把|将)?(?:主|水平)?时基(?:也)?(?:改为|改成|调整为|设为|设置为)\\s*(?:[0-9]+(?:\\.[0-9]+)?|[零〇一二两三四五六七八九十百点]+)(?:毫|微|纳)?秒每格";String[] parts=input.trim().replaceFirst("[。.!！]$","").split("[，,]",-1);if(parts.length!=2)return false;String a=parts[0].replaceFirst("^(?:请|帮我|麻烦)",""),z=parts[1].trim();return a.matches(display)&&z.matches(timebase)||a.matches(timebase)&&z.matches(display);}
 private static final String[] UNITS={"毫伏","微伏","千伏","伏特","伏","毫秒","微秒","纳秒","秒","千赫","兆赫","赫兹","赫","安培","毫安","安","%"};
 private static final String[] NEGATIONS={"不要","禁止","禁用","取消","停止","无需","不可","别","不"};
 private static final String[] PROHIBITIVE={"不要再","不要","别","请勿"};
 private static final String[] QUALIFIERS={"计划","统计","权限","状态"};
 private static final String[] PROTECTED={"通道","触发状态","频率计","自动设置","窗口透明度","垂直档位","总线配置","光标功能总开关"};
 private static final String[] OPEN={"打开","开启","启用"};
 private static final String[] CLOSE={"关闭","关掉","禁用","停止"};
 private static final String[] QUERY={"查询","查看","读取"};
 private static final String[] SET={"设置","调成","调到"};
 /** Politeness changes the speech act, never supplies a target, unit, source or current state. */
 static boolean chineseInformationQuestion(String input){return input.matches("(?s).*(?:权限|授权|许可|允许|有资格|安全吗|风险|解释|介绍|说明|讨论|是否支持).*");}
 /** Courtesy is a speech-act proof only; numeric, enum, source and authority checks remain mandatory. */
 private static final String SOURCE_COURTESY_PREFIX="\\s*(?:能否|可否|能不能|可不可以|可以|能)(?:帮我|帮|替我)?";
 private static final String SOURCE_COURTESY_SOURCE="(?:通道[一二两三四0-9]+|(?:第)?[一二两三四0-9]+路)";
 private static final String SOURCE_COUPLING_ACTION="(?:改用|改成|设为)",SOURCE_LEVEL_ACTION="(?:调到|设为|改为)",SOURCE_MODE_ACTION="(?:切到|改用|切换到)";
 private static final String SOURCE_COUPLING_VALUE="(?:直流|交流|接地)耦合",SOURCE_MODE_VALUE="(?:高分辨率|峰值|平均|采样)采集";
 private static boolean sourceDailyCourtesy(String input){String n="(?:负|[+-])?(?:[0-9]+(?:\\.[0-9]+)?|[零〇一二两三四五六七八九十百点]+)";return input.matches("(?s)"+SOURCE_COURTESY_PREFIX+"(?:"+SOURCE_COURTESY_SOURCE+SOURCE_COUPLING_ACTION+SOURCE_COUPLING_VALUE+"|"+SOURCE_COURTESY_SOURCE+"A触发电平"+SOURCE_LEVEL_ACTION+n+"(?:毫伏|伏)|"+SOURCE_MODE_ACTION+SOURCE_MODE_VALUE+")(?:吗|么|呢)?[。.!！?？]?\\s*");}
 /** The same supported object/action stems select both full consumption and residual rejection. */
 static boolean unconsumedSourceCourtesy(String input){String family="(?:"+SOURCE_COURTESY_SOURCE+SOURCE_COUPLING_ACTION+"(?="+SOURCE_COUPLING_VALUE+")|"+SOURCE_COURTESY_SOURCE+"A触发电平"+SOURCE_LEVEL_ACTION+"|"+SOURCE_MODE_ACTION+"(?="+SOURCE_MODE_VALUE+"))";return Pattern.compile("(?s)^"+SOURCE_COURTESY_PREFIX+family).matcher(input).find()&&!sourceDailyCourtesy(input);}
 static boolean chineseDailyCourtesy(String input){String channel="(?:通道[零〇一二两三四五六七八九十0-9]+|[零〇一二两三四五六七八九十0-9]+(?:号)?通道)",n="(?:负|[+-])?(?:[0-9]+(?:\\.[0-9]+)?|[零〇一二两三四五六七八九十百千万点]+)";return sourceDailyCourtesy(input)||naturalDisplay(input)||input.matches("(?s)\\s*(?:能否|可否|能不能|可不可以|可以|能)(?:帮我|替我)?(?:"+channel+"(?:垂直位置|垂直档位|垂直刻度|A触发电平)(?:要|为|设为|设成|改成)?[，,:：]?\\s*"+n+"(?:毫伏每格|伏每格|毫伏|伏|格)|"+channel+"(?:改成|设成|设为)(?:交流耦合|直流耦合|接地耦合)|(?:开启|打开|启用|关闭|禁用|停用)采集|采集模式(?:改成|设为|设成|切换为)(?:高分辨率|平均|采样|峰值)|采集平均次数(?:取|设为|要)"+n+"次)[。.!！?？]?\\s*");}
 /** A desired automatic display arrangement plus its explicit autoset detail names one action. */
 static boolean chineseAutosetGoalDetail(String input){return input.matches("(?s)\\s*(?:能否|可否|能不能|可不可以|可以|能)(?:帮我)?(?:让|使)示波器自动(?:整理|调整|配置)(?:当前|现在)?(?:的)?(?:显示|波形显示|波形)(?:设置)?[，,]\\s*(?:请)?(?:执行|运行|进行)(?:一次)?自动设置[。.!！?？]?\\s*");}
 static boolean chinesePoliteSetting(String input){String s=Normalizer.normalize(chineseConnectedContext(input==null?"":input),Normalizer.Form.NFKC);if(requestedQuery(s)||chineseInformationQuestion(s)||s.matches("(?s).*(?:不要|禁止|请勿|不得|不允许|已经|已设|已放|曾经|如果|假如).*"))return false;boolean courtesy=Pattern.compile("(?:能否|可否|能不能|可以|能)(?:帮我|替我|替它)?(?=为|让|使|把|将|设置|设定|设为|设在|设成|调整|修改|更改|选择|选|放在|隐藏|关闭|打开|启用|显示|移除|删除|采用)").matcher(s).find()||s.matches("(?s).*可以(?:(?:按后者|按此|按这个设置)?执行)?(?:吗|么|呢)[?？]?");return dailyRepairCandidate(s)!=null||dailySetting(s)||chineseWaitCourtesy(s)||chineseDailyCourtesy(s)||courtesy&&Pattern.compile("让|使|设置|设定|设为|设在|调整|修改|更改|改为|改成|调到|调成|放在|选择|选|隐藏|关闭|打开|启用|显示|移除|删除|采用|把|将").matcher(s).find();}
 static String chinesePoliteSurface(String input){if(!chinesePoliteSetting(input))return input;String s=input;if(sourceDailyCourtesy(s)){Matcher prefix=Pattern.compile("^"+SOURCE_COURTESY_PREFIX).matcher(s);if(prefix.find())s=prefix.group().replaceAll("."," ")+s.substring(prefix.end());}for(String expression:new String[]{"(?:能否|可否|能不能|可以|能)(?:帮我|替我|替它)?(?=为|让|使|把|将|设置|设定|设为|设在|设成|调整|修改|更改|选择|选|放在|隐藏|关闭|打开|启用|显示|移除|删除|采用)","(?:可以(?:(?:按后者|按此|按这个设置)?执行)?)?(?:吗|么|呢)[?？]?\\s*$","本(?:来)?想(?:用|采用)[^，,；;]+[，,]\\s*现(?=改成|改为|调整)","(?:参考输入|参考对象)(?:指的是|是)"}){Matcher m=Pattern.compile(expression).matcher(s);StringBuffer out=new StringBuffer();while(m.find())m.appendReplacement(out,Matcher.quoteReplacement(m.group().replaceAll("."," ")));m.appendTail(out);s=out.toString();}return s;}
 static boolean chinesePurposeAction(String input){Matcher m=Pattern.compile("(?s)^\\s*为(?:使|了获得|了达到)[^，,；;]+[，,]\\s*本次操作(?:须|需|要求)(.+)").matcher(input);if(!m.matches())return false;String body=m.group(1).trim();return body.matches("(?s)^(?:将|把|设置|调整|修改|设定|乘以|除以|减半|加倍).*" )&&!body.matches("(?s).*(?:如果|假如|不要|请勿|禁止|取消|解释|介绍|说明|讨论|未知|未指定).*" );}
 static boolean chineseNominalSetting(String input){String s=input.trim();if(s.matches("(?s)^(?:如果|假如|若|当|介绍|解释|说明|讨论|分析|如何).*" )||s.matches("(?s).*(?:不要|请勿|禁止|不需要|并非所需).*"))return false;return s.matches("(?s)^(?:操作员)?设置(?:要求|请求)[—–\\-：:].*")||s.matches("(?s)^[^，,；;]+所需(?:要)?的设置(?:是|为).*")||chinesePurposeAction(s);}
 /** Only a full current-utterance replacement can mask an explicitly rejected operand. */
 static String chineseCorrectionSurface(String input){
  String n="(?:负|[+-])?(?:[0-9]+(?:\\.[0-9]+)?|[零〇一二两三四五六七八九十百点]+)",u="(?:毫秒每格|微秒每格|纳秒每格|秒每格|毫伏每格|伏每格|毫伏|伏|格|兆赫|千赫|赫兹)";
  Matcher quantity=Pattern.compile("(?s)^\\s*(?:请|帮我|麻烦)?(?:把|将)?(?:主时基|水平时基|(?:通道[一二两三四0-9]+|[一二两三四0-9]+通道)(?:垂直刻度|垂直档位|垂直位置|A触发电平|带宽))(?:用|设为|改为|调到|调整为)"+n+"(?<unit>"+u+")(?<discard>[，,]\\s*(?:而不是|不是)"+n+"\\k<unit>)[。.!！?？]?\\s*$").matcher(input);
  if(quantity.matches())return input.substring(0,quantity.start("discard"))+input.substring(quantity.start("discard")).replaceAll("."," ");
  Matcher lateSource=Pattern.compile("(?s)^\\s*(?:我说的|本次要求的|当前要求的)(?:是|为)"+n+"(?<unit>毫伏每格|微伏每格|伏每格)(?<separator>[，,]\\s*)(?<source>通道[一二两三四0-9]+|[一二两三四0-9]+通道|(?:第)?[一二两三四0-9]+路)(?<discard>(?:不用|不采用|不使用)"+n+"\\k<unit>)[。.!！?？]?\\s*$").matcher(input);
  if(lateSource.matches()){char[] c=input.toCharArray();Matcher lead=Pattern.compile("^\\s*(?:我说的|本次要求的|当前要求的)(?:是|为)").matcher(input);if(lead.find())Arrays.fill(c,lead.start(),lead.end(),' ');Arrays.fill(c,lateSource.start("separator"),lateSource.end("separator"),' ');Arrays.fill(c,lateSource.start("discard"),input.length(),' ');return new String(c);}
  Matcher coupling=Pattern.compile("(?is)^\\s*(?:通道[一二两三四0-9]+|(?:第)?[一二两三四0-9]+路)(?<prior>刚才说错了[，,])耦合(?:选|用|改为|设为)(?:AC|DC|GND|直流|交流|接地)(?<discard>而不是(?:AC|DC|GND|直流|交流|接地))[。.!！?？]?\\s*$").matcher(input);
  if(coupling.matches()){char[] chars=input.toCharArray();Arrays.fill(chars,coupling.start("prior"),coupling.end("prior"),' ');Arrays.fill(chars,coupling.start("discard"),input.length(),' ');return new String(chars);}
  Matcher replacedCoupling=Pattern.compile("(?is)^\\s*(?:请|帮我|麻烦)?(?:把|将)?(?:通道[一二两三四0-9]+|(?:第)?[一二两三四0-9]+路)(?:的)?耦合(?<discard>不是(?:AC|DC|GND|直流|交流|接地)[，,]\\s*)(?:要|用|选|选用|改成|设为)(?:AC|DC|GND|直流|交流|接地)[。.!！?？]?\\s*$").matcher(input);
  if(replacedCoupling.matches())return input.substring(0,replacedCoupling.start("discard"))+input.substring(replacedCoupling.start("discard"),replacedCoupling.end("discard")).replaceAll("."," ")+input.substring(replacedCoupling.end("discard"));return input;
 }
 static String chineseScopeSurface(String input){input=chineseCorrectionSurface(input);Matcher prior=Pattern.compile("[，,]\\s*(?:刚才|之前|原来)(?:的)?(?:正值|旧值|数值|设置)(?:不采用|不用|不使用)[。.!！]?\\s*$").matcher(input);if(prior.find()){String main=input.substring(0,prior.start());ChineseNumericNormalizer.Result typed=new ChineseNumericNormalizer().normalize(main);long count=typed.tokens.stream().filter(t->t.type==ChineseNumericNormalizer.TokenType.NUMBER).count();boolean unit=typed.tokens.stream().anyMatch(t->t.type==ChineseNumericNormalizer.TokenType.UNIT);if(count==1&&unit&&main.matches("(?s).*(?:触发电平|垂直刻度|垂直位置|主时基|延迟).*" )&&main.matches("(?s).*(?:用|设为|设置|调整|改为).*"))input=main+input.substring(prior.start()).replaceAll("."," ");}Matcher prefix=Pattern.compile("^\\s*(?:需求|请求|要求)(?:尚未完整|不完整|仍不完整)[：:]\\s*").matcher(input);String s=prefix.find()?prefix.group().replaceAll("."," ")+input.substring(prefix.end()):input;if(chinesePurposeAction(s)){Matcher delimiter=Pattern.compile("[，,]\\s*本次操作(?:须|需|要求)").matcher(s);if(delimiter.find())s=s.substring(0,delimiter.start())+delimiter.group().replaceAll("."," ")+s.substring(delimiter.end());}return s;}
 /** A connection fact supplies no setting/state; only a separate explicit current operation is active. */
 private static String chineseConnectedContext(String input){Matcher m=Pattern.compile("^\\s*(?:信号|探头|输入|设备)(?:已经|已)(?:接入|连接|准备好)[，,]\\s*").matcher(input);if(!m.find())return input;String current=input.substring(m.end());if(!current.matches("(?s).*(?:让|使|把|将|执行|运行|设置|调整|打开|关闭).*" )||current.matches("(?s).*(?:如果|假如|不要|禁止|未指定|未知|查询|读取).*"))return input;return input.substring(0,m.end()).replaceAll("."," ")+current;}
 static boolean chineseNonExecutingSetting(String input){String s=chineseScopeSurface(chineseConnectedContext(input)).trim();if(s.matches("(?s)^(?:如果|假如|若|当|介绍|解释|说明|讨论|分析|如何).*" )||s.matches("(?s).*(?:如果|假如|已经|曾经|刚才|被(?:设置|调整)|并非所需|不需要).*") )return true;if(chineseNominalSetting(input))return false;if(s.matches("(?s)^(?:(?:请|帮我|麻烦)\\s*)*(?:把|将|设置|调整|修改|设定|关闭|打开|禁用|启用).*"))return false;return s.matches("(?s).*(?:(?:当前|现在|目前)(?:的)?(?:设置)?(?:是|为)|设置是).*");}
 /** Query act has scope over descriptive state/configuration nouns. */
 public static boolean requestedQuery(String input){
  return SemanticPolicy.ShortDailyRequest.explicitReadHead(input)!=null||requestedQueryAct(input);
 }
 private static boolean requestedQueryAct(String input){
  if(dailyQueryHead(input)!=null)return true;String text=normalize(input);
  if(text.matches("(?:请|帮我)?确认.*操作完成.*(?:再继续|再执行).*")||prohibitedRequest(text)||text.matches("(?s)^(?:请|帮我|请帮我|我想)?(?:解释|介绍|说明|分析|评估|讨论|如何|为什么|什么是).*") )return false;
  if(text.matches("(?s)^(?:请|帮我|麻烦)?(?:看看|看一下|我想知道|我需要知道)(?:当前|目前|现在)(?:的)?(?:(?:通道[零〇一二两三四五六七八九十0-9]+|[零〇一二两三四五六七八九十0-9]+(?:号)?通道)(?:A触发电平|垂直位置|垂直档位|垂直刻度|耦合|带宽|显示状态)|操作完成标志|采集状态|采集模式|采集平均次数|记录长度|水平延迟)[。.!！?？]?"))return true;
  if(text.matches("(?s)(?:我想查(?:第)?[一二三四0-9]+路(?:目前|当前|现在)的耦合方式|(?:当前|现在|目前)(?:第)?[一二三四0-9]+路垂直档位是多少[，,]告诉我)[。.!！?？]?"))return true;
  boolean read=Pattern.compile("^(?:请|帮我|请帮我|麻烦|设备)?(?:查询|查看|读取|读出|检查|核对|确认|报告|获取|告诉我|我想了解)").matcher(text).find()
   ||Pattern.compile("(?:能否|可否|能不能|可不可以|可以|能)(?:告诉我|读取|读出|查询|查看|报告|获取|报出)").matcher(text).find();
  if(read)return true;
  if(text.matches("(?s)(?:把|将)?(?:第)?[一二两三四五六七八1-8]项测量(?:现在|当前|目前)(?:的)?(?:读数|现值|当前值)告诉我[。.!！?？]?"))return true;
  boolean explicitRead=Pattern.compile("(?:^|[，,。.!！?？])\\s*(?:(?:请|麻烦|帮我|请帮我|我(?:只)?(?:想|要|需要)?)\\s*)?(?:查询|查看|读取|读出|查一下|读一下|读(?=通道|第|当前|现在|测量))").matcher(text).find()
   ||Pattern.compile("(?:请|麻烦|我(?:只)?(?:想|要|需要))\\s*(?:查询|查看|读取|读出|查一下|读一下|读(?=通道|第|当前|现在|测量))").matcher(text).find()
   ||Pattern.compile("读一下(?:现值|当前值|现在值|状态|数值)?").matcher(text).find();
  if(explicitRead&&!text.matches("(?s).*(?:如何设置|怎么设置|如何使用|如何查询|查询方法|读取方法).*"))return true;
  if(Pattern.compile("(?:是否|是不是)(?:已经|已)(?:设置|设为|设在|调到|放在|开启|关闭)").matcher(text).find())return true;
  // A requested target choice/value is incomplete control input; it is not a question about the current device.
  if(Pattern.compile("设为|设成|设在|设置为|设置成|调到|调成|调整为|改为|改成|选成|换成").matcher(text).find())return false;
  return Pattern.compile("多少|哪些|哪个|哪套|哪种|什么|哪一种|有哪些|(?:当前|目前|现在).*(?:是否|是不是|读出来|怎样|如何|怎么|多大)|(?:打开还是关闭|开启还是关闭)$").matcher(text).find()
   ||text.matches(".*(?:读出来|读出)$");
 }

 /** A prohibition on an AUTOSET sub-setting requests OFF; it does not execute AUTOSET. */
 static boolean explicitConfigurationDisable(String input,String command,Object value){if(!"OFF".equals(value))return false;String h=command.toUpperCase(Locale.ROOT),text=normalize(input);String object=h.equals("AUTOSET:KEEPCOUP")?"(?:保留|保持)耦合(?:参数)?":h.equals("AUTOSET:OPENCH")?"(?:自动)?打开通道":null;return object!=null&&text.matches("(?:请|帮我)?(?:禁止|不要让|不要|禁用|关闭)自动设置(?:过程|的)?"+object+"[。.!！]?");}
 public static boolean prohibitedRequest(String input){String text=normalize(input);return Pattern.compile("(?:不要再?|请勿|不得|不允许|禁止|别)(?=把|将|动|改动|改变|让|使|打开|开启|启用|关闭|设置|设为|设定|执行|运行|查询|读取|查看|读出|获取|保存|复制|清除|清空|重置|自动设置)").matcher(text).find();}

 private static List<String> present(String text,String[] terms){SortedSet<String> found=new TreeSet<>();for(String s:terms)if(!s.isEmpty()&&text.contains(s))found.add(s);return new ArrayList<>(found);}
 public static String normalize(String raw){String s=Normalizer.normalize(raw==null?"":raw,Normalizer.Form.NFKC).replaceAll("\\s+","").replaceAll("^[，。！？；：,.!?;:、]+|[，。！？；：,.!?;:、]+$","");String chars="零一二两三四五六七八九",digits="01223456789";for(int i=0;i<chars.length();i++){String a=chars.substring(i,i+1),b=digits.substring(i,i+1);s=s.replace("通道"+a,"通道"+b).replace(a+"号通道","通道"+b);for(String unit:UNITS)s=s.replace(a+unit,b+unit);}return s;}
 private static Map<String,Object> signature(String s){Map<String,Object> m=new LinkedHashMap<>();List<String> neg=present(s,NEGATIONS);neg.remove("不");if(Pattern.compile("不(?!要|可|需|同)").matcher(s).find())neg.add("不");Collections.sort(neg);m.put("negation",neg);Map<String,Object> dirs=new TreeMap<>();String d=s.replace("自动设置","自动功能");String[] keys={"open","close","query","set"};String[][] terms={OPEN,CLOSE,QUERY,SET};for(int i=0;i<keys.length;i++){List<String> v=present(d,terms[i]);if(!v.isEmpty())dirs.put(keys[i],v);}m.put("direction",dirs);m.put("object",present(s,PROTECTED));List<String> nums=new ArrayList<>();Matcher n=Pattern.compile("(?<![A-Za-z])[+-]?(?:\\d+(?:\\.\\d+)?)").matcher(s);while(n.find())nums.add(n.group());m.put("number",nums);m.put("unit",present(s,UNITS));m.put("qualifier",present(s,QUALIFIERS));return m;}
 private static String typedScaleBoundary(String input){
  ChineseNumericNormalizer.Result typed=new ChineseNumericNormalizer().normalize(input);
  ChineseNumericNormalizer.Token channel=null,number=null,unit=null;
  for(ChineseNumericNormalizer.Token token:typed.tokens){
   if(token.type==ChineseNumericNormalizer.TokenType.CHANNEL){if(channel!=null||!token.valid)return null;channel=token;}
   if(token.type==ChineseNumericNormalizer.TokenType.NUMBER){if(number!=null)return null;number=token;}
   if(token.type==ChineseNumericNormalizer.TokenType.UNIT&&"V/div".equals(token.canonicalUnit)){if(unit!=null)return null;unit=token;}
  }
  if(channel==null||number==null||unit==null||channel.rawEnd>number.rawStart
      ||!typed.raw.substring(channel.rawEnd,number.rawStart).trim().isEmpty())return null;
  return input;
 }
 public static Map<String,Object> choose(String original,String corrected,boolean passed){String a=normalize(original),b=normalize(corrected);List<String> conflicts=new ArrayList<>();Map<String,Object> sa=signature(a),sb=signature(b);if(!a.isEmpty()&&!b.isEmpty())for(String k:new String[]{"negation","direction","object","number","unit","qualifier"})if(!sa.get(k).equals(sb.get(k)))conflicts.add(k);boolean useOriginal=!a.isEmpty()&&(b.isEmpty()||!conflicts.isEmpty());String chosen=useOriginal?a:b;String rawBoundary=typedScaleBoundary(original);String correctedBoundary=typedScaleBoundary(corrected);String typedBoundary=!useOriginal&&rawBoundary!=null&&correctedBoundary!=null?rawBoundary:useOriginal?rawBoundary:correctedBoundary;if(typedBoundary!=null){chosen=typedBoundary;useOriginal=typedBoundary.equals(original);}Map<String,Object> out=new LinkedHashMap<>();out.put("numeric_boundary_preserved",typedBoundary!=null);out.put("original",original);out.put("corrected",corrected);out.put("passed",passed);out.put("normalized_original",a);out.put("normalized_corrected",b);out.put("semantic_input_text",chosen.isEmpty()?null:chosen);out.put("semantic_input_source",chosen.isEmpty()?null:useOriginal?"original":"corrected");out.put("proceed_to_semantic",!chosen.isEmpty());out.put("critical_semantic_conflict",!conflicts.isEmpty());out.put("conflict_categories",conflicts);out.put("reason_code",chosen.isEmpty()?"asr_text_unavailable":conflicts.isEmpty()?"critical_semantics_consistent":"correction_rejected_preserve_original");return out;}
 public static String consistency(String input,String intent,String command,Object parameter,boolean exactTableQuery){String raw=Normalizer.normalize(input==null?"":input,Normalizer.Form.NFKC);boolean trailingQuestionMark=Pattern.compile("[?？]\\s*$").matcher(raw).find();boolean polite=chinesePoliteSetting(input)||EnglishRequestCompiler.politeControlRequest(input);String text=normalize(chineseScopeSurface(chinesePoliteSurface(input)));Map<String,Object> sig=signature(text);Set<String> dirs=new HashSet<>(((Map<String,Object>)sig.get("direction")).keySet());String value=String.valueOf(parameter).toUpperCase(Locale.ROOT);boolean positive=Arrays.asList("ON","1","1.0","TRUE").contains(value),negative=Arrays.asList("OFF","0","0.0","FALSE").contains(value);boolean query=command.endsWith("?"),nonBooleanAction=!query&&intent.toLowerCase(Locale.ROOT).endsWith("_action")&&!positive&&!negative;boolean leadingQuery=Pattern.compile("\\p{IsHan}").matcher(input).find()?requestedQuery(input):EnglishRequestCompiler.explicitQuery(input),leadingExecute=Pattern.compile("^(?:请|帮我|请帮我|现在|立即)?执行").matcher(text).find();boolean interrogative=leadingQuery||!polite&&trailingQuestionMark||!polite&&!leadingExecute&&Pattern.compile("^(?:请|帮我|请帮我)?(?:是否|是不是|有没有|当前|现在|如何|什么|多少)").matcher(text).find()||!polite&&Pattern.compile("(?:吗|么|呢)$").matcher(text).find();
 // A newly covered state question is a read, but keeps the older precise conflict reasons.
 // Only an explicit read act overrides the described ON/OFF direction in the signature.
 boolean typedReadOnly=Pattern.compile("\\p{IsHan}").matcher(input).find()&&SemanticPolicy.ShortDailyRequest.explicitReadHead(input)!=null&&!requestedQueryAct(input);
 if(leadingQuery&&!typedReadOnly){dirs.clear();dirs.add("query");}else{dirs.remove("query");if(leadingExecute)dirs.removeAll(Arrays.asList("open","close","set"));boolean explicitSet=Pattern.compile("^(?:请)?(?:将|把|设置)").matcher(text).find()&&Pattern.compile("设置为|设为|调成|调到").matcher(text).find();if(explicitSet&&!leadingExecute){dirs.add("set");dirs.removeAll(Arrays.asList("open","close"));}Matcher state=Pattern.compile("(?:设置为|设为|调成|调到|为)(开启|打开|启用|关闭|关掉|禁用|停用)$").matcher(text);if(!leadingExecute&&state.find()){dirs.removeAll(Arrays.asList("open","close"));dirs.add("set");dirs.add(Arrays.asList(OPEN).contains(state.group(1))?"open":"close");}}
 boolean neg=!((List)sig.get("negation")).isEmpty();if(explicitConfigurationDisable(input,command,parameter))return null;if(prohibitedRequest(input))return "explicit_prohibitive_request_requires_no_action";
 if(leadingExecute&&query)return "action_to_query_conflict";
 if((dirs.contains("open")||dirs.contains("close")||dirs.contains("set"))&&query&&!(exactTableQuery&&(interrogative||text.endsWith("状态"))))return "action_to_query_conflict";
 if(query&&!leadingQuery&&!(exactTableQuery&&(interrogative||text.endsWith("状态"))))return "explicit_read_request_required";
 if(nonBooleanAction&&Pattern.compile("^(?:请|帮我|请帮我)?(?:解释|介绍|说明|分析|评估|讨论|告诉我)").matcher(text).find())return "descriptive_non_execution_request";
 if(neg&&(positive||nonBooleanAction))return "negated_positive_action_conflict";
 if(neg&&negative&&!interrogative)return null;
 if(dirs.contains("close")&&positive||dirs.contains("open")&&negative)return "action_conflict";
 if((dirs.contains("open")||dirs.contains("close"))&&nonBooleanAction)return "state_direction_to_non_boolean_action_conflict";
 if(dirs.contains("query")&&!query)return "query_to_nonquery_conflict";
 if(interrogative&&!query){SemanticPolicy.ShortDailyRequest typed=SemanticPolicy.ShortDailyRequest.parse(input);boolean typedCurrentAction=!leadingQuery&&!trailingQuestionMark&&!text.matches("(?s).*(?:吗|么|呢)$")&&typed!=null&&typed.issue==null&&(typed.command.matches("REF[1-4]")&&"SAVE".equals(typed.parameter)||typed.command.equals("AUTOSet")&&"EXECute".equals(typed.parameter))&&typed.matches(input,command,parameter);if(!typedCurrentAction)return "interrogative_command_requires_clarification";}
 if(dirs.contains("set")&&!dirs.contains("open")&&!dirs.contains("close")&&!neg&&(positive||negative)&&((List)sig.get("number")).isEmpty()&&!(chineseNominalSetting(input)&&Pattern.compile("(?i)(?<![A-Za-z0-9])"+Pattern.quote(value)+"(?![A-Za-z0-9])").matcher(input).find()))return "set_value_not_explicit";
 return null;
 }
}

