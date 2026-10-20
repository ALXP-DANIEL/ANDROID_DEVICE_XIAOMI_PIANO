/** @file
  Device tree for Linux.

  The boot manager (Visor) cannot pass a device tree, so it is published
  here as the UEFI FDT configuration table, where the kernel's EFI stub
  finds it. The tree is \EFI\piano\piano.dtb on any file system; the EFI
  stub takes memory from the UEFI memory map, so the tree needs no ABL
  fixups. Without the file nothing is installed.

  Copyright (c) 2026, MUHAMMAD ALIF DANIEL BIN MOHD HAIRUL HEZZELIN.

  SPDX-License-Identifier: BSD-2-Clause-Patent
**/

#include <Uefi.h>
#include <Guid/FileInfo.h>
#include <Library/BaseLib.h>
#include <Library/MemoryAllocationLib.h>
#include <Library/UefiBootServicesTableLib.h>
#include <Library/UefiLib.h>
#include <Protocol/SimpleFileSystem.h>

#include "Piano.h"

#define PIANO_DTB_PATH  L"\\EFI\\piano\\piano.dtb"
#define FDT_MAGIC       0xD00DFEED

STATIC
VOID *
ReadDtb (
  IN EFI_FILE_PROTOCOL  *Root
  )
{
  EFI_FILE_PROTOCOL  *File;
  EFI_FILE_INFO      *Info;
  UINTN              Size;
  VOID               *Dtb;

  if (EFI_ERROR (Root->Open (Root, &File, PIANO_DTB_PATH, EFI_FILE_MODE_READ, 0))) {
    return NULL;
  }

  Dtb  = NULL;
  Info = NULL;
  Size = 0;
  if (File->GetInfo (File, &gEfiFileInfoGuid, &Size, NULL) == EFI_BUFFER_TOO_SMALL) {
    Info = AllocatePool (Size);
    if ((Info != NULL) && EFI_ERROR (File->GetInfo (File, &gEfiFileInfoGuid, &Size, Info))) {
      FreePool (Info);
      Info = NULL;
    }
  }

  if ((Info != NULL) && (Info->FileSize >= 40) && (Info->FileSize < SIZE_16MB)) {
    Size = (UINTN)Info->FileSize;
    Dtb  = AllocatePages (EFI_SIZE_TO_PAGES (Size));
    if ((Dtb != NULL) &&
        (EFI_ERROR (File->Read (File, &Size, Dtb)) || (Size != Info->FileSize) ||
         (SwapBytes32 (*(UINT32 *)Dtb) != FDT_MAGIC)))
    {
      FreePages (Dtb, EFI_SIZE_TO_PAGES ((UINTN)Info->FileSize));
      Dtb = NULL;
    }
  }

  if (Info != NULL) {
    FreePool (Info);
  }

  File->Close (File);
  return Dtb;
}

VOID
PianoDtbInstall (
  VOID
  )
{
  EFI_HANDLE                       *Handles;
  UINTN                            Count;
  UINTN                            Index;
  EFI_SIMPLE_FILE_SYSTEM_PROTOCOL  *Fs;
  EFI_FILE_PROTOCOL                *Root;
  VOID                             *Dtb;

  if (EFI_ERROR (gBS->LocateHandleBuffer (ByProtocol, &gEfiSimpleFileSystemProtocolGuid, NULL, &Count, &Handles))) {
    return;
  }

  Dtb = NULL;
  for (Index = 0; (Index < Count) && (Dtb == NULL); Index++) {
    if (EFI_ERROR (gBS->HandleProtocol (Handles[Index], &gEfiSimpleFileSystemProtocolGuid, (VOID **)&Fs)) ||
        EFI_ERROR (Fs->OpenVolume (Fs, &Root)))
    {
      continue;
    }

    Dtb = ReadDtb (Root);
    Root->Close (Root);
  }

  FreePool (Handles);
  if (Dtb != NULL) {
    gBS->InstallConfigurationTable (&gFdtTableGuid, Dtb);
  }
}
