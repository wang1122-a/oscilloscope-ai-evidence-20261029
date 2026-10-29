package com.jmu.text2scpi.model;
import kotlinx.serialization.json.*;
/**
 * 已核验设备的连接信息。保存地址、完整身份响应、厂商、型号、序列号与固件版本。
 */
public final class ConnectionInfo {
    public final String host;
    public final int port;
    public final String idn;
    public final String manufacturer;
    public final String model;
    public final String serialNumber;
    public final String firmware;
    public ConnectionInfo(String host,int port,String idn,String manufacturer,String model,String serialNumber,String firmware) {
        this.host=host;
        this.port=port;
        this.idn=idn;
        this.manufacturer=manufacturer;
        this.model=model;
        this.serialNumber=serialNumber;
        this.firmware=firmware;
    }
    public String getHost() {
        return host;
    }
    public int getPort() {
        return port;
    }
    public String getIdn() {
        return idn;
    }
    public String getManufacturer() {
        return manufacturer;
    }
    public String getModel() {
        return model;
    }
    public String getSerialNumber() {
        return serialNumber;
    }
    public String getFirmware() {
        return firmware;
    }
}
