package com.jmu.text2scpi.model;
/**
 * 意图处理阶段状态，供界面区分理解结果和需用户补充信息的情形。
 */
public enum IntentStatus {
    UNDERSTOOD,NEED_CLARIFICATION,NOT_UNDERSTOOD,NOT_COMMAND,ERROR
}
