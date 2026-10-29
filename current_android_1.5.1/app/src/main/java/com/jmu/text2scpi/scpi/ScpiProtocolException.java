package com.jmu.text2scpi.scpi;
import com.jmu.text2scpi.model.DeliveryState;
/**
 * SCPI 协议异常。用于身份不匹配、命令格式或响应格式不符合约束等情形。
 */
public final class ScpiProtocolException extends ScpiClientException {
    public ScpiProtocolException(String message) {
        this(message,DeliveryState.NOT_SENT);
    }
    public ScpiProtocolException(String message,DeliveryState state) {
        super(message,null,state);
    }
}
