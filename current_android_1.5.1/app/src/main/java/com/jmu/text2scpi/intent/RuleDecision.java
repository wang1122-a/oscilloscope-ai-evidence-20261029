package com.jmu.text2scpi.intent;
import com.jmu.text2scpi.model.*;
/**
 * 规则层处理结论。携带规范化意图、可发送预览、回读查询和拒绝原因，供流水线统一输出。
 */
public final class RuleDecision {
    private final RuleDisposition disposition;
    private final ModelIntent normalized;
    private final String wireCommand,verificationQuery,reason;
    public RuleDecision(RuleDisposition disposition,ModelIntent normalized,String wireCommand,String verificationQuery,String reason) {
        this.disposition=disposition;
        this.normalized=normalized;
        this.wireCommand=wireCommand;
        this.verificationQuery=verificationQuery;
        this.reason=reason;
    }
    public RuleDisposition getDisposition() {
        return disposition;
    }
    public ModelIntent getNormalized() {
        return normalized;
    }
    public String getWireCommand() {
        return wireCommand;
    }
    public String getVerificationQuery() {
        return verificationQuery;
    }
    public String getReason() {
        return reason;
    }
}
