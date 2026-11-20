package android.view;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * 编译期桩（compile-only stub）。真实类 android.view.InputMonitor 是 @hide，
 * 不出现在公开 android.jar。这里仅提供签名对齐的桩，运行时由真机类顶替。
 */
public final class InputMonitor implements Parcelable {

    public static final Parcelable.Creator<InputMonitor> CREATOR =
        new Parcelable.Creator<InputMonitor>() {
            public InputMonitor createFromParcel(Parcel source) {
                return new InputMonitor(source);
            }
            public InputMonitor[] newArray(int size) {
                return new InputMonitor[size];
            }
        };

    private InputChannel mInputChannel;
    private String mName;

    public InputMonitor(String name, InputChannel channel, android.os.IBinder host) {
        mName = name;
        mInputChannel = channel;
    }

    private InputMonitor(Parcel in) {
    }

    public InputChannel getInputChannel() {
        return mInputChannel;
    }

    public String getName() {
        return mName;
    }

    public void dispose() {
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
    }
}
