package com.jmu.text2scpi.tts;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
/**
 * 完整句 PCM 缓存。缓存键包含资产身份和合成参数，总量上限 64 MiB；缓存损坏视作未命中。
 */
public final class PcmCache {
    public static final long DEFAULT_LIMIT=64L*1024*1024;
    private static final int MAGIC=0x58545331;
    private final File root;
    private final long limit;
    public static final class Audio {
        public final float[] samples;
        public final int sampleRate;
        public Audio(float[] samples,int sampleRate) {
            this.samples=samples;
            this.sampleRate=sampleRate;
        }
    }
    public PcmCache(File root) {
        this(root,DEFAULT_LIMIT);
    }
    public PcmCache(File root,long limit) {
        this.root=root;
        this.limit=limit;
        if(!root.isDirectory()&&!root.mkdirs())throw new IllegalStateException("无法创建语音缓存");
        trim();
    }
    /** 把模型资产身份、语言、音色、速度及完整文字合成缓存键，避免资源变化后复用旧声音。 */
    public static String key(String model,String language,int voice,float speed,String text) {
        // Length-delimited fields avoid ambiguous cache identities.
        String identity=field(model)+field(language)+field(Integer.toString(voice))+field(Float.toString(speed))+field(text);
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder();
            for(byte b:digest)out.append(String.format(Locale.ROOT,"%02x",b&255));
            return out.toString();
        }
        catch(NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    private static String field(String s) {
        return s.length()+":"+s;
    }
    private File file(String key) {
        if(!key.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("cache key");
        return new File(root,key+".pcm");
    }
    /** 读取并验证缓存头、长度及浮点样本；损坏时删除该缓存并返回未命中。 */
    public synchronized Audio get(String key) {
        File file=file(key);
        if(!file.isFile())return null;
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if(in.readInt()!=MAGIC)throw new IOException("header");
            int rate=in.readInt(),count=in.readInt();
            if(rate<8000||rate>192000||count<=0||count*4L+12!=file.length()||file.length()>limit)throw new IOException("length");
            float[] samples=new float[count];
            for(int i=0;i<count;i++) {
                samples[i]=in.readFloat();
                if(!Float.isFinite(samples[i]))throw new IOException("sample");
            }
            file.setLastModified(System.currentTimeMillis());
            return new Audio(samples,rate);
        }
        catch(IOException e) {
            file.delete();
            return null;
        }
    }
    /** 完整写入临时 PCM 文件后再发布，按最近使用时间清理，使磁盘缓存总量不超过上限。 */
    public synchronized void put(String key,Audio audio)throws IOException {
        long size=audio.samples.length*4L+12;
        if(size>limit||audio.samples.length==0)return;
        if(audio.sampleRate<8000||audio.sampleRate>192000)throw new IOException("sample rate");
        File target=file(key),temporary=new File(root,key+".tmp");
        try {
            try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temporary)))) {
                out.writeInt(MAGIC);
                out.writeInt(audio.sampleRate);
                out.writeInt(audio.samples.length);
                for(float sample:audio.samples) {
                    if(!Float.isFinite(sample))throw new IOException("invalid sample");
                    out.writeFloat(sample);
                }
            }
            if(!temporary.renameTo(target))throw new IOException("cache publish");
            target.setLastModified(System.currentTimeMillis());
            trim();
        }
        finally {
            temporary.delete();
        }
    }
    private void trim() {
        File[] files=root.listFiles((d,n)->n.matches("[0-9a-f]{64}\\.pcm"));
        if(files==null)return;
        Arrays.sort(files,Comparator.comparingLong(File::lastModified));
        long size=0;
        for(File f:files)size+=f.length();
        for(File f:files) {
            if(size<=limit)break;
            long length=f.length();
            if(f.delete())size-=length;
        }
    }
}
