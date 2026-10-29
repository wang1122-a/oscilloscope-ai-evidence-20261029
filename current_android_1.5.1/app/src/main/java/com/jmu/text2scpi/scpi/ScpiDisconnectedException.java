package com.jmu.text2scpi.scpi;
import com.jmu.text2scpi.model.DeliveryState;
/**
 * 连接中断异常。记录通信失败原因，由服务显示连接状态并保留事务的执行边界。
 */
public final class ScpiDisconnectedException extends ScpiClientException {
    public ScpiDisconnectedException(String message) {
        this(message,null,DeliveryState.NOT_SENT);
    }
    public ScpiDisconnectedException(String message,Throwable cause) {
        this(message,cause,DeliveryState.NOT_SENT);
    }
    public ScpiDisconnectedException(String message,Throwable cause,DeliveryState state) {
        super(message,cause,state);
    }
}
