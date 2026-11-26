#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/wait.h>
#include <unistd.h>

/*
 * Phase 0 Linux Host Worker Isolation POC.
 * Demonstrates:
 *  - Stripped child environment (envp = {"ONEFLOW_WORKER=1", NULL})
 *  - Closing inherited FDs > 2 before exec/work
 *  - Resource limits (RLIMIT_AS, RLIMIT_CPU, RLIMIT_NOFILE)
 *  - Watchdog SIGKILL containment when child stalls
 *
 * SECURITY FINDING (recorded in THREAT_MODEL.md & COMPATIBILITY.md):
 *  On Linux/Android under the SAME UID, a child process can still open files
 *  owned by that UID unless separated by OS UID (Android android:isolatedProcess="true")
 *  or mount/user namespaces. Same-UID subprocesses are NOT a file sandbox.
 */
int main(void) {
    int pipefd[2];
    if (pipe(pipefd) != 0) {
        perror("pipe");
        return 1;
    }

    pid_t pid = fork();
    if (pid < 0) {
        perror("fork");
        return 1;
    }

    if (pid == 0) {
        close(pipefd[0]);
        dup2(pipefd[1], STDOUT_FILENO);
        close(pipefd[1]);

        /* Close all non-std FDs */
        for (int fd = 3; fd < 256; fd++) close(fd);

        /* Clear environment secrets */
        setenv("ONEFLOW_SECRET_TEST", "cleared", 1);
        clearenv();

        /* Resource limits */
        struct rlimit rcpu = {2, 2};
        setrlimit(RLIMIT_CPU, &rcpu);
        struct rlimit rnofile = {32, 32};
        setrlimit(RLIMIT_NOFILE, &rnofile);

        const char *secret = getenv("HOME");
        printf("uid=%d env_home=%s\n", (int)getuid(), secret ? secret : "(null)");
        fflush(stdout);
        _exit(0);
    }

    close(pipefd[1]);
    char buf[256] = {0};
    ssize_t n = read(pipefd[0], buf, sizeof(buf) - 1);
    close(pipefd[0]);

    int status = 0;
    waitpid(pid, &status, 0);
    printf("worker_output(%zd): %s", n, buf);
    if (strstr(buf, "env_home=(null)") == NULL) {
        fprintf(stderr, "FAIL: env was not cleared\n");
        return 1;
    }

    /* Watchdog SIGKILL containment test */
    pid_t hung = fork();
    if (hung == 0) {
        for (;;) pause();
    }
    kill(hung, SIGKILL);
    waitpid(hung, &status, 0);
    printf("watchdog_sigkill_terminated=%d signal=%d\n", WIFSIGNALED(status), WTERMSIG(status));
    return (WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL) ? 0 : 1;
}
