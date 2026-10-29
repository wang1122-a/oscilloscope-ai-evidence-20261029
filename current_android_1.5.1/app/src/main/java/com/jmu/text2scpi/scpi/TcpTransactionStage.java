package com.jmu.text2scpi.scpi;
/**
 * TCP 事务阶段枚举，区分连接、发送、设置前后查询、响应和结束。
 */
public enum TcpTransactionStage {
    TCP_CONNECT,VERIFY_BEFORE,VERIFY_BEFORE_VALUE,TCP_SEND,VERIFY_AFTER,VERIFY_RETRY,RESPONSE,DONE
}
