/*
 * stage0: under bare QEMU, provides what the host-side "mini_init" does on real
 * Hyper-V BEFORE handing control to the guest /init
 * (see microsoft/WSL, src/linux/init/main.cpp, the function around
 * LX_INIT_UTILITY_VM_INIT_SOCKET_FD): an open fd number 100 (channel to the host)
 * and the WSL2_PID environment variable.
 *
 * Without this the native /init (in the patched WSA image this is wsainit, reached
 * via lspinit -> magiskinit -> wsainit) dies on the very
 * first call of InitEntryUtilityVm(): fcntl(100, F_SETFD, FD_CLOEXEC) sees
 * fd 100 closed (ENOENT/EBADF), that is a FATAL_ERROR, PID 1 exits,
 * the kernel panics "Attempted to kill init!".
 *
 * stage0 does not emulate the host protocol — it just provides a valid fd at slot 100
 * (a socketpair; the other end is deliberately leaked open so that writes to the channel
 * do not get SIGPIPE) and WSL2_PID=1, then execs /init as usual. After that
 * the real wsainit/lspinit/magiskinit run unchanged.
 *
 * Found 15.09.2026, see docs/findings.md "continuation 26".
 */
#include <sys/socket.h>
#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <dirent.h>
#include <time.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/types.h>

#define TARGET_FD 100

/* Parallel dumper: while the real chain (lspinit -> magiskinit ->
 * wsainit) hangs as the first process of the system, this fork reads
 * /proc/1/{status,wchan,stack,fd/*} once a second and prints to the same console — the only
 * way to see which system call PID 1 is stuck in without having
 * a single live shell inside the guest. It does not interfere with the main process: it shares
 * no file descriptors with it (except the unrelated fd 100/TARGET_FD,
 * which is not used here). */
static void dump_file(const char *path, const char *label) {
    char buf[4096];
    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        fprintf(stderr, "stage0-dump: %s: open: %s\n", label, strerror(errno));
        return;
    }
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n < 0) {
        fprintf(stderr, "stage0-dump: %s: read: %s\n", label, strerror(errno));
        return;
    }
    buf[n] = '\0';
    fprintf(stderr, "stage0-dump: %s: %s%s", label, buf, (n > 0 && buf[n - 1] == '\n') ? "" : "\n");
}

static void dump_fds(void) {
    DIR *d = opendir("/proc/1/fd");
    if (!d) {
        fprintf(stderr, "stage0-dump: /proc/1/fd: opendir: %s\n", strerror(errno));
        return;
    }
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (e->d_name[0] == '.') continue;
        char linkpath[300], target[300];
        snprintf(linkpath, sizeof(linkpath), "/proc/1/fd/%s", e->d_name);
        ssize_t n = readlink(linkpath, target, sizeof(target) - 1);
        if (n >= 0) {
            target[n] = '\0';
            fprintf(stderr, "stage0-dump: fd %s -> %s\n", e->d_name, target);
        }
    }
    closedir(d);
}

static void diagnostic_loop(void) {
    for (int i = 0; i < 120; i++) {
        struct timespec ts = {1, 0};
        nanosleep(&ts, NULL);
        fprintf(stderr, "=== stage0-dump t=%ds ===\n", i + 1);
        dump_file("/proc/1/status", "status(partial)");
        dump_file("/proc/1/wchan", "wchan");
        dump_file("/proc/1/stack", "stack");
        dump_fds();
        fflush(stderr);
    }
    _exit(0);
}

/* fake-host: holds the "host" end of the channel (sv[1]) in a SEPARATE process
 * and logs line by line + echoes back everything wsainit writes there.
 * Diagnostics on 15.09.2026 showed: PID 1 (init-distro) hangs forever in
 * recvfrom() on a unix socket right after sending LX_MINI_INIT_CREATE_INSTANCE_RESULT
 * — i.e. it waits for a REPLY from the host that nobody sends (sv[1] was just
 * leaked with no reader). The real LX_MINI_INIT_* protocol is unknown to us
 * (structures on the Windows host side, not in the open src/linux), so this is
 * not a protocol emulation but a blind echo — the goal is to get either unblocking
 * or a new, more informative message parsing error instead of silence. */
/* MESSAGE_HEADER from microsoft/WSL src/shared/inc/lxinitshared.h:
 *   LX_MESSAGE_TYPE MessageType; unsigned int MessageSize;
 *   unsigned int TransactionId; unsigned int TransactionStep;
 * — 4 fields of 4 bytes, 16 bytes total. wsainit hangs in recvfrom() IMMEDIATELY,
 * without even reaching its SendMessage (fake-host saw not a single
 * byte from it) — so this version of the function first waits for something from the host ITSELF.
 * We deliberately send a wrong (recognizable) MessageType: the code in
 * SocketChannel::ReceiveMessageOrClosed will either not collect byte-size for
 * the expected type (log "MessageSize is too small... expected type: X"), or
 * reach ValidateMessageHeader (log "Protocol error: ... Expected type: X")
 * — in both cases the log names the real expected message type as number X,
 * and we finally learn the protocol instead of silence. TransactionId=1, because
 * m_received_non_transaction_messages is incremented BEFORE receiving and must
 * match TransactionId for the first message. */
static void send_probe(int fd) {
    unsigned int header[4] = {
        999999,  /* MessageType — deliberately invalid, look for it in the log as "Expected type" */
        16,      /* MessageSize — header only, no payload */
        1,       /* TransactionId */
        0        /* TransactionStep = NONE */
    };
    ssize_t w = write(fd, header, sizeof(header));
    fprintf(stderr, "fake-host: sent probe header (%zd bytes)\n", w);
    fflush(stderr);
}

static void fake_host_loop(int fd) {
    unsigned char buf[8192];
    send_probe(fd);
    for (;;) {
        ssize_t n = read(fd, buf, sizeof(buf));
        if (n <= 0) {
            fprintf(stderr, "fake-host: read returned %zd (errno=%s) — channel closed, exiting\n", n, strerror(errno));
            _exit(0);
        }
        fprintf(stderr, "fake-host: received %zd bytes from wsainit:", n);
        for (ssize_t i = 0; i < n && i < 64; i++) fprintf(stderr, " %02x", buf[i]);
        fprintf(stderr, "%s\n", n > 64 ? " ..." : "");
        fflush(stderr);
        ssize_t w = write(fd, buf, (size_t)n);
        if (w < 0) {
            fprintf(stderr, "fake-host: echo write back failed: %s\n", strerror(errno));
        }
    }
}

int main(int argc, char **argv) {
    (void)argc;
    (void)argv;

    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) {
        perror("stage0: socketpair");
        _exit(1);
    }

    if (sv[0] != TARGET_FD) {
        if (dup2(sv[0], TARGET_FD) < 0) {
            perror("stage0: dup2");
            _exit(1);
        }
        close(sv[0]);
    }

    pid_t host = fork();
    if (host == 0) {
        fake_host_loop(sv[1]);
        _exit(0);
    } else if (host < 0) {
        perror("stage0: fork(fake_host)");
    }

    setenv("WSL2_PID", "1", 1);

    /* Split off the diagnostic dumper BEFORE exec: after fork() the parent (this
       same process, still PID 1) execs /lspinit and keeps living as
       PID 1 with the new image; the forked child stays a separate process
       and just watches PID 1 from outside. */
    pid_t dumper = fork();
    if (dumper == 0) {
        diagnostic_loop();
        _exit(0);
    } else if (dumper < 0) {
        perror("stage0: fork(dumper)");
    }

    /* stage0 itself takes the place of /init (the cpio symlink /init -> lspinit
       is replaced with stage0), so exec goes to the direct path rather than
       through /init — otherwise it would exec itself. */
    const char *next = "/lspinit";

    fprintf(stderr, "stage0: fd %d ready, WSL2_PID=1, exec %s\n", TARGET_FD, next);
    fflush(stderr);

    execv(next, (char *[]){(char *)next, NULL});
    perror("stage0: execv");
    _exit(1);
}
