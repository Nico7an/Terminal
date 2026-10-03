// Local processes on a pseudo-terminal, for the built-in Linux environment.
// Same approach as termux-app's termux.c (Apache 2.0, see LICENSE-termux-terminal).
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static jint throw_io(JNIEnv *env, const char *what) {
    char message[256];
    snprintf(message, sizeof(message), "%s: %s", what, strerror(errno));
    jclass io = (*env)->FindClass(env, "java/io/IOException");
    (*env)->ThrowNew(env, io, message);
    return -1;
}

/** NULL-terminated copy of a Java String[] (may be null). */
static char **to_c_array(JNIEnv *env, jobjectArray array) {
    if (array == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, array);
    char **out = calloc((size_t) n + 1, sizeof(char *));
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        const char *chars = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = strdup(chars);
        (*env)->ReleaseStringUTFChars(env, s, chars);
        (*env)->DeleteLocalRef(env, s);
    }
    return out;
}

static void free_c_array(char **array) {
    if (array == NULL) return;
    for (char **p = array; *p != NULL; p++) free(*p);
    free(array);
}

JNIEXPORT jint JNICALL
Java_com_nico7an_terminal_Pty_spawn(JNIEnv *env, jclass clazz, jstring cmd, jstring cwd, jobjectArray args,
                                    jobjectArray envs, jintArray pid_out, jint rows, jint columns) {
    (void) clazz;
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) return throw_io(env, "open /dev/ptmx");
    char pts_name[64];
    if (grantpt(ptm) || unlockpt(ptm) || ptsname_r(ptm, pts_name, sizeof(pts_name))) {
        close(ptm);
        return throw_io(env, "pty");
    }

    struct termios tios;
    tcgetattr(ptm, &tios);
    tios.c_iflag |= IUTF8;
    tios.c_iflag &= ~(IXON | IXOFF);
    tcsetattr(ptm, TCSANOW, &tios);
    struct winsize size = {.ws_row = (unsigned short) rows, .ws_col = (unsigned short) columns};
    ioctl(ptm, TIOCSWINSZ, &size);

    const char *cmd_chars = (*env)->GetStringUTFChars(env, cmd, NULL);
    const char *cwd_chars = (*env)->GetStringUTFChars(env, cwd, NULL);
    char *c_cmd = strdup(cmd_chars);
    char *c_cwd = strdup(cwd_chars);
    (*env)->ReleaseStringUTFChars(env, cmd, cmd_chars);
    (*env)->ReleaseStringUTFChars(env, cwd, cwd_chars);
    char **c_args = to_c_array(env, args);
    char **c_envs = to_c_array(env, envs);

    pid_t pid = fork();
    if (pid < 0) {
        free(c_cmd); free(c_cwd); free_c_array(c_args); free_c_array(c_envs);
        close(ptm);
        return throw_io(env, "fork");
    }
    if (pid > 0) {
        jint value = pid;
        (*env)->SetIntArrayRegion(env, pid_out, 0, 1, &value);
        free(c_cmd); free(c_cwd); free_c_array(c_args); free_c_array(c_envs);
        return ptm;
    }

    // Child: new session with the pty as controlling terminal, nothing inherited from the app.
    sigset_t signals;
    sigemptyset(&signals);
    sigprocmask(SIG_SETMASK, &signals, NULL);
    for (int s = 1; s < 32; s++) signal(s, SIG_DFL);
    close(ptm);
    setsid();
    int pts = open(pts_name, O_RDWR);
    if (pts < 0) _exit(126);
    ioctl(pts, TIOCSCTTY, 0);
    dup2(pts, 0);
    dup2(pts, 1);
    dup2(pts, 2);
    DIR *fds = opendir("/proc/self/fd");
    if (fds != NULL) {
        int self = dirfd(fds);
        struct dirent *entry;
        while ((entry = readdir(fds)) != NULL) {
            int fd = atoi(entry->d_name);
            if (fd > 2 && fd != self) close(fd);
        }
        closedir(fds);
    }
    clearenv();
    if (c_envs != NULL) for (char **e = c_envs; *e != NULL; e++) putenv(*e);
    if (chdir(c_cwd) != 0) chdir("/");
    execvp(c_cmd, c_args);
    char message[512];
    int n = snprintf(message, sizeof(message), "Impossible de lancer %s : %s\r\n", c_cmd, strerror(errno));
    if (n > 0) write(1, message, (size_t) n);
    _exit(127);
}

JNIEXPORT void JNICALL
Java_com_nico7an_terminal_Pty_resize(JNIEnv *env, jclass clazz, jint fd, jint rows, jint columns) {
    (void) env; (void) clazz;
    struct winsize size = {.ws_row = (unsigned short) rows, .ws_col = (unsigned short) columns};
    ioctl(fd, TIOCSWINSZ, &size);
}

/** Exit code, or -signal when killed. Blocking. */
JNIEXPORT jint JNICALL
Java_com_nico7an_terminal_Pty_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    (void) env; (void) clazz;
    int status;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return 0;
}
