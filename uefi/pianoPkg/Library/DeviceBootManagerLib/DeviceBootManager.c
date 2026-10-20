/** @file
  Piano boot manager hooks, from Mu-Silicium's Qualcomm PlatformBootManagerLib.

  SPDX-License-Identifier: BSD-2-Clause
**/
#include <Library/DebugLib.h>
#include <Library/UefiBootServicesTableLib.h>
#include <Library/DeviceBootManagerLib.h>
#include <Library/RfsProtectionLib.h>
#include <Library/UefiBootManagerLib.h>
#include <Library/DevicePathLib.h>
#include <Library/MemoryAllocationLib.h>
#include <Library/UefiLib.h>
#include <Protocol/LoadedImage.h>
#include <Protocol/SimpleFileSystem.h>

#include "PianoMenu.h"

//
// Start \EFI\BOOT\BOOTAA64.EFI (rEFInd or another boot manager) from the
// first file system that has it. Returns only if none starts.
//
STATIC
VOID
StartEspLoader (
  VOID
  )
{
  EFI_HANDLE                *Handles;
  UINTN                     Count;
  UINTN                     Index;
  EFI_DEVICE_PATH_PROTOCOL  *Path;
  EFI_HANDLE                Image;

  EfiBootManagerConnectAll ();
  if (EFI_ERROR (gBS->LocateHandleBuffer (ByProtocol, &gEfiSimpleFileSystemProtocolGuid, NULL, &Count, &Handles))) {
    return;
  }

  for (Index = 0; Index < Count; Index++) {
    Path = FileDevicePath (Handles[Index], EFI_REMOVABLE_MEDIA_FILE_NAME);
    if (Path == NULL) {
      continue;
    }

    if (!EFI_ERROR (gBS->LoadImage (TRUE, gImageHandle, Path, NULL, 0, &Image))) {
      gBS->StartImage (Image, NULL, NULL);
      gBS->UnloadImage (Image);
    }

    FreePool (Path);
  }

  FreePool (Handles);
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

  // Consoles are up: start the boot manager on the ESP, or show the
  // button menu when there is none.
  PianoResetHookInstall ();
  PianoDtbInstall ();
  PianoFastbootStart ();
  StartEspLoader ();
  if (PianoMenuRun () == PianoMenuShell) {
    DeviceBootManagerUnableToBoot ();
  }

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
// Nothing bootable (no Visor on the ESP): show the button menu. Its shell
// entry opens the UEFI Shell from the firmware volume.
//
VOID
EFIAPI
DeviceBootManagerUnableToBoot ()
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

  EfiInitializeFwVolDevicepathNode (&FileNode, &gUefiShellFileGuid);
  DevicePath = AppendDevicePathNode (DevicePathFromHandle (LoadedImage->DeviceHandle), (EFI_DEVICE_PATH_PROTOCOL *)&FileNode);
  if (DevicePath == NULL) {
    return;
  }

  Status = EfiBootManagerInitializeLoadOption (&Option, LoadOptionNumberUnassigned, LoadOptionTypeBoot, LOAD_OPTION_ACTIVE, L"UEFI Shell", DevicePath, NULL, 0);
  FreePool (DevicePath);
  if (EFI_ERROR (Status)) {
    return;
  }

  EfiBootManagerBoot (&Option);
  EfiBootManagerFreeLoadOption (&Option);
}

VOID
EFIAPI
DeviceBootManagerBdsEntry ()
{
  // Protect Shared RFS Area
  ProtectRfsSharedArea ();
}
