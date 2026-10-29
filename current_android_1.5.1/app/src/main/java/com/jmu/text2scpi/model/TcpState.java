package com.jmu.text2scpi.model;
/**
 * 设备连接状态，用于显示未连接、连接中、就绪或错误。
 */
public enum TcpState {
    DISCONNECTED,CONNECTING,READY,ERROR
}
