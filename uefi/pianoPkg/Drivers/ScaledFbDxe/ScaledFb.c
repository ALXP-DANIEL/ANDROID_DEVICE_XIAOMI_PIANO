/** @file
  Graphics output at half the panel resolution, drawn with 2x2 pixels.

  The 3200x2136 panel makes the 8x19 console font tiny. This driver
  reports a 1600x1068 mode, keeps it in a shadow buffer and copies every
  changed rectangle to the inherited framebuffer at twice the size.

  Copyright (c) 2026, MUHAMMAD ALIF DANIEL BIN MOHD HAIRUL HEZZELIN.

  SPDX-License-Identifier: BSD-2-Clause-Patent
**/

#include <Uefi.h>
#include <Protocol/GraphicsOutput.h>
#include <Library/BaseMemoryLib.h>
#include <Library/DebugLib.h>
#include <Library/FrameBufferBltLib.h>
#include <Library/MemoryAllocationLib.h>
#include <Library/MemoryMapHelperLib.h>
#include <Library/PcdLib.h>
#include <Library/UefiBootServicesTableLib.h>

#include <Configuration/BootDevices.h>

#define SCALE  2   /* Present() assumes 2 */

STATIC UINT32                                *mPanel;
STATIC UINT32                                mPanelWidth;
STATIC UINT32                                *mShadow;
STATIC EFI_GRAPHICS_OUTPUT_MODE_INFORMATION  mInfo;
STATIC EFI_GRAPHICS_OUTPUT_PROTOCOL_MODE     mMode;
STATIC FRAME_BUFFER_CONFIGURE                *mBltConfig;

/*
  Copy a shadow rectangle to the panel, each pixel as a 2x2 block. Both
  panel rows are written from the shadow with one 64-bit store per pixel;
  the panel is write-through, so reading it back would be slow.
*/
STATIC
VOID
Present (
  IN UINTN  X,
  IN UINTN  Y,
  IN UINTN  Width,
  IN UINTN  Height
  )
{
  UINTN   Row;
  UINTN   Col;
  UINT32  *Src;
  UINT64  *Top;
  UINT64  *Bottom;
  UINT64  Pair;

  if ((X >= mInfo.HorizontalResolution) || (Y >= mInfo.VerticalResolution)) {
    return;
  }

  Width  = MIN (Width, mInfo.HorizontalResolution - X);
  Height = MIN (Height, mInfo.VerticalResolution - Y);

  for (Row = Y; Row < Y + Height; Row++) {
    Src    = mShadow + Row * mInfo.PixelsPerScanLine + X;
    Top    = (UINT64 *)(mPanel + Row * SCALE * mPanelWidth + X * SCALE);
    Bottom = (UINT64 *)((UINT32 *)Top + mPanelWidth);
    for (Col = 0; Col < Width; Col++) {
      Pair        = Src[Col] | ((UINT64)Src[Col] << 32);
      Top[Col]    = Pair;
      Bottom[Col] = Pair;
    }
  }
}

STATIC
EFI_STATUS
EFIAPI
ScaledQueryMode (
  IN  EFI_GRAPHICS_OUTPUT_PROTOCOL          *This,
  IN  UINT32                                ModeNumber,
  OUT UINTN                                 *SizeOfInfo,
  OUT EFI_GRAPHICS_OUTPUT_MODE_INFORMATION  **Info
  )
{
  if ((ModeNumber != 0) || (SizeOfInfo == NULL) || (Info == NULL)) {
    return EFI_INVALID_PARAMETER;
  }

  *Info = AllocateCopyPool (sizeof (mInfo), &mInfo);
  if (*Info == NULL) {
    return EFI_OUT_OF_RESOURCES;
  }

  *SizeOfInfo = sizeof (mInfo);
  return EFI_SUCCESS;
}

STATIC
EFI_STATUS
EFIAPI
ScaledSetMode (
  IN EFI_GRAPHICS_OUTPUT_PROTOCOL  *This,
  IN UINT32                        ModeNumber
  )
{
  return (ModeNumber == 0) ? EFI_SUCCESS : EFI_UNSUPPORTED;
}

STATIC
EFI_STATUS
EFIAPI
ScaledBlt (
  IN EFI_GRAPHICS_OUTPUT_PROTOCOL       *This,
  IN EFI_GRAPHICS_OUTPUT_BLT_PIXEL      *BltBuffer OPTIONAL,
  IN EFI_GRAPHICS_OUTPUT_BLT_OPERATION  BltOperation,
  IN UINTN                              SourceX,
  IN UINTN                              SourceY,
  IN UINTN                              DestinationX,
  IN UINTN                              DestinationY,
  IN UINTN                              Width,
  IN UINTN                              Height,
  IN UINTN                              Delta OPTIONAL
  )
{
  EFI_STATUS  Status;
  EFI_TPL     Tpl;

  Tpl    = gBS->RaiseTPL (TPL_NOTIFY);
  Status = FrameBufferBlt (
             mBltConfig,
             BltBuffer,
             BltOperation,
             SourceX,
             SourceY,
             DestinationX,
             DestinationY,
             Width,
             Height,
             Delta
             );
  if (!EFI_ERROR (Status) && (BltOperation != EfiBltVideoToBltBuffer)) {
    Present (DestinationX, DestinationY, Width, Height);
  }

  gBS->RestoreTPL (Tpl);
  return Status;
}

STATIC EFI_GRAPHICS_OUTPUT_PROTOCOL  mGop = {
  ScaledQueryMode,
  ScaledSetMode,
  ScaledBlt,
  &mMode
};

EFI_STATUS
EFIAPI
ScaledFbInitialize (
  IN EFI_HANDLE        ImageHandle,
  IN EFI_SYSTEM_TABLE  *SystemTable
  )
{
  EFI_MEMORY_REGION_DESCRIPTOR  Region;
  UINTN                                 ConfigSize;
  EFI_STATUS                            Status;
  EFI_GRAPHICS_OUTPUT_MODE_INFORMATION  ShadowInfo;

  ZeroMem (&Region, sizeof (Region));
  LocateMemoryRegionByName ("Display Reserved", &Region);
  LocateMemoryRegionByName ("Display_Reserved", &Region);
  if (Region.Address == 0) {
    DEBUG ((DEBUG_ERROR, "ScaledFb: no Display Reserved region\n"));
    return EFI_NOT_FOUND;
  }

  ASSERT (FixedPcdGet32 (PcdFrameBufferColorDepth) == 32);
  mPanel      = (UINT32 *)(UINTN)Region.Address;
  mPanelWidth = FixedPcdGet32 (PcdFrameBufferWidth);

  mInfo.Version              = 0;
  mInfo.HorizontalResolution = mPanelWidth / SCALE;
  mInfo.VerticalResolution   = FixedPcdGet32 (PcdFrameBufferHeight) / SCALE;
  // The shadow is not the panel: apps that draw into FrameBufferBase would
  // never be seen. Blt-only makes them (Visor, GRUB) go through Blt.
  mInfo.PixelFormat          = PixelBltOnly;
  mInfo.PixelsPerScanLine    = mInfo.HorizontalResolution;

  mMode.MaxMode         = 1;
  mMode.Mode            = 0;
  mMode.Info            = &mInfo;
  mMode.SizeOfInfo      = sizeof (mInfo);
  mMode.FrameBufferSize = mInfo.HorizontalResolution * mInfo.VerticalResolution * sizeof (UINT32);

  mShadow = AllocateZeroPool (mMode.FrameBufferSize);
  if (mShadow == NULL) {
    return EFI_OUT_OF_RESOURCES;
  }

  // The Blt library still draws into the shadow, which is BGR.
  CopyMem (&ShadowInfo, &mInfo, sizeof (ShadowInfo));
  ShadowInfo.PixelFormat = PixelBlueGreenRedReserved8BitPerColor;

  ConfigSize = 0;
  FrameBufferBltConfigure (mShadow, &ShadowInfo, NULL, &ConfigSize);
  mBltConfig = AllocatePool (ConfigSize);
  if (mBltConfig == NULL) {
    return EFI_OUT_OF_RESOURCES;
  }

  Status = FrameBufferBltConfigure (mShadow, &ShadowInfo, mBltConfig, &ConfigSize);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  return gBS->InstallMultipleProtocolInterfaces (
                &ImageHandle,
                &gEfiDevicePathProtocolGuid,
                &DisplayDevicePath,
                &gEfiGraphicsOutputProtocolGuid,
                &mGop,
                NULL
                );
}
