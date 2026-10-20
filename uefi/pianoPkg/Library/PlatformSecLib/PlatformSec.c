/**
  Copyright (c) 2011-2012, ARM Limited. All rights reserved.
  SPDX-License-Identifier: BSD-2-Clause-Patent
**/

#include <Library/PlatformSecLib.h>
#include <Library/ArmSmmuDetachLib.h>
#include <Library/IoLib.h>

#include "../MemorySerialPortLib/PianoLog.h"

//
// Keep the SMMU state ABL left, which the detach below wipes, so that
// AndroidBoot.efi can hand it back to Android.
//
STATIC
VOID
SaveSmmu (
  VOID
  )
{
  PIANO_SMMU  *S = (PIANO_SMMU *)(UINTN)PIANO_SMMU_BASE;
  UINT32      Id1;
  UINTN       PageShift;
  UINTN       NumPage;
  UINTN       Cb;
  UINT32      I;

  Id1       = MmioRead32 (PIANO_SMMU_MMIO + PIANO_SMMU_GR0_ID1);
  PageShift = (Id1 & BIT31) ? 16 : 12;
  NumPage   = 1 << (((Id1 >> 28) & 0x7) + 1);

  S->Magic   = 0;
  S->NumSmrg = MmioRead32 (PIANO_SMMU_MMIO + PIANO_SMMU_GR0_ID0) & 0xFF;
  S->NumCb   = Id1 & 0xFF;
  if (S->NumSmrg > PIANO_SMMU_MAX) {
    S->NumSmrg = PIANO_SMMU_MAX;
  }

  if (S->NumCb > PIANO_SMMU_MAX) {
    S->NumCb = PIANO_SMMU_MAX;
  }

  S->Scr0 = MmioRead32 (PIANO_SMMU_MMIO + PIANO_SMMU_GR0_SCR0);
  for (I = 0; I < S->NumSmrg; I++) {
    S->Smr[I]  = MmioRead32 (PIANO_SMMU_MMIO + PIANO_SMMU_GR0_SMR (I));
    S->S2cr[I] = MmioRead32 (PIANO_SMMU_MMIO + PIANO_SMMU_GR0_S2CR (I));
  }

  for (I = 0; I < S->NumCb; I++) {
    Cb         = PIANO_SMMU_MMIO + ((NumPage + I) << PageShift);
    S->Cbar[I]  = MmioRead32 (PIANO_SMMU_MMIO + (1 << PageShift) + PIANO_SMMU_GR1_CBAR (I));
    S->Cba2r[I] = MmioRead32 (PIANO_SMMU_MMIO + (1 << PageShift) + PIANO_SMMU_GR1_CBA2R (I));
    S->Sctlr[I] = MmioRead32 (Cb + PIANO_SMMU_CB_SCTLR);
    S->Tcr[I]   = MmioRead32 (Cb + PIANO_SMMU_CB_TCR);
    S->Tcr2[I]  = MmioRead32 (Cb + PIANO_SMMU_CB_TCR2);
    S->Mair0[I] = MmioRead32 (Cb + PIANO_SMMU_CB_MAIR0);
    S->Mair1[I] = MmioRead32 (Cb + PIANO_SMMU_CB_MAIR1);
    S->Ttbr0[I] = MmioRead64 (Cb + PIANO_SMMU_CB_TTBR0);
    S->Ttbr1[I] = MmioRead64 (Cb + PIANO_SMMU_CB_TTBR1);
  }

  S->Magic = PIANO_SMMU_MAGIC;
}

VOID
PlatformInitialize ()
{
  // Set MDP SIDs
  CONST UINT16 MdpStreams[] = { 0x800, 0x801, 0x804 };

  SaveSmmu ();

  // Detach IOMMU Domains
  ArmSmmuDetach (MdpStreams, ARRAY_SIZE (MdpStreams));
}
