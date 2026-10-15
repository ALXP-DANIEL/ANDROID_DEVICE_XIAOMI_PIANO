/* SPDX-License-Identifier: BSD-2-Clause-Patent */
/*
 * Runs before the kernel and picks what starts, from the keys held while
 * the Mi logo shows. Visor (in UEFI) is the menu; this has no screen.
 *
 *   nothing held   UEFI, or Android when the image has no UEFI
 *   volume down    restart into fastboot
 *   volume up      restart into recovery
 *
 * A boot into recovery goes straight to the kernel.
 *
 * Runs with the MMU and caches off, at EL1, on ABL's state.
 */
#include "selector.h"

/* Restart reasons from the kernel's qcom-reboot-reason driver. */
#define REASON_RECOVERY		0x01
#define REASON_BOOTLOADER	0x02
#define PSCI_SYSTEM_RESET	0x84000009UL

u64 timer_ticks(void)
{
	u64 t;

	__asm__ volatile("isb\n mrs %0, cntvct_el0" : "=r"(t));
	return t;
}

u64 timer_freq(void)
{
	u64 f;

	__asm__ volatile("mrs %0, cntfrq_el0" : "=r"(f));
	return f;
}

static void restart(u8 reason)
{
	pmic_set_restart_reason(reason);
	register u64 x0 __asm__("x0") = PSCI_SYSTEM_RESET;
	__asm__ volatile("smc #0" : "+r"(x0) : : "x1", "x2", "x3", "memory");
	for (;;)	/* PSCI does not return; if it does, stay put. */
		__asm__ volatile("wfe");
}

static int can_run(void)
{
	u64 el, sctlr;

	__asm__ volatile("mrs %0, CurrentEL" : "=r"(el));
	if (el != 4)			/* EL1 only */
		return 0;
	__asm__ volatile("mrs %0, sctlr_el1" : "=r"(sctlr));
	return !(sctlr & 1);		/* MMU must be off */
}

struct piano_meta {
	char magic[16];
	u64 selector_offset;
	u32 code1, image_size_lo;
	u64 image_size, uefi_offset, uefi_size;
};

/*
 * Where ABL left Android, for UEFI to start it later without a restart:
 * the kernel entry and ABL's device tree, which holds the ramdisk and the
 * command line. Kept in the last page of UEFI's reserved Piano_Log region.
 */
#define ANDROID_HANDOFF		0xD7FFF000UL
#define ANDROID_MAGIC		0x444E41504F4E4950UL	/* "PIANOPAND" */

static void android_handoff(void *fdt, const struct piano_meta *meta, u64 base)
{
	volatile u64 *h = (volatile u64 *)ANDROID_HANDOFF;
	u64 code1 = meta->code1;
	s64 imm = (s64)(code1 << 38) >> 38;	/* imm26 */

	h[1] = base;
	h[2] = base + 4 + (u64)(imm * 4);	/* original primary_entry */
	h[3] = (u64)fdt;
	h[4] = meta->image_size;
	h[0] = ANDROID_MAGIC;
}

/* ABL's splash framebuffer (cont_splash reservation). */
#define FB_BASE		0xFC800000UL
#define FB_BYTES	0x02B00000UL

/*
 * ABL leaves splash lines in the data cache. Clean and invalidate them
 * before UEFI takes the display, or a late eviction writes ABL's stale
 * pixels over what UEFI and then Android put there, and Android started
 * from Visor crashes. dc civac works by address with the MMU off.
 */
static void fb_clean(void)
{
	u64 ctr, line;

	__asm__ volatile("mrs %0, ctr_el0" : "=r"(ctr));
	line = 4UL << ((ctr >> 16) & 0xF);	/* DminLine */
	for (u64 a = FB_BASE; a < FB_BASE + FB_BYTES; a += line)
		__asm__ volatile("dc civac, %0" : : "r"(a) : "memory");
	__asm__ volatile("dsb sy" ::: "memory");
}

/*
 * A restart into recovery loads this same boot image; ABL then says so in
 * the command line in its device tree. Look for it anywhere in the blob:
 * the string only appears in /chosen's bootargs.
 */
static int recovery_boot(const u8 *fdt)
{
	static const char want[] = "bootmonitor.bootmode=recovery";
	u32 size;

	if (fdt[0] != 0xD0 || fdt[1] != 0x0D || fdt[2] != 0xFE || fdt[3] != 0xED)
		return 0;
	size = (u32)fdt[4] << 24 | (u32)fdt[5] << 16 | (u32)fdt[6] << 8 | fdt[7];
	for (u32 i = 0; i + sizeof(want) - 1 <= size; i++) {
		u32 n = 0;

		while (n < sizeof(want) - 1 && fdt[i + n] == (u8)want[n])
			n++;
		if (n == sizeof(want) - 1)
			return 1;
	}
	return 0;
}

/* Returns 0 to boot Android, 1 to start UEFI. */
int piano_boot(void *fdt, const struct piano_meta *meta, u64 base)
{
	struct keys k;
	u32 held;

	if (!can_run())
		return 0;

	keys_init(&k);
	held = keys_read(&k);
	if (held & KEY_DOWN)
		restart(REASON_BOOTLOADER);
	if (held & KEY_UP)
		restart(REASON_RECOVERY);

	if (!meta->uefi_offset || recovery_boot(fdt))
		return 0;
	fb_clean();
	android_handoff(fdt, meta, base);
	return 1;
}
