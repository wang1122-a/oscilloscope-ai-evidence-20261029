package com.jmu.text2scpi.model;
/**
 * 通信状态分类。为界面及维护定位区分空闲、正常、断开、超时、协议错误和服务错误。
 */
public enum TransportStatus {
    IDLE,OK,TCP_DISCONNECTED,TCP_TIMEOUT,SCPI_ERROR,SERVICE_ERROR
}
