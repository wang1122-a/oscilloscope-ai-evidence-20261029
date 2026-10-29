package com.jmu.text2scpi.intent;
/**
 * 生成固定的 Text2SCPI 提示词模板。要求模型按固定顺序输出三个字段，模型与模板必须作为同一版本维护。
 */
public final class PromptBuilder {
    public static final String SYSTEM_PROMPT="你是示波器SCPI指令解析器。将用户的自然语言指令转换为一个JSON对象，只输出intent、SCPIcommand、SCPIparams三个字段；不得解释、不得使用Markdown、不得输出思考过程。";
    private PromptBuilder() {
    }
    /** Exact frozen Qwen3 chat template, including the empty thinking segment. */
    public static String render(String userText) {
        return "<|im_start|>system\n"+SYSTEM_PROMPT+"<|im_end|>\n<|im_start|>user\n"+userText+
        "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
    }
}
