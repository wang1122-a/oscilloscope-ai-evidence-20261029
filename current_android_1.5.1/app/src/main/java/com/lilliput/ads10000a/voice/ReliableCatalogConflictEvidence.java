package com.lilliput.ads10000a.voice;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Positive current-request object/property/choice evidence only. This detector does not
 * generate a command, choose a missing enum, rewrite ASR, or turn a failed parser into a veto.
 * null means uncovered, not proof that the model's semantics are correct.
 */
final class ReliableCatalogConflictEvidence {
    static final String VERSION="reliable_catalog_conflict_evidence_v1";
    private static final int FLAGS=Pattern.CASE_INSENSITIVE|Pattern.DOTALL;
    private static final Map<String,Pattern> CUES=new HashMap<>();
    static {
        cue("counter","\\b(?:counter|frequency\\s+counter)\\b|计数器|频率计");
        cue("dvm","\\b(?:dvm|voltmeter|digital\\s+voltage\\s+meter)\\b|数字电压表|万用表|电压表");
        cue("afg","\\b(?:afg|function\\s+generator)\\b|函数发生器");
        cue("reference","\\breference\\s+(?:waveform|[0-9]+|zero|one|two|three|four|first|second|third|fourth)\\b|\\bREF[0-9]+\\b|参考波形");
        cue("math","\\bmath(?:ematical)?(?:\\s+waveform)?\\b|\\bMATH[0-9]+\\b|数学波形");
        cue("digital","\\bdigital\\s+(?:input|channel|line|trace)\\b|\\bD[0-9]+\\b|数字(?:输入|通道|路)|逻辑通道");
        cue("trigger","\\btrigger(?:ing)?\\b|触发");
        cue("acquire","\\bacqui(?:re|sition)\\b|采集");
        cue("zoom","\\bzoom\\b|缩放视图|缩放窗口|放大窗口");
        cue("timebase","\\btime\\s*base\\b|\\btimebase\\b|主时基|水平时基|横轴.*每格.*时间|每格时间");
        cue("coupling","\\bcoupling\\b|耦合");
        cue("scale","\\b(?:vertical\\s+)?scale\\b|\\bvolts?\\s*(?:/|per|a|each)\\s*(?:division|div)\\b|垂直刻度|垂直档位|每格.*伏|每格(?:对应|表示)|通道.{0,6}刻度");
        cue("display","\\bdisplay\\s+(?:state|status)\\b|显示状态");
        cue("position","\\b(?:vertical\\s+)?position\\b|垂直位置");
        cue("bandwidth","\\bbandwidth\\b|带宽");
        cue("mode","\\b(?:mode|method|processing)\\b|模式|采集方式|采集方法");
        cue("state","\\b(?:state|status|enabled|running|stopped)\\b|状态|是否.*(?:开启|启用|运行)|启用状态|开还是关");
        cue("source","\\bsource\\b|来源|信源|测量源");
        cue("units","\\bunits?\\b|单位");
        cue("statistics","\\bstatistics?\\b|统计");
        cue("maximum","\\bmaximum\\b|最大值");
        cue("minimum","\\bminimum\\b|最小值");
        cue("average","\\baverage\\b|平均值");
        cue("value","\\b(?:value|reading|readout|result)\\b|当前数值|当前值|读数|测量值|测量结果");
        cue("graticule","\\bgraticule\\b|\\bgrid\\s+(?:style|pattern)\\b|格线|屏幕网格|网格样式");
        cue("axis","\\baxis\\s+labels?\\b|坐标轴标签|轴标签");
        cue("withyt","XY.{0,12}YT.{0,12}(?:同时|同显|显示|together|simultaneous)|XY.{0,12}(?:with|and)\\s*YT");
        cue("image","\\b(?:image|screenshot)\\b|图像|图片|截图");
        cue("waveform","\\bwaveform\\b|波形");
        cue("save","\\b(?:save|saving|store|storing)\\b|保存|存储");
        cue("layout","\\blayout\\b|布局");
        cue("format","\\b(?:file\\s*)?format\\b|格式");
        cue("operation","\\boperations?\\b|操作");
        cue("complete","\\b(?:complete|completion|finish(?:es|ed)?)\\b|完成|结束");
        cue("wait","\\bwait\\b|等待|等候");
        cue("measurement","\\bmeasurements?\\b|测量");
        cue("global","\\b(?:entire|whole|global)\\b|整个|整体|全局|测量系统|measurement\\s+system");
        cue("read","\\b(?:read|query|check|what|which|whether|tell\\s+me)\\b|查询|读取|读出|查一下|是多少|是否|还是");
        cue("set","\\b(?:set|change|adjust|choose|select|use|turn|switch|enable|disable|resume|stop)\\b|设置|设为|改为|改成|更改|改用|换为|选择|选|用|打开|关闭|停止");
        cue("undecided","\\b(?:unspecified|undecided|unresolved)\\b|(?:not|haven't|have\\s+not)\\s+(?:yet\\s+)?(?:decided|specified|chosen)|尚未决定|未决定|未指定|尚未选择|待确定|还没选");
    }
    private static void cue(String key,String expression) { CUES.put(key,Pattern.compile(expression,FLAGS)); }
    private static boolean has(String text,String key) { return CUES.get(key).matcher(text).find(); }
    private static boolean match(String text,String expression) { return Pattern.compile(expression,FLAGS).matcher(text).find(); }
    private static String require(boolean evidence,String head,String allowed,String reason) {
        return evidence&&!head.matches(allowed)?"explicit_"+reason+"_conflict":null;
    }
    private ReliableCatalogConflictEvidence() {}
    static String issue(String original,String command,Object parameter,SemanticLanguage language,
                        ValidatedExplicitContext context,ScpiTableEligibility table) {
        if(original==null||command==null||language==null||table==null) return null;
        String text=currentSurface(original,language);
        String head=table.canonicalHead(command.trim()).toUpperCase(Locale.ROOT).replaceFirst("\\?$","");
        boolean query=command.trim().endsWith("?");
        String missing=missingChoice(text,head,query,table);
        if(missing!=null){ExistingMeasurementRequest bound=context==null?null:ExistingMeasurementRequest.parse(original,context,table);boolean exactExisting=bound!=null&&bound.issue==null&&bound.command.equalsIgnoreCase(table.canonicalHead(command))&&Objects.equals(bound.parameter,parameter);if(!(missing.equals("request_missing_named_object_index")&&exactExisting))return missing;}
        boolean clearGlobal=head.equals("MEASUREMENT:STATE")&&"OFF".equals(parameter)&&"MEASURE_CLEAN".equals(EnglishRequestCompiler.derivedIntent(original))&&!EnglishRequestCompiler.explicitQuery(original);
        String object=objectOrProperty(text,head,query,clearGlobal);
        if(object!=null) return object;
        String source=sourceChoice(text,head,parameter,query);
        if(source!=null) return source;
        if(context!=null) {
            ExistingMeasurementRequest known=ExistingMeasurementRequest.parse(original,context,table);
            if(known!=null&&known.issue==null) {
                String wanted=table.canonicalHead(known.command).toUpperCase(Locale.ROOT);
                if(!wanted.equals(table.canonicalHead(command).toUpperCase(Locale.ROOT))
                        ||!table.qualify(known.command,known.parameter).canonical.equalsIgnoreCase(
                        table.qualify(command,parameter).canonical)) {
                    return "explicit_existing_measurement_catalog_conflict";
                }
            }
        }
        if(!query&&parameter instanceof String) return enumChoice(text,head,(String)parameter,table);
        return null;
    }

    static String currentSurface(String original,SemanticLanguage language) {
        String scoped=language==SemanticLanguage.EN?EnglishRequestCompiler.scopeSurface(original):PcSemanticGuard.chineseScopeSurface(original);
        return activeClauses(Normalizer.normalize(ModelLedConstraintGuard.correctionSurface(scoped,language),Normalizer.Form.NFKC));
    }

    private static String activeClauses(String text) {
        String[] clauses=text.split("[;；。!?！？]|(?<![0-9])\\.(?![0-9])");
        List<String> current=new ArrayList<>();
        for(String clause:clauses) {
            if(has(clause,"read")||has(clause,"set")) {
                boolean historical=match(clause,"\\b(?:already|previously)\\b|已经|曾经");
                if(!historical||has(clause,"read")) current.add(clause);
            }
        }
        // A nominal property/enum frame stays available. A non-command fact is removed
        // only when another clause actually supplies an explicit current operation.
        return current.isEmpty()?text:String.join(" ",current);
    }

    private static String objectOrProperty(String s,String h,boolean query,boolean clearGlobal) {
        boolean counter=has(s,"counter"),dvm=has(s,"dvm"),afg=has(s,"afg"),ref=has(s,"reference"),math=has(s,"math");
        boolean digital=has(s,"digital"),trigger=has(s,"trigger"),acquire=has(s,"acquire"),zoom=has(s,"zoom"),autoset=match(s,"\\bautoset\\b|automatic\\s+setup|自动设置");
        String problem;
        if(h.equals("ACQUIRE:STATE")&&match(s,"\\b(?:restart|pause|stop)\\b.{0,40}\\b(?:display(?:ing)?|(?:CH[1-4]|channel\\s+[a-z0-9]+)\\s+trace)\\b"))return "explicit_display_vs_acquisition_action_conflict";
        if(h.equals("*CLS")&&!analogSources(s).isEmpty())return "explicit_analog_vs_global_status_object_conflict";
        if(!afg&&match(s,"(?:打开|开启|关闭|关掉|隐藏)\\s*(?:第?[一二两三四1-4](?:路|通道)|通道[一二两三四1-4]|CH[1-4])")&&!h.matches("SELECT:CH[1-4]"))return "explicit_channel_display_action_object_conflict";
        if(!query&&match(s,"(?:并|然后|同时|再|和).{0,3}(?:查询|读取|查)(?:一下)?(?:主)?时基"))return "explicit_additional_query_operation_conflict";
        if(match(s,"(?:另做|再做|另执行|再执行).{0,12}未知操作|\\b(?:also|then)\\s+(?:do|perform|execute)\\s+(?:an?\\s+)?unknown\\s+operation\\b"))return "explicit_additional_unknown_operation";
        if(match(s,"水平延迟|\\bhorizontal\\s+delay\\b")&&!h.equals(has(s,"mode")?"HORIZONTAL:DELAY:MODE":"HORIZONTAL:DELAY:TIME"))return "explicit_horizontal_delay_property_conflict";
        if(counter&&!dvm&&!h.equals("COUNTER")&&!h.startsWith("COUNTER:")) return "explicit_counter_subsystem_conflict";
        if(dvm&&!counter&&!h.startsWith("DVM")) return "explicit_dvm_subsystem_conflict";
        if(afg&&!h.startsWith("AFG"))return "explicit_function_generator_subsystem_conflict";
        if(afg&&h.matches("AFG:OUTPUT[12]:.*")){
            Set<Integer> outputs=analogSources(s);int output=h.charAt("AFG:OUTPUT".length())-'0';
            if(!outputs.isEmpty()&&(outputs.size()!=1||!outputs.contains(output)))return "explicit_function_generator_output_index_conflict";
        }
        if(ref&&!math&&!h.startsWith("REF")) return "explicit_reference_subsystem_conflict";
        if(math&&!ref&&!h.matches("(?:MATH|FFT).*")) return "explicit_math_subsystem_conflict";
        if(digital&&h.matches("(?:CH[0-9]+|SELECT:CH[0-9]+).*")) return "explicit_digital_vs_analog_object_conflict";
        if(counter||dvm) {
            String prefix=counter?"COUNTER":"DVM";
            if(has(s,"units")&&!h.matches(prefix+":UNITS")) return "explicit_unit_read_property_conflict";
            if(has(s,"source")&&!h.matches(prefix+":SOURCE")) return "explicit_source_property_conflict";
            if(has(s,"statistics")&&has(s,"state")&&!h.matches(prefix+":STATISTIC(?:S|E):STATE"))
                return "explicit_statistics_state_property_conflict";
            if(has(s,"mode")&&!h.matches(prefix+":MODE")) return "explicit_subsystem_mode_property_conflict";
            if(has(s,"maximum")&&!h.matches(prefix+":STATISTICS:MAXIMUM")) return "explicit_statistics_maximum_property_conflict";
            if(has(s,"minimum")&&!h.matches(prefix+":STATISTICS:MINIMUM")) return "explicit_statistics_minimum_property_conflict";
            if(has(s,"average")&&!h.matches(prefix+":STATISTICS:AVERAGE")) return "explicit_statistics_average_property_conflict";
        }
        if(ref&&has(s,"save")&&!has(s,"source")&&!has(s,"read")&&(!h.matches("REF[0-9]+")||query))
            return "explicit_reference_save_vs_property_conflict";
        if(acquire) {
            boolean fast=match(s,"\\bfast\\s*(?:acquisition|acq)\\b|快速采集");
            if(fast&&has(s,"state")&&!h.equals("ACQUIRE:FASTACQ:STATE"))return "explicit_fast_acquisition_state_property_conflict";
            if(match(s,"\\baveraging\\s+count\\b|平均次数|平均计数")&&!h.equals("ACQUIRE:NUMAVG"))return "explicit_acquisition_count_property_conflict";
            if(has(s,"mode")&&!h.equals("ACQUIRE:MODE")) return "explicit_acquisition_mode_property_conflict";
            if(!fast&&!has(s,"mode")&&has(s,"state")&&!h.equals("ACQUIRE:STATE")) return "explicit_acquisition_state_property_conflict";
        }
        if(has(s,"timebase")&&!zoom){
            String property=match(s,"\\bsample\\s*rate\\b|采样率")?"HORIZONTAL:SAMPLERATE":match(s,"\\brecord\\s*length\\b|记录长度")?"HORIZONTAL:RECORDLENGTH":match(s,"\\bdelay\\b|延迟")?(has(s,"mode")?"HORIZONTAL:DELAY:MODE":"HORIZONTAL:DELAY:TIME"):match(s,"\\broll(?:ing)?\\b|滚动")?"HORIZONTAL:ROLL":"HORIZONTAL:SCALE";
            if(!h.equals(property))return "explicit_main_timebase_property_conflict";
        }
        if(trigger) {
            if(match(s,"\\bB\\s*trigger\\b|B触发")&&h.startsWith("TRIGGER:A:"))return "explicit_trigger_bank_conflict";
            if(match(s,"trigger\\s+level|触发电平")){
                boolean automatic=ModelLedConstraintGuard.nativeAutomaticTriggerLevel(s);
                if(automatic&&!h.equals("TRIGGER:A")||!automatic&&!h.matches("TRIGGER:A:LEVEL:(?:CH[1-4]|EXT)"))return "explicit_trigger_level_property_conflict";
            }
            if(has(s,"coupling")&&!h.equals("TRIGGER:A:EDGE:COUPLING")) return "explicit_trigger_coupling_property_conflict";
            if(!has(s,"coupling")&&has(s,"mode")&&!h.equals("TRIGGER:A:MODE")) return "explicit_trigger_mode_property_conflict";
        }
        boolean analog= !digital&&!ref&&!math&&!counter&&!dvm&&!afg;
        boolean label=match(s,"\\blabel\\b|标签");
        if(analog&&label&&has(s,"display")&&!h.matches("CH[0-9]+:LABEL:DISPLAY"))return "explicit_channel_label_display_property_conflict";
        if(analog&&!analogSources(s).isEmpty()&&match(s,"(?:波形(?:的)?显示|通道.{0,8}显示).{0,12}(?:关掉|关闭|打开|开启)")&&!h.matches("SELECT:CH[1-4]"))return "explicit_analog_display_state_property_conflict";
        boolean divisionDisplay=analog&&match(s,"\\bdisplay\\b.{0,40}\\b(?:divisions?|div)\\b");
        if(divisionDisplay&&!h.matches("CH[0-9]+:SCALE"))return "explicit_division_quantity_vs_display_switch_conflict";
        if(analog&&!label&&query&&has(s,"display")&&!analogSources(s).isEmpty()
                &&!h.matches("(?:SELECT:CH[0-9]+|CH[0-9]+:DISPLAY)"))
            return "explicit_channel_display_read_property_conflict";
        if(analog&&!trigger&&!autoset&&has(s,"coupling")&&!h.matches("CH[0-9]+:COUPLING"))
            return "explicit_analog_coupling_property_conflict";
        if(!trigger&&!acquire&&!counter&&!dvm&&!autoset) {
            if(has(s,"position")&&!h.contains("POSITION")) return "explicit_position_property_conflict";
            if(has(s,"scale")&&!has(s,"position")&&!has(s,"timebase")&&!has(s,"graticule")
                    &&!h.contains("SCALE")) return "explicit_scale_property_conflict";
            if(analog&&!zoom&&has(s,"scale")&&!has(s,"position")&&!analogSources(s).isEmpty()&&h.startsWith("ZOOM:VERTICAL:"))return "explicit_main_channel_vs_zoom_scale_conflict";
            if(has(s,"bandwidth")&&!h.matches("CH[0-9]+:BANDWIDTH")) return "explicit_bandwidth_property_conflict";
        }
        if((problem=require(has(s,"graticule"),h,"DISPLAY:GRATICULE","graticule_property"))!=null) return problem;
        if((problem=require(has(s,"axis"),h,"DISPLAY:AXISLABEL","axis_label_property"))!=null) return problem;
        if((problem=require(has(s,"withyt"),h,"DISPLAY:XY:WITHYT","xy_with_yt_property"))!=null) return problem;
        if(has(s,"save")) {
            if(!query&&h.matches("SAVE:(?:IMAGE|WAVEFORM):.*")
                    &&match(s,"\\b(?:save|store)\\s+(?:the\\s+)?(?:waveform|image|screenshot)\\b.{0,80}\\b(?:as|to|into)\\b.{0,80}\\b(?:file|filename|disk)\\b|(?:把|将)?(?:当前)?(?:波形|图像|图片).{0,30}(?:保存|存储)(?:为|到|成).{0,30}(?:文件|磁盘|路径)"))
                return "explicit_save_file_vs_configuration_conflict";
            if(has(s,"image")&&has(s,"layout")&&!h.equals("SAVE:IMAGE:LAYOUT")) return "explicit_image_layout_property_conflict";
            if(has(s,"image")&&has(s,"format")&&!h.equals("SAVE:IMAGE:FILEFORMAT")) return "explicit_image_format_property_conflict";
            if(has(s,"waveform")&&!ref&&has(s,"format")&&!h.equals("SAVE:WAVEFORM:FILEFORMAT"))
                return "explicit_waveform_format_property_conflict";
        }
        if(has(s,"operation")&&has(s,"complete")) {
            boolean flag=match(s,"\\b(?:operation[- ]complete|completion)\\s+flag\\b|完成标志");
            boolean setFlag=flag&&match(s,"\\b(?:set|assert|raise)\\b.{0,40}\\bflag\\b|置位|设置.{0,30}完成标志");
            if(setFlag&&!has(s,"read")&&(query||!h.equals("*OPC")))return "explicit_operation_completion_flag_action_conflict";
            if(has(s,"read")&&!has(s,"wait")&&!query)return "explicit_completion_query_vs_action_conflict";
            if(!flag&&!query&&has(s,"wait")&&!h.equals("*WAI")) return "explicit_wait_operation_property_conflict";
            if(query&&!h.equals("*OPC")) return "explicit_operation_completion_query_object_conflict";
        }
        if(has(s,"measurement")) {
            if(has(s,"global")&&has(s,"state")&&!h.equals("MEASUREMENT:STATE"))
                return "explicit_measurement_global_state_object_conflict";
            boolean named=match(s,"\\b(?:MEAS[0-9]+|measurement\\s+(?:(?:slot|number|item)\\s+)?[a-z0-9]+)\\b|(?:第)?[零一二三四五六七八九0-9]+(?:测量项|项测量)");
            if(named&&!has(s,"global")&&!clearGlobal) {
                if(has(s,"state")&&!h.matches("MEASUREMENT:MEAS[0-9]+:STATE")) return "explicit_measurement_state_property_conflict";
                if(has(s,"value")&&!has(s,"state")&&!h.matches("MEASUREMENT:MEAS[0-9]+:VALUE")) return "explicit_measurement_value_property_conflict";
            }
            if(match(s,"\\bmeasurement\\s+(?:(?:slot|number|item)\\s+)?([a-z0-9]+)\\s+(?:and|or)\\s+([a-z0-9]+)\\b|[一二三四五六七八0-9]+和[一二三四五六七八0-9]+(?:测量项|项测量)"))
                return "request_explicitly_ambiguous_measurement_slots";
        }
        return null;
    }

    private static String sourceChoice(String s,String head,Object parameter,boolean query) {
        if(query||!(parameter instanceof String)||!head.matches(".*(?:SOURCE[12]?|XSOURCE|YSOURCE)$")) return null;
        Set<String> chosen=new LinkedHashSet<>();
        Matcher digital=Pattern.compile("(?i)(?<![A-Za-z0-9])D([0-9]+)(?![A-Za-z0-9])").matcher(s);
        while(digital.find()) chosen.add("D"+digital.group(1));
        for(Integer channel:analogSources(s)) chosen.add("CH"+channel);
        if(chosen.size()>1) return "request_explicitly_ambiguous_source_choices";
        if(chosen.size()==1&&!chosen.contains(((String)parameter).toUpperCase(Locale.ROOT)))
            return "explicit_catalog_source_parameter_conflict";
        return null;
    }

    private static Set<Integer> analogSources(String text) {
        if(Pattern.compile("\\p{IsHan}").matcher(text).find())return PcSemanticGuard.analogSources(text);
        Set<Integer> result=new LinkedHashSet<>();
        Matcher channel=EnglishChannelEvidence.PATTERN.matcher(text);
        while(channel.find()) if(EnglishChannelEvidence.analog(channel,text))
            result.add(EnglishChannelEvidence.index(channel));
        return result;
    }

    private static String missingChoice(String s,String head,boolean query,ScpiTableEligibility table) {
        boolean enumProperty=!table.describeForNarration(head).enumValues.isEmpty();
        if(enumProperty&&has(s,"set")&&!has(s,"read")&&has(s,"undecided"))
            return "request_explicitly_missing_enum_choice";
        if(enumProperty&&!query&&match(s,"(?:模式|耦合|格线).{0,12}(?:切成|改成|设为|选择)(?:什么|哪种)|^\\s*采集状态(?:请)?(?:更改|修改|改)(?:一下)?[。.!！?？]?\\s*$|^\\s*(?:please\\s+)?(?:choose|select)\\s+(?:the\\s+)?coupling\\s+for\\s+(?:"+EnglishChannelEvidence.EXPRESSION+")[.!?]?\\s*$"))return "request_explicitly_missing_enum_choice";
        // These are specific syntactically empty choices. Unknown tails cannot match them.
        if(enumProperty&&s.trim().matches("(?is)(?:please\\s+)?(?:change|adjust|choose|select)\\s+(?:the\\s+|an?\\s+)?(?:acquisition\\s+state|trigger(?:ing)?\\s+mode|(?:display\\s+)?graticule(?:\\s+style)?)[.!?]?")
                ||enumProperty&&s.trim().matches("(?:请|麻烦|帮我)?(?:更改|修改|改变|选择)(?:一下)?(?:屏幕)?(?:格线|网格)(?:样式)?(?:一下)?[。.!！?？]?"))
            return "request_explicitly_missing_enum_choice";
        if(query&&s.trim().matches("(?is)(?:please\\s+)?(?:read|query|check)\\s+(?:the\\s+)?current\\s+(?:state|status)[.!?]?|(?:请)?(?:读取|查询|查看)(?:当前|现在)(?:状态)[。.!！?？]?"))
            return "request_explicitly_ambiguous_query_object";
        if(match(s,"(?:尚未|还没|未)(?:指明|指定|选择|选).{0,8}(?:哪个|哪一|哪路).*通道|(?:哪个|哪一|哪路).*通道.{0,8}(?:尚未|还没|未)(?:指明|指定|选择|选)"))return "request_explicitly_missing_source_choice";
        return null;
    }

    private static String enumChoice(String original,String head,String parameter,ScpiTableEligibility table) {
        List<String> allowed=table.describeForNarration(head).enumValues;
        if(allowed.isEmpty()) return null;
        String s=original;
        Matcher replaceTarget=Pattern.compile("(?i)\\breplace\\b[^;.!?]{0,100}\\bwith\\b").matcher(s);if(replaceTarget.find())s=s.substring(replaceTarget.end());
        if(head.endsWith(":COUPLING")){
            Matcher replacement=Pattern.compile("(?i)\\breplace\\s+(?<old>AC|DC|GND|ground)(?:\\s+coupling)?\\s+with\\s+(?:AC|DC|GND|ground)(?:\\s+coupling)?").matcher(s);
            if(replacement.find())s=s.substring(0,replacement.start("old"))+replacement.group("old").replaceAll("."," ")+s.substring(replacement.end("old"));
        }
        Matcher chosen=Pattern.compile("(?i)\\b(?:to|as)\\b|设为|设成|改为|改成|改用|调成|调为|调整为|换为|换成|选为|选择").matcher(s);
        int latest=-1;while(chosen.find())latest=chosen.end();
        if(latest>=0) s=s.substring(latest);
        Set<String> values=new LinkedHashSet<>();
        // "full grid" is one authoritative value; mask only that compound occurrence so
        // "full grid or grid" still contains the second independent GRID choice.
        if(head.equals("DISPLAY:GRATICULE")) {
            // A leading property label before a delimiter is the object, not a GRID value.
            // Subsequent independent enum choices are still checked (grid, full or frame).
            s=s.replaceFirst("(?is)^\\s*(?:the\\s+)?(?:grid|graticule)\\s*[,=:]\\s*", "");
            s=s.replaceAll("(?i)\\b(?:display|screen|waveform)\\s+grid\\b|屏幕(?:网格|格线)"," ");
            Matcher compound=Pattern.compile("(?i)\\bfull\\s+grid\\b|全网格").matcher(s);
            StringBuffer masked=new StringBuffer();
            while(compound.find()) {
                values.add("FULL");
                compound.appendReplacement(masked,Matcher.quoteReplacement(compound.group().replaceAll("."," ")));
            }
            compound.appendTail(masked);s=masked.toString();
        }
        boolean booleanValues=allowed.stream().anyMatch(v->v.equalsIgnoreCase("ON"))
                &&allowed.stream().anyMatch(v->v.equalsIgnoreCase("OFF"));
        if(booleanValues) {
            s=s.replaceAll("(?:启用|开启|显示|运行|停止)(?=状态)","  ");
            boolean negativeCapability=head.startsWith("AUTOSET:")&&match(s,"(?:不要|禁止|不允许|别)(?:让|使)?[^，。;；]*(?:打开|开启|启用|保留)|\\b(?:do\\s+not|don't|disable)\\b.{0,60}\\b(?:open|enable|keep)\\b");
            if(negativeCapability){values.add("OFF");s=s.replaceAll("打开|开启|启用|保留|(?i)\\b(?:open|enable|keep)\\b"," ");}
            if(match(s,"\\b(?:turn|switch)\\s+on\\b|\\b(?:enable|activate|show)\\b|打开|开启|启用"))values.add("ON");
            if(match(s,"\\b(?:turn|switch)\\s+off\\b|\\b(?:disable|deactivate|hide)\\b|关闭|关掉|停用|禁用|隐藏"))values.add("OFF");
            if(match(s,"\\b(?:between|choice|state|mode|or)\\b|还是|或")) {
                if(match(s,"\\bon\\b"))values.add("ON");
                if(match(s,"\\boff\\b"))values.add("OFF");
            }
        } else for(String value:allowed) {
            if(match(s,"(?<![A-Za-z0-9])"+Pattern.quote(value.replace("\"",""))+"(?![A-Za-z0-9])"))values.add(value.toUpperCase(Locale.ROOT));
        }
        for(String[] alias:aliases(head)) if(match(s,alias[1])) values.add(alias[0]);
        if(values.size()>1) return "request_explicitly_ambiguous_enum_choices";
        if(values.size()==1) {
            String value=values.iterator().next();
            ScpiTableEligibility.Decision requested=table.qualify(head,value);
            if(requested.status!=ScpiTableEligibility.Status.IN_TABLE_SET&&requested.status!=ScpiTableEligibility.Status.IN_TABLE_ACTION)
                return "illegal_requested_catalog_enum";
            ScpiTableEligibility.Decision actual=table.qualify(head,parameter);
            if(!requested.canonical.equalsIgnoreCase(actual.canonical)) return "explicit_catalog_enum_parameter_conflict";
        }
        return null;
    }

    private static String[][] aliases(String head) {
        if(head.endsWith(":COUPLING"))return new String[][]{{"AC","\\balternating[- ]current\\b|交流"},{"DC","\\bdirect[- ]current\\b|直流"},{"GND","\\b(?:ground|GND)\\b|接地"},{"HFREJ","high[- ]frequency\\s+reject|高频抑制"}};
        if(head.equals("DISPLAY:GRATICULE"))return new String[][]{{"CROSSHAIR","cross[- ]?hair|十字线"},{"FRAME","\\bframe\\b|边框"},{"GRID","(?<!full )\\bgrid\\b|(?<!全)网格"},{"FULL","\\bfull\\s+grid\\b|全网格"},{"NONE","无网格"}};
        if(head.equals("TRIGGER:A:MODE"))return new String[][]{{"NORMAL","\\bnormal\\b|正常"},{"AUTO","\\bauto(?:matic)?\\b|自动"},{"SINGLE","\\bsingle\\b|单次"}};
        if(head.equals("ACQUIRE:STATE"))return new String[][]{{"RUN","\\b(?:run|start|resume|restart)\\b|启动|开始|恢复|重新采集"},{"STOP","\\b(?:stop|pause|freeze|halt)\\b|停止|暂停|冻结"}};
        if(head.equals("ACQUIRE:MODE"))return new String[][]{{"SAMPLE","\\bsampl(?:e|ing)\\b|采样"},{"AVERAGE","\\baverag(?:e|ing)\\b|平均"},{"PEAK","\\bpeak\\b|峰值"},{"HIRES","high[- ]resolution|高分辨率"}};
        if(head.startsWith("NAVIGATE:"))return new String[][]{{"CENTER","\\bcenter\\b|中心"}};
        return new String[0][];
    }
}
