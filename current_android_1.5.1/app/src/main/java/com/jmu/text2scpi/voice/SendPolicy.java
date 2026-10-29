package com.jmu.text2scpi.voice;
/**
 * 发送策略的纯逻辑入口。先检查规则资格，再区分查询自动发送、设置人工确认及用户开启的自动模式。
 */
public final class SendPolicy {
    private SendPolicy() {
    }
    /** 先检查发送资格，再决定查询自动发送、设置等待确认或按用户自动模式发送。 */
    public static SendDecision decide(boolean eligible,boolean query,boolean auto) {
        return !eligible ? SendDecision.BLOCK : query || auto ? SendDecision.SEND : SendDecision.CONFIRM;
    }
}
