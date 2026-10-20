/* SPDX-License-Identifier: BSD-2-Clause-Patent */
/*
 * A temporary 1:1 mapping so framebuffer stores can be merged into bursts.
 *
 * With the MMU off every access is Device-nGnRnE: each 4-byte store is its own
 * bus write, and filling the 27 MB framebuffer takes seconds. With the MMU on
 * and DRAM mapped as Normal memory, stores combine; with the framebuffer
 * cacheable, they are faster still.
 *
 * Map over a 39-bit VA, 1 GiB level-1 blocks plus 2 MiB blocks for 3-4 GiB:
 *   0x0000_0000 - 0x7FFF_FFFF  Device-nGnRnE, never executable (MMIO, IMEM)
 *   0x8000_0000 - upward       Normal non-cacheable (DRAM, the kernel, us)
 *   framebuffer                Normal write-back, cleaned by fb_flush()
 *
 * The data cache is on (SCTLR.C) but only the framebuffer is cacheable, so
 * only it ever needs cleaning; mmu_off() cleans and invalidates it.
 */
#include "selector.h"

#define MAIR_DEVICE	0x00UL		/* attr0: Device-nGnRnE */
#define MAIR_NORMAL_NC	0x44UL		/* attr1: Normal, inner/outer non-cacheable */
#define MAIR_NORMAL_WB	0xFFUL		/* attr2: Normal, write-back, RW-allocate */

#define FB_BASE		0xFC800000UL
#define FB_BYTES	0x02B00000UL	/* cont_splash reservation */

#define DESC_BLOCK	(1UL << 0)
#define DESC_ATTR(i)	((u64)(i) << 2)
#define DESC_TABLE	(3UL << 0)
#define DESC_SH_OUTER	(2UL << 8)
#define DESC_SH_INNER	(3UL << 8)
#define DESC_AF		(1UL << 10)
#define DESC_PXN	(1UL << 53)
#define DESC_UXN	(1UL << 54)

#define TCR_T0SZ_39	25UL
#define TCR_EPD1	(1UL << 23)
#define TCR_TG0_4K	(0UL << 14)

static u64 l1_table[512] __attribute__((aligned(4096)));
static u64 l2_table[512] __attribute__((aligned(4096)));	/* 3-4 GiB */
static u64 line_bytes;

static u64 saved_mair, saved_tcr, saved_ttbr0, saved_sctlr;

#define SYSREG_READ(name, var)	__asm__ volatile("mrs %0, " #name : "=r"(var))
#define SYSREG_WRITE(name, val)	__asm__ volatile("msr " #name ", %0" : : "r"((u64)(val)))

int mmu_on(void)
{
	u64 mmfr0, pa;

	SYSREG_READ(id_aa64mmfr0_el1, mmfr0);
	pa = mmfr0 & 0xF;			/* PARange; same encoding as TCR.IPS */
	if (pa > 6)
		return 0;

	for (u64 i = 0; i < 512; i++) {
		u64 base = i << 30;

		if (base < 0x80000000UL)
			l1_table[i] = base | DESC_BLOCK | DESC_ATTR(0) | DESC_AF |
				      DESC_PXN | DESC_UXN;
		else
			l1_table[i] = base | DESC_BLOCK | DESC_ATTR(1) | DESC_AF |
				      DESC_SH_OUTER;
	}
	for (u64 i = 0; i < 512; i++) {
		u64 base = (3UL << 30) + (i << 21);
		int fb = base + (2UL << 20) > FB_BASE && base < FB_BASE + FB_BYTES;

		l2_table[i] = base | DESC_BLOCK | DESC_AF |
			      (fb ? DESC_ATTR(2) | DESC_SH_INNER : DESC_ATTR(1) | DESC_SH_OUTER);
	}
	l1_table[3] = (u64)l2_table | DESC_TABLE;

	u64 ctr;
	SYSREG_READ(ctr_el0, ctr);
	line_bytes = 4UL << ((ctr >> 16) & 0xF);	/* DminLine */

	SYSREG_READ(mair_el1, saved_mair);
	SYSREG_READ(tcr_el1, saved_tcr);
	SYSREG_READ(ttbr0_el1, saved_ttbr0);
	SYSREG_READ(sctlr_el1, saved_sctlr);

	/* Table walks are non-cacheable, matching the caches being off. */
	SYSREG_WRITE(mair_el1, MAIR_DEVICE | (MAIR_NORMAL_NC << 8) | (MAIR_NORMAL_WB << 16));
	SYSREG_WRITE(tcr_el1, TCR_T0SZ_39 | TCR_TG0_4K | TCR_EPD1 | (pa << 32));
	SYSREG_WRITE(ttbr0_el1, (u64)l1_table);
	__asm__ volatile("dsb sy\n tlbi vmalle1\n dsb sy\n isb" ::: "memory");
	SYSREG_WRITE(sctlr_el1, saved_sctlr | 1 | (1UL << 2));	/* M and C */
	__asm__ volatile("isb" ::: "memory");
	return 1;
}

/* Push framebuffer bytes [from, to) out of the cache to the display. */
void fb_flush(u64 from, u64 to)
{
	if (!line_bytes)
		return;
	for (u64 a = (FB_BASE + from) & ~(line_bytes - 1); a < FB_BASE + to; a += line_bytes)
		__asm__ volatile("dc cvac, %0" : : "r"(a) : "memory");
	__asm__ volatile("dsb sy" ::: "memory");
}

void mmu_off(void)
{
	/* Clean and invalidate the only cacheable range before caches go off. */
	for (u64 a = FB_BASE; a < FB_BASE + FB_BYTES; a += line_bytes)
		__asm__ volatile("dc civac, %0" : : "r"(a) : "memory");
	__asm__ volatile("dsb sy" ::: "memory");
	SYSREG_WRITE(sctlr_el1, saved_sctlr);
	line_bytes = 0;
	__asm__ volatile("isb\n tlbi vmalle1\n dsb sy\n isb" ::: "memory");
	SYSREG_WRITE(mair_el1, saved_mair);
	SYSREG_WRITE(tcr_el1, saved_tcr);
	SYSREG_WRITE(ttbr0_el1, saved_ttbr0);
	__asm__ volatile("isb" ::: "memory");
}
