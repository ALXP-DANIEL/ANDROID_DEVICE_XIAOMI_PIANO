/** @file
  Reboot and power off from the boot menus.

  ResetSystem sat on a black screen for 5 minutes: the reset notification
  of the Qualcomm UFS driver (UFSDxe) flushes every LUN, then sends START
  STOP UNIT (POWERDOWN) to the device W-LUN. After a few seconds of idle
  the device never answers it, and UFSDxe waits 5 minutes before giving
  up (Linux waits 10 s). The controller's auto-hibernate is off, and the
  flush, the config lookup and touch DMA are not the cause; without idle
  POWERDOWN completes at once.

  The notification is unregistered, and one of our own flushes every
  block device instead, so nothing written is lost; the other drivers'
  notifications and the PSCI reset run as usual. Only the POWERDOWN
  courtesy is skipped, as on any forced reset.

  Copyright (c) 2026, MUHAMMAD ALIF DANIEL BIN MOHD HAIRUL HEZZELIN.

  SPDX-License-Identifier: BSD-2-Clause-Patent
**/

#include <Uefi.h>
#include <Library/BaseLib.h>
#include <Library/MemoryAllocationLib.h>
#include <Library/PeCoffGetEntryPointLib.h>
#include <Library/UefiBootServicesTableLib.h>
#include <Protocol/BlockIo.h>
#include <Protocol/LoadedImage.h>
#include <Protocol/ResetNotification.h>

#include "PianoMenu.h"

// ResetSystemRuntimeDxe's list entry (ResetSystem.h).
typedef struct {
  UINT32              Signature;
  LIST_ENTRY          Link;
  EFI_RESET_SYSTEM    ResetNotify;
} RESET_NOTIFY_ENTRY;

/* TRUE if Fn lies in a loaded image whose debug name contains Name. */
STATIC
BOOLEAN
IsInImage (
  IN VOID         *Fn,
  IN CONST CHAR8  *Name
  )
{
  EFI_HANDLE                 *Handles;
  UINTN                      Count;
  UINTN                      Index;
  EFI_LOADED_IMAGE_PROTOCOL  *Image;
  CHAR8                      *Pdb;
  BOOLEAN                    Found;

  if (EFI_ERROR (gBS->LocateHandleBuffer (ByProtocol, &gEfiLoadedImageProtocolGuid, NULL, &Count, &Handles))) {
    return FALSE;
  }

  Found = FALSE;
  for (Index = 0; Index < Count; Index++) {
    if (!EFI_ERROR (gBS->HandleProtocol (Handles[Index], &gEfiLoadedImageProtocolGuid, (VOID **)&Image)) &&
        ((UINTN)Fn >= (UINTN)Image->ImageBase) &&
        ((UINTN)Fn < (UINTN)Image->ImageBase + Image->ImageSize))
    {
      Pdb   = PeCoffLoaderGetPdbPointer (Image->ImageBase);
      Found = (Pdb != NULL) && (AsciiStrStr (Pdb, Name) != NULL);
      break;
    }
  }

  FreePool (Handles);
  return Found;
}

STATIC
VOID
EFIAPI
FlushBlockDevices (
  IN EFI_RESET_TYPE  ResetType,
  IN EFI_STATUS      ResetStatus,
  IN UINTN           DataSize,
  IN VOID            *ResetData OPTIONAL
  )
{
  EFI_HANDLE             *Handles;
  UINTN                  Count;
  UINTN                  Index;
  EFI_BLOCK_IO_PROTOCOL  *BlockIo;

  if (EFI_ERROR (gBS->LocateHandleBuffer (ByProtocol, &gEfiBlockIoProtocolGuid, NULL, &Count, &Handles))) {
    return;
  }

  for (Index = 0; Index < Count; Index++) {
    if (!EFI_ERROR (gBS->HandleProtocol (Handles[Index], &gEfiBlockIoProtocolGuid, (VOID **)&BlockIo)) &&
        BlockIo->Media->MediaPresent && !BlockIo->Media->ReadOnly)
    {
      BlockIo->FlushBlocks (BlockIo);
    }
  }

  FreePool (Handles);
}

VOID
PianoResetHookInstall (
  VOID
  )
{
  EFI_RESET_NOTIFICATION_PROTOCOL  *Notify;
  LIST_ENTRY                       *Head;
  LIST_ENTRY                       *Link;
  RESET_NOTIFY_ENTRY               *Entry;

  if (EFI_ERROR (gBS->LocateProtocol (&gEfiResetNotificationProtocolGuid, NULL, (VOID **)&Notify))) {
    return;
  }

  // The protocol sits in RESET_NOTIFICATION_INSTANCE between its
  // signature and the list head; check the signature before walking.
  if (*(UINT32 *)((UINT8 *)Notify - 8) == SIGNATURE_32 ('r', 's', 't', 'i')) {
    Head = (LIST_ENTRY *)(Notify + 1);
    for (Link = Head->ForwardLink; Link != Head; Link = Link->ForwardLink) {
      Entry = BASE_CR (Link, RESET_NOTIFY_ENTRY, Link);
      if (IsInImage ((VOID *)Entry->ResetNotify, "UFSDxe")) {
        Notify->UnregisterResetNotify (Notify, Entry->ResetNotify);
        break;
      }
    }
  }

  Notify->RegisterResetNotify (Notify, FlushBlockDevices);
}
