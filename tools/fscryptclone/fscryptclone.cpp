#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

static bool directory_empty(const char* path) {
    DIR* d = opendir(path);
    if (!d) {
        fprintf(stderr, "opendir(%s): %s\n", path, strerror(errno));
        return false;
    }

    struct dirent* e;
    while ((e = readdir(d)) != nullptr) {
        if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, ".."))
            continue;

        closedir(d);
        return false;
    }

    closedir(d);
    return true;
}

static int get_policy(const char* path,
                      struct fscrypt_get_policy_ex_arg* arg) {
    int fd = open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) {
        fprintf(stderr, "open(%s): %s\n", path, strerror(errno));
        return -1;
    }

    memset(arg, 0, sizeof(*arg));
    arg->policy_size = sizeof(arg->policy);

    int rc = ioctl(fd, FS_IOC_GET_ENCRYPTION_POLICY_EX, arg);
    int saved = errno;
    close(fd);

    if (rc != 0) {
        errno = saved;
        fprintf(stderr, "GET_POLICY(%s): %s\n", path, strerror(errno));
        return -1;
    }

    return 0;
}

int main(int argc, char** argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: %s SOURCE_DIR EMPTY_DEST_DIR\n", argv[0]);
        return 2;
    }

    const char* src = argv[1];
    const char* dst = argv[2];

    if (!directory_empty(dst)) {
        fprintf(stderr, "REFUSING: destination is not empty: %s\n", dst);
        return 3;
    }

    struct fscrypt_get_policy_ex_arg src_policy;
    if (get_policy(src, &src_policy) != 0)
        return 4;

    // Android fscrypt v2 policy version is 2.
    if (src_policy.policy.version != 2) {
        fprintf(stderr,
                "REFUSING: source policy version is %u, expected v2\n",
                src_policy.policy.version);
        return 5;
    }

    int fd = open(dst, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) {
        fprintf(stderr, "open(%s): %s\n", dst, strerror(errno));
        return 6;
    }

    if (ioctl(fd,
              FS_IOC_SET_ENCRYPTION_POLICY,
              &src_policy.policy.v2) != 0) {
        fprintf(stderr,
                "SET_POLICY(%s): %s\n",
                dst,
                strerror(errno));
        close(fd);
        return 7;
    }

    close(fd);

    struct fscrypt_get_policy_ex_arg verify;
    if (get_policy(dst, &verify) != 0)
        return 8;

    if (verify.policy.version != src_policy.policy.version ||
        memcmp(&verify.policy.v2,
               &src_policy.policy.v2,
               sizeof(src_policy.policy.v2)) != 0) {
        fprintf(stderr, "VERIFY FAILED: policy mismatch\n");
        return 9;
    }

    printf("POLICY CLONE VERIFIED: %s -> %s\n", src, dst);
    return 0;
}
