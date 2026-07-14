#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <vector>
#include <signal.h>

#define LOG_TAG "RikkaTermuxJni"

static char *copy_java_string(JNIEnv *env, jstring value) {
    if (value == nullptr) return nullptr;
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return nullptr;
    char *copy = strdup(chars);
    env->ReleaseStringUTFChars(value, chars);
    return copy;
}

static std::vector<char *> copy_java_string_array(JNIEnv *env, jobjectArray values) {
    std::vector<char *> result;
    if (values == nullptr) return result;
    const jsize length = env->GetArrayLength(values);
    result.reserve(static_cast<size_t>(length));
    for (jsize i = 0; i < length; i++) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        result.push_back(copy_java_string(env, value));
        env->DeleteLocalRef(value);
    }
    return result;
}

static void free_string_vector(std::vector<char *> &values) {
    for (char *value: values) {
        free(value);
    }
    values.clear();
}

static int open_pty_master(char *slave_name, size_t slave_name_size) {
    const int master = posix_openpt(O_RDWR | O_CLOEXEC);
    if (master < 0) return -1;
    if (grantpt(master) != 0 || unlockpt(master) != 0 || ptsname_r(master, slave_name, slave_name_size) != 0) {
        close(master);
        return -1;
    }
    return master;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_termux_terminal_JNI_createSubprocess(
        JNIEnv *env,
        jclass,
        jstring cmd,
        jstring cwd,
        jobjectArray args,
        jobjectArray envVars,
        jintArray processId,
        jint rows,
        jint columns) {
    char slave_name[128] = {};
    const int master = open_pty_master(slave_name, sizeof(slave_name));
    if (master < 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "open pty failed: %s", strerror(errno));
        return -1;
    }

    char *command = copy_java_string(env, cmd);
    char *working_dir = copy_java_string(env, cwd);
    std::vector<char *> java_args = copy_java_string_array(env, args);
    std::vector<char *> java_env = copy_java_string_array(env, envVars);

    std::vector<char *> argv;
    argv.reserve(java_args.size() + 2);
    argv.push_back(command);
    for (char *arg: java_args) {
        argv.push_back(arg);
    }
    argv.push_back(nullptr);

    java_env.push_back(nullptr);

    const pid_t pid = fork();
    if (pid < 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "fork failed: %s", strerror(errno));
        close(master);
        free(command);
        free(working_dir);
        free_string_vector(java_args);
        free_string_vector(java_env);
        return -1;
    }

    if (pid == 0) {
        // Child process
        setsid();
        const int slave = open(slave_name, O_RDWR);
        if (slave < 0) _exit(127);
        ioctl(slave, TIOCSCTTY, 0);

        winsize size = {};
        size.ws_row = static_cast<unsigned short>(rows);
        size.ws_col = static_cast<unsigned short>(columns);
        ioctl(slave, TIOCSWINSZ, &size);

        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        close(master);

        if (working_dir != nullptr && working_dir[0] != '\0') {
            chdir(working_dir);
        }

        execve(command, argv.data(), java_env.data());
        _exit(127);
    }

    // Parent process
    if (processId != nullptr) {
        jint process_ids[1] = {static_cast<jint>(pid)};
        env->SetIntArrayRegion(processId, 0, 1, process_ids);
    }

    free(command);
    free(working_dir);
    free_string_vector(java_args);
    free_string_vector(java_env);
    return master;
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_JNI_setPtyWindowSize(JNIEnv *, jclass, jint fd, jint rows, jint columns) {
    winsize size = {};
    size.ws_row = static_cast<unsigned short>(rows);
    size.ws_col = static_cast<unsigned short>(columns);
    ioctl(fd, TIOCSWINSZ, &size);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_termux_terminal_JNI_waitFor(JNIEnv *, jclass, jint pid) {
    int status = 0;
    if (waitpid(pid, &status, 0) < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return status;
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_JNI_close(JNIEnv *, jclass, jint fd) {
    close(fd);
}

/**
 * 向 PTY 文件描述符写入数据
 * @param fd PTY master fd
 * @param data 要写入的字节数组
 * @return 实际写入的字节数，失败返回 -1
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_termux_terminal_JNI_writeFd(
        JNIEnv *env,
        jclass,
        jint fd,
        jbyteArray data) {
    jsize len = env->GetArrayLength(data);
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) return -1;

    ssize_t written = write(fd, bytes, static_cast<size_t>(len));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    if (written < 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "write fd %d failed: %s", fd, strerror(errno));
        return -1;
    }
    return static_cast<jint>(written);
}

/**
 * 从 PTY 文件描述符读取数据
 * @param fd PTY master fd
 * @param buffer 接收数据的缓冲区
 * @return 实际读取的字节数，0 表示无数据，-1 表示错误
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_termux_terminal_JNI_readFd(
        JNIEnv *env,
        jclass,
        jint fd,
        jbyteArray buffer) {
    jsize len = env->GetArrayLength(buffer);
    jbyte *bytes = env->GetByteArrayElements(buffer, nullptr);
    if (bytes == nullptr) return -1;

    ssize_t read_count = read(fd, bytes, static_cast<size_t>(len));
    env->ReleaseByteArrayElements(buffer, bytes, 0);

    if (read_count < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            // Non-blocking read, no data available
            return 0;
        }
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "read fd %d failed: %s", fd, strerror(errno));
        return -1;
    }
    return static_cast<jint>(read_count);
}

/**
 * 向 PTY 中的进程发送信号
 * @param fd PTY master fd (未使用，保留以保持接口一致性)
 * @param signal 信号编号 (SIGINT=2, SIGTERM=15, SIGKILL=9, SIGQUIT=3)
 * 注意：通过 PTY 发送信号的最佳方式是向 slave 的前台进程组发送信号。
 * 这里使用 TIOSIGSEND 或直接通过 tcsetpgrp 获取前台进程组后 kill。
 * 简化实现：直接通过 kill(0, signal) 或者写入控制字符。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_JNI_sendSignal(
        JNIEnv *env,
        jclass,
        jint fd,
        jint signal) {
    // 通过 TIOSIGSEND ioctl 或者向 PTY 写入控制字符
    // 对于 SIGINT (2)，写入 Ctrl+C (0x03)
    // 对于 SIGQUIT (3)，写入 Ctrl+\ (0x1C)
    // 对于 SIGTSTP (20)，写入 Ctrl+Z (0x1A)
    // 对于 SIGTERM 和 SIGKILL，需要获取子进程 PID 后 kill

    unsigned char ctrl_char = 0;
    switch (signal) {
        case SIGINT:  // 2
            ctrl_char = 0x03;
            break;
        case SIGQUIT: // 3
            ctrl_char = 0x1C;
            break;
        case SIGTSTP: // 20
            ctrl_char = 0x1A;
            break;
        default:
            // SIGTERM/SIGKILL 需要在 Java 层通过 Process.destroy() 处理
            // 此处仅记录日志
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                "sendSignal: signal %d not supported via PTY control chars, use Process.destroy()", signal);
            return;
    }

    if (ctrl_char != 0) {
        write(fd, &ctrl_char, 1);
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG,
            "sendSignal: wrote control char 0x%02x for signal %d to fd %d", ctrl_char, signal, fd);
    }
}
