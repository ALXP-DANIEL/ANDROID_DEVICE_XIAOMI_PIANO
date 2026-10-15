/* SPDX-License-Identifier: BSD-2-Clause-Patent */
/*
 * Selector entry. The packer rewrites the kernel's second instruction (the
 * branch to primary_entry) to branch here, so ABL's arguments arrive
 * untouched: x0 = FDT, x1..x3 = 0, MMU and D-cache off, EL1.
 *
 * We run piano_boot on our own stack, then restore every argument register,
 * the stack pointer and the interrupt mask, and continue at the kernel's
 * original primary_entry. Nothing in the kernel image is written at runtime.
 */
.section .text.entry,"ax",%progbits
.global _start
.type _start,%function
_start:
	mov	x19, x0			/* FDT */
	mov	x20, x1
	mov	x21, x2
	mov	x22, x3
	mov	x23, sp
	mrs	x24, daif
	msr	daifset, #15

	/* Kernel base = our address - selector_offset (from the metadata). */
	adr	x25, _start
	adr	x26, piano_meta
	ldr	x9, [x26, #16]		/* selector_offset */
	sub	x25, x25, x9

	adr	x9, __stack_end
	mov	sp, x9
	mov	x0, x19
	mov	x1, x26
	mov	x2, x25
	bl	piano_boot		/* 0 = Android, 1 = UEFI */

	cbz	w0, 1f
	/* UEFI: the payload after the selector starts like a Linux Image. */
	ldr	x9, [x26, #40]		/* uefi_offset from the kernel base */
	add	x9, x25, x9
	b	2f
1:
	/* Original primary_entry = base + 4 + imm26 * 4 from the saved branch. */
	ldr	w8, [x26, #24]		/* original code1 */
	sbfx	x8, x8, #0, #26
	add	x9, x25, #4
	add	x9, x9, x8, lsl #2
2:

	mov	sp, x23
	mov	x0, x19
	mov	x1, x20
	mov	x2, x21
	mov	x3, x22
	msr	daif, x24
	isb
	br	x9
.size _start, .-_start

/* Filled in by tools/pack.py. Layout is checked there. */
.section .rodata.meta,"a",%progbits
.balign 16
.global piano_meta
piano_meta:
	.ascii	"PIANO-UEFI-SEL1\0"	/* 0: magic */
	.quad	0			/* 16: selector_offset */
	.word	0			/* 24: original code1 */
	.word	0			/* 28: original image_size (low) */
	.quad	0			/* 32: original image_size */
	.quad	0			/* 40: uefi_offset, 0 if none */
	.quad	0			/* 48: uefi_size */
	.quad	0			/* 56: original kernel file size */
.size piano_meta, 64

.section .note.GNU-stack,"",%progbits
