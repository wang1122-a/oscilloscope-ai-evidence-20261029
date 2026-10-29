package com.jmu.text2scpi.scpi;
import com.jmu.text2scpi.model.DeliveryState;
/**
 * 通信超时异常。超时不能一概解释为命令没有执行；上层依据发送状态决定提示内容。
 */
public final class ScpiTimeoutException extends ScpiClientException {
    public ScpiTimeoutException(String message) {
        this(message,null,DeliveryState.NOT_SENT);
    }
    public ScpiTimeoutException(String message,Throwable cause) {
        this(message,cause,DeliveryState.NOT_SENT);
    }
    public ScpiTimeoutException(String message,Throwable cause,DeliveryState state) {
        super(message,cause,state);
    }
}
