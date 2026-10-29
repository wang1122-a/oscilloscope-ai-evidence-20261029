package com.lilliput.ads10000a.voice;
import java.util.*;import java.io.*;import java.nio.charset.StandardCharsets;
/** Shared JSON encoder only. Rejected inference protocol adapters removed. */
final class CompactSemanticProtocol {
 static String encode(Object v){if(v==null)return "null";if(v instanceof String){StringBuilder b=new StringBuilder("\"");for(char c:((String)v).toCharArray()){if(c=='"'||c=='\\')b.append('\\').append(c);else if(c<32)b.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else b.append(c);}return b.append('"').toString();}if(v instanceof Number){if(!Double.isFinite(((Number)v).doubleValue()))throw new IllegalArgumentException("nonfinite");return v.toString();}if(v instanceof Boolean)return v.toString();StringJoiner b=new StringJoiner(",",v instanceof Map?"{":"[",v instanceof Map?"}":"]");if(v instanceof Map){for(Object entry:((Map)v).entrySet()){Map.Entry e=(Map.Entry)entry;b.add(encode(e.getKey().toString())+":"+encode(e.getValue()));}}else if(v instanceof List){for(Object e:(List)v)b.add(encode(e));}else throw new IllegalArgumentException("json_type");return b.toString();}
}
