/* SPDX-License-Identifier: BSD-2-Clause-Patent */
/*
 * Just enough flattened device tree walking to move the initrd: find
 * /chosen and rewrite linux,initrd-start and linux,initrd-end in place.
 * ABL always creates both, so the blob never has to grow.
 */
#include "selector.h"

#define FDT_MAGIC	0xD00DFEEDU
#define FDT_BEGIN_NODE	1U
#define FDT_END_NODE	2U
#define FDT_PROP	3U
#define FDT_NOP		4U
#define FDT_END		9U

static u32 be32(const void *p)
{
	const u8 *b = p;

	return (u32)b[0] << 24 | (u32)b[1] << 16 | (u32)b[2] << 8 | b[3];
}

static void put_be(u8 *p, u32 len, u64 v)
{
	for (u32 i = 0; i < len; i++)
		p[i] = (u8)(v >> (8 * (len - 1 - i)));
}

static int streq(const char *a, const char *b)
{
	while (*a && *a == *b)
		a++, b++;
	return *a == *b;
}

int fdt_set_initrd(void *fdt, u64 start, u64 end)
{
	u8 *base = fdt;
	int depth = 0, in_chosen = 0, done = 0;

	if (be32(base) != FDT_MAGIC)
		return 0;
	u8 *p = base + be32(base + 8);
	const char *strings = (const char *)base + be32(base + 12);

	for (;;) {
		u32 tag = be32(p);

		p += 4;
		if (tag == FDT_BEGIN_NODE) {
			const char *name = (const char *)p;
			u32 n = 0;

			while (name[n])
				n++;
			depth++;
			if (depth == 2 && streq(name, "chosen"))
				in_chosen = 1;
			p += (n + 4) & ~3U;
		} else if (tag == FDT_END_NODE) {
			if (in_chosen && depth == 2)
				return done == 3;
			depth--;
		} else if (tag == FDT_PROP) {
			u32 len = be32(p), nameoff = be32(p + 4);
			u8 *val = p + 8;

			if (in_chosen && depth == 2 && (len == 4 || len == 8)) {
				if (streq(strings + nameoff, "linux,initrd-start")) {
					put_be(val, len, start);
					done |= 1;
				} else if (streq(strings + nameoff, "linux,initrd-end")) {
					put_be(val, len, end);
					done |= 2;
				}
			}
			p = val + ((len + 3) & ~3U);
		} else if (tag != FDT_NOP) {
			return 0;		/* FDT_END or garbage */
		}
	}
}
