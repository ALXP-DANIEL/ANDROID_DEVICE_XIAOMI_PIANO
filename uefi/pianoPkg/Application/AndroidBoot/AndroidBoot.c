/** @file
  Start Android from UEFI without a restart.

  ABL loaded the Android kernel, its ramdisk and its device tree before the
  selector started UEFI, and the selector wrote down where (PianoLog.h). UEFI
  keeps out of that memory ("Kernel" in MemoryMapLib), so all three are still
  there: leave UEFI and jump to the kernel the way ABL would have, with ABL's
  device tree in x0, MMU and caches off.

  SPDX-License-Identifier: BSD-2-Clause-Patent
**/
#include <Uefi.h>
#include <Library/BaseLib.h>
#include <Library/IoLib.h>
#include <Library/BaseMemoryLib.h>
#include <Library/MemoryAllocationLib.h>
#include <Library/UefiBootServicesTableLib.h>
#include <Library/UefiLib.h>
#include <Protocol/EFIScm.h>

#include "../../Library/MemorySerialPortLib/PianoLog.h"

// AndroidBootJump.S: clean the caches, turn the MMU off, branch to Entry.
VOID
AndroidBootJump (
  IN UINT64  Entry,
  IN UINT64  Fdt
  );

//
// UEFI's UFS driver serves TrustZone's RPMB requests through a listener.
// Drop it, and every other UEFI listener, the way ABL does before Linux:
// otherwise qseecomd cannot register its own and keystore never starts.
//
STATIC
EFI_STATUS
ReleaseTrustZone (
  VOID
  )
{
  QCOM_SCM_PROTOCOL  *Scm;
  EFI_STATUS         Status;

  Status = gBS->LocateProtocol (&gQcomScmProtocolGuid, NULL, (VOID **)&Scm);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  return Scm->ScmExitBootServicesHandler (Scm);
}

// Write an SMMU register only if the detach changed it: the registers it
// left alone may belong to the hypervisor.
STATIC
VOID
PutSmmu (
  IN UINTN   Address,
  IN UINT32  Value
  )
{
  if (MmioRead32 (Address) != Value) {
    MmioWrite32 (Address, Value);
    MemoryFence ();
  }
}

//
// Put back the SMMU state PlatformSecLib saved before it detached the SMMU
// for UEFI's display: Android's DSPs and USB use ABL's stream mappings.
// Context banks first, so no stream points at a half-restored one.
//
STATIC
VOID
RestoreSmmu (
  VOID
  )
{
  PIANO_SMMU  *S = (PIANO_SMMU *)(UINTN)PIANO_SMMU_BASE;
  UINT32      Id1;
  UINTN       PageShift;
  UINTN       NumPage;
  UINTN       Cb;
  UINT32      I;

  if (S->Magic != PIANO_SMMU_MAGIC) {
    return;
  }

  Id1       = MmioRead32 (PIANO_SMMU_MMIO + PIANO_SMMU_GR0_ID1);
  PageShift = (Id1 & BIT31) ? 16 : 12;
  NumPage   = 1 << (((Id1 >> 28) & 0x7) + 1);

  for (I = 0; I < S->NumCb; I++) {
    Cb = PIANO_SMMU_MMIO + ((NumPage + I) << PageShift);
    PutSmmu (PIANO_SMMU_MMIO + (1 << PageShift) + PIANO_SMMU_GR1_CBAR (I), S->Cbar[I]);
    PutSmmu (Cb + PIANO_SMMU_CB_TTBR0, (UINT32)S->Ttbr0[I]);
    PutSmmu (Cb + PIANO_SMMU_CB_TTBR0 + 4, (UINT32)(S->Ttbr0[I] >> 32));
    PutSmmu (Cb + PIANO_SMMU_CB_TTBR1, (UINT32)S->Ttbr1[I]);
    PutSmmu (Cb + PIANO_SMMU_CB_TTBR1 + 4, (UINT32)(S->Ttbr1[I] >> 32));
    PutSmmu (PIANO_SMMU_MMIO + (1 << PageShift) + PIANO_SMMU_GR1_CBA2R (I), S->Cba2r[I]);
    PutSmmu (Cb + PIANO_SMMU_CB_TCR2, S->Tcr2[I]);
    PutSmmu (Cb + PIANO_SMMU_CB_TCR, S->Tcr[I]);
    PutSmmu (Cb + PIANO_SMMU_CB_MAIR0, S->Mair0[I]);
    PutSmmu (Cb + PIANO_SMMU_CB_MAIR1, S->Mair1[I]);
    PutSmmu (Cb + PIANO_SMMU_CB_SCTLR, S->Sctlr[I]);
  }

  for (I = 0; I < S->NumSmrg; I++) {
    // A changed entry: drop the match first, so no stream sees a
    // half-restored route. Entries UEFI left alone are not touched.
    if ((MmioRead32 (PIANO_SMMU_MMIO + PIANO_SMMU_GR0_SMR (I)) != S->Smr[I]) ||
        (MmioRead32 (PIANO_SMMU_MMIO + PIANO_SMMU_GR0_S2CR (I)) != S->S2cr[I]))
    {
      PutSmmu (PIANO_SMMU_MMIO + PIANO_SMMU_GR0_SMR (I), S->Smr[I] & ~BIT31);
      PutSmmu (PIANO_SMMU_MMIO + PIANO_SMMU_GR0_S2CR (I), S->S2cr[I]);
      PutSmmu (PIANO_SMMU_MMIO + PIANO_SMMU_GR0_SMR (I), S->Smr[I]);
    }
  }

  PutSmmu (PIANO_SMMU_MMIO + PIANO_SMMU_GR0_SCR0, S->Scr0);
}

STATIC
BOOLEAN
HandoffValid (
  IN PIANO_HANDOFF  *H
  )
{
  if (H->Magic != PIANO_HANDOFF_MAGIC) {
    Print (L"No Android to start: this boot did not come through the selector.\n");
    return FALSE;
  }

  if (*(UINT32 *)(UINTN)(H->Base + 0x38) != 0x644D5241) {   // "ARMd"
    Print (L"Android kernel at 0x%lx is gone.\n", H->Base);
    return FALSE;
  }

  if (*(UINT32 *)(UINTN)H->Fdt != 0xEDFE0DD0) {             // FDT magic, BE
    Print (L"Android device tree at 0x%lx is gone.\n", H->Fdt);
    return FALSE;
  }

  return TRUE;
}

//
// The debug fastboot service runs the USB controller in device mode. Stop
// it, so Android's USB driver starts from a halted controller as after ABL.
// Stop is the sixth function of Qualcomm's EFI_USB_DEVICE_PROTOCOL.
//
STATIC
VOID
StopUsbDevice (
  VOID
  )
{
  EFI_GUID  Guid = { 0xD9D9CE48, 0x44B8, 0x4F49, { 0x8E, 0x3E, 0x2A, 0x3B, 0x92, 0x7D, 0xC6, 0xC1 } };
  struct {
    UINTN         Revision;
    VOID          *Fn[5];
    EFI_STATUS    (*Stop)(VOID);
  }         *Usb;

  if (!EFI_ERROR (gBS->LocateProtocol (&Guid, NULL, (VOID **)&Usb))) {
    Usb->Stop ();
  }
}

EFI_STATUS
EFIAPI
AndroidBootMain (
  IN EFI_HANDLE        ImageHandle,
  IN EFI_SYSTEM_TABLE  *SystemTable
  )
{
  PIANO_HANDOFF          *H;
  EFI_MEMORY_DESCRIPTOR  *Map;
  UINTN                  MapSize;
  UINTN                  MapKey;
  UINTN                  DescSize;
  UINT32                 DescVersion;
  EFI_STATUS             Status;
  UINTN                  Try;

  H = (PIANO_HANDOFF *)(UINTN)PIANO_HANDOFF_BASE;
  if (!HandoffValid (H)) {
    gBS->Stall (5000000);
    return EFI_NOT_FOUND;
  }

  Status = ReleaseTrustZone ();
  if (EFI_ERROR (Status)) {
    Print (L"Could not release TrustZone: %r\n", Status);
    gBS->Stall (5000000);
    return Status;
  }

  StopUsbDevice ();

  // ExitBootServices needs the current map key; retry if the map changes.
  Map = NULL;
  for (Try = 0; Try < 4; Try++) {
    MapSize = 0;
    Status  = gBS->GetMemoryMap (&MapSize, NULL, &MapKey, &DescSize, &DescVersion);
    if (Status != EFI_BUFFER_TOO_SMALL) {
      break;
    }

    MapSize += 8 * DescSize;
    if (Map != NULL) {
      FreePool (Map);
    }

    Map = AllocatePool (MapSize);
    if (Map == NULL) {
      Status = EFI_OUT_OF_RESOURCES;
      break;
    }

    Status = gBS->GetMemoryMap (&MapSize, Map, &MapKey, &DescSize, &DescVersion);
    if (!EFI_ERROR (Status)) {
      Status = gBS->ExitBootServices (ImageHandle, MapKey);
      if (!EFI_ERROR (Status)) {
        RestoreSmmu ();
        AndroidBootJump (H->Entry, H->Fdt);
      }
    }
  }

  Print (L"Could not leave UEFI: %r\n", Status);
  gBS->Stall (5000000);
  return Status;
}
