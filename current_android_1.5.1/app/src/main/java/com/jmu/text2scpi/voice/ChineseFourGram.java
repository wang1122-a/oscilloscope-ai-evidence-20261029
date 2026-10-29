package com.jmu.text2scpi.voice;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Restricted homophone candidate ranking over text, independent of acoustic decoding. */
public final class ChineseFourGram {
    private static final Pattern TOKEN=Pattern.compile("[\\u3400-\\u9fff]|[A-Za-z]+|[+-]?\\d+(?:\\.\\d+)?");
    private static final String PROTECTED="不没勿无别莫非否禁负正零〇一二两三四五六七八九十百千万亿点第秒伏毫微纳皮安赫兹欧姆度格倍兆吉";
    private static final String[] LITERALS={"文件名","命名","保存为","重命名","路径","目录","标签","注释","字符串","\"","'","“","”","‘","’","`"};
    private final Map<String,Integer> counts=new HashMap<>();
    private final Map<String,Context> contexts=new HashMap<>();
    private final Map<Character,Set<String>> phones=new HashMap<>();
    private final Set<String> terms=new TreeSet<>();
    private int total,vocabulary;
    private static final class Context {int total;final Set<String> next=new HashSet<>();}
    public static final class Decision {
        public final String original,text,reason;public final double gain;
        Decision(String raw,String selected,String why,double g){original=raw;text=selected;reason=why;gain=g;}
    }
    public ChineseFourGram(File directory)throws IOException {
        try(BufferedReader in=reader(new File(directory,"counts.tsv"))){
            if(!"#XIAOU_ZH_4GRAM_COUNTS_V1".equals(in.readLine()))throw new IOException("Invalid Chinese LM counts");
            String line;while((line=in.readLine())!=null){String[] row=line.split("\t");if(row.length<2||row.length>5)throw new IOException("Invalid LM order");
                int n=Integer.parseInt(row[0]);if(n<1)throw new IOException("Invalid LM count");
                List<String> sequence=new ArrayList<>();for(int i=1;i<row.length;i++)sequence.add(new String(Base64.getUrlDecoder().decode(row[i]),StandardCharsets.UTF_8));
                if(counts.put(key(sequence),n)!=null)throw new IOException("Duplicate LM count");
                if(sequence.size()==1){total+=n;vocabulary++;}
                else {String history=key(sequence.subList(0,sequence.size()-1));Context c=contexts.computeIfAbsent(history,k->new Context());c.total+=n;c.next.add(sequence.get(sequence.size()-1));}
            }
        }catch(RuntimeException e){throw new IOException("Invalid Chinese LM",e);}
        try(BufferedReader in=reader(new File(directory,"phones.tsv"))){
            if(!"#XIAOU_ZH_PHONES_V1".equals(in.readLine()))throw new IOException("Invalid phonetic table");
            String line;while((line=in.readLine())!=null){String[] row=line.split("\t");if(row.length!=2||row[0].length()!=1)throw new IOException("Invalid phone row");phones.put(row[0].charAt(0),new HashSet<>(Arrays.asList(row[1].split(","))));}
        }
        try(BufferedReader in=reader(new File(directory,"terms.tsv"))){
            if(!"#XIAOU_ZH_TERMS_V1".equals(in.readLine()))throw new IOException("Invalid term table");
            String line;while((line=in.readLine())!=null){String[] row=line.split("\t");if(row.length!=3)throw new IOException("Invalid term row");if(Integer.parseInt(row[2])>=3&&row[1].matches("[\\u3400-\\u9fff]{2,4}"))terms.add(row[1]);}
        }
        if(total<=0||vocabulary<10||terms.isEmpty())throw new IOException("Empty Chinese LM");
    }
    private static BufferedReader reader(File file)throws IOException{return new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8));}
    private static String key(List<String> words){return String.join("\u001f",words);}
    private int count(List<String> words){return counts.getOrDefault(key(words),0);}
    private static List<String> tokens(String text){List<String> out=new ArrayList<>();Matcher m=TOKEN.matcher(text);while(m.find())out.add(m.group());return out;}
    private double score(String text){
        List<String> seq=new ArrayList<>(Arrays.asList("<s>","<s>","<s>"));seq.addAll(tokens(text));seq.add("</s>");double score=0;
        for(int i=3;i<seq.size();i++){
            double p=(count(seq.subList(i,i+1))+.1)/(total+.1*(vocabulary+1));
            for(int length=1;length<=3;length++){Context c=contexts.get(key(seq.subList(i-length,i)));if(c!=null){double denom=c.total+c.next.size();p=count(seq.subList(i-length,i+1))/denom+c.next.size()/denom*p;}}
            score+=Math.log(Math.max(p,1e-30));
        }return score;
    }
    private boolean homophone(String a,String b){
        int changed=0;for(int i=0;i<a.length();i++)if(a.charAt(i)!=b.charAt(i)){
            if(++changed>2||PROTECTED.indexOf(a.charAt(i))>=0||PROTECTED.indexOf(b.charAt(i))>=0)return false;
            Set<String> x=phones.get(a.charAt(i)),y=phones.get(b.charAt(i));if(x==null||y==null||Collections.disjoint(x,y))return false;
        }return changed>0;
    }
    // Only an observed four-token context covering a changed token qualifies.
    private int support(String text,Set<Integer> changed){
        List<String> seq=new ArrayList<>(Arrays.asList("<s>","<s>","<s>"));List<Integer> offsets=new ArrayList<>(Arrays.asList(-1,-1,-1));
        Matcher m=TOKEN.matcher(text);while(m.find()){seq.add(m.group());offsets.add(m.start());}seq.add("</s>");offsets.add(-1);int best=0;
        for(int i=3;i<seq.size();i++){boolean covers=false;for(int j=i-3;j<=i;j++)covers|=changed.contains(offsets.get(j));if(covers&&!seq.subList(i-3,i+1).contains("<s>")&&!seq.subList(i-3,i+1).contains("</s>"))best=Math.max(best,count(seq.subList(i-3,i+1)));}return best;
    }
    public Decision apply(String raw){
        if(raw==null)raw="";if(raw.length()>256)return new Decision(raw,raw,"input_limit",0);
        for(String marker:LITERALS)if(raw.contains(marker))return new Decision(raw,raw,"literal_context",0);
        Map<String,Double> candidates=new TreeMap<>();double base=score(raw);
        for(int start=0;start<raw.length();start++)for(String target:terms){
            int end=start+target.length();if(end>raw.length())continue;String original=raw.substring(start,end);
            if(!original.matches("[\\u3400-\\u9fff]+")||!homophone(original,target))continue;
            Set<Integer> changed=new HashSet<>();for(int j=0;j<target.length();j++)if(original.charAt(j)!=target.charAt(j))changed.add(start+j);
            if(support(raw,changed)>0)continue;
            String candidate=raw.substring(0,start)+target+raw.substring(end);
            if(support(candidate,changed)<3)continue;
            candidates.put(candidate,score(candidate));if(candidates.size()>64)return new Decision(raw,raw,"candidate_limit",0);
        }
        String best=raw;double high=base,runner=base;
        for(Map.Entry<String,Double> e:candidates.entrySet()){double value=e.getValue();if(value>high){runner=high;high=value;best=e.getKey();}else runner=Math.max(runner,value);}
        if(high-base<4||high-runner<2)return new Decision(raw,raw,"insufficient_evidence",high-base);
        return new Decision(raw,best,"phonetic_4gram_gain",high-base);
    }
}
