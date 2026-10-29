package com.jmu.text2scpi.tts;
import com.jmu.text2scpi.model.*;
import java.util.*;
import java.util.regex.*;
/**
 * 根据结构化事务结果生成中英文反馈。区分确认成功、仅写入、不一致及不确定，不直接朗读任意协议响应。
 */
public final class FeedbackText {
    public static final class Message {
        public final FeedbackLanguage language;
        public final String text;
        public Message(FeedbackLanguage language,String text) {
            this.language=language;
            this.text=text;
        }
    }
    private FeedbackText() {
    }
    /** 依据事务实际结果选择反馈模板，区分查询值、设置已确认、仅写入及不一致，再按偏好选择语言。 */
    public static Message result(FeedbackLanguage preference,String original,TransactionResult tx) {
        FeedbackLanguage language=FeedbackLanguage.select(preference,original);
        boolean en=language==FeedbackLanguage.ENGLISH;
        if(tx.deliveryState!=DeliveryState.CONFIRMED)return failure(language,original,tx.deliveryState);
        if(!tx.isQuery&&!tx.verified)return failure(language,original,DeliveryState.WRITTEN_UNVERIFIED);
        String command=tx.command.trim().toUpperCase(Locale.ROOT),head=command.split(" ",2)[0],raw=tx.isQuery?tx.response:tx.verifiedResponse;
        String channel=channel(head,language),value=raw==null?"":raw.trim();
        if(value.length()>160||value.contains("#")||value.contains("\n"))return screen(language);
        if(head.matches(":?(?:SELECT:CH[1-4]|CH[1-4]:DISPLAY)\\??")) {
            String enabled=value.equals("1")||value.equalsIgnoreCase("ON")?"on":value.equals("0")||value.equalsIgnoreCase("OFF")?"off":null;
            if(enabled!=null)return new Message(language,en?channel+" is "+(enabled.equals("on")?"enabled.":"disabled."):channel+(enabled.equals("on")?"已打开。":"已关闭。"));
        }
        if(head.matches(":?CH[1-4]:COUPLING\\??")) {
            String mode=mode(value,language);
            if(mode!=null)return new Message(language,en?channel+" coupling is "+mode+".":channel+"耦合"+(tx.isQuery?"为":"已设置为")+mode+"。");
        }
        String kind=null,label=null;
        if(head.matches(":?CH[1-4]:SCALE\\??")) {
            kind="voltage_per_div";
            label=en?"vertical scale":"垂直档位";
        }
        else if(head.matches(":?CH[1-4]:OFFSET\\??")) {
            kind="voltage";
            label=en?"offset":"偏移";
        }
        else if(head.matches(":?CH[1-4]:BANDWIDTH\\??")) {
            kind="frequency";
            label=en?"bandwidth":"带宽";
        }
        else if(head.contains("PERIOD")||head.contains("PERI")) {
            kind="time";
            label=en?"period":"周期";
        }
        else if(head.contains("FREQUENCY")||head.contains("FREQ")) {
            kind="frequency";
            label=en?"frequency":"频率";
        }
        if(kind!=null) {
            String quantity=SpeechNumbers.quantity(value,kind,language);
            if(quantity!=null)return new Message(language,en?(channel.isEmpty()?"":channel+" ")+label+" is "+quantity+".":channel+label+(tx.isQuery?"为":"已设置为")+quantity+"。");
        }
        if(tx.isQuery)return screen(language);
        return new Message(language,en?"Setting confirmed.":"已设置并确认。");
    }
    public static Message wake(){return new Message(FeedbackLanguage.CHINESE,"我在");}
    public static Message failure(FeedbackLanguage preference,String original,DeliveryState state) {
        FeedbackLanguage lang=FeedbackLanguage.select(preference,original);
        boolean en=lang==FeedbackLanguage.ENGLISH;
        String text;
        switch(state) {
            case MISMATCH:text=en?"The setting does not match. Please check the screen.":"回读与目标不一致，请查看屏幕。";
            break;
            case UNKNOWN:text="";
            break;
            case WRITTEN_UNVERIFIED:text=en?"Command sent.":"已发送。";
            break;
            default:text=en?"Please say that again.":"请再说一遍。";
        }
        return new Message(lang,text);
    }
    public static Message screen(FeedbackLanguage lang) {
        return new Message(lang,lang==FeedbackLanguage.ENGLISH?"Response received. Please check the screen.":"已收到响应，请查看屏幕。");
    }
    private static String channel(String command,FeedbackLanguage language) {
        Matcher m=Pattern.compile("(?:^|:)CH([1-4])(?:[:? ]|$)").matcher(command);
        if(!m.find())return "";
        return language==FeedbackLanguage.ENGLISH?"Channel "+SpeechNumbers.number(new java.math.BigDecimal(m.group(1)),language):"通道"+"一二三四".charAt(Integer.parseInt(m.group(1))-1);
    }
    private static String mode(String value,FeedbackLanguage language) {
        String v=value.toUpperCase(Locale.ROOT);
        if(v.equals("GND"))return language==FeedbackLanguage.ENGLISH?"ground":"接地";
        if(v.equals("AC"))return language==FeedbackLanguage.ENGLISH?"alternating current":"交流";
        if(v.equals("DC"))return language==FeedbackLanguage.ENGLISH?"direct current":"直流";
        return null;
    }
    public static List<Message> commonPhrases() {
        List<Message> messages=new ArrayList<>();
        for(FeedbackLanguage lang:new FeedbackLanguage[] {
            FeedbackLanguage.CHINESE,FeedbackLanguage.ENGLISH
        }
        ) {
            for(DeliveryState state:new DeliveryState[] {
                DeliveryState.NOT_SENT,DeliveryState.UNKNOWN,DeliveryState.WRITTEN_UNVERIFIED,DeliveryState.MISMATCH
            }
            )messages.add(failure(lang,"",state));
            messages.add(screen(lang));
            messages.add(new Message(lang,lang==FeedbackLanguage.ENGLISH?"Setting confirmed.":"已设置并确认。"));
            for(int ch=1;ch<=4;ch++) {
                for(String toggle:new String[] {
                    "ON","OFF"
                }
                ) {
                    TransactionResult tx=new TransactionResult(":SELect:CH"+ch+" "+toggle,false,toggle,0,null,null,toggle,true,DeliveryState.CONFIRMED);
                    messages.add(result(lang,"",tx));
                }
                for(String mode:new String[] {
                    "AC","DC","GND"
                }
                ) {
                    TransactionResult tx=new TransactionResult(":CH"+ch+":COUPling "+mode,false,mode,0,null,null,mode,true,DeliveryState.CONFIRMED);
                    messages.add(result(lang,"",tx));
                    TransactionResult query=new TransactionResult(":CH"+ch+":COUPling?",true,mode,0,null,null,null,false,DeliveryState.CONFIRMED);
                    messages.add(result(lang,"",query));
                }
            }
        }
        return messages;
    }
}
