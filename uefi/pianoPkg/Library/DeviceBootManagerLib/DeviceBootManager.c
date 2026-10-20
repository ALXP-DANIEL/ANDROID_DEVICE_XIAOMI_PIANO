/** @file
  Piano boot manager hooks, from Mu-Silicium's Qualcomm PlatformBootManagerLib.

  SPDX-License-Identifier: BSD-2-Clause
**/
#include <Library/DebugLib.h>
#include <Library/BaseMemoryLib.h>
#include <Library/UefiBootServicesTableLib.h>
#include <Library/DeviceBootManagerLib.h>
#include <Library/RfsProtectionLib.h>
#include <Library/UefiBootManagerLib.h>
#include <Library/DevicePathLib.h>
#include <Library/MemoryAllocationLib.h>
#include <Library/UefiLib.h>
#include <Protocol/LoadedImage.h>
#include <Protocol/SimpleFileSystem.h>
#include <Protocol/PartitionInfo.h>
#include <Guid/Gpt.h>

#include "Piano.h"

//
// Start \EFI\BOOT\BOOTAA64.EFI (rEFInd or another boot manager) from the
// first file system that has it. Returns only if none starts.
//
STATIC
BOOLEAN
StartEspLoader (
  VOID
  )
{
  EFI_HANDLE                *Handles;
  UINTN                     Count;
  UINTN                     Index;
  EFI_DEVICE_PATH_PROTOCOL  *Path;
  EFI_HANDLE                Image;
  BOOLEAN                   Found;
  EFI_PARTITION_INFO_PROTOCOL *Partition;

  Found = FALSE;
  EfiBootManagerConnectAll ();
  if (EFI_ERROR (gBS->LocateHandleBuffer (ByProtocol, &gEfiSimpleFileSystemProtocolGuid, NULL, &Count, &Handles))) {
    return FALSE;
  }

  for (Index = 0; Index < Count; Index++) {
    if (EFI_ERROR (gBS->HandleProtocol (Handles[Index], &gEfiPartitionInfoProtocolGuid, (VOID **)&Partition)) ||
        Partition->Type != PARTITION_TYPE_GPT ||
        !CompareGuid (&Partition->Info.Gpt.PartitionTypeGUID, &gEfiPartTypeSystemPartGuid)) {
      continue;
    }
    Path = FileDevicePath (Handles[Index], EFI_REMOVABLE_MEDIA_FILE_NAME);
    if (Path == NULL) {
      continue;
    }

    if (!EFI_ERROR (gBS->LoadImage (TRUE, gImageHandle, Path, NULL, 0, &Image))) {
      Found = TRUE;
      gBS->StartImage (Image, NULL, NULL);
      gBS->UnloadImage (Image);
    }

    FreePool (Path);
  }

  FreePool (Handles);
  return Found;
}


// AndroidBoot.efi in the firmware volume (FILE_GUID in AndroidBoot.inf).
STATIC EFI_GUID  mAndroidBootFileGuid = {
  0x2F6C1A4E, 0x8B3D, 0x4C57, { 0xA1, 0xE9, 0x6D, 0x0B, 0x7F, 0x3C, 0x5A, 0x21 }
};

// Start an application from our own firmware volume by its file GUID.
STATIC
VOID
StartFvApp (
  IN EFI_GUID  *FileGuid,
  IN CHAR16    *Name
  )
{
  EFI_STATUS                         Status;
  EFI_LOADED_IMAGE_PROTOCOL         *LoadedImage;
  MEDIA_FW_VOL_FILEPATH_DEVICE_PATH  FileNode;
  EFI_DEVICE_PATH_PROTOCOL          *DevicePath;
  EFI_BOOT_MANAGER_LOAD_OPTION       Option;

  Status = gBS->HandleProtocol (gImageHandle, &gEfiLoadedImageProtocolGuid, (VOID **)&LoadedImage);
  if (EFI_ERROR (Status)) {
    return;
  }

  EfiInitializeFwVolDevicepathNode (&FileNode, FileGuid);
  DevicePath = AppendDevicePathNode (DevicePathFromHandle (LoadedImage->DeviceHandle), (EFI_DEVICE_PATH_PROTOCOL *)&FileNode);
  if (DevicePath == NULL) {
    return;
  }

  Status = EfiBootManagerInitializeLoadOption (&Option, LoadOptionNumberUnassigned, LoadOptionTypeBoot, LOAD_OPTION_ACTIVE, Name, DevicePath, NULL, 0);
  FreePool (DevicePath);
  if (EFI_ERROR (Status)) {
    return;
  }

  EfiBootManagerBoot (&Option);
  EfiBootManagerFreeLoadOption (&Option);
}

STATIC
VOID
DummyNotify (
  IN EFI_EVENT Event,
  IN VOID     *Context) 
{
  return;
}

EFI_HANDLE
EFIAPI
DeviceBootManagerBeforeConsole (
  OUT EFI_DEVICE_PATH_PROTOCOL  **DevicePath,
  OUT BDS_CONSOLE_CONNECT_ENTRY **PlatformConsoles)
{
  return NULL;
}

EFI_DEVICE_PATH_PROTOCOL**
EFIAPI
DeviceBootManagerAfterConsole ()
{
  EFI_STATUS Status;
  EFI_EVENT  InitEvent;

  // Create SD Card Init Event
  Status = gBS->CreateEventEx (EVT_NOTIFY_SIGNAL, TPL_CALLBACK, DummyNotify, NULL, &gSdCardInitGuid, &InitEvent);
  if (EFI_ERROR (Status)) {
    DEBUG ((EFI_D_ERROR, "%a: Failed to Create SD Card Init Event! Status = %r\n", __FUNCTION__, Status));
    return NULL;
  }

  // Init SD Card
  gBS->SignalEvent (InitEvent);
  gBS->CloseEvent  (InitEvent);

  // Start Visor from a real ESP. Missing or failed loaders fall through
  // to Android; never publish an embedded ESP disk.
  PianoResetHookInstall ();
  PianoDtbInstall ();
  PianoFastbootStart ();
  StartEspLoader ();

  StartFvApp (&mAndroidBootFileGuid, L"Android");

  return NULL;
}

EFI_DEVICE_PATH_PROTOCOL**
EFIAPI
DeviceBootManagerOnDemandConInConnect ()
{
  EFI_STATUS Status;
  EFI_EVENT  InitEvent;

  // Create USB Controller Start Event
  Status = gBS->CreateEventEx (EVT_NOTIFY_SIGNAL, TPL_CALLBACK, DummyNotify, NULL, &gUsbControllerInitGuid, &InitEvent);
  if (EFI_ERROR (Status)) {
    DEBUG ((EFI_D_ERROR, "%a: Failed to Create USB Port Controller Start Event! Status = %r\n", __FUNCTION__, Status));
    return NULL;
  }

  // Start USB Controller
  gBS->SignalEvent (InitEvent);
  gBS->CloseEvent  (InitEvent);

  return NULL;
}

VOID
EFIAPI
DeviceBootManagerProcessBootCompletion (OUT EFI_BOOT_MANAGER_LOAD_OPTION *BootOption)
{
  return;
}

EFI_STATUS
EFIAPI
DeviceBootManagerPriorityBoot (IN OUT EFI_BOOT_MANAGER_LOAD_OPTION *BootOption)
{
  return EFI_SUCCESS;
}

//
// Nothing could start (no Visor and no Android): the UEFI Shell from the
// firmware volume, the last thing UEFI can offer.
//
VOID
EFIAPI
DeviceBootManagerUnableToBoot ()
{
  StartFvApp (&gUefiShellFileGuid, L"UEFI Shell");
}

VOID
EFIAPI
DeviceBootManagerBdsEntry ()
{
  // Protect Shared RFS Area
  ProtectRfsSharedArea ();
}
