package com.lilliput.ads10000a.voice;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;

/** Re-parses ONLY the current utterance using declared table-backed semantic bindings.
 * Never consumes a model command or parameter to select a template. No transport. */
final class AuthoritativeTemplateResolver {
 static final class Resolution {
  final boolean handled;final String status,reason,command,intent,parameter,template;
  final Map<String,Object> evidence;
  Resolution(boolean h,String s,String r,String c,String i,String p,String t,Map<String,Object> e){handled=h;status=s;reason=r;command=c;intent=i;parameter=p;template=t;evidence=e;}
 }
 private static final class Binding {
  final Map data;final Pattern utterance,object;final Map<String,String> commands=new LinkedHashMap<>();
  Binding(Map d,Map root,ScpiTableEligibility table){data=d;utterance=Pattern.compile(d.get("utterance_regex").toString(),Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
   List<String> words=new ArrayList<>();for(Object o:(List)d.get("semantic_objects"))words.add(Pattern.quote(o.toString()).replace(" ","\\E\\s+\\Q"));object=Pattern.compile(String.join("|",words),Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
   String template=d.get("template").toString();String[] parts=template.split(Pattern.quote("{channel}"),-1);if(parts.length!=2)throw new IllegalArgumentException("unsupported_binding_slots");
   Pattern head=Pattern.compile(Pattern.quote(parts[0])+"([0-9]+)"+Pattern.quote(parts[1]),Pattern.CASE_INSENSITIVE);
   for(Object o:(List)((Map)root.get("catalog")).get("commands")){Map c=(Map)o;String name=c.get("SCPIcommand").toString();Matcher m=head.matcher(name);if(m.matches()&&Objects.equals(c.get("parameter_type"),d.get("parameter_type"))&&Objects.equals(c.get("intent"),d.get("intent"))&&table.classify(name)==ScpiTableEligibility.Status.IN_TABLE_SET)commands.put(m.group(1),name);}
   if(commands.isEmpty())throw new IllegalArgumentException("binding_not_in_authoritative_catalog");
  }
 }
 private final List<Binding> bindings=new ArrayList<>();private final ScpiTableEligibility table;private final UnitSourceModel units;
 AuthoritativeTemplateResolver(Map root,ScpiTableEligibility t){table=t;units=new UnitSourceModel(root);for(Object o:(List)root.getOrDefault("semantic_template_bindings",Collections.emptyList()))bindings.add(new Binding((Map)o,root,t));}
 boolean coversCommand(String head){for(Binding b:bindings)for(String c:b.commands.values())if(c.equalsIgnoreCase(head))return true;return false;}
 Resolution resolve(String user){
  List<Binding> relevant=new ArrayList<>();for(Binding b:bindings)if(b.object.matcher(user).find())relevant.add(b);
  if(relevant.isEmpty())return new Resolution(false,"reject","no_authoritative_semantic_binding","","","","",Collections.emptyMap());
  Map<String,Object> e=new LinkedHashMap<>();e.put("input",user);e.put("source","current_utterance_only");e.put("matching_bindings",relevant.size());
  if(relevant.size()!=1)return failure("请补充唯一的命令对象。",e);
  Binding b=relevant.get(0);e.put("binding_id",b.data.get("id"));e.put("authority_source",b.data.get("source_ref"));
  if(user.matches("(?s).*(打开|开启|启用).*")&&user.matches("(?s).*(关闭|关掉|禁用).*"))return new Resolution(true,"reject","action_conflict","","","", "",e);
  Matcher m=b.utterance.matcher(user);if(!m.matches())return failure("请补充单一通道、唯一倍率和明确的设置动作（不可混合其他命令或单位）。",e);
  e.put("object_evidence",span(user,m,"object"));
  if(m.group("channel")==null)return failure("请补充通道编号。",e);
  String channel;try{channel=UnitSourceModel.decimal(m.group("channel")).toBigIntegerExact().toString();}catch(Exception ex){return failure("请补充合法通道编号。",e);}
  String cmd=b.commands.get(channel);e.put("channel_evidence",span(user,m,"channel"));
  if(cmd==null)return new Resolution(true,"reject","channel_not_in_authoritative_template","","","","",e);
  if(m.group("value")==null)return failure("请补充"+b.data.get("missing_value_label")+"。",e);
  e.put("value_evidence",span(user,m,"value"));if(m.group("unit")!=null)e.put("unit_evidence",span(user,m,"unit"));
  String parameter=UnitSourceModel.decimal(m.group("value")).stripTrailingZeros().toPlainString();
  if(m.group("unit")!=null){UnitSourceModel.Evidence u=units.at(user,m.start("unit"));if(u==null||u.end!=m.end("unit")||!u.normalized.equals(units.expected(cmd)))return failure("请补充探头倍率的正确单位。",e);parameter=new BigDecimal(parameter).multiply(u.factor).stripTrailingZeros().toPlainString();e.put("unit",u.json(1));}
  e.put("unique_authoritative_head",cmd);e.put("template",b.data.get("template"));e.put("parameter",parameter);
  // The caller still runs normal table, provenance, semantic safety and confirmation policy.
  return new Resolution(true,"resolved","unique_current_utterance_template",cmd,b.data.get("intent").toString(),parameter,b.data.get("template").toString(),e);
 }
 private static Resolution failure(String reason,Map<String,Object> e){return new Resolution(true,"clarify",reason,"","","","",e);}
 private static Map<String,Object> span(String text,Matcher m,String group){Map<String,Object> x=new LinkedHashMap<>();x.put("source_text",text.substring(m.start(group),m.end(group)));x.put("source_start",m.start(group));x.put("source_end",m.end(group));return x;}
}
