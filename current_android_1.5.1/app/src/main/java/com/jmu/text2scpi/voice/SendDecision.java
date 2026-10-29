package com.jmu.text2scpi.voice;
/**
 * 发送策略结果：拒绝、直接发送或等待确认。由服务落实执行，不直接访问设备。
 */
public enum SendDecision {
    BLOCK, CONFIRM, SEND
}
