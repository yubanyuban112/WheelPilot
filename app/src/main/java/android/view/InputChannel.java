package android.view;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * 编译期桩（compile-only stub）。真实类 android.view.InputChannel 是 @hide，
 * 不出现在公开 android.jar。这里仅提供签名对齐的桩，运行时由真机类顶替。
 */
public final class InputChannel implements Parcelable {

    public static final Parcelable.Creator<InputChannel> CREATOR =
        new Parcelable.Creator<InputChannel>() {
            public InputChannel createFromParcel(Parcel source) {
                return new InputChannel();
            }
            public InputChannel[] newArray(int size) {
                return new InputChannel[size];
            }
        };

    private long mPtr;

    public InputChannel() {
    }

    public static InputChannel[] openInputChannelPair(String name) {
        return new InputChannel[] { new InputChannel(), new InputChannel() };
    }

    public String getName() {
        return "stub";
    }

    public void dispose() {
    }

    public void transferTo(InputChannel outParameter) {
    }

    public InputChannel dup() {
        return new InputChannel();
    }

    @Override
    public int describeContents() {
        return Parcelable.CONTENTS_FILE_DESCRIPTOR;
    }

    public void readFromParcel(Parcel in) {
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
    }

    public IBinder getToken() {
        return null;
    }

    public void setToken(IBinder token) {
    }

    @Override
    public String toString() {
        return getName();
    }
}
