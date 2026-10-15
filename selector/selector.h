/* SPDX-License-Identifier: BSD-2-Clause-Patent */
#ifndef SELECTOR_H
#define SELECTOR_H

typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
typedef unsigned long u64;

#define KEY_UP		1U
#define KEY_DOWN	2U
#define KEY_POWER	4U

struct keys {
	int ok;
	u16 pon, gpio;
};

int keys_init(struct keys *k);
u32 keys_read(const struct keys *k);
int pmic_set_restart_reason(u8 reason);

int mmu_on(void);
void mmu_off(void);
void fb_flush(u64 from, u64 to);

u64 timer_ticks(void);
u64 timer_freq(void);

void *memcpy(void *d, const void *s, unsigned long n);
void *memset(void *d, int v, unsigned long n);

/* 5x7 glyph rows for c, low 5 bits each; NULL if not in the font. */
const u8 *font_glyph(char c);

/* Point /chosen's initrd at [start, end); 0 if the properties are missing. */
int fdt_set_initrd(void *fdt, u64 start, u64 end);

#endif
