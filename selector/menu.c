/* SPDX-License-Identifier: BSD-2-Clause-Patent */
/*
 * Boot menu drawn straight into the framebuffer ABL leaves running
 * (cont_splash at 0xFC800000, 3200x2136, XRGB8888, stride 3200).
 * Runs with the MMU and caches off, before Linux, on ABL's display state.
 */
#include "selector.h"

#define FB_BASE		0xFC800000UL
#define FB_W		3200U
#define FB_H		2136U

#define TIMEOUT_S	5

#define C_BG		0x0D1117U
#define C_PANEL		0x1A1F2BU
#define C_BORDER	0x5CC8BEU
#define C_TEXT		0xF0F0F0U
#define C_DIM		0x8A93A3U
#define C_ACCENT	0x5CC8BEU

enum action {
	ACT_ANDROID, ACT_LINUX, ACT_UEFI, ACT_ADVANCED, ACT_SLOT_A, ACT_SLOT_B,
	ACT_RECOVERY, ACT_FASTBOOT, ACT_BACK,
};

/* Plain arrays, no pointers: the selector runs wherever ABL loads it. */
struct item {
	char title[24];
	char note[40];
	u8 action;
	u8 enabled;
};

struct page {
	char heading[32];
	u8 count;
	struct item items[6];
};

enum { PAGE_MAIN, PAGE_ADVANCED };

static struct page pages[2] = {
	[PAGE_MAIN] = { "Select operating system", 3, {
		{ "Android", "Default boot option", ACT_ANDROID, 1 },
		{ "Arch Linux", "Not included in this image", ACT_LINUX, 0 },
		{ "Advanced", "", ACT_ADVANCED, 1 },
	} },
	[PAGE_ADVANCED] = { "Advanced", 6, {
		{ "UEFI", "Not included in this image", ACT_UEFI, 0 },
		{ "Android slot A", "Fastboot: set_active a", ACT_SLOT_A, 1 },
		{ "Android slot B", "Fastboot: set_active b", ACT_SLOT_B, 1 },
		{ "Recovery", "Restart into recovery", ACT_RECOVERY, 1 },
		{ "Fastboot", "Restart into the bootloader", ACT_FASTBOOT, 1 },
		{ "Back", "Return to the main menu", ACT_BACK, 1 },
	} },
};

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

/*
 * With the MMU off every store goes straight to memory, so write 16 bytes
 * (four pixels) per store where the row allows it.
 */
static u32 dirty_lo = FB_H, dirty_hi;

/* Make everything drawn since the last call visible. */
static void present(void)
{
	if (dirty_lo < dirty_hi)
		fb_flush((u64)dirty_lo * FB_W * 4, (u64)dirty_hi * FB_W * 4);
	dirty_lo = FB_H;
	dirty_hi = 0;
}

static void fill(u32 x, u32 y, u32 w, u32 h, u32 color)
{
	u64 pair = ((u64)color << 32) | color;

	if (x >= FB_W || y >= FB_H)
		return;
	if (w > FB_W - x)
		w = FB_W - x;
	if (h > FB_H - y)
		h = FB_H - y;
	if (y < dirty_lo)
		dirty_lo = y;
	if (y + h > dirty_hi)
		dirty_hi = y + h;
	for (u32 r = y; r < y + h; r++) {
		volatile u32 *p = (volatile u32 *)FB_BASE + (u64)r * FB_W + x;
		u32 n = w;

		while (n && ((u64)p & 15)) {
			*p++ = color;
			n--;
		}
		for (; n >= 4; n -= 4, p += 4)
			__asm__ volatile("stp %0, %0, [%1]" : : "r"(pair), "r"(p) : "memory");
		while (n--)
			*p++ = color;
	}
}

/* Draws text with 5x7 glyphs scaled by s; returns the x after the text. */
static u32 text(u32 x, u32 y, u32 s, u32 color, const char *str)
{
	for (; *str; str++) {
		const u8 *g = font_glyph(*str);

		if (g)
			for (u32 r = 0; r < 7; r++)
				for (u32 c = 0; c < 5; c++)
					if (g[r] & (0x10 >> c))
						fill(x + c * s, y + r * s, s, s, color);
		x += 6 * s;
	}
	return x;
}

#define ITEM_X		120U
#define ITEM_W		(FB_W - 240U)
#define LIST_TOP	500U
#define LIST_SPAN	1270U		/* item area, above the footer line */
#define EDGE		8U

static const struct page *cur;

static u32 item_pitch(void)
{
	u32 p = LIST_SPAN / cur->count;

	return p > 320 ? 320 : p;
}

static u32 item_y(int i)
{
	return LIST_TOP + (u32)i * item_pitch();
}

static u32 item_h(void)
{
	return item_pitch() - 30;
}

/* Only the outline shows the selection, so moving it redraws a few lines. */
static void draw_outline(int i, int selected)
{
	u32 y = item_y(i), h = item_h(), c = selected ? C_BORDER : C_PANEL;

	fill(ITEM_X, y, ITEM_W, EDGE, c);
	fill(ITEM_X, y + h - EDGE, ITEM_W, EDGE, c);
	fill(ITEM_X, y, EDGE, h, c);
	fill(ITEM_X + ITEM_W - EDGE, y, EDGE, h, c);
}

static void draw_item(int i, int selected)
{
	const struct item *it = &cur->items[i];
	u32 y = item_y(i), h = item_h(), big = h >= 280;

	fill(ITEM_X, y, ITEM_W, h, C_PANEL);
	draw_outline(i, selected);
	u32 ts = big ? 12 : 10;

	/* An entry without a note centres its title in the card. */
	if (it->note[0]) {
		text(220, y + (big ? 50 : 30), ts, it->enabled ? C_TEXT : C_DIM, it->title);
		text(220, y + (big ? 175 : 130), big ? 7 : 6, C_DIM, it->note);
	} else {
		text(220, y + (h - 7 * ts) / 2, ts, it->enabled ? C_TEXT : C_DIM, it->title);
	}
}

static void draw_countdown(int seconds)
{
	char msg[] = "Auto-boot: 0s";

	fill(2100, 1880, 1000, 80, C_BG);
	if (seconds < 0)
		return;
	msg[11] = (char)('0' + seconds);
	text(2380, 1890, 9, C_ACCENT, msg);
}

/* The frame stays put between pages; only the heading and list change. */
static void draw_frame(void)
{
	fill(0, 0, FB_W, FB_H, C_BG);
	text(120, 130, 14, C_TEXT, "PIANO BOOT");
	text(2380, 160, 7, C_DIM, "Xiaomi Pad 8 Pro");
	fill(120, 300, FB_W - 240, 4, C_PANEL);
	fill(120, 1800, FB_W - 240, 4, C_PANEL);
	text(120, 1890, 7, C_DIM, "Volume keys to move - Power to select");
}

static void draw_page(int sel)
{
	fill(0, 360, FB_W, 1800 - 360, C_BG);
	text(120, 380, 9, C_TEXT, cur->heading);
	for (int i = 0; i < cur->count; i++)
		draw_item(i, i == sel);
	present();
}

/* Restart reasons from the kernel's qcom-reboot-reason driver. */
#define REASON_RECOVERY		0x01
#define REASON_BOOTLOADER	0x02
#define PSCI_SYSTEM_RESET	0x84000009UL

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

static void status(const char *msg)
{
	fill(2100, 1880, 1000, 80, C_BG);
	text(2100, 1890, 9, C_ACCENT, msg);
	present();
}

/* Returns 0 to boot Android. Anything unexpected falls back to Android. */
struct piano_meta {
	char magic[16];
	u64 selector_offset;
	u32 code1, image_size_lo;
	u64 image_size, uefi_offset, uefi_size;
	u32 linux_offset, linux_size;	/* Linux initramfs, from the kernel base */
	u32 image_offset, image_file;	/* mainline Image, 0 if none */
	u32 dtb_offset, payload_end;
	u64 linux_image_size;		/* from the Image header */
	char android_note[40];		/* e.g. "Evolution X 12.3 - Android 17" */
	char linux_note[40];		/* e.g. "Arch Linux ARM - Linux 7.2.6" */
};

extern u64 linux_jump[2];

/* 8-byte copy: the Image is tens of MB and DRAM is uncached here. */
static void copy64(u64 *d, const u64 *s, u64 bytes)
{
	for (u64 i = 0; i < bytes / 8; i++)
		d[i] = s[i];
}

int piano_menu(void *fdt, const struct piano_meta *meta, u64 base)
{
	struct keys k;
	u64 freq = timer_freq(), start, now;
	int sel = 0, left = TIMEOUT_S, counting = 1, mmu;
	u32 held, last;
	u8 act;

	if (!can_run() || freq < 1000000)
		return 0;

	if (meta->android_note[0])
		memcpy(pages[PAGE_MAIN].items[0].note, meta->android_note, 40);
	if (meta->linux_size) {
		pages[PAGE_MAIN].items[1].enabled = 1;
		if (meta->image_offset)
			memcpy(pages[PAGE_MAIN].items[1].note, "Mainline kernel", 16);
		else
			memcpy(pages[PAGE_MAIN].items[1].note, "Same kernel, Linux userspace", 29);
		if (meta->linux_note[0])
			memcpy(pages[PAGE_MAIN].items[1].note, meta->linux_note, 40);
	}
	if (meta->uefi_offset) {
		pages[PAGE_ADVANCED].items[0].enabled = 1;
		memcpy(pages[PAGE_ADVANCED].items[0].note, "Firmware and boot manager", 26);
	}

	mmu = mmu_on();
	keys_init(&k);
	cur = &pages[PAGE_MAIN];
	draw_frame();
	draw_countdown(left);
	draw_page(sel);

	/* Ignore keys already held when we start; act on fresh presses only. */
	last = keys_read(&k);
	start = timer_ticks();
	for (;;) {
		now = timer_ticks();
		if (counting) {
			int remain = TIMEOUT_S - (int)((now - start) / freq);

			if (remain <= 0) {
				act = ACT_ANDROID;
				break;
			}
			if (remain != left) {
				left = remain;
				draw_countdown(left);
				present();
			}
		}

		held = keys_read(&k);
		u32 pressed = held & ~last;

		last = held;
		if (pressed && counting) {
			counting = 0;
			draw_countdown(-1);
			present();
		}
		if (pressed & (KEY_UP | KEY_DOWN)) {
			int n = cur->count;
			int next = (sel + ((pressed & KEY_DOWN) ? 1 : n - 1)) % n;

			draw_outline(sel, 0);
			draw_outline(next, 1);
			present();
			sel = next;
		} else if ((pressed & KEY_POWER) && cur->items[sel].enabled) {
			act = cur->items[sel].action;
			if (act == ACT_ADVANCED || act == ACT_BACK) {
				cur = &pages[act == ACT_ADVANCED ? PAGE_ADVANCED : PAGE_MAIN];
				sel = act == ACT_BACK ? 3 : 0;
				draw_page(sel);
				continue;
			}
			break;
		}

		/* About 20 ms between key samples. */
		while (timer_ticks() - now < freq / 50)
			;
	}

	if (act == ACT_SLOT_A || act == ACT_SLOT_B) {
		/* ABL has no restart reason for a slot switch, and switching
		 * means rewriting the boot chain's GPT and the UFS boot LUN.
		 * Leave that to ABL: restart into fastboot for set_active. */
		status(act == ACT_SLOT_A ? "Run set_active a" : "Run set_active b");
		u64 t0 = timer_ticks();

		while (timer_ticks() - t0 < 3 * freq)
			;
		act = ACT_FASTBOOT;
	}

	if (act == ACT_RECOVERY || act == ACT_FASTBOOT) {
		status("Restarting...");
		if (mmu)
			mmu_off();
		restart(act == ACT_RECOVERY ? REASON_RECOVERY : REASON_BOOTLOADER);
	}

	if (act == ACT_UEFI) {
		status("Starting UEFI...");
		if (mmu)
			mmu_off();
		return 1;
	}

	if (act == ACT_LINUX && meta->image_offset) {
		/* The arm64 boot protocol wants the Image on a 2 MiB boundary;
		 * the first one past our payloads is free RAM. */
		u64 dest = (base + meta->payload_end + 0x1FFFFF) & ~0x1FFFFFUL;
		u64 start = base + meta->linux_offset;
		void *dtb = (void *)(base + meta->dtb_offset);

		status("Booting Linux...");
		copy64((u64 *)dest, (const u64 *)(base + meta->image_offset),
		       (meta->image_file + 7) & ~7U);
		/* Linux clears its own BSS; nothing else to prepare. */
		if (fdt_set_initrd(dtb, start, start + meta->linux_size)) {
			linux_jump[0] = dest;
			linux_jump[1] = (u64)dtb;
			if (mmu)
				mmu_off();
			return 2;
		}
		status("No initrd in our DT");
	} else if (act == ACT_LINUX) {
		/* Same kernel; only the initramfs changes, to our own. */
		u64 start = base + meta->linux_offset;

		if (fdt_set_initrd(fdt, start, start + meta->linux_size)) {
			status("Booting Linux...");
			if (mmu)
				mmu_off();
			return 0;
		}
		status("No initrd in DT");
	}

	/* The kernel takes a few seconds before its boot animation appears. */
	status("Booting Android...");
	if (mmu)
		mmu_off();
	return 0;
}

/* The compiler may emit calls to these for struct copies or clears. */
void *memset(void *d, int v, unsigned long n)
{
	volatile u8 *p = d;

	while (n--)
		*p++ = (u8)v;
	return d;
}

void *memcpy(void *d, const void *s, unsigned long n)
{
	volatile u8 *p = d;
	const u8 *q = s;

	while (n--)
		*p++ = *q++;
	return d;
}
