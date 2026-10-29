package com.jmu.text2scpi.model;
import kotlinx.serialization.json.*;
/**
 * 设备事务结果数据。保存发送命令、响应、耗时以及设置前后值，明确区分已写入与已验证。
 */
public final class TransactionResult {
    public final String command;
    public final boolean isQuery;
    public final String response;
    public final long elapsedMs;
    public final String verificationQuery;
    public final String previousResponse;
    public final String verifiedResponse;
    public final boolean verified;
    public final DeliveryState deliveryState;
    public TransactionResult(String command,boolean isQuery,String response,long elapsedMs,String verificationQuery,String previousResponse,String verifiedResponse,boolean verified,DeliveryState deliveryState) {
        this.command=command;
        this.isQuery=isQuery;
        this.response=response;
        this.elapsedMs=elapsedMs;
        this.verificationQuery=verificationQuery;
        this.previousResponse=previousResponse;
        this.verifiedResponse=verifiedResponse;
        this.verified=verified;
        this.deliveryState=deliveryState;
    }
    public String getCommand() {
        return command;
    }
    public boolean isQuery() {
        return isQuery;
    }
    public String getResponse() {
        return response;
    }
    public long getElapsedMs() {
        return elapsedMs;
    }
    public String getVerificationQuery() {
        return verificationQuery;
    }
    public String getPreviousResponse() {
        return previousResponse;
    }
    public String getVerifiedResponse() {
        return verifiedResponse;
    }
    public boolean getVerified() {
        return verified;
    }
    public DeliveryState getDeliveryState() {
        return deliveryState;
    }
}
