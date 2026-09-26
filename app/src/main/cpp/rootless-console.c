/*
 * rootless-console: bridges this process's stdin/stdout to a QEMU serial socket, so a plain
 * Termux terminal session becomes a live root console into the guest.
 *
 * Why this exists: the guest auto-logs-in as root on ttyAMA0 and QEMU exposes that serial line as
 * a UNIX socket (serial.sock). A Termux session, however, is a pty handed to a child process - so
 * something has to sit in the middle. This is that something, and it is deliberately tiny:
 * connect, put the tty in raw mode, shovel bytes both ways, exit on EOF.
 *
 * Usage: rootless-console <path-to-serial.sock> [mirror-log]
 *
 * The optional mirror log receives a copy of everything the GUEST writes (not what is typed). It
 * is how the app gets structured results back out of the guest: a scan prints framed rows, the app
 * parses the frames off this file, and the user sees a real list instead of reading a console.
 *
 * No credentials are involved anywhere: the guest's ttyAMA0 getty logs root in automatically, and
 * the socket is app-private, so this only works from inside the app's own process tree.
 */

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <termios.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>

#define BUF_SIZE 8192

/* Cap on the mirror log: a long capture run can stream for hours, and the app only ever parses the
 * tail of it, so stop writing once it is this large. */
#define LOG_MAX_BYTES (4 * 1024 * 1024)

static int g_stdin_fd = 0;
static int g_log_fd = -1;
static off_t g_log_written = 0;

static void restore_tty(void) {
    /* Terminal attributes are restored by closing the pty, nothing to do here. */
}

static int write_all(int fd, const char *buf, size_t len);

static void mirror_log(const char *buf, size_t len) {
    if (g_log_fd < 0 || g_log_written >= LOG_MAX_BYTES) return;
    if (write_all(g_log_fd, buf, len) == 0) g_log_written += (off_t) len;
}

/* Put the controlling terminal into raw mode: no echo, no line buffering, no CR/NL translation.
 * Without this the guest's shell sees doubled characters and only receives input on Enter. */
static void set_raw_mode(void) {
    struct termios tio;
    if (tcgetattr(g_stdin_fd, &tio) != 0) return;   /* not a tty (piped): leave it alone */
    cfmakeraw(&tio);
    /* Keep output post-processing so CRLF from the guest renders on its own line. */
    tio.c_oflag |= OPOST;
    tcsetattr(g_stdin_fd, TCSANOW, &tio);
}

static int connect_unix_socket(const char *path) {
    struct sockaddr_un addr;
    int fd;

    if (strlen(path) >= sizeof(addr.sun_path)) {
        fprintf(stderr, "rootless-console: socket path too long: %s\n", path);
        return -1;
    }

    fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        perror("rootless-console: socket");
        return -1;
    }

    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);

    if (connect(fd, (struct sockaddr *) &addr, sizeof(addr)) != 0) {
        fprintf(stderr, "rootless-console: cannot connect to %s: %s\n", path, strerror(errno));
        fprintf(stderr, "rootless-console: the guest is probably not running (no serial socket yet).\n");
        close(fd);
        return -1;
    }

    return fd;
}

/* Write everything, tolerating short writes and EINTR. Returns 0 on success, -1 on error. */
static int write_all(int fd, const char *buf, size_t len) {
    size_t off = 0;
    while (off < len) {
        ssize_t n = write(fd, buf + off, len - off);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return -1;
        off += (size_t) n;
    }
    return 0;
}

static int pump(const char *socket_path) {
    int sock = connect_unix_socket(socket_path);
    if (sock < 0) return 1;

    set_raw_mode();
    atexit(restore_tty);

    char buf[BUF_SIZE];

    for (;;) {
        struct pollfd fds[2];
        fds[0].fd = g_stdin_fd;
        fds[0].events = POLLIN;
        fds[0].revents = 0;
        fds[1].fd = sock;
        fds[1].events = POLLIN;
        fds[1].revents = 0;

        int rc = poll(fds, 2, -1);
        if (rc < 0) {
            if (errno == EINTR) continue;
            perror("rootless-console: poll");
            break;
        }

        /* Guest -> terminal */
        if (fds[1].revents & (POLLIN | POLLHUP)) {
            ssize_t n = read(sock, buf, sizeof(buf));
            if (n > 0) {
                mirror_log(buf, (size_t) n);
                if (write_all(STDOUT_FILENO, buf, (size_t) n) != 0) break;
            } else if (n == 0) {
                break;   /* guest closed the line */
            } else if (errno != EINTR) {
                break;
            }
        }

        /* Terminal -> guest. A CR is sent as-is: this is a serial tty in canonical mode. */
        if (fds[0].revents & (POLLIN | POLLHUP)) {
            ssize_t n = read(g_stdin_fd, buf, sizeof(buf));
            if (n > 0) {
                if (write_all(sock, buf, (size_t) n) != 0) break;
            } else if (n == 0) {
                break;   /* terminal went away */
            } else if (errno != EINTR) {
                break;
            }
        }

        if (fds[0].revents & (POLLERR | POLLNVAL)) break;
        if (fds[1].revents & (POLLERR | POLLNVAL)) break;
    }

    close(sock);
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2 || argc > 3) {
        fprintf(stderr, "usage: rootless-console <path-to-serial.sock> [mirror-log]\n");
        return 2;
    }

    if (argc == 3) {
        g_log_fd = open(argv[2], O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
        if (g_log_fd < 0)
            fprintf(stderr, "rootless-console: cannot open mirror log %s: %s\n", argv[2], strerror(errno));
        else
            g_log_written = lseek(g_log_fd, 0, SEEK_END);
    }

    int rc = pump(argv[1]);
    if (g_log_fd >= 0) close(g_log_fd);
    return rc;
}
