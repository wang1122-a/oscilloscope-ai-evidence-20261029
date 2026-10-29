package com.jmu.text2scpi.voice;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import kotlinx.serialization.json.*;
/**
 * 语音识别文本的领域后处理。使用本地术语与纠错表，保存原始文本并给出领域匹配结果，供服务决定是否进入意图处理。
 */
public final class SpeechText {
    private final List<String> commands=new ArrayList<>(),negatives=new ArrayList<>();
    private final List<String[]> fixes=new ArrayList<>();
    private final JsonObject terms,actions,synonyms,negativeTerms;
    public SpeechText(Context context) {
        for(String s:lines(context,"commands.txt")) {
            if(s.startsWith("NOT:")) negatives.add(s.substring(4).trim());
            else commands.add(s);
        }
        for(String s:lines(context,"fixes.txt")) if(s.contains("→")) {
            String[] p=s.split("→",2);
            fixes.add(new String[] {
                p[0].trim(),p[1].trim()
            }
            );
        }
        fixes.sort((a,b)->Integer.compare(b[0].length(),a[0].length()));
        terms=json(context,"domain_terms");
        actions=json(context,"action_aliases");
        synonyms=json(context,"synonyms");
        negativeTerms=json(context,"negative_terms");
    }
    private static String read(Context c,String name) {
        try(InputStream in=c.getAssets().open("speech/text/"+name)) {
            return com.jmu.text2scpi.intent.AssetInstaller.readUtf8(in);
        }
        catch(IOException e) {
            throw new IllegalStateException("无法读取语音词典："+name,e);
        }
    }
    private static List<String> lines(Context c,String name) {
        List<String> list=new ArrayList<>();
        for(String s:read(c,name).split("\\R")) {
            s=s.trim();
            if(!s.isEmpty()&&!s.startsWith("#"))list.add(s);
        }
        return list;
    }
    private static JsonObject json(Context c,String name) {
        return (JsonObject)Json.Default.parseToJsonElement(read(c,name+".json"));
    }
    private static String content(JsonElement e) {
        return ((JsonPrimitive)e).getContent();
    }
    /** 保留原识别文字，去除句首唤醒词并应用领域纠错，返回修正文字及是否达到领域分数门槛。 */
    public SpeechTextResult process(String raw) {
        String original=raw.trim(),corrected=original.replaceFirst("(?i)^(?:(?:小欧[，,、 ]*){2}|hey\\s+owon)[，,、。.!！?？ ]*","");
        for(int i=0;i<2;i++) for(String[] fix:fixes) corrected=corrected.replace(fix[0],fix[1]);
        corrected=corrected.replaceFirst("^(?:请|麻烦|帮我|我想|我要)","");
        corrected=SpokenNumbers.normalize(corrected).replaceAll("^[ ，。！？.!?]+|[ ，。！？.!?]+$","");
        for(String negative:negatives) if(original.contains(negative)||corrected.contains(negative)) return new SpeechTextResult(original,corrected,false,-1000);
        int score=Math.max(score(corrected),score(original));
        return new SpeechTextResult(original,corrected,score>=4&&!corrected.trim().isEmpty(),score);
    }
    /** 结合术语、动作、通道单位及否定词计算领域相关分数；只判断是否像示波器指令，不生成 SCPI。 */
    private int score(String text) {
        List<String> surfaces=new ArrayList<>();
        Map<String,Integer> concepts=new HashMap<>();
        for(Map.Entry<String,JsonElement> entry:terms.entrySet()) for(JsonElement e:(JsonArray)entry.getValue()) {
            String word=content(e);
            if(text.contains(word)) {
                concepts.put(word,entry.getKey().equals("strong")?4:2);
                surfaces.add(word);
            }
        }
        for(Map.Entry<String,JsonElement> entry:synonyms.entrySet()) for(JsonElement e:(JsonArray)entry.getValue()) {
            String alias=content(e);
            if(text.contains(alias)) {
                concepts.putIfAbsent(entry.getKey(),2);
                surfaces.add(alias);
            }
        }
        int actionCount=0;
        for(JsonElement aliases:actions.values()) for(JsonElement e:(JsonArray)aliases) if(text.contains(content(e))) {
            actionCount++;
            break;
        }
        boolean params=Pattern.compile("CH\\d|通道\\d|\\d+(?:\\.\\d+)?\\s*(?:mV|MHz|kHz|Hz|V|伏|毫伏|秒|倍|格)",Pattern.CASE_INSENSITIVE).matcher(text).find();
        boolean hit=false;
        if(!text.isEmpty()) for(String command:commands) if(command.contains(text)||text.contains(command)) {
            hit=true;
            break;
        }
        int negative=0;
        for(Map.Entry<String,JsonElement> entry:negativeTerms.entrySet()) {
            String word=entry.getKey();
            boolean longer=false;
            for(String surface:surfaces) if(surface.length()>word.length()&&surface.contains(word)) {
                longer=true;
                break;
            }
            if(text.contains(word)&&!longer) negative+=Integer.parseInt(content(entry.getValue()));
        }
        List<Integer> scores=new ArrayList<>(concepts.values());
        scores.sort(Collections.reverseOrder());
        int sum=0;
        for(int i=0;i<Math.min(3,scores.size());i++)sum+=scores.get(i);
        return Math.min(2,actionCount)+sum+(params?1:0)+(hit?3:0)+negative;
    }
}
