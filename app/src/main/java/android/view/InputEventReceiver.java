package android.view;

import android.os.Looper;
import android.os.MessageQueue;

/**
 * 编译期桩（compile-only stub）。
 *
 * 真实类 android.view.InputEventReceiver 是 @hide 的 public abstract class，
 * 不出现在公开 android.jar 里，应用层无法直接 import/继承。
 * 为了让验证模块 InputEventReceiverBridge 能在编译期继承它，
 * 这里提供一个签名对齐的桩类；运行时由真机 framework 的真实类顶替。
 *
 * 仅签名对齐，不实现逻辑（运行时用的是系统类，不是这个桩）。
 */
public abstract class InputEventReceiver {

    private InputChannel mInputChannel;
    private MessageQueue mMessageQueue;
    private long mReceiverPtr;

    public InputEventReceiver(InputChannel inputChannel, Looper looper) {
        if (inputChannel == null) {
            throw new IllegalArgumentException("inputChannel must not be null");
        }
        if (looper == null) {
            throw new IllegalArgumentException("looper must not be null");
        }
        mInputChannel = inputChannel;
        mMessageQueue = looper.getQueue();
    }

    public void dispose() {
    }

    public void onInputEvent(InputEvent event) {
        finishInputEvent(event, false);
    }

    public void onBatchedInputEventPending() {
        consumeBatchedInputEvents(-1);
    }

    public void onFocusEvent(boolean hasFocus, boolean inTouchMode) {
    }

    public final void finishInputEvent(InputEvent event, boolean handled) {
    }

    public final boolean consumeBatchedInputEvents(long frameTimeNanos) {
        return false;
    }
}
