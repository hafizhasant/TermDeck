#define _XOPEN_SOURCE 600
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdlib.h>
#include <stdio.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <errno.h>

// Starts sh inside a real pseudo-terminal.
// Stdin goes to the shell, and the shell's screen output goes to stdout.
int main(void) {
    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) { perror("posix_openpt"); return 1; }
    if (grantpt(master) < 0 || unlockpt(master) < 0) { perror("grantpt"); return 1; }
    char *slave_name = ptsname(master);
    if (!slave_name) { perror("ptsname"); return 1; }

    struct winsize ws = {24, 80, 0, 0};
    ioctl(master, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid < 0) { perror("fork"); return 1; }
    if (pid == 0) {
        setsid();
        int slave = open(slave_name, O_RDWR);
        if (slave < 0) _exit(126);
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2) close(slave);
        close(master);
        setenv("TERM", "xterm-256color", 1);
        execl("/system/bin/sh", "sh", "-i", (char *)NULL);
        _exit(127);
    }

    struct pollfd fds[2] = { {0, POLLIN, 0}, {master, POLLIN, 0} };
    char buf[4096];
    for (;;) {
        if (poll(fds, 2, -1) < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (fds[0].revents & (POLLIN | POLLHUP)) {
            ssize_t n = read(0, buf, sizeof buf);
            if (n <= 0) break;
            if (write(master, buf, (size_t)n) < 0) break;
        }
        if (fds[1].revents & (POLLIN | POLLHUP)) {
            ssize_t n = read(master, buf, sizeof buf);
            if (n <= 0) break;
            if (write(1, buf, (size_t)n) < 0) break;
        }
    }
    kill(pid, SIGHUP);
    waitpid(pid, NULL, 0);
    return 0;
}
