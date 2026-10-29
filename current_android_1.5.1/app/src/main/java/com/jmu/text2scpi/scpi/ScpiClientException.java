package com.jmu.text2scpi.scpi;
import java.io.IOException;
import com.jmu.text2scpi.model.DeliveryState;
/**
 * TCP/SCPI 基础异常。附带发送状态，使上层区分未发送与可能已经执行，避免盲目重试设置。
 */
public class ScpiClientException extends IOException {
    private final DeliveryState deliveryState;
    public ScpiClientException(String message) {
        this(message,null,DeliveryState.NOT_SENT);
    }
    public ScpiClientException(String message,Throwable cause,DeliveryState deliveryState) {
        super(message,cause);
        this.deliveryState=deliveryState;
    }
    public DeliveryState getDeliveryState() {
        return deliveryState;
    }
}
