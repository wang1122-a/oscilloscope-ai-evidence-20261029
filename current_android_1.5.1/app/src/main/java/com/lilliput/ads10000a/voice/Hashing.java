package com.lilliput.ads10000a.voice;
import java.io.*;import java.security.*;
public final class Hashing {
 public static String sha256(InputStream in)throws IOException{return sha256(in,()->true);}
 public static String sha256(InputStream in,java.util.function.BooleanSupplier allowed)throws IOException {try{MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=new byte[1024*1024];for(;;){if(allowed==null||!allowed.getAsBoolean())throw new java.util.concurrent.CancellationException("MODEL_HASH_CANCELLED");int n=in.read(b);if(n==-1)break;d.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte v:d.digest())s.append(String.format(java.util.Locale.ROOT,"%02X",v&255));return s.toString();}catch(NoSuchAlgorithmException e){throw new IOException(e);}}
 public static String sha256(File f)throws IOException{try(InputStream i=new FileInputStream(f)){return sha256(i);}}
 public static String sha256(File f,java.util.function.BooleanSupplier allowed)throws IOException{try(InputStream i=new FileInputStream(f)){return sha256(i,allowed);}}
}
