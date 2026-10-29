package com.jmu.text2scpi.model;
/**
 * 设备事务结果状态。区分未发送、仅写入、确认生效、回读不一致与结果不确定，供反馈和故障处理使用。
 */
public enum DeliveryState {
    NOT_SENT,CONFIRMED,UNKNOWN,WRITTEN_UNVERIFIED,MISMATCH
}
