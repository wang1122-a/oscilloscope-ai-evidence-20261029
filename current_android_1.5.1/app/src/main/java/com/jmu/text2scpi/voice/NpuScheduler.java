package com.jmu.text2scpi.voice;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
/**
 * NPU 推理串行调度入口。让语音识别与 Text2SCPI 重推理互斥，避免模型并发争用；不以线程中断强行终止原生调用。
 */
public final class NpuScheduler {
    // 公平锁让语音识别与 Text2SCPI 依次使用 NPU，不代表固定为不同模型划分 NPU 核心。
    private static final ReentrantLock LOCK=new ReentrantLock(true);
    private NpuScheduler() {
    }
    /** 在共享锁内执行一次有返回值的模型任务，完成或抛异常都会解锁；调用会阻塞，不应放在 UI 线程。 */
    public static <T> T call(Supplier<T> action) {
        LOCK.lock();
        try {
            return action.get();
        }
        finally {
            LOCK.unlock();
        }
    }
    /** 将无返回值的初始化或释放动作复用同一串行入口，与模型推理保持互斥。 */
    public static void run(Runnable action) {
        call(()-> {
            action.run();return null;
        }
        );
    }
}
