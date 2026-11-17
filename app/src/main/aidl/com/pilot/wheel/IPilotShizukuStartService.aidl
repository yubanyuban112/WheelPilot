package com.pilot.wheel;

import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;

interface IPilotShizukuStartService {
    void start(
        String commandLine,
        in ParcelFileDescriptor stdout,
        in ParcelFileDescriptor stderr,
        in ResultReceiver resultReceiver
    ) = 1;
    void destroy() = 16777114;
}
