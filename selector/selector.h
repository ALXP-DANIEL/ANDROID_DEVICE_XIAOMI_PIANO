/* SPDX-License-Identifier: BSD-2-Clause-Patent */
#ifndef SELECTOR_H
#define SELECTOR_H

typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
typedef unsigned long u64;
typedef long s64;

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

u64 timer_ticks(void);
u64 timer_freq(void);

#endif
