package com.jmu.text2scpi.model;
import kotlinx.serialization.json.*;
/**
 * 应用内过程日志条目。关联请求编号、阶段与内容，便于沿单条指令追踪数据流。
 */
public final class LogEntry {
    public final String requestId;
    public final String stage;
    public final String content;
    public final long timestampMs;
    public LogEntry(String requestId,String stage,String content,long timestampMs) {
        this.requestId=requestId;
        this.stage=stage;
        this.content=content;
        this.timestampMs=timestampMs;
    }
    public String getRequestId() {
        return requestId;
    }
    public String getStage() {
        return stage;
    }
    public String getContent() {
        return content;
    }
    public long getTimestampMs() {
        return timestampMs;
    }
    public LogEntry(String requestId,String stage,String content) {
        this(requestId,stage,content,System.currentTimeMillis());
    }
}
