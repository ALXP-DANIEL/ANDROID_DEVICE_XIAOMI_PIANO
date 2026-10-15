// SPDX-License-Identifier: BSD-2-Clause-Patent
/*
 * piano-inject: put the selector and UEFI into an Android boot.img, the way
 * tools/pack.py does, on the device (recovery has no Python).
 *
 *   piano-inject BOOT.IMG SELECTOR.BIN UEFI.BIN OUT.IMG
 *   piano-inject --remove BOOT.IMG OUT.IMG
 *
 * BOOT.IMG may already carry a selector; it is taken out first, so running
 * this again replaces an older selector or UEFI, and --remove gives back the
 * plain boot.img. UEFI.BIN is the kernel part of the Mu-piano boot image
 * (BootShim + FD). The output has no AVB footer.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define PAGE           4096u
#define BRANCH_RANGE   (1u << 27)

static const char meta_magic[16] = "PIANO-UEFI-SEL1";

static void fail(const char *msg)
{
	fprintf(stderr, "piano-inject: %s\n", msg);
	exit(1);
}

static uint64_t align(uint64_t n, uint64_t a)
{
	return (n + a - 1) / a * a;
}

static uint32_t rd32(const uint8_t *p) { uint32_t v; memcpy(&v, p, 4); return v; }
static uint64_t rd64(const uint8_t *p) { uint64_t v; memcpy(&v, p, 8); return v; }
static void wr32(uint8_t *p, uint32_t v) { memcpy(p, &v, 4); }
static void wr64(uint8_t *p, uint64_t v) { memcpy(p, &v, 8); }

static uint8_t *load(const char *path, size_t *size)
{
	FILE *f = fopen(path, "rb");
	uint8_t *buf;

	if (!f)
		fail(path);
	fseek(f, 0, SEEK_END);
	*size = (size_t)ftell(f);
	fseek(f, 0, SEEK_SET);
	buf = malloc(*size ? *size : 1);
	if (!buf || fread(buf, 1, *size, f) != *size)
		fail(path);
	fclose(f);
	return buf;
}

static const uint8_t *find(const uint8_t *hay, size_t n, const void *needle, size_t m)
{
	for (size_t i = 0; i + m <= n; i++)
		if (!memcmp(hay + i, needle, m))
			return hay + i;
	return NULL;
}

/* Undo an earlier wrap: restore the kernel header and drop what follows it. */
static size_t unwrap(uint8_t *kernel, size_t size)
{
	uint32_t code1 = rd32(kernel + 4);
	uint64_t sel_off;
	const uint8_t *meta;

	if (code1 >> 26 != 0x5)
		fail("kernel code1 is not a branch");
	sel_off = 4 + (uint64_t)(code1 & 0x3ffffff) * 4;
	if (sel_off >= size)
		return size;            /* branch into the kernel: not wrapped */
	meta = find(kernel + sel_off, size - sel_off, meta_magic, sizeof(meta_magic));
	if (!meta || rd64(meta + 16) != sel_off)
		return size;
	wr32(kernel + 4, rd32(meta + 24));
	wr64(kernel + 16, rd64(meta + 32));
	if (rd64(meta + 56) && rd64(meta + 56) <= sel_off)
		return rd64(meta + 56);
	/* Older selectors did not keep the kernel size. The kernel file never
	 * extends past image_size, which also covers its BSS. */
	return sel_off < rd64(meta + 32) ? sel_off : rd64(meta + 32);
}

/* Write the boot image back with KERNEL in place of its kernel. */
static size_t write_boot(const char *path, const uint8_t *boot, const uint8_t *kernel,
			 uint64_t kernel_len)
{
	uint32_t kernel_size = rd32(boot + 8), ramdisk_size = rd32(boot + 12);
	uint32_t header_size = rd32(boot + 20), version = rd32(boot + 40);
	const uint8_t *ramdisk = boot + PAGE + align(kernel_size, PAGE);
	uint8_t header[PAGE] = { 0 };
	size_t img_size;
	uint8_t *img;
	FILE *f;

	/* Header: new kernel size, no boot signature (it no longer matches). */
	memcpy(header, boot, header_size);
	wr32(header + 8, (uint32_t)kernel_len);
	if (version == 4)
		wr32(header + 1580, 0);

	img_size = PAGE + align(kernel_len, PAGE) + align(ramdisk_size, PAGE);
	img = calloc(1, img_size);
	if (!img)
		fail("out of memory");
	memcpy(img, header, PAGE);
	memcpy(img + PAGE, kernel, kernel_len);
	memcpy(img + PAGE + align(kernel_len, PAGE), ramdisk, ramdisk_size);

	f = fopen(path, "wb");
	if (!f || fwrite(img, 1, img_size, f) != img_size || fclose(f))
		fail(path);
	return img_size;
}

int main(int argc, char **argv)
{
	size_t boot_size, sel_size, uefi_size;
	uint8_t *boot, *sel, *uefi, *out;
	uint32_t kernel_size, ramdisk_size, header_size, version, code1;
	uint64_t image_size, sel_off, uefi_off, end, total, out_size, kernel_len;
	const uint8_t *meta;
	size_t meta_off, img_size;
	int remove = argc == 4 && !strcmp(argv[1], "--remove");

	if (argc != 5 && !remove)
		fail("usage: piano-inject BOOT.IMG SELECTOR.BIN UEFI.BIN OUT.IMG\n"
		     "       piano-inject --remove BOOT.IMG OUT.IMG");
	boot = load(argv[1 + remove], &boot_size);

	if (boot_size < PAGE || memcmp(boot, "ANDROID!", 8))
		fail("not an Android boot image");
	kernel_size = rd32(boot + 8);
	ramdisk_size = rd32(boot + 12);
	header_size = rd32(boot + 20);
	version = rd32(boot + 40);
	if (version != 3 && version != 4)
		fail("only boot header v3/v4 is supported");
	if (header_size > PAGE || PAGE + align(kernel_size, PAGE) + ramdisk_size > boot_size)
		fail("boot image is truncated");

	uint8_t *kernel = boot + PAGE;

	if (kernel_size < 64 || memcmp(kernel, "MZ", 2) || memcmp(kernel + 56, "ARMd", 4))
		fail("kernel is not a raw arm64 Image");
	kernel_len = unwrap(kernel, kernel_size);
	code1 = rd32(kernel + 4);
	image_size = rd64(kernel + 16);
	if (code1 >> 26 != 0x5 || image_size < kernel_len || (rd64(kernel + 24) & 1))
		fail("unexpected kernel header");

	if (remove) {
		img_size = write_boot(argv[3], boot, kernel, kernel_len);
		printf("kernel %llu bytes, boot.img %zu bytes\n",
		       (unsigned long long)kernel_len, img_size);
		return 0;
	}
	sel = load(argv[2], &sel_size);
	uefi = load(argv[3], &uefi_size);

	meta = find(sel, sel_size, meta_magic, sizeof(meta_magic));
	if (!meta || find(meta + 1, sel_size - (size_t)(meta + 1 - sel), meta_magic, sizeof(meta_magic)))
		fail("selector metadata block not found exactly once");
	meta_off = (size_t)(meta - sel);

	sel_off = align(image_size, PAGE);
	uefi_off = align(sel_off + sel_size + 16384, 0x10000);
	end = uefi_off + uefi_size;
	total = align(end, PAGE);
	if (sel_off - 4 >= BRANCH_RANGE)
		fail("selector out of branch range");

	out_size = end;
	out = calloc(1, out_size);
	if (!out)
		fail("out of memory");
	memcpy(out, kernel, kernel_len);
	memcpy(out + sel_off, sel, sel_size);
	memcpy(out + uefi_off, uefi, uefi_size);

	uint8_t *m = out + sel_off + meta_off;
	wr64(m + 16, sel_off);
	wr32(m + 24, code1);
	wr32(m + 28, (uint32_t)image_size);
	wr64(m + 32, image_size);
	wr64(m + 40, uefi_off);
	wr64(m + 48, uefi_size);
	wr64(m + 56, kernel_len);
	wr32(out + 4, 0x14000000u | (uint32_t)((sel_off - 4) / 4));
	wr64(out + 16, total);

	img_size = write_boot(argv[4], boot, out, out_size);
	printf("kernel %llu bytes, selector at %#llx, uefi at %#llx, boot.img %zu bytes\n",
	       (unsigned long long)kernel_len, (unsigned long long)sel_off,
	       (unsigned long long)uefi_off, img_size);
	return 0;
}
