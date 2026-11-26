#include <stdio.h>
#include <sys/utsname.h>
#include <unistd.h>

int main(void) {
    struct utsname u;
    uname(&u);
    printf("sys=%s machine=%s uid=%d\n", u.sysname, u.machine, (int)getuid());
    return 0;
}
