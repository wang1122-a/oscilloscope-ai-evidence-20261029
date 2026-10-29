package com.jmu.text2scpi.model;
import kotlinx.serialization.json.*;
/**
 * 解析后的模型意图数据。保存 intent、SCPIcommand、SCPIparams 与原始 JSON 对象，尚不代表允许发送。
 */
public final class ModelIntent {
    public final String intent;
    public final String scpiCommand;
    public final JsonElement scpiParams;
    public final JsonObject raw;
    public ModelIntent(String intent,String scpiCommand,JsonElement scpiParams,JsonObject raw) {
        this.intent=intent;
        this.scpiCommand=scpiCommand;
        this.scpiParams=scpiParams;
        this.raw=raw;
    }
    public String getIntent() {
        return intent;
    }
    public String getScpiCommand() {
        return scpiCommand;
    }
    public JsonElement getScpiParams() {
        return scpiParams;
    }
    public JsonObject getRaw() {
        return raw;
    }
}
