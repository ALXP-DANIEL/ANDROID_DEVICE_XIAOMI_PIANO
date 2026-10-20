/** @file
  RAM log written by MemorySerialPortLib and read by the debug fastboot.

  SPDX-License-Identifier: BSD-2-Clause-Patent
**/
#ifndef PIANO_LOG_H_
#define PIANO_LOG_H_

// "Piano_Log" in MemoryMapLib: reserved, so Linux leaves it alone. Not in
// "Kernel": ABL puts Android's kernel, ramdisk and device tree there.
#define PIANO_LOG_BASE   0xD7F00000
#define PIANO_LOG_SIZE   0x100000
#define PIANO_LOG_MAGIC  0x474F4C50   // "PLOG"

typedef struct {
  UINT32    Magic;
  UINT32    Head;       // next write offset in Data
  UINT32    Wrapped;    // Data is full and Head is the oldest byte
  UINT32    Reserved;
  UINT8     Data[];
} PIANO_LOG;

// The last three pages are the SMMU snapshot and the Android handoff, so
// the log stops before them.
#define PIANO_LOG_DATA_SIZE  (PIANO_LOG_SIZE - 0x3000 - sizeof (PIANO_LOG))

//
// Written by the selector (selector/boot.c) before it starts UEFI: where
// ABL left Android, for AndroidBoot.efi to start it without a restart.
//
#define PIANO_HANDOFF_BASE   (PIANO_LOG_BASE + PIANO_LOG_SIZE - 0x1000)
#define PIANO_HANDOFF_MAGIC  0x444E41504F4E4950ULL   // "PIANOPAND"

typedef struct {
  UINT64    Magic;
  UINT64    Base;       // kernel load address
  UINT64    Entry;      // the kernel's own primary_entry
  UINT64    Fdt;        // ABL's device tree: ramdisk, command line
  UINT64    ImageSize;
} PIANO_HANDOFF;

//
// Written by PlatformSecLib before it detaches the SMMU for UEFI's display:
// the stream mappings and context banks ABL left. AndroidBoot.efi puts them
// back before it starts Android, whose DSPs and USB need them.
//
#define PIANO_SMMU_BASE      (PIANO_HANDOFF_BASE - 0x2000)
#define PIANO_SMMU_MAGIC     0x554D4D53   // "SMMU"
#define PIANO_SMMU_MAX       128

typedef struct {
  UINT32    Magic;
  UINT32    NumSmrg;
  UINT32    NumCb;
  UINT32    Scr0;
  UINT32    Smr[PIANO_SMMU_MAX];
  UINT32    S2cr[PIANO_SMMU_MAX];
  UINT32    Cbar[PIANO_SMMU_MAX];
  UINT32    Tcr[PIANO_SMMU_MAX];
  UINT32    Tcr2[PIANO_SMMU_MAX];
  UINT32    Sctlr[PIANO_SMMU_MAX];
  UINT32    Mair0[PIANO_SMMU_MAX];
  UINT32    Mair1[PIANO_SMMU_MAX];
  UINT32    Cba2r[PIANO_SMMU_MAX];
  UINT64    Ttbr0[PIANO_SMMU_MAX];
  UINT64    Ttbr1[PIANO_SMMU_MAX];
} PIANO_SMMU;

// ARM SMMUv2 registers the snapshot covers ("SMMU" in MemoryMapLib).
#define PIANO_SMMU_MMIO         0x15000000
#define PIANO_SMMU_GR0_ID0      0x20
#define PIANO_SMMU_GR0_ID1      0x24
#define PIANO_SMMU_GR0_SMR(n)   (0x800 + (n) * 4)
#define PIANO_SMMU_GR0_S2CR(n)  (0xC00 + (n) * 4)
#define PIANO_SMMU_GR0_SCR0     0x00
#define PIANO_SMMU_GR1_CBAR(n)  ((n) * 4)
#define PIANO_SMMU_GR1_CBA2R(n) (0x800 + (n) * 4)
#define PIANO_SMMU_CB_SCTLR     0x00
#define PIANO_SMMU_CB_TCR2      0x10
#define PIANO_SMMU_CB_MAIR0     0x38
#define PIANO_SMMU_CB_MAIR1     0x3C
#define PIANO_SMMU_CB_TTBR0     0x20
#define PIANO_SMMU_CB_TTBR1     0x28
#define PIANO_SMMU_CB_TCR       0x30

#endif
