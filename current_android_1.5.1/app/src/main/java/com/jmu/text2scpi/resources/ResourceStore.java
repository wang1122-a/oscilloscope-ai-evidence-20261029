package com.jmu.text2scpi.resources;

import android.content.Context;
import android.net.Uri;
import android.os.StatFs;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;

/** Immutable, data-only packages. Publication follows complete hash validation. */
public final class ResourceStore {
    private final Context context;
    private final File root;
    private final JSONObject catalog;
    public ResourceStore(Context context) {
        this.context=context.getApplicationContext();
        root=new File(context.getFilesDir(),"models/resources");
        // RKLLM 1.3.0 requires weights below the app-private files/models directory.
        File previous=new File(context.getFilesDir(),"resources");
        if(!root.exists()&&previous.isDirectory()) {
            root.getParentFile().mkdirs();
            if(!previous.renameTo(root))throw new IllegalStateException("无法迁移私有模型目录，请重启后重新导入");
        }
        try(InputStream in=context.getAssets().open("resource_catalog.json")) {
            catalog=new JSONObject(new String(read(in,2_000_000),"UTF-8")).getJSONObject("packages");
        } catch(Exception e) { throw new IllegalStateException("资源目录配置不可用",e); }
    }
    public synchronized File required(String type) throws IOException {
        try {
            JSONObject expected=catalog.getJSONObject(type);
            String key=expected.getString("manifest_sha256").substring(0,24);
            String active=context.getSharedPreferences("resources",0).getString(type,"");
            if(!active.matches(key+"(?:-[a-f0-9]{8})?"))throw new IOException("请先导入资源包："+type);
            File directory=new File(new File(root,type),active);
            if(!directory.isDirectory())throw new IOException("资源文件丢失："+type);
            return directory;
        } catch(JSONException e) {throw new IOException(e);}
    }
    public boolean installed(String type) {try{required(type);return true;}catch(Exception e){return false;}}
    public synchronized File verify(String type) throws IOException {
        File directory=required(type);
        try {
            JSONObject expected=catalog.getJSONObject(type);
            byte[] manifest=read(new FileInputStream(new File(directory,"manifest.json")),2_000_000);
            if(!digest(manifest).equals(expected.getString("manifest_sha256")))throw new IOException("资源清单损坏："+type);
            verifyFiles(directory,expected.getJSONObject("files"));
            return directory;
        }catch(JSONException e){throw new IOException(e);}
    }
    public Map<String,String> hashes(String type,String prefix) throws IOException {
        try {
            JSONObject files=catalog.getJSONObject(type).getJSONObject("files");
            Map<String,String> result=new TreeMap<>();
            for(Iterator<String> it=files.keys();it.hasNext();){String name=it.next();if(name.startsWith(prefix+"/"))result.put(name.substring(prefix.length()+1),files.getJSONObject(name).getString("sha256"));}
            return result;
        }catch(JSONException e){throw new IOException(e);}
    }
    public String status() {
        StringBuilder text=new StringBuilder();
        for(String type:new String[]{"common","zh","en","semantic","zh_lm"}) {
            try{text.append(label(type)).append("：").append(installed(type)?catalog.getJSONObject(type).getString("id"):"待导入").append('\n');}
            catch(JSONException ignored){}
        }
        return text.toString().trim();
    }
    public static String label(String type){if(type.equals("zh_lm"))return "中文4-gram纠歧（可选）";return type.equals("common")?"唤醒 / VAD / TTS":type.equals("zh")?"中文识别":type.equals("en")?"英文识别＋4-gram":"双语千问指令";}

    public String importUri(Uri uri,Consumer<String> progress) throws Exception {
        root.mkdirs();
        File archive=File.createTempFile("resource-",".zip",context.getCacheDir());
        File staging=null;
        try {
            progress.accept("正在读取资源包…");
            try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(archive)) {
                if(in==null)throw new IOException("无法打开所选文件");
                copy(in,out,3_000_000_000L);
            }
            try(ZipFile zip=new ZipFile(archive)) {
                ZipEntry manifestEntry=zip.getEntry("manifest.json");
                if(manifestEntry==null||manifestEntry.getSize()>2_000_000)throw new IOException("不是小欧资源包");
                byte[] manifest;
                try(InputStream in=zip.getInputStream(manifestEntry)){manifest=read(in,2_000_000);}
                JSONObject data=new JSONObject(new String(manifest,"UTF-8"));
                String type=data.getString("type");
                JSONObject expected=catalog.getJSONObject(type);
                if(data.getInt("schema_version")!=1||!digest(manifest).equals(expected.getString("manifest_sha256")))throw new IOException("资源包与当前应用版本不匹配");
                JSONObject files=expected.getJSONObject("files");
                long needed=expected.getLong("uncompressed_bytes")+16_777_216;
                if(new StatFs(root.getAbsolutePath()).getAvailableBytes()<needed)throw new IOException("空间不足，至少需要 "+(needed/1048576)+" MiB 可用空间");
                Set<String> names=new HashSet<>();
                for(Enumeration<? extends ZipEntry> entries=zip.entries();entries.hasMoreElements();) {
                    ZipEntry entry=entries.nextElement();String name=entry.getName();
                    if(entry.isDirectory()||!safeName(name)||!names.add(name.toLowerCase(Locale.ROOT))||(!name.equals("manifest.json")&&!files.has(name)))throw new IOException("资源包含非法或重复文件");
                }
                if(names.size()!=files.length()+1)throw new IOException("资源包文件不完整");
                File parent=new File(root,type);parent.mkdirs();
                staging=new File(parent,"incoming-"+UUID.randomUUID());
                if(!staging.mkdir())throw new IOException("无法建立导入暂存目录");
                int count=0;
                for(Iterator<String> it=files.keys();it.hasNext();) {
                    String name=it.next();JSONObject item=files.getJSONObject(name);ZipEntry entry=zip.getEntry(name);
                    if(entry==null||entry.getSize()!=item.getLong("bytes"))throw new IOException("资源长度不匹配："+name);
                    progress.accept("正在导入 "+label(type)+" "+(++count)+"/"+files.length());
                    File target=child(staging,name);target.getParentFile().mkdirs();
                    try(InputStream in=zip.getInputStream(entry);FileOutputStream out=new FileOutputStream(target)){copy(in,out,item.getLong("bytes"));out.getFD().sync();}
                    if(!hash(target).equals(item.getString("sha256")))throw new IOException("资源校验失败："+name);
                }
                try(FileOutputStream out=new FileOutputStream(new File(staging,"manifest.json"))){out.write(manifest);out.getFD().sync();}
                String key=expected.getString("manifest_sha256").substring(0,24);
                File finalDir=new File(parent,key);
                if(finalDir.exists()) {
                    boolean reusable=true;
                    try{verifyFiles(finalDir,files);if(!hash(new File(finalDir,"manifest.json")).equals(expected.getString("manifest_sha256")))reusable=false;}catch(IOException invalid){reusable=false;}
                    if(reusable){removeStaging(staging);staging=null;}
                    else{key=key+"-"+UUID.randomUUID().toString().substring(0,8);finalDir=new File(parent,key);}
                }
                if(staging!=null){if(!staging.renameTo(finalDir))throw new IOException("无法发布资源包");staging=null;}
                if(!context.getSharedPreferences("resources",0).edit().putString(type,key).commit())throw new IOException("无法保存资源选择");
                return label(type)+"导入并校验成功";
            }
        } finally {
            if(staging!=null)removeStaging(staging);
            if(archive.exists())archive.delete();
        }
    }
    private static void verifyFiles(File root,JSONObject files)throws IOException,JSONException {
        for(Iterator<String> it=files.keys();it.hasNext();) {
            String name=it.next();File file=child(root,name);JSONObject item=files.getJSONObject(name);
            if(!file.isFile()||file.length()!=item.getLong("bytes")||!hash(file).equals(item.getString("sha256")))throw new IOException("资源损坏或丢失："+name);
        }
    }
    private static boolean safeName(String name){return !name.isEmpty()&&!name.startsWith("/")&&!name.contains("\\")&&!name.contains(":")&&!Arrays.asList(name.split("/")).contains("..")&&!Arrays.asList(name.split("/")).contains(".");}
    private static File child(File root,String name)throws IOException {
        if(!safeName(name))throw new IOException("非法资源路径");
        File file=new File(root,name);if(!file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("资源路径越界");return file;
    }
    private static long copy(InputStream in,OutputStream out,long limit)throws IOException {
        byte[] buffer=new byte[1024*1024];long total=0;int n;
        while((n=in.read(buffer))!=-1){total+=n;if(total>limit)throw new IOException("资源包超过允许大小");if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("导入已中断");out.write(buffer,0,n);}return total;
    }
    private static byte[] read(InputStream input,int limit)throws IOException {try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){copy(in,out,limit);return out.toByteArray();}}
    public static String hash(File file)throws IOException {
        try(InputStream in=new FileInputStream(file)){MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] b=new byte[1024*1024];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);return hex(hash.digest());}catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}
    }
    private static String digest(byte[] bytes)throws IOException{try{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    private static void removeStaging(File file)throws IOException {
        File[] entries=file.listFiles();if(entries!=null)for(File entry:entries){if(entry.isDirectory())removeStaging(entry);else if(!entry.delete())throw new IOException("暂存文件清理失败");}if(!file.delete())throw new IOException("暂存目录清理失败");
    }
}
