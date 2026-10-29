package com.jmu.text2scpi.scpi;
@FunctionalInterface /**
 * TCP 事务观察回调。接收过程事件供日志记录，不承担重发或更改指令的职责。
 */
public interface TransactionObserver {
    void onEvent(TcpTransactionEvent event);
}
