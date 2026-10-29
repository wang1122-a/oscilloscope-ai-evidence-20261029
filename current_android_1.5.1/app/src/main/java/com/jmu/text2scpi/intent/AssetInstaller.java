package com.jmu.text2scpi.intent;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
/**
 * 离线资产安装器。读取 SHA256 清单，校验路径和文件内容，再将 APK 资源安装到应用私有目录；缺失或损坏时抛出异常。
 */
public final class AssetInstaller {
    private final Context context;
    public AssetInstaller(Context context) {
        this.context=context;
    }
    public static final class InstalledModel {
        private final File modelFile;
        private final Map<String,String> manifest;
        InstalledModel(File file,Map<String,String> manifest) {
            this.modelFile=file;
            this.manifest=Collections.unmodifiableMap(new LinkedHashMap<>(manifest));
        }
        public File getModelFile() {
            return modelFile;
        }
        public Map<String,String> getManifest() {
            return manifest;
        }
    }
    /** 安装并校验 models 目录，要求其中只有一个 RKLLM 模型，再向引擎返回真实文件路径。 */
    public InstalledModel installModelBundle() {
        try {
            Map<String,String> manifest=installBundle(context,"models",new File(context.getFilesDir(),"models"));
            List<String> models=new ArrayList<>();
            for(String name:manifest.keySet())if(name.endsWith(".rkllm"))models.add(name);
            if(models.size()!=1)throw new IllegalStateException("模型清单必须包含且仅包含一个 .rkllm 文件");
            return new InstalledModel(new File(context.getFilesDir(),"models/"+models.get(0)),manifest);
        }
        catch(IOException e) {
            throw new IntentEngineException("模型资产安装失败",e);
        }
    }
    /** Asset paths are checked, copied and hashed before atomic publication. Models stay in their original directories. */
    /** 读取资产目录的 SHA256SUMS.txt，拒绝重复条目与路径越界；已有文件正确则复用，否则先写临时文件、校验后发布。 */
    public static Map<String,String> installBundle(Context context,String assetRoot,File root)throws IOException {
        Map<String,String> manifest=new LinkedHashMap<>();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(context.getAssets().open(assetRoot+"/SHA256SUMS.txt"),StandardCharsets.UTF_8))) {
            String line;
            while((line=reader.readLine())!=null) {
                line=line.trim();
                if(line.isEmpty()||line.startsWith("#"))continue;
                String[] parts=line.split("\\s+",2);
                if(parts.length!=2||!parts[0].matches("[0-9a-fA-F]{64}"))throw new IOException("SHA256SUMS 格式错误");
                String name=parts[1].replaceFirst("^\\*","");
                if(manifest.put(name,parts[0].toUpperCase(Locale.ROOT))!=null)throw new IOException("资产清单重复："+name);
            }
        }
        if(manifest.isEmpty())throw new IOException("模型清单为空");
        root.mkdirs();
        String prefix=root.getCanonicalPath()+File.separator;
        // 已安装且哈希正确的文件直接复用，其余先写临时文件、验证后再替换。
        for(Map.Entry<String,String> entry:manifest.entrySet()) {
            File target=new File(root,entry.getKey());
            if(!target.getCanonicalPath().startsWith(prefix))throw new IOException("资产路径越界");
            if(!target.isFile()||!sha256(target).equals(entry.getValue())) {
                target.getParentFile().mkdirs();
                File temp=new File(target.getParentFile(),target.getName()+".tmp");
                try(InputStream in=context.getAssets().open(assetRoot+"/"+entry.getKey());OutputStream out=new FileOutputStream(temp)) {
                    byte[] b=new byte[1024*1024];
                    int n;
                    while((n=in.read(b))>=0)out.write(b,0,n);
                }
                if(!sha256(temp).equals(entry.getValue()))throw new IOException("资产校验失败："+entry.getKey());
                if(!temp.renameTo(target))throw new IOException("无法安装资产："+entry.getKey());
            }
        }
        return manifest;
    }
    public static String sha256(File file)throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream in=new FileInputStream(file)) {
                byte[] b=new byte[1024*1024];
                int n;
                while((n=in.read(b))>=0)digest.update(b,0,n);
            }
            StringBuilder hash=new StringBuilder();
            for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02X",b&255));
            return hash.toString();
        }
        catch(NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    /** Android 12 does not provide InputStream.readAllBytes(). */
    public static String readUtf8(InputStream input)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        byte[] buffer=new byte[8192];
        int count;
        while((count=input.read(buffer))>=0)bytes.write(buffer,0,count);
        return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
    }
}
