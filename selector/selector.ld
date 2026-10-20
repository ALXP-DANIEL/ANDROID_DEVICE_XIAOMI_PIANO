/* SPDX-License-Identifier: BSD-2-Clause-Patent */
ENTRY(_start)
SECTIONS {
	. = 0;
	.text : { KEEP(*(.text.entry)) *(.text*) }
	.rodata : ALIGN(16) { KEEP(*(.rodata.meta)) *(.rodata*) }
	.data : ALIGN(16) { *(.data*) *(.bss*) *(COMMON) }
	. = ALIGN(16);
	.stack (NOLOAD) : { . += 16384; __stack_end = .; }
	__image_end = .;
	/DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) }
}
