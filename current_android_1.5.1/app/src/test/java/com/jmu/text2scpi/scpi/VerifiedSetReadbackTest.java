package com.jmu.text2scpi.scpi;

import com.jmu.text2scpi.model.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** A real loopback device: settings succeed but their original socket cannot answer afterward. */
public final class VerifiedSetReadbackTest {
    private static final String SET="HORizontal:SCAle 0.01", QUERY="HORizontal:SCAle?";
    private static final class Device implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,8,InetAddress.getLoopbackAddress());
        final AtomicInteger writes=new AtomicInteger(),postQueries=new AtomicInteger();
        final List<String> wire=Collections.synchronizedList(new ArrayList<>());
        volatile boolean closed,dropBefore,dropAll,wrongValue,wrongIdentity;
        volatile int dropFirst;
        volatile Socket active;
        final Thread worker;
        Device()throws IOException {
            worker=new Thread(()->{
                while(!closed)try(Socket socket=server.accept()) {
                    active=socket;boolean settingOnThisConnection=false;
                    BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                    BufferedWriter out=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.US_ASCII));
                    String line;
                    while((line=in.readLine())!=null) {
                        wire.add(line);String response=null;
                        if(line.equals("*IDN?"))response="OWON,ADS10104A,"+(wrongIdentity&&writes.get()>0?"other":"2553009")+",test";
                        else if(line.equals(SET)){writes.incrementAndGet();settingOnThisConnection=true;}
                        else if(line.equals(QUERY)) {
                            if(writes.get()==0){if(!dropBefore)response="5.0000E-3";}
                            else {
                                int number=postQueries.incrementAndGet();
                                if(!settingOnThisConnection&&!dropAll&&number>dropFirst)response=wrongValue?"5.0000E-3":"1.0000E-2";
                            }
                        }
                        if(response!=null){out.write(response);out.write('\n');out.flush();}
                    }
                }catch(IOException failure){if(!closed)throw new RuntimeException(failure);}
            },"fake-scpi-device");worker.setDaemon(true);worker.start();
        }
        ScpiTcpClient client(){return new ScpiTcpClient(new ScpiConnectionConfig("127.0.0.1",server.getLocalPort(),100,80,4096));}
        public void close()throws Exception {closed=true;server.close();if(active!=null)active.close();worker.join(1000);}
    }
    @Test public void successfulWriteIsConfirmedOnFreshConnectionWithOneSettingOnly()throws Exception {
        try(Device d=new Device()) {
            TransactionResult result=d.client().verifiedSet(SET,QUERY);
            assertEquals(DeliveryState.CONFIRMED,result.deliveryState);assertTrue(result.verified);
            assertEquals("5.0000E-3",result.previousResponse);assertEquals("1.0000E-2",result.verifiedResponse);
            assertEquals(1,d.writes.get());assertEquals(2,Collections.frequency(d.wire,"*IDN?"));
        }
    }
    @Test public void lostFirstReadbackRetriesOnlyTheQuery()throws Exception {
        try(Device d=new Device()){d.dropFirst=1;TransactionResult r=d.client().verifiedSet(SET,QUERY);assertEquals(DeliveryState.CONFIRMED,r.deliveryState);assertEquals(1,d.writes.get());assertEquals(2,d.postQueries.get());}
    }
    @Test public void missingAllReadbacksStaysUnknownWithoutSettingReplay()throws Exception {
        try(Device d=new Device()){d.dropAll=true;try{d.client().verifiedSet(SET,QUERY);fail("must not claim success");}catch(ScpiClientException e){assertEquals(DeliveryState.UNKNOWN,e.getDeliveryState());}assertEquals(1,d.writes.get());assertEquals(3,d.postQueries.get());}
    }
    @Test public void failedBeforeQueryNeverSendsASetting()throws Exception {
        try(Device d=new Device()){d.dropBefore=true;try{d.client().verifiedSet(SET,QUERY);fail();}catch(ScpiClientException e){assertEquals(DeliveryState.NOT_SENT,e.getDeliveryState());}assertEquals(0,d.writes.get());}
    }
    @Test public void stableDifferentReadbackIsMismatch()throws Exception {
        try(Device d=new Device()){d.wrongValue=true;TransactionResult r=d.client().verifiedSet(SET,QUERY);assertEquals(DeliveryState.MISMATCH,r.deliveryState);assertFalse(r.verified);assertEquals(1,d.writes.get());assertEquals(3,d.postQueries.get());}
    }
    @Test public void changedDeviceIdentityCannotConfirmPriorSetting()throws Exception {
        try(Device d=new Device()){d.wrongIdentity=true;try{d.client().verifiedSet(SET,QUERY);fail();}catch(ScpiClientException e){assertEquals(DeliveryState.UNKNOWN,e.getDeliveryState());}assertEquals(1,d.writes.get());assertEquals(0,d.postQueries.get());}
    }
    @Test public void interruptionAfterWriteRemainsUnknownAndNeverReplays()throws Exception {
        try(Device d=new Device()) {
            try{d.client().verifiedSet(SET,QUERY,event->{if(event.getStage()==TcpTransactionStage.TCP_SEND){long until=System.nanoTime()+1_000_000_000L;while(d.writes.get()==0&&System.nanoTime()<until)Thread.yield();Thread.currentThread().interrupt();}});fail();}
            catch(ScpiClientException e){assertEquals(DeliveryState.UNKNOWN,e.getDeliveryState());assertTrue(Thread.currentThread().isInterrupted());}
            finally{Thread.interrupted();}
            assertEquals(1,d.writes.get());assertEquals(0,d.postQueries.get());
        }
    }
}
