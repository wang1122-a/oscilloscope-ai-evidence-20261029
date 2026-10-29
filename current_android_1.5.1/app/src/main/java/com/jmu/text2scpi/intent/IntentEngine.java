package com.jmu.text2scpi.intent;
/**
 * Text2SCPI 引擎接口。约定初始化、文本推理和释放资源三个阶段；调用方负责工作线程调度。
 */
public interface IntentEngine {
    void initialize();
    String infer(String text);
    void release();
}
