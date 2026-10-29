package com.jmu.text2scpi.scpi;
import com.jmu.text2scpi.model.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
/**
 * 阻塞式 TCP 事务客户端，仅供工作线程调用。事务串行，每次建立连接并核验设备身份；设置结果不确定时不自动重发。
 */
public final class ScpiTcpClient {
    private final ScpiConnectionConfig config;
    private Socket socket;
    private BufferedInputStream input;
    private BufferedOutputStream output;
    private static final TransactionObserver NOOP=e-> {
    }
    ;
    public ScpiTcpClient() {
        this(new ScpiConnectionConfig());
    }
    public ScpiTcpClient(ScpiConnectionConfig config) {
        this.config=config;
    }
    public synchronized ConnectionInfo connect() throws ScpiClientException {
        try {
            return openAndVerifyWithRetry();
        }
        finally {
            closeUnlocked();
        }
    }
    public TransactionResult command(String scpi)throws ScpiClientException {
        return command(scpi,NOOP);
    }
    /** 发送没有回读查询的设置，仅返回已写入状态，不把成功写 socket 当成设备已生效。 */
    public synchronized TransactionResult command(String scpi,TransactionObserver observer)throws ScpiClientException {
        String command=scpi.trim();
        validateSingleCommand(command);
        require(!isQuery(command),"查询必须通过 query() 执行");
        long started=System.nanoTime();
        try {
            ConnectionInfo info=openAndVerifyWithRetry();
            emit(observer,TcpTransactionStage.TCP_CONNECT,info.getIdn());
            try {
                writeLineUnlocked(command);
            }
            catch(Exception e) {
                throw uncertainWrite(e);
            }
            emit(observer,TcpTransactionStage.TCP_SEND,command);
            emit(observer,TcpTransactionStage.DONE,"WRITE_OK");
            return new TransactionResult(command,false,null,elapsed(started),null,null,null,false,DeliveryState.WRITTEN_UNVERIFIED);
        }
        finally {
            closeUnlocked();
        }
    }
    public TransactionResult query(String scpi)throws ScpiClientException {
        return query(scpi,NOOP);
    }
    /** 在新连接上核验设备身份、发送一条查询并读回文本，最后关闭本次事务连接。 */
    public synchronized TransactionResult query(String scpi,TransactionObserver observer)throws ScpiClientException {
        String command=scpi.trim();
        validateSingleCommand(command);
        require(isQuery(command),"非查询命令必须通过 command() 执行");
        long started=System.nanoTime();
        try {
            ConnectionInfo info=openAndVerifyWithRetry();
            emit(observer,TcpTransactionStage.TCP_CONNECT,info.getIdn());
            String response=queryLineUnlocked(command,DeliveryState.NOT_SENT,()->emit(observer,TcpTransactionStage.TCP_SEND,command));
            emit(observer,TcpTransactionStage.RESPONSE,response);
            emit(observer,TcpTransactionStage.DONE,"QUERY_OK");
            return new TransactionResult(command,true,response,elapsed(started),null,null,null,false,DeliveryState.CONFIRMED);
        }
        finally {
            closeUnlocked();
        }
    }
    public TransactionResult verifiedSet(String command,String verificationQuery)throws ScpiClientException {
        return verifiedSet(command,verificationQuery,NOOP);
    }
    /** Read the original value, write once, then verify on fresh, bounded read-only connections. */
    public synchronized TransactionResult verifiedSet(String command,String verificationQuery,TransactionObserver observer)throws ScpiClientException {
        String setting=command.trim(),query=verificationQuery.trim();
        validateSingleCommand(setting);
        validateSingleCommand(query);
        require(!isQuery(setting),"验证设置事务的主命令不能是查询");
        require(isQuery(query),"验证设置事务必须提供查询命令");
        long started=System.nanoTime();
        try {
            ConnectionInfo info=openAndVerifyWithRetry();
            emit(observer,TcpTransactionStage.TCP_CONNECT,info.getIdn());
            emit(observer,TcpTransactionStage.VERIFY_BEFORE,query);
            String before;
            try {
                // 设置前先读取原值；这一步失败则不会发出设置。
                before=queryLineUnlocked(query,DeliveryState.NOT_SENT,()-> {
                }
                );
            }
            catch(ScpiClientException e) {
                throw new ScpiClientException("设置前查询失败，设置未发送："+e.getMessage(),e,DeliveryState.NOT_SENT);
            }
            emit(observer,TcpTransactionStage.VERIFY_BEFORE_VALUE,before);
            try {
                writeLineUnlocked(setting);
            }
            catch(Exception e) {
                throw uncertainWrite(e);
            }
            emit(observer,TcpTransactionStage.TCP_SEND,setting);
            String after=readBackAfterWrite(info,setting,query,observer);
            emit(observer,TcpTransactionStage.RESPONSE,after);
            // 写入后按回读判定结果，无法比较与明确不一致分别保留状态。
            Boolean matched=ReadbackVerifier.matches(setting,after);
            emit(observer,TcpTransactionStage.DONE,matched==null?"UNVERIFIED":matched?"VERIFIED":"MISMATCH");
            return new TransactionResult(setting,false,after,elapsed(started),query,before,after,Boolean.TRUE.equals(matched),matched==null?DeliveryState.WRITTEN_UNVERIFIED:matched?DeliveryState.CONFIRMED:DeliveryState.MISMATCH);
        }
        finally {
            closeUnlocked();
        }
    }
    /** A setting may make the device stop answering on its original connection. Never replay it. */
    private String readBackAfterWrite(ConnectionInfo original,String setting,String query,TransactionObserver observer)throws ScpiClientException {
        ScpiClientException lastFailure=null;
        String lastResponse=null;
        closeUnlocked();
        for(int attempt=0;attempt<3;attempt++) {
            try {
                try { Thread.sleep(attempt==0?150:250L*attempt); }
                catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ScpiClientException("设置已发送，核对被中断，设置不会重发",interrupted,DeliveryState.UNKNOWN);
                }
                emit(observer,TcpTransactionStage.VERIFY_AFTER,query+" [readback "+(attempt+1)+"/3, new connection]");
                ConnectionInfo current=openAndVerifyOnce(Math.min(config.readTimeoutMs,1500),Math.min(config.connectTimeoutMs,1500));
                if(!original.manufacturer.equals(current.manufacturer)||!original.model.equals(current.model)||!original.serialNumber.equals(current.serialNumber))
                    throw new ScpiProtocolException("回读设备身份发生变化，不能确认原设置");
                lastResponse=queryLineUnlocked(query,DeliveryState.UNKNOWN,()->{});
                lastFailure=null;
                Boolean matched=ReadbackVerifier.matches(setting,lastResponse);
                if(matched==null||matched)return lastResponse;
                emit(observer,TcpTransactionStage.VERIFY_RETRY,"尚未达到目标值，稍后仅重新查询："+lastResponse);
            } catch(ScpiClientException failure) {
                lastFailure=failure;
                emit(observer,TcpTransactionStage.VERIFY_RETRY,"回读失败，仅重试查询，设置不会重发："+failure.getMessage());
                if(failure instanceof ScpiProtocolException||Thread.currentThread().isInterrupted())break;
            } finally { closeUnlocked(); }
        }
        if(lastFailure!=null)throw new ScpiClientException("设置已发送，但独立回读仍未确认，设置不会重发："+lastFailure.getMessage(),lastFailure,DeliveryState.UNKNOWN);
        return lastResponse;
    }
    public synchronized void disconnect() {
        closeUnlocked();
    }
    /** 最多进行两次连接和身份核验尝试；只在发送业务命令前重试连接，不据此重放设置。 */
    private ConnectionInfo openAndVerifyWithRetry()throws ScpiClientException {
        ScpiClientException last=null;
        for(int i=0;i<2;i++)try {
            return openAndVerifyOnce();
        }
        catch(ScpiClientException e) {
            if(e instanceof ScpiProtocolException)throw e;
            last=e;
            closeUnlocked();
        }
        throw last!=null?last:new ScpiDisconnectedException("无法连接 "+config.host+":"+config.port);
    }
    /** 连接设备本机端口并发送 *IDN?，只接受 OWON ADS10000A 身份。 */
    private ConnectionInfo openAndVerifyOnce()throws ScpiClientException {
        return openAndVerifyOnce(config.readTimeoutMs,config.connectTimeoutMs);
    }
    private ConnectionInfo openAndVerifyOnce(int readTimeoutMs,int connectTimeoutMs)throws ScpiClientException {
        closeUnlocked();
        try {
            // Store ownership before connect so a failed connect cannot leak a descriptor.
            socket=new Socket();
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(readTimeoutMs);
            socket.connect(new InetSocketAddress(config.host,config.port),connectTimeoutMs);
            input=new BufferedInputStream(socket.getInputStream());
            output=new BufferedOutputStream(socket.getOutputStream());
            String idn=queryLineUnlocked("*IDN?",DeliveryState.NOT_SENT,()-> {
            }
            );
            List<String> fields=new ArrayList<>();
            for(String f:idn.split(",",-1))fields.add(f.trim());
            while(fields.size()<4)fields.add("");
            if(!fields.get(0).equals("OWON")||!(fields.get(1).equals("ADS10000A")||fields.get(1).equals("ADS10104A")))throw new ScpiProtocolException("设备身份不匹配："+idn);
            return new ConnectionInfo(config.host,config.port,idn,fields.get(0),fields.get(1),fields.get(2),String.join(",",fields.subList(3,fields.size())));
        }
        catch(ScpiClientException e) {
            throw e;
        }
        catch(SocketTimeoutException e) {
            throw new ScpiTimeoutException("连接或身份查询超时",e);
        }
        catch(IOException e) {
            throw new ScpiDisconnectedException("无法连接 "+config.host+":"+config.port,e);
        }
    }
    private String queryLineUnlocked(String command,DeliveryState failure,Runnable afterWrite)throws ScpiClientException {
        try {
            writeLineUnlocked(command);
            afterWrite.run();
            return readLineUnlocked();
        }
        catch(SocketTimeoutException e) {
            throw new ScpiTimeoutException("查询超时，连接已关闭以避免迟到响应污染下一事务",e,failure);
        }
        catch(ScpiClientException e) {
            if(e.getDeliveryState()==failure)throw e;
            throw new ScpiClientException(e.getMessage(),e,failure);
        }
        catch(IOException e) {
            throw new ScpiDisconnectedException("查询期间连接断开",e,failure);
        }
    }
    private void writeLineUnlocked(String command)throws IOException {
        if(output==null)throw new ScpiDisconnectedException("TCP 尚未连接");
        output.write(command.getBytes(StandardCharsets.US_ASCII));
        output.write('\n');
        output.flush();
    }
    private ScpiClientException uncertainWrite(Exception error) {
        return new ScpiDisconnectedException("SCPI 写入失败，结果不确定且不会自动重发",error,DeliveryState.UNKNOWN);
    }
    private String readLineUnlocked()throws IOException {
        if(input==null)throw new ScpiDisconnectedException("TCP 尚未连接");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        while(true) {
            int value=input.read();
            if(value<0)throw new EOFException("SCPI Server 已关闭连接");
            if(value==0)throw new ScpiProtocolException("拒绝含 NUL 的响应");
            if(bytes.size()==0&&value=='#')throw new ScpiProtocolException("拒绝 IEEE 二进制块响应");
            if(value=='\n')break;
            if(value!='\r')bytes.write(value);
            if(bytes.size()>config.maxTextResponseBytes)throw new ScpiProtocolException("设备响应超过 1 MiB 文本上限");
        }
        byte[] payload=bytes.toByteArray();
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(payload)).toString();
        }
        catch(Exception e) {
            return new String(payload,StandardCharsets.US_ASCII);
        }
    }
    private void closeUnlocked() {
        try {
            if(input!=null)input.close();
        }
        catch(IOException ignored) {
        }
        try {
            if(output!=null)output.close();
        }
        catch(IOException ignored) {
        }
        try {
            if(socket!=null)socket.close();
        }
        catch(IOException ignored) {
        }
        input=null;
        output=null;
        socket=null;
    }
    public static void validateSingleCommand(String command) {
        require(!command.trim().isEmpty(),"SCPI 命令不能为空");
        require(!command.contains(";")&&!command.contains("\r")&&!command.contains("\n"),"一次只允许一条无换行 SCPI 命令");
        require(command.matches("[\\x20-\\x7E]*"),"SCPI wire command 必须是 ASCII");
    }
    public static boolean isQuery(String command) {
        return command.trim().split(" ",2)[0].endsWith("?");
    }
    private static void require(boolean ok,String message) {
        if(!ok)throw new IllegalArgumentException(message);
    }
    private static long elapsed(long started) {
        return (System.nanoTime()-started)/1000000;
    }
    private static void emit(TransactionObserver observer,TcpTransactionStage stage,String detail) {
        observer.onEvent(new TcpTransactionEvent(stage,detail));
    }
}
