/*
 * native_input_probe.cpp — Native 直读 /dev/input 探测（探测项 1）
 *
 * 目的：在 Shizuku UserService（shell uid 2000）进程里，绕过 getevent 子进程，
 *       直接 open()/read() /dev/input/event*，验证 shell 身份是否具备原生读取权。
 *
 * 关键事实：native open() 与 getevent 走同一条内核权限路径（同一 SELinux 规则 +
 *        input 组/ACL 约束），native 并不改变权限模型。若 getevent 读不到，
 *        本探测几乎必然同样被 EACCES / SELinux avc: denied 拒绝 ——
 *        本实现如实返回 errno 字符串，供调用方打印原始错误。
 *
 * 编译约束：当前构建机 SDK 无 NDK/CMake，本 .so 默认不挂入 gradle。
 *        Java 侧 NativeInputProbe 用 System.loadLibrary 延迟加载，
 *        加载失败（UnsatisfiedLinkError）即判定 NOT_COMPILED，绝不虚构成功。
 *
 * 用法（设备端，编译出 libnative_input_probe.so 后）：
 *   adb push libnative_input_probe.so /data/local/tmp/  (或打进 APK 的 jniLibs/arm64-v8a)
 *   运行 UserService 的 probeAllTouchChannels() 即会调用 nativeProbe()。
 */

#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <cstring>
#include <cerrno>

#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>
#include <linux/input.h>
#include <sys/poll.h>

#include <atomic>
#include <thread>
#include <mutex>

static std::string errnoStr(int e) {
    switch (e) {
        case EACCES:   return "EACCES(权限拒绝)";
        case EPERM:    return "EPERM(操作不允许)";
        case ENOENT:   return "ENOENT(不存在)";
        case ENODEV:   return "ENODEV(无此设备)";
        case EINVAL:   return "EINVAL(参数无效)";
        case EBUSY:    return "EBUSY(设备忙)";
        default:       return "errno=" + std::to_string(e);
    }
}

// 列出 /dev/input/ 下所有 event 设备
static std::vector<std::string> listEventDevices() {
    std::vector<std::string> out;
    DIR* d = opendir("/dev/input");
    if (d == nullptr) return out;
    struct dirent* de;
    while ((de = readdir(d)) != nullptr) {
        const std::string name = de->d_name;
        // 只认 event[0-9]+
        if (name.rfind("event", 0) == 0) {
            const std::string num = name.substr(5);
            bool allDigit = !num.empty();
            for (char c : num) if (c < '0' || c > '9') { allDigit = false; break; }
            if (allDigit) out.push_back("/dev/input/" + name);
        }
    }
    closedir(d);
    return out;
}

// 单个设备：以 O_RDONLY | O_NONBLOCK 打开，非阻塞 poll 短暂读一批 input_event
static std::string probeDevice(const std::string& path) {
    std::ostringstream os;
    int fd = open(path.c_str(), O_RDONLY | O_NONBLOCK);
    if (fd < 0) {
        os << "  OPEN_FAIL " << path << " errno=" << errno
           << " " << errnoStr(errno);
        return os.str();
    }
    os << "  OPEN_OK " << path << " fd=" << fd << "\n";

    // 读设备名（EVIOCGNAME）
    char name[256];
    std::memset(name, 0, sizeof(name));
    if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) >= 0) {
        os << "    name=" << name << "\n";
    }

    // 非阻塞读一批事件（最多 16 个，避免占用）
    struct input_event ev[16];
    ssize_t n = read(fd, ev, sizeof(ev));
    if (n < 0) {
        os << "    READ_FAIL errno=" << errno << " " << errnoStr(errno)
           << " (非阻塞无数据时为 EAGAIN，属正常)";
    } else if (n == 0) {
        os << "    READ_EOF";
    } else {
        const size_t cnt = n / sizeof(struct input_event);
        os << "    read " << cnt << " events:\n";
        for (size_t i = 0; i < cnt; i++) {
            os << "      type=" << (int)ev[i].type
               << " code=" << (int)ev[i].code
               << " value=" << ev[i].value << "\n";
        }
    }
    close(fd);
    return os.str();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pilot_wheel_NativeInputProbe_nativeProbe(JNIEnv* env, jobject /*thiz*/) {
    std::ostringstream os;
    os << "== NativeInputProbe (uid=";
    {
        // 直接读 /proc/self/status 里的 Uid 行，避免依赖 android.os.Process
        FILE* f = fopen("/proc/self/status", "r");
        char line[256];
        while (f && fgets(line, sizeof(line), f)) {
            if (strncmp(line, "Uid:", 4) == 0) {
                os << line + 4; // 形如 "\t2000\t2000..."
                break;
            }
        }
        if (f) fclose(f);
        os << ") ==\n";
    }

    std::vector<std::string> devices = listEventDevices();
    if (devices.empty()) {
        os << "RESULT=NO_EVENT_DEVICES (opendir /dev/input 失败或为空，errno="
           << errno << " " << errnoStr(errno) << ")\n";
        os << "提示：若 SELinux 拒绝，请 logcat 查 'avc: denied' 行，"
              "补打印原始 avc 见调用方 probeAllTouchChannels。\n";
        return env->NewStringUTF(os.str().c_str());
    }

    os << "发现 " << devices.size() << " 个 event 设备，逐一 O_RDONLY|O_NONBLOCK 探测：\n";
    int openOk = 0, openFail = 0;
    for (const auto& d : devices) {
        const bool ok = (d.find("OPEN_OK") != std::string::npos); // 占位，下面重算
        (void)ok;
        // probeDevice 返回里带 OPEN_OK/OPEN_FAIL 标记
        std::string r = probeDevice(d);
        os << r << "\n";
        if (r.find("OPEN_OK") != std::string::npos) openOk++; else openFail++;
    }
    os << "RESULT=SCANNED openOk=" << openOk << " openFail=" << openFail << "\n";
    if (openOk == 0) {
        os << "结论：shell(uid 2000) 无法 open /dev/input/event*（"
           << errnoStr(EACCES) << " 或 SELinux 拒绝），与 getevent 同源。\n";
    }
    return env->NewStringUTF(os.str().c_str());
}

// ============================================================================
// 旁路触摸采集（native 直读，替换 getevent -lt 子进程）
// ============================================================================
//
// 目的：在 shell(uid 2000) 进程内直接 open()/read() /dev/input/eventN，读取原始
//       struct input_event 二进制流，解析多点触控 ABS_MT_* 事件，合成
//       DOWN/MOVE/UP 三级触点事件，通过 JNI 回调 Java 静态方法
//       NativeInputProbe.onNativeTouch(int type, float x, float y, int slot)。
//
// 相比 getevent 子进程：
//   - 省掉 fork/exec + /system/bin/sh 中转，零子进程；
//   - 省掉文本正则解析，直接读二进制 struct，低延迟、低 CPU；
//   - 只读不消费（O_RDONLY），触摸照常发给下层应用。
//
// 停止方式：poll() 以 200ms 超时轮询 std::atomic<bool> 停止标志，有数据才 read()，
//           避免「close(fd) 与阻塞 read 竞态」的未定义行为；stop 仅置标志，
//           线程最迟 200ms 内退出。

// 与 Java 侧 PilotShellService 保持一致的触点类型（事件码直接用 <linux/input.h> 的宏）
static constexpr int TYPE_DOWN = 0;
static constexpr int TYPE_MOVE = 1;
static constexpr int TYPE_UP   = 2;

// 最多追踪的触点数（type B 槽位上限；市售屏通常 9~10 槽，16 足够）
static constexpr int MAX_SLOTS = 16;

// 事件类型与 code 直接复用 <linux/input.h> / <linux/input-event-codes.h> 宏：
//   EV_ABS(0x03) / EV_KEY(0x01)
//   ABS_MT_SLOT(0x2f) / ABS_MT_POSITION_X(0x35) / ABS_MT_POSITION_Y(0x36)
//   ABS_MT_TRACKING_ID(0x39) / BTN_TOUCH(0x14a)

// 采集线程全局状态（同一时刻只允许一个采集会话）
static std::atomic<bool>      g_collect_running{false};
static std::thread            g_collect_thread;
static std::mutex             g_collect_mutex;

// 全局缓存的 JavaVM 指针（首次 JNI 调用时缓存，供采集线程 AttachCurrentThread）
static JavaVM* g_jvm = nullptr;

// 缓存 NativeInputProbe.onNativeTouch 的静态 jmethodID（线程安全：启动前解析一次）
static jclass    g_native_probe_cls = nullptr;
static jmethodID g_on_native_touch = nullptr;

// 单个触点的槽位状态（type B 协议）
struct SlotState {
    int   trackingId = -1;   // -1 = 无触点；>=0 = 触点 ID
    float x = 0.0f;
    float y = 0.0f;
};

// 采集线程体：阻塞式 poll 循环读取 input_event 并回调 Java
//
// 正确性要点（多点触控 type B，Linux input 协议）：
//   内核把一帧触点数据拆成多个 input_event 连续上报（ABS_MT_SLOT / ABS_MT_TRACKING_ID /
//   ABS_MT_POSITION_X / ABS_MT_POSITION_Y），最后以 EV_SYN + SYN_REPORT 收口。
//   只有收到 SYN_REPORT，这一帧才算完整，坐标才是正确值。若在 X/Y 事件一到就 emit，
//   会拿到「上一帧旧值 + X/Y 各一次的半帧冗余」（旧 bug）。
//   故本实现只把事件写入槽位缓存，到 SYN_REPORT 边界按 trackingId 迁移一次合成
//   DOWN/MOVE/UP。type B 设备仍会上报 BTN_TOUCH，必须与 ABS_MT_TRACKING_ID 二选一，
//   否则双发 DOWN —— 以「是否见过 ABS_MT_TRACKING_ID」区分协议、互斥处理。
static void collectLoop(int fd) {
    // 附着到 JVM，拿到本线程可用的 JNIEnv（采集线程是纯 native pthread）
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_jvm != nullptr) {
        if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attached = true;
            }
        }
    }

    auto emit = [&](int type, float x, float y, int slot) {
        if (env == nullptr || g_native_probe_cls == nullptr || g_on_native_touch == nullptr) {
            return;
        }
        env->CallStaticVoidMethod(g_native_probe_cls, g_on_native_touch,
                                  static_cast<jint>(type),
                                  static_cast<jfloat>(x),
                                  static_cast<jfloat>(y),
                                  static_cast<jint>(slot));
        if (env->ExceptionCheck()) env->ExceptionClear();
    };

    // —— type B 槽位缓存 ——
    SlotState slots[MAX_SLOTS];
    int curSlot = 0;
    int   prevTrackingId[MAX_SLOTS];   // 上一帧每槽的 trackingId（判 DOWN/MOVE/UP 迁移）
    float prevX[MAX_SLOTS];
    float prevY[MAX_SLOTS];
    for (int i = 0; i < MAX_SLOTS; i++) {
        prevTrackingId[i] = -1;
        prevX[i] = 0.0f;
        prevY[i] = 0.0f;
    }
    bool sawTrackingId = false;  // 见过 ABS_MT_TRACKING_ID → type B 主路径
    bool frameDirty = false;     // 本帧是否出现过触点相关事件

    // —— type A 兜底（老式单点/type A 设备，无 trackingId）——
    int  btnTouch = 0;           // BTN_TOUCH 持久值（仅按下/抬起才上报，不每帧重置）
    bool prevAPressed = false;
    float prevAX = 0.0f, prevAY = 0.0f;

    // SYN_REPORT 收口：type B 主路径，按 trackingId 迁移合成
    auto emitFrameTypeB = [&]() {
        for (int s = 0; s < MAX_SLOTS; s++) {
            const int  nowId   = slots[s].trackingId;
            const bool nowDown = nowId >= 0;
            const bool wasDown = prevTrackingId[s] >= 0;
            if (nowDown && !wasDown) {
                emit(TYPE_DOWN, slots[s].x, slots[s].y, s);
            } else if (nowDown && wasDown) {
                if (slots[s].x != prevX[s] || slots[s].y != prevY[s]) {
                    emit(TYPE_MOVE, slots[s].x, slots[s].y, s);
                }
            } else if (!nowDown && wasDown) {
                emit(TYPE_UP, prevX[s], prevY[s], s);
            }
            prevTrackingId[s] = nowId;
            prevX[s] = slots[s].x;
            prevY[s] = slots[s].y;
        }
    };

    // SYN_REPORT 收口：type A 兜底，BTN_TOUCH + 槽 0 坐标
    auto emitFrameTypeA = [&]() {
        const bool  nowPressed = (btnTouch == 1);
        const float ax = slots[0].x;
        const float ay = slots[0].y;
        if (nowPressed && !prevAPressed) {
            emit(TYPE_DOWN, ax, ay, 0);
        } else if (nowPressed && prevAPressed) {
            if (ax != prevAX || ay != prevAY) {
                emit(TYPE_MOVE, ax, ay, 0);
            }
        } else if (!nowPressed && prevAPressed) {
            emit(TYPE_UP, prevAX, prevAY, 0);
        }
        prevAPressed = nowPressed;
        prevAX = ax;
        prevAY = ay;
    };

    struct input_event ev[32];
    while (g_collect_running.load(std::memory_order_acquire)) {
        struct pollfd pfd;
        pfd.fd = fd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        int pr = poll(&pfd, 1, 200 /* ms */);
        if (pr < 0) {
            if (errno == EINTR) continue;
            break; // poll 失败（如 fd 被关闭），退出
        }
        if (pr == 0) continue; // 超时，回到循环检查停止标志
        if (!(pfd.revents & POLLIN)) continue;

        ssize_t n = read(fd, ev, sizeof(ev));
        if (n <= 0) {
            if (n < 0 && errno == EINTR) continue;
            if (n < 0 && errno == EAGAIN) continue;
            break; // EOF 或致命错误
        }
        const size_t cnt = static_cast<size_t>(n) / sizeof(struct input_event);
        for (size_t i = 0; i < cnt; i++) {
            const struct input_event& e = ev[i];
            if (e.type == EV_ABS) {
                switch (e.code) {
                    case ABS_MT_SLOT:
                        if (e.value >= 0 && e.value < MAX_SLOTS) curSlot = e.value;
                        break;
                    case ABS_MT_POSITION_X:
                        slots[curSlot].x = static_cast<float>(e.value);
                        frameDirty = true;
                        break;
                    case ABS_MT_POSITION_Y:
                        slots[curSlot].y = static_cast<float>(e.value);
                        frameDirty = true;
                        break;
                    case ABS_MT_TRACKING_ID:
                        slots[curSlot].trackingId = e.value; // -1 = 抬起
                        sawTrackingId = true;
                        frameDirty = true;
                        break;
                    default:
                        break;
                }
            } else if (e.type == EV_KEY && e.code == BTN_TOUCH) {
                btnTouch = e.value;
                frameDirty = true;
            } else if (e.type == EV_SYN && e.code == SYN_REPORT) {
                if (frameDirty) {
                    if (sawTrackingId) {
                        emitFrameTypeB();
                    } else {
                        emitFrameTypeA();
                    }
                    frameDirty = false;
                }
            }
        }
    }

    close(fd);

    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pilot_wheel_NativeInputProbe_nativeStartCollect(JNIEnv* env, jobject /*thiz*/,
                                                         jstring jpath) {
    // 缓存 JavaVM 与回调方法（首次）
    if (g_jvm == nullptr) {
        env->GetJavaVM(&g_jvm);
    }
    if (g_on_native_touch == nullptr) {
        jclass local = env->FindClass("com/pilot/wheel/NativeInputProbe");
        g_native_probe_cls = static_cast<jclass>(env->NewGlobalRef(local));
        g_on_native_touch = env->GetStaticMethodID(
            g_native_probe_cls, "onNativeTouch", "(IFFI)V");
        env->DeleteLocalRef(local);
        if (g_on_native_touch == nullptr) {
            return -2; // 方法解析失败
        }
    }

    std::lock_guard<std::mutex> lk(g_collect_mutex);
    if (g_collect_running.load(std::memory_order_acquire)) {
        return -3; // 已在采集
    }

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    int fd = open(path, O_RDONLY);
    env->ReleaseStringUTFChars(jpath, path);
    if (fd < 0) {
        return -1; // 打开失败（errno 由调用方查询）
    }

    g_collect_running.store(true, std::memory_order_release);
    g_collect_thread = std::thread(collectLoop, fd);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pilot_wheel_NativeInputProbe_nativeStopCollect(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lk(g_collect_mutex);
    g_collect_running.store(false, std::memory_order_release);
    if (g_collect_thread.joinable()) {
        g_collect_thread.join();
    }
}
