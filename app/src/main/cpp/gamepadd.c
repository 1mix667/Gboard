// gamepadd — root daemon: bridges the Gboard+ gamepad panel to a REAL
// virtual Xbox 360 controller on /dev/uinput, so Parsec (and Android itself)
// sees genuine "Microsoft X-Box 360 pad" hardware instead of injected keys.
//
// Transport: abstract-namespace local socket "gbp_gamepad".
// Protocol: fixed 16-byte little-endian frames, one per state change:
//   [0..3]   uint32  button bitmask (bits = GamepadProto.BTN_*)
//   [4..5]   int16   left stick X   (-32768..32767)
//   [6..7]   int16   left stick Y
//   [8..9]   int16   right stick X
//   [10..11] int16   right stick Y
//   [12]     uint8   LT axis (ABS_Z,  0..255)
//   [13]     uint8   RT axis (ABS_RZ, 0..255)
//   [14]     int8    D-pad hat X (ABS_HAT0X, -1/0/1)
//   [15]     int8    D-pad hat Y (ABS_HAT0Y, -1/0/1)
// The daemon diffs each frame against the previous one and emits only the
// changed input_events + SYN_REPORT. When a client disconnects, the state is
// zeroed so nothing can stay stuck "pressed" on the host PC.
//
// Run as root (su). Optional arg --daemonize detaches via daemon(0, 0).

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stddef.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/types.h>
#include <linux/input.h>
#include <linux/uinput.h>

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "gamepadd", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "gamepadd", __VA_ARGS__)
#else
#define LOGI(...) do { fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#define LOGE(...) do { fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

#define SOCK_NAME "gbp_gamepad"
#define FRAME_SIZE 16

// Button bit index -> evdev key code. Order must match GamepadProto.BTN_*.
static const int kKeyForBit[11] = {
    BTN_SOUTH,  // 0 A
    BTN_EAST,   // 1 B
    BTN_NORTH,  // 2 X
    BTN_WEST,   // 3 Y
    BTN_TL,     // 4 LB
    BTN_TR,     // 5 RB
    BTN_SELECT, // 6 Back
    BTN_START,  // 7 Start
    BTN_MODE,   // 8 Guide
    BTN_THUMBL, // 9 L3
    BTN_THUMBR, // 10 R3
};

typedef struct __attribute__((packed)) {
    uint32_t buttons;
    int16_t lx, ly, rx, ry;
    uint8_t lt, rt;
    int8_t hatx, haty;
} Frame;

_Static_assert(sizeof(Frame) == FRAME_SIZE, "frame layout drift");

static volatile sig_atomic_t g_stop = 0;
static void on_signal(int sig) { (void)sig; g_stop = 1; }

static int emit_event(int fd, uint16_t type, uint16_t code, int32_t value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    // gettimeofday is unnecessary; kernel stamps events with a zero time fine.
    if (write(fd, &ev, sizeof(ev)) != (ssize_t)sizeof(ev)) return -1;
    return 0;
}

// Create the virtual "Microsoft X-Box 360 pad". Returns uinput fd or -1.
static int create_device(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        LOGE("open /dev/uinput failed: %s", strerror(errno));
        return -1;
    }

    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);

    for (int i = 0; i < 11; i++) ioctl(fd, UI_SET_KEYBIT, kKeyForBit[i]);

    static const int axes[8] = {
        ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_Z, ABS_RZ, ABS_HAT0X, ABS_HAT0Y
    };
    for (int i = 0; i < 8; i++) ioctl(fd, UI_SET_ABSBIT, axes[i]);

    struct uinput_user_dev dev;
    memset(&dev, 0, sizeof(dev));
    snprintf(dev.name, UINPUT_MAX_NAME_SIZE, "Microsoft X-Box 360 pad");
    dev.id.bustype = BUS_USB;
    dev.id.vendor = 0x045E;   // Microsoft
    dev.id.product = 0x028E;  // Xbox 360 controller
    dev.id.version = 0x0114;

    dev.absmin[ABS_X] = -32768; dev.absmax[ABS_X] = 32767;
    dev.absmin[ABS_Y] = -32768; dev.absmax[ABS_Y] = 32767;
    dev.absmin[ABS_RX] = -32768; dev.absmax[ABS_RX] = 32767;
    dev.absmin[ABS_RY] = -32768; dev.absmax[ABS_RY] = 32767;
    dev.absmin[ABS_Z] = 0; dev.absmax[ABS_Z] = 255;
    dev.absmin[ABS_RZ] = 0; dev.absmax[ABS_RZ] = 255;
    dev.absmin[ABS_HAT0X] = -1; dev.absmax[ABS_HAT0X] = 1;
    dev.absmin[ABS_HAT0Y] = -1; dev.absmax[ABS_HAT0Y] = 1;

    if (write(fd, &dev, sizeof(dev)) != (ssize_t)sizeof(dev)) {
        LOGE("write uinput_user_dev failed: %s", strerror(errno));
        close(fd);
        return -1;
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("UI_DEV_CREATE failed: %s", strerror(errno));
        close(fd);
        return -1;
    }
    // Give the kernel/userspace a moment to register the input device.
    usleep(200 * 1000);
    LOGI("virtual Xbox 360 pad created");
    return fd;
}

// Emit the diff between prev and cur, then SYN_REPORT. Updates *prev.
static void apply_frame(int fd, const Frame *cur, Frame *prev) {
    int dirty = 0;
    for (int i = 0; i < 11; i++) {
        int now = (int)((cur->buttons >> i) & 1u);
        int was = (int)((prev->buttons >> i) & 1u);
        if (now != was) {
            emit_event(fd, EV_KEY, (uint16_t)kKeyForBit[i], now);
            dirty = 1;
        }
    }
#define AXIS(field, code)                                              \
    do {                                                               \
        if (cur->field != prev->field) {                               \
            emit_event(fd, EV_ABS, code, cur->field);                  \
            dirty = 1;                                                 \
        }                                                              \
    } while (0)
    AXIS(lx, ABS_X);
    AXIS(ly, ABS_Y);
    AXIS(rx, ABS_RX);
    AXIS(ry, ABS_RY);
    AXIS(lt, ABS_Z);
    AXIS(rt, ABS_RZ);
    AXIS(hatx, ABS_HAT0X);
    AXIS(haty, ABS_HAT0Y);
#undef AXIS
    if (dirty) emit_event(fd, EV_SYN, SYN_REPORT, 0);
    *prev = *cur;
}

static int make_server_socket(void) {
    int s = socket(AF_UNIX, SOCK_STREAM, 0);
    if (s < 0) {
        LOGE("socket failed: %s", strerror(errno));
        return -1;
    }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';  // abstract namespace
    strncpy(&addr.sun_path[1], SOCK_NAME, sizeof(addr.sun_path) - 2);
    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path)
                                + 1 + strlen(SOCK_NAME));
    if (bind(s, (struct sockaddr *)&addr, len) < 0) {
        LOGE("bind @%s failed: %s (already running?)", SOCK_NAME, strerror(errno));
        close(s);
        return -1;
    }
    if (listen(s, 2) < 0) {
        LOGE("listen failed: %s", strerror(errno));
        close(s);
        return -1;
    }
    return s;
}

int main(int argc, char **argv) {
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--daemonize") == 0) {
            if (daemon(0, 0) < 0) LOGE("daemon() failed: %s", strerror(errno));
        }
    }
    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);

    int uinput = create_device();
    if (uinput < 0) return 1;

    int server = make_server_socket();
    if (server < 0) {
        ioctl(uinput, UI_DEV_DESTROY);
        close(uinput);
        return 1;
    }
    LOGI("listening on abstract socket @%s", SOCK_NAME);

    Frame state;
    memset(&state, 0, sizeof(state));

    while (!g_stop) {
        int client = accept(server, NULL, NULL);
        if (client < 0) {
            if (errno == EINTR) continue;
            LOGE("accept failed: %s", strerror(errno));
            break;
        }
        LOGI("client connected");
        uint8_t buf[FRAME_SIZE];
        size_t have = 0;
        while (!g_stop) {
            ssize_t n = read(client, buf + have, FRAME_SIZE - have);
            if (n == 0) break;  // EOF — client gone
            if (n < 0) {
                if (errno == EINTR) continue;
                break;
            }
            have += (size_t)n;
            if (have == FRAME_SIZE) {
                Frame cur;
                memcpy(&cur, buf, FRAME_SIZE);
                apply_frame(uinput, &cur, &state);
                have = 0;
            }
        }
        close(client);
        LOGI("client disconnected — zeroing state");
        Frame zero;
        memset(&zero, 0, sizeof(zero));
        apply_frame(uinput, &zero, &state);
    }

    Frame zero;
    memset(&zero, 0, sizeof(zero));
    apply_frame(uinput, &zero, &state);
    close(server);
    ioctl(uinput, UI_DEV_DESTROY);
    close(uinput);
    LOGI("bye");
    return 0;
}
