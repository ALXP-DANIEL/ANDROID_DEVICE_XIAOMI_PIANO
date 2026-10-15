// SPDX-License-Identifier: GPL-2.0-only
/*
 * Read the power and volume keys before Linux, through the SPMI PMIC
 * arbiter's observer channel (layout from Linux drivers/spmi/spmi-pmic-arb.c,
 * arbiter v7). Keys only need one-byte reads. The single PMIC write is the
 * restart reason, done just before a reset. No interrupt, clock or arbiter
 * setup is done.
 */
#include "selector.h"

#define ARB_CORE	0x0C400000UL	/* version, channel count, APID map */
#define ARB_CNFG	0x0C42D000UL	/* per-APID owner */
#define ARB_OBSV	0x0C440000UL	/* EE0 observer channels, 0x20 each */
#define ARB_CHNLS	0x0C500000UL	/* read/write channels, 0x1000 each */

#define PPID_PON	0x013		/* PON peripheral: power, vol-down */
#define PPID_GPIO	0x18D		/* GPIO peripheral: vol-up */
#define PPID_SDAM	0x071		/* SID 0 SDAM at 0x7100: restart reason */

static inline u32 rd32(unsigned long a) { return *(volatile u32 *)a; }
static inline void wr32(unsigned long a, u32 v) { *(volatile u32 *)a = v; }

static int find_apid(u16 ppid, u32 count, u16 *apid)
{
	for (u32 i = 0; i < count; i++) {
		u32 map = rd32(ARB_CORE + 0x2000 + 4 * i);

		if (!map || ((map >> 8) & 0xFFF) != ppid)
			continue;
		if ((rd32(ARB_CNFG + 4 * i) & 7) == 0) {
			*apid = (u16)i;
			return 1;
		}
	}
	return 0;
}

static int read_byte(u16 apid, u16 addr, u8 *val)
{
	unsigned long ch = ARB_OBSV + 0x20UL * apid;
	u64 start = timer_ticks(), limit = timer_freq() / 1000;

	wr32(ch, (1U << 27) | ((addr & 0xFFU) << 4));	/* EXT_READL, 1 byte */
	while (timer_ticks() - start < limit) {
		u32 st = rd32(ch + 0x08);

		if (st & 1) {
			if (st & 0xE)
				return 0;
			*val = (u8)rd32(ch + 0x18);
			return 1;
		}
	}
	return 0;
}

static int wait_done(unsigned long ch)
{
	u64 start = timer_ticks(), limit = timer_freq() / 1000;

	while (timer_ticks() - start < limit) {
		u32 st = rd32(ch + 0x08);

		if (st & 1)
			return !(st & 0xE);
	}
	return 0;
}

static int write_byte(u16 apid, u16 addr, u8 val)
{
	unsigned long ch = ARB_CHNLS + 0x1000UL * apid;

	wr32(ch + 0x10, val);				/* WDATA0 */
	wr32(ch, (0U << 27) | ((addr & 0xFFU) << 4));	/* EXT_WRITEL, 1 byte */
	return wait_done(ch);
}

static u32 arb_count(void)
{
	u32 ver = rd32(ARB_CORE), count = rd32(ARB_CORE + 4) & 0x7FF;

	if (ver < 0x70000000 || ver >= 0x80000000 || !count || count > 1024)
		return 0;
	return count;
}

/*
 * ABL reads the restart reason from the PMIC SDAM cell the kernel's
 * qcom,reboot-reason driver uses: byte 0x48, bits 1-7. Bit 0 is preserved.
 */
int pmic_set_restart_reason(u8 reason)
{
	u32 count = arb_count();
	u16 apid;
	u8 old;

	if (!count || !find_apid(PPID_SDAM, count, &apid) ||
	    !read_byte(apid, 0x7148, &old))
		return 0;
	return write_byte(apid, 0x7148, (u8)((old & 1) | (reason << 1)));
}

int keys_init(struct keys *k)
{
	u32 count = arb_count();

	k->ok = 0;
	if (!count)
		return 0;
	k->ok = find_apid(PPID_PON, count, &k->pon) &&
		find_apid(PPID_GPIO, count, &k->gpio);
	return k->ok;
}

/* Returns a KEY_* mask of the keys held right now, or 0 on failure. */
u32 keys_read(const struct keys *k)
{
	u8 pon, gpio;

	if (!k->ok || !read_byte(k->pon, 0x1310, &pon) ||
	    !read_byte(k->gpio, 0x8D10, &gpio))
		return 0;
	return ((pon & 0x80) ? KEY_POWER : 0) | ((pon & 0x40) ? KEY_DOWN : 0) |
	       ((gpio & 1) ? 0 : KEY_UP);
}
