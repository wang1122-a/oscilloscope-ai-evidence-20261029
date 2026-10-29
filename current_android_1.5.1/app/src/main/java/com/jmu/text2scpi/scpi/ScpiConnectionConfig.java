package com.jmu.text2scpi.scpi;
/**
 * TCP 参数。默认设备本机 127.0.0.1:3000，连接超时 5 秒、读取超时 30 秒，文本响应上限 1 MiB。
 */
public final class ScpiConnectionConfig {
    public final String host;
    public final int port,connectTimeoutMs,readTimeoutMs,maxTextResponseBytes;
    public ScpiConnectionConfig() {
        this("127.0.0.1",3000,5000,30000,1048576);
    }
    public ScpiConnectionConfig(String host,int port,int connectTimeoutMs,int readTimeoutMs,int maxTextResponseBytes) {
        this.host=host;
        this.port=port;
        this.connectTimeoutMs=connectTimeoutMs;
        this.readTimeoutMs=readTimeoutMs;
        this.maxTextResponseBytes=maxTextResponseBytes;
    }
}
