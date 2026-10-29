package com.jmu.text2scpi.model;
/**
 * 规则结论枚举及其协议名称。仅原样有效或安全修正的结论具有发送资格。
 */
public enum RuleDisposition {
    VALID_UNCHANGED("valid_unchanged"),SAFELY_CORRECTED("safely_corrected"),CLARIFICATION_REQUIRED("clarification_required"),RUNTIME_BLOCKED("runtime_blocked"),REJECTED_UNSAFE("rejected_unsafe");
    private final String wireName;
    RuleDisposition(String wireName) {
        this.wireName=wireName;
    }
    public String getWireName() {
        return wireName;
    }
    public boolean getSendable() {
        return this==VALID_UNCHANGED||this==SAFELY_CORRECTED;
    }
}
