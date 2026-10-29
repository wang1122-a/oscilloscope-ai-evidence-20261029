package com.jmu.text2scpi.scpi;
/**
 * 一次 TCP 事务的阶段事件。携带阶段和说明，用于界面日志与故障定位。
 */
public final class TcpTransactionEvent {
    private final TcpTransactionStage stage;
    private final String detail;
    public TcpTransactionEvent(TcpTransactionStage stage,String detail) {
        this.stage=stage;
        this.detail=detail;
    }
    public TcpTransactionStage getStage() {
        return stage;
    }
    public String getDetail() {
        return detail;
    }
}
