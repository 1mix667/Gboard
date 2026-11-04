// gamepadd — root daemon: bridges the Gboard+ gamepad panel to a REAL
// virtual Xbox 360 controller on /dev/uinput, so Parsec (and Android itself)
// sees genuine "Microsoft X-Box 360 pad" hardware instead of injected keys.
//
// Transport: abstract-namespace local socket "gbp_gamepad".
// Protocol v2: fixed 24-byte little-endian frames, one per state change:
//   [0..3]   uint32  button bitmask (bits = GamepadProto.BTN_*)
//   [4..5]   int16   left stick X   (-32768..32767)
//   [6..7]   int16   left stick Y
//   [8..9]   int16   right stick X
//   [10..11] int16   right stick Y
//   [12]     uint8   LT axis (ABS_Z,  0..255)
//   [13]     uint8   RT axis (ABS_RZ, 0..255)
//   [14]     int8    D-pad hat X (ABS_HAT0X, -1/0/1)
//   [15]     int8    D-pad hat Y (ABS_HAT0Y, -1/0/1)
//   [16]     uint8   mouse button bitmask (bit0=BTN_LEFT, bit1=BTN_RIGHT,
//                    bit2=BTN_MIDDLE) — routed to the virtual mouse below
//   [17]     uint8   pad
//   [18..19] int16   mouse relative X delta
//   [20..21] int16   mouse relative Y delta
//   [22]     int8    mouse wheel delta
//   [23]     uint8   pad
// The daemon diffs each frame against the previous one and emits only the
// changed input_events + SYN_REPORT. Mouse relative deltas are NOT state:
// they are emitted as-is from the frame and then forgotten (zeroed in the
// stored state), so a later unrelated frame can never re-send them.
// A second uinput device, "Gboard+ Mouse", carries the mouse events.
// When a client disconnects, the state is zeroed so nothing can stay stuck
// "pressed" on the host PC.
//
// Run as root (su). Optional args:
//   --daemonize   detach via daemon(0, 0)
//   --log PATH    also append every log line to PATH (for in-app diagnostics:
//                 after --daemonize, stderr is /dev/null, so the file is the
//                 only place the init trace survives)

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stddef.h>
#include <stdarg.h>
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
#endif

// Every log line goes to logcat (Android) or stderr (host) AND, when
// --log PATH was given, to that file (append, flushed per line).
static FILE *g_log_file = NULL;

static void gbp_log(int is_err, const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
#ifdef __ANDROID__
    __android_log_print(is_err ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO,
                        "gamepadd", "%s", buf);
#else
    (void)is_err;
    fprintf(stderr, "%s\n", buf);
    fflush(stderr);
#endif
    if (g_log_file) {
        fprintf(g_log_file, "%s\n", buf);
        fflush(g_log_file);
    }
}
#define LOGI(...) gbp_log(0, __VA_ARGS__)
#define LOGE(...) gbp_log(1, __VA_ARGS__)

#define SOCK_NAME "gbp_gamepad"
#define FRAME_SIZE 24

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

// Mouse button bit index -> evdev key code (frame v2 mouse section).
static const int kMouseKeyForBit[3] = {
    BTN_LEFT,   // 0 ЛКМ
    BTN_RIGHT,  // 1 ПКМ
    BTN_MIDDLE, // 2 СКМ
};

typedef struct __attribute__((packed)) {
    uint32_t buttons;
    int16_t lx, ly, rx, ry;
    uint8_t lt, rt;
    int8_t hatx, haty;
    uint8_t mouse_btns;
    uint8_t pad0;
    int16_t rel_x, rel_y;
    int8_t wheel;
    uint8_t pad1;
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
// Every init step is logged (with errno on failure) so a daemon that
// "just doesn't start" leaves a diagnosable trace.
static int create_device(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        LOGE("pad: open /dev/uinput failed: errno=%d (%s)", errno, strerror(errno));
        return -1;
    }
    LOGI("pad: /dev/uinput opened (fd=%d)", fd);

    int fails = 0;
    if (ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0) fails++;
    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0) fails++;
    if (ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0) fails++;
    if (fails) {
        LOGE("pad: UI_SET_EVBIT failed x%d: errno=%d (%s)", fails, errno, strerror(errno));
        close(fd);
        return -1;
    }
    for (int i = 0; i < 11; i++)
        if (ioctl(fd, UI_SET_KEYBIT, kKeyForBit[i]) < 0) fails++;
    static const int axes[8] = {
        ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_Z, ABS_RZ, ABS_HAT0X, ABS_HAT0Y
    };
    for (int i = 0; i < 8; i++)
        if (ioctl(fd, UI_SET_ABSBIT, axes[i]) < 0) fails++;
    if (fails) {
        LOGE("pad: UI_SET_KEYBIT/ABSBIT failed x%d: errno=%d (%s)", fails, errno, strerror(errno));
        close(fd);
        return -1;
    }
    LOGI("pad: capabilities set (11 buttons, 8 axes)");

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
        LOGE("pad: write uinput_user_dev failed: errno=%d (%s)", errno, strerror(errno));
        close(fd);
        return -1;
    }
    LOGI("pad: device descriptor written (\"Microsoft X-Box 360 pad\" 045e:028e)");
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("pad: UI_DEV_CREATE failed: errno=%d (%s)", errno, strerror(errno));
        close(fd);
        return -1;
    }
    // Give the kernel/userspace a moment to register the input device.
    usleep(200 * 1000);
    LOGI("pad: UI_DEV_CREATE ok — virtual Xbox 360 pad created");
    return fd;
}

// Create the virtual mouse "Gboard+ Mouse". Returns uinput fd or -1.
static int create_mouse_device(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        LOGE("mouse: open /dev/uinput failed: errno=%d (%s)", errno, strerror(errno));
        return -1;
    }
    LOGI("mouse: /dev/uinput opened (fd=%d)", fd);

    int fails = 0;
    if (ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0) fails++;
    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0) fails++;
    if (ioctl(fd, UI_SET_EVBIT, EV_REL) < 0) fails++;
    for (int i = 0; i < 3; i++)
        if (ioctl(fd, UI_SET_KEYBIT, kMouseKeyForBit[i]) < 0) fails++;
    if (ioctl(fd, UI_SET_RELBIT, REL_X) < 0) fails++;
    if (ioctl(fd, UI_SET_RELBIT, REL_Y) < 0) fails++;
    if (ioctl(fd, UI_SET_RELBIT, REL_WHEEL) < 0) fails++;
    if (fails) {
        LOGE("mouse: UI_SET_* failed x%d: errno=%d (%s)", fails, errno, strerror(errno));
        close(fd);
        return -1;
    }
    LOGI("mouse: capabilities set (3 buttons, REL_X/Y/WHEEL)");

    struct uinput_user_dev dev;
    memset(&dev, 0, sizeof(dev));
    snprintf(dev.name, UINPUT_MAX_NAME_SIZE, "Gboard+ Mouse");
    dev.id.bustype = BUS_USB;
    dev.id.vendor = 0;
    dev.id.product = 0;
    dev.id.version = 0;

    if (write(fd, &dev, sizeof(dev)) != (ssize_t)sizeof(dev)) {
        LOGE("mouse: write uinput_user_dev failed: errno=%d (%s)", errno, strerror(errno));
        close(fd);
        return -1;
    }
    LOGI("mouse: device descriptor written (\"Gboard+ Mouse\")");
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("mouse: UI_DEV_CREATE failed: errno=%d (%s)", errno, strerror(errno));
        close(fd);
        return -1;
    }
    usleep(200 * 1000);
    LOGI("mouse: UI_DEV_CREATE ok — virtual mouse created");
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

// Mouse half of a frame: buttons are diffed like the pad's; the relative
// deltas are emitted verbatim and then zeroed in the stored state so they
// fire exactly once.
static void apply_mouse_frame(int fd, const Frame *cur, Frame *prev) {
    int dirty = 0;
    for (int i = 0; i < 3; i++) {
        int now = (int)((cur->mouse_btns >> i) & 1u);
        int was = (int)((prev->mouse_btns >> i) & 1u);
        if (now != was) {
            emit_event(fd, EV_KEY, (uint16_t)kMouseKeyForBit[i], now);
            dirty = 1;
        }
    }
    if (cur->rel_x != 0) { emit_event(fd, EV_REL, REL_X, cur->rel_x); dirty = 1; }
    if (cur->rel_y != 0) { emit_event(fd, EV_REL, REL_Y, cur->rel_y); dirty = 1; }
    if (cur->wheel != 0) { emit_event(fd, EV_REL, REL_WHEEL, cur->wheel); dirty = 1; }
    if (dirty) emit_event(fd, EV_SYN, SYN_REPORT, 0);
    *prev = *cur;
    prev->rel_x = 0;
    prev->rel_y = 0;
    prev->wheel = 0;
}

// Apply one frame to both virtual devices (pad + mouse).
static void apply_both(int pad_fd, int mouse_fd, const Frame *cur, Frame *state) {
    apply_frame(pad_fd, cur, state);
    apply_mouse_frame(mouse_fd, cur, state);
}

static int make_server_socket(void) {
    int s = socket(AF_UNIX, SOCK_STREAM, 0);
    if (s < 0) {
        LOGE("socket: socket() failed: errno=%d (%s)", errno, strerror(errno));
        return -1;
    }
    LOGI("socket: created (fd=%d)", s);
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';  // abstract namespace
    strncpy(&addr.sun_path[1], SOCK_NAME, sizeof(addr.sun_path) - 2);
    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path)
                                + 1 + strlen(SOCK_NAME));
    if (bind(s, (struct sockaddr *)&addr, len) < 0) {
        LOGE("socket: bind @%s failed: errno=%d (%s) (already running?)",
             SOCK_NAME, errno, strerror(errno));
        close(s);
        return -1;
    }
    LOGI("socket: bind @%s ok", SOCK_NAME);
    if (listen(s, 2) < 0) {
        LOGE("socket: listen failed: errno=%d (%s)", errno, strerror(errno));
        close(s);
        return -1;
    }
    LOGI("socket: listen ok");
    return s;
}

int main(int argc, char **argv) {
    int want_daemonize = 0;
    const char *log_path = NULL;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--daemonize") == 0) {
            want_daemonize = 1;
        } else if (strcmp(argv[i], "--log") == 0 && i + 1 < argc) {
            log_path = argv[++i];
        }
    }
    // Open the log file BEFORE daemonize: the FILE* survives the fork, and
    // after daemon(0, 0) stderr points at /dev/null, so the file copy of the
    // log is the only trace the app can show the user.
    if (log_path) {
        g_log_file = fopen(log_path, "a");
        if (!g_log_file) {
            fprintf(stderr, "gamepadd: cannot open --log %s: %s\n",
                    log_path, strerror(errno));
        }
    }
    LOGI("gamepadd starting (pid=%d, uid=%d, daemonize=%d, log=%s)",
         (int)getpid(), (int)getuid(), want_daemonize,
         log_path ? log_path : "-");
    if (want_daemonize) {
        if (daemon(0, 0) < 0) {
            LOGE("daemon() failed: errno=%d (%s)", errno, strerror(errno));
        } else {
            LOGI("daemonized (pid=%d)", (int)getpid());
        }
    }
    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);

    int uinput = create_device();
    if (uinput < 0) {
        LOGE("init: pad creation failed — exiting");
        return 1;
    }

    int mouse = create_mouse_device();
    if (mouse < 0) {
        LOGE("init: mouse creation failed — exiting");
        ioctl(uinput, UI_DEV_DESTROY);
        close(uinput);
        return 1;
    }

    int server = make_server_socket();
    if (server < 0) {
        LOGE("init: server socket failed — exiting");
        ioctl(mouse, UI_DEV_DESTROY);
        close(mouse);
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
            LOGE("accept failed: errno=%d (%s)", errno, strerror(errno));
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
                apply_both(uinput, mouse, &cur, &state);
                have = 0;
            }
        }
        close(client);
        LOGI("client disconnected — zeroing state");
        Frame zero;
        memset(&zero, 0, sizeof(zero));
        apply_both(uinput, mouse, &zero, &state);
    }

    Frame zero;
    memset(&zero, 0, sizeof(zero));
    apply_both(uinput, mouse, &zero, &state);
    close(server);
    ioctl(mouse, UI_DEV_DESTROY);
    close(mouse);
    ioctl(uinput, UI_DEV_DESTROY);
    close(uinput);
    LOGI("bye");
    return 0;
}
