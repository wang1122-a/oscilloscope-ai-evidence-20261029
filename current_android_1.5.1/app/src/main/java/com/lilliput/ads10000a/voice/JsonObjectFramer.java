package com.lilliput.ads10000a.voice;
import java.io.*;import java.nio.charset.StandardCharsets;
/** Streaming boundary detector only; StrictJson and SemanticPolicy remain mandatory. */
public final class JsonObjectFramer {
 private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private int depth;private boolean started,quoted,escaped,complete,invalid;
 public synchronized boolean accept(byte[] chunk){for(byte bb:chunk){int b=bb&255;if(complete)break;if(invalid)return false;if(bytes.size()>=32768){invalid=true;return false;}if(!started){if(b==32||b==9||b==10||b==13)continue;if(b!=123){invalid=true;return false;}started=true;}bytes.write(b);if(quoted){if(escaped)escaped=false;else if(b==92)escaped=true;else if(b==34)quoted=false;}else if(b==34)quoted=true;else if(b==123)depth++;else if(b==125){if(--depth==0){try{StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes.toByteArray()));complete=true;}catch(java.nio.charset.CharacterCodingException e){invalid=true;}}else if(depth<0)invalid=true;}}return complete&&!invalid;}
 public synchronized String text(){return new String(bytes.toByteArray(),StandardCharsets.UTF_8);}public synchronized boolean complete(){return complete&&!invalid;}
}
