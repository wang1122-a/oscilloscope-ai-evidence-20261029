package com.jmu.text2scpi.intent;
/**
 * Text2SCPI 引擎运行异常。将模型加载或推理失败传回控制服务，不产生可发送的设备命令。
 */
public final class IntentEngineException extends RuntimeException {
    public IntentEngineException(String message) {
        super(message);
    }
    public IntentEngineException(String message,Throwable cause) {
        super(message,cause);
    }
}
