package com.jmu.text2scpi.model;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
/**
 * 状态快照与观察者容器。服务在主线程更新状态，界面订阅后立即取得当前快照；离开界面时必须解除订阅。
 */
public final class StateStore {
    public interface Subscription extends AutoCloseable {
        @Override void close();
    }
    private volatile ControlUiState value=new ControlUiState();
    private final CopyOnWriteArrayList<Consumer<ControlUiState>> observers=new CopyOnWriteArrayList<>();
    public ControlUiState getValue() {
        return value;
    }
    /** 注册界面观察者并立即提供当前快照；返回的订阅用于界面退出时解绑。 */
    public Subscription observe(Consumer<ControlUiState> observer) {
        observers.add(observer);
        observer.accept(value);
        return ()->observers.remove(observer);
    }
    /** 由服务在主线程上构造下一份不可变快照，再通知观察者；工作线程仅读取发布后的快照。 */
    public void update(Consumer<ControlUiState.Builder> edit) {
        ControlUiState.Builder b=value.toBuilder();
        edit.accept(b);
        value=b.build();
        for(Consumer<ControlUiState> o:observers)o.accept(value);
    }
    public void clearObservers() {
        observers.clear();
    }
}
