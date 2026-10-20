// SPDX-License-Identifier: BSD-2-Clause-Patent
// rb <reason>: restart with a reason (bootloader, recovery, ...), which
// busybox reboot cannot pass. Static, no libc: raw syscalls only.
#define SYS_sync	81
#define SYS_reboot	142
#define SYS_exit	93

static long sys(long n, long a, long b, long c, long d)
{
	register long x8 __asm__("x8") = n, x0 __asm__("x0") = a,
		x1 __asm__("x1") = b, x2 __asm__("x2") = c, x3 __asm__("x3") = d;
	__asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3) : "memory");
	return x0;
}

void _start_c(long *sp)
{
	char **argv = (char **)(sp + 1);

	sys(SYS_sync, 0, 0, 0, 0);
	/* LINUX_REBOOT_CMD_RESTART2 */
	sys(SYS_reboot, 0xfee1dead, 672274793, 0xA1B2C3D4, (long)(argv[1] ? argv[1] : ""));
	sys(SYS_exit, 1, 0, 0, 0);
}

__asm__(".global _start\n_start:\n mov x0, sp\n bl _start_c\n");
