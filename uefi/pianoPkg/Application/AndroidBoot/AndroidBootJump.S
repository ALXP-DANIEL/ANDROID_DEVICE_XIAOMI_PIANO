/** @file
  Leave the MMU and caches the way ABL hands over to the kernel.

  SPDX-License-Identifier: BSD-2-Clause-Patent
**/

// VOID AndroidBootJump (UINT64 Entry, UINT64 Fdt)
.text
.global AndroidBootJump
.p2align 2
AndroidBootJump:
  msr   daifset, #0xf
  mov   x19, x0
  mov   x20, x1

  // Clean and invalidate every data cache level by set/way, so nothing
  // dirty from UEFI is written back over the kernel later.
  mrs   x0, clidr_el1
  and   x3, x0, #0x7000000
  lsr   x3, x3, #23              // LoC * 2
  cbz   x3, 5f
  mov   x10, #0                  // level * 2
1:
  add   x2, x10, x10, lsr #1     // level * 3
  lsr   x1, x0, x2
  and   x1, x1, #7               // cache type at this level
  cmp   x1, #2
  b.lt  4f                       // no data cache
  msr   csselr_el1, x10
  isb
  mrs   x1, ccsidr_el1
  and   x2, x1, #7
  add   x2, x2, #4               // log2 line size
  mov   x4, #0x3ff
  and   x4, x4, x1, lsr #3       // max way
  clz   w5, w4                   // way shift
  mov   x7, #0x7fff
  and   x7, x7, x1, lsr #13      // max set
2:
  mov   x9, x4
3:
  lsl   x6, x9, x5
  orr   x11, x10, x6
  lsl   x6, x7, x2
  orr   x11, x11, x6
  dc    cisw, x11
  subs  x9, x9, #1
  b.ge  3b
  subs  x7, x7, #1
  b.ge  2b
4:
  add   x10, x10, #2
  cmp   x3, x10
  b.gt  1b
5:
  mov   x10, #0
  msr   csselr_el1, x10
  dsb   sy
  isb

  // MMU, data and instruction caches off. UEFI maps RAM 1:1, so this code
  // keeps running at the same address.
  mrs   x0, sctlr_el1
  bic   x0, x0, #1               // M
  bic   x0, x0, #(1 << 2)        // C
  bic   x0, x0, #(1 << 12)       // I
  msr   sctlr_el1, x0
  isb
  ic    iallu
  tlbi  vmalle1
  dsb   sy
  isb

  mov   x0, x20                  // device tree
  mov   x1, xzr
  mov   x2, xzr
  mov   x3, xzr
  br    x19
