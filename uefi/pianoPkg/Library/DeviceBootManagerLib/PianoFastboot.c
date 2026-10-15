/** @file
  Debug fastboot over USB while UEFI runs.

  Uses Qualcomm's EFI_USB_DEVICE_PROTOCOL (UsbDeviceDxe on UsbfnDwc3Dxe), the
  same stack the stock bootloader's fastboot uses, with descriptors following
  ABL's FastbootLib. The device shows up as serial "piano-uefi" so it is not
  mistaken for the real bootloader. It never writes storage.

    getvar:product|version   identify the firmware
    oem log                  stage the console output; "fastboot get_staged"
    oem screenshot           stage the framebuffer as a BMP
    oem dump <addr> <size>   stage memory (hex), for logs and registers
    stage <file>, boot       run an EFI application from RAM
    boot <file>              the same in one step
    reboot                   cold reset

  Everything else, flash and erase included, is refused.

  SPDX-License-Identifier: BSD-2-Clause-Patent
**/
#include <Uefi.h>
#include <Protocol/GraphicsOutput.h>
#include <Protocol/UsbIo.h>
#include <Library/BaseLib.h>
#include <Library/BaseMemoryLib.h>
#include <Library/DebugLib.h>
#include <Library/MemoryAllocationLib.h>
#include <Library/PrintLib.h>
#include <Library/UefiBootServicesTableLib.h>
#include <Library/UefiLib.h>
#include <Library/UefiRuntimeServicesTableLib.h>

#include "PianoMenu.h"

#define FB_EP_RX     0x01      // bulk OUT, host to device
#define FB_EP_TX     0x81      // bulk IN, device to host
#define FB_RX_SIZE   4096
#define FB_TX_SIZE   256
#define FB_POLL_MS   5
#define FB_CHUNK     SIZE_1MB
#define FB_LOG_SIZE  SIZE_256KB
#define FB_MAX_DOWNLOAD  SIZE_64MB

//
// Qualcomm's EFI_USB_DEVICE_PROTOCOL (QcomPkg EFIUsbDevice.h). Its header
// clashes with MdePkg's Usb.h, so the parts used here are declared locally.
//
#define USB_DESC_TYPE_DEVICE_QUALIFIER       0x06
#define USB_DESC_TYPE_SS_ENDPOINT_COMPANION  0x30

#pragma pack(1)
typedef struct {
  UINT8     Length;
  UINT8     DescriptorType;
  UINT16    BcdUSB;
  UINT8     DeviceClass;
  UINT8     DeviceSubClass;
  UINT8     DeviceProtocol;
  UINT8     MaxPacketSize0;
  UINT8     NumConfigurations;
  UINT8     Reserved;
} EFI_USB_DEVICE_QUALIFIER_DESCRIPTOR;

typedef struct {
  UINT8     Length;
  UINT8     DescriptorType;
  UINT8     MaxBurst;
  UINT8     Attributes;
  UINT16    BytesPerInterval;
} EFI_USB_SS_ENDPOINT_COMPANION_DESCRIPTOR;
#pragma pack()

typedef enum {
  UsbDeviceEventNoEvent,
  UsbDeviceEventDeviceStateChange,
  UsbDeviceEventTransferNotification,
  UsbDeviceEventOemEvent
} USB_DEVICE_EVENT;

typedef enum {
  UsbDeviceTransferStatusActive,
  UsbDeviceTransferStatusCompleteOK,
  UsbDeviceTransferStatusCancelled,
  UsbDeviceTransferStatusCompleteError,
} USB_DEVICE_TRANSFER_STATUS;

typedef enum {
  UsbDeviceStateConnected,
  UsbDeviceStateDisconnected
} USB_DEVICE_STATE;

typedef struct {
  USB_DEVICE_TRANSFER_STATUS    Status;
  UINT8                         EndpointIndex;
  UINTN                         BytesCompleted;
  VOID                          *DataBuffer;
} USB_DEVICE_TRANSFER_OUTCOME;

typedef struct {
  EFI_GUID    DataGuid;
  UINTN       VersionNumber;
  UINTN       Size;
  VOID        *Context;
} USB_DEVICE_OEM_DATA;

typedef union {
  USB_DEVICE_STATE               DeviceState;
  USB_DEVICE_TRANSFER_OUTCOME    TransferOutcome;
  USB_DEVICE_OEM_DATA            OemData;
} USB_DEVICE_EVENT_DATA;

typedef struct {
  EFI_USB_DEVICE_DESCRIPTOR              *DeviceDescriptor;
  VOID                                   **Descriptors;
  EFI_USB_DEVICE_DESCRIPTOR              *SSDeviceDescriptor;
  VOID                                   **SSDescriptors;
  EFI_USB_DEVICE_QUALIFIER_DESCRIPTOR    *DeviceQualifierDescriptor;
  VOID                                   *BinaryDeviceOjectStore;
  UINT8                                  StringDescriptorCount;
  EFI_USB_STRING_DESCRIPTOR              **StringDescritors;
} USB_DEVICE_DESCRIPTOR_SET;

typedef struct {
  UINTN    Revision;
  VOID     *Start;
  EFI_STATUS (*Send)(UINT8 EndpointIndex, UINTN Size, VOID *Buffer);
  EFI_STATUS (EFIAPI *HandleEvent)(USB_DEVICE_EVENT *Event, UINTN *DataSize, USB_DEVICE_EVENT_DATA *EventData);
  EFI_STATUS (EFIAPI *AllocateTransferBuffer)(UINTN Size, VOID **Buffer);
  EFI_STATUS (EFIAPI *FreeTransferBuffer)(VOID *Buffer);
  EFI_STATUS (*Stop)(VOID);
  EFI_STATUS (*AbortXfer)(UINT8 EndpointIndex);
  EFI_STATUS (EFIAPI *SetEndpointStallState)(UINT8 EndpointIndex, BOOLEAN State);
  EFI_STATUS (*StartEx)(USB_DEVICE_DESCRIPTOR_SET *UsbDevDescSet);
} EFI_USB_DEVICE_PROTOCOL;

#pragma pack(1)
typedef struct {
  EFI_USB_CONFIG_DESCRIPTOR       Config;
  EFI_USB_INTERFACE_DESCRIPTOR    Interface;
  EFI_USB_ENDPOINT_DESCRIPTOR     In;
  EFI_USB_ENDPOINT_DESCRIPTOR     Out;
} FB_CONFIG;

typedef struct {
  EFI_USB_CONFIG_DESCRIPTOR                   Config;
  EFI_USB_INTERFACE_DESCRIPTOR                Interface;
  EFI_USB_ENDPOINT_DESCRIPTOR                 In;
  EFI_USB_SS_ENDPOINT_COMPANION_DESCRIPTOR    InCompanion;
  EFI_USB_ENDPOINT_DESCRIPTOR                 Out;
  EFI_USB_SS_ENDPOINT_COMPANION_DESCRIPTOR    OutCompanion;
} FB_SS_CONFIG;
#pragma pack()

STATIC EFI_USB_DEVICE_DESCRIPTOR  mDevice = {
  sizeof (EFI_USB_DEVICE_DESCRIPTOR), USB_DESC_TYPE_DEVICE, 0x0210,
  0, 0, 0, 64, 0x18D1, 0xD00D, 0x0100, 1, 2, 3, 1
};

STATIC EFI_USB_DEVICE_DESCRIPTOR  mSsDevice = {
  sizeof (EFI_USB_DEVICE_DESCRIPTOR), USB_DESC_TYPE_DEVICE, 0x0300,
  0, 0, 0, 9, 0x18D1, 0xD00D, 0x0100, 1, 2, 3, 1
};

STATIC EFI_USB_DEVICE_QUALIFIER_DESCRIPTOR  mQualifier = {
  sizeof (EFI_USB_DEVICE_QUALIFIER_DESCRIPTOR), USB_DESC_TYPE_DEVICE_QUALIFIER,
  0x0200, 0xFF, 0xFF, 0xFF, 64, 1, 0
};

STATIC FB_CONFIG  mConfig = {
  { sizeof (EFI_USB_CONFIG_DESCRIPTOR), USB_DESC_TYPE_CONFIG, sizeof (FB_CONFIG), 1, 1, 0, 0x80, 0x50 },
  { sizeof (EFI_USB_INTERFACE_DESCRIPTOR), USB_DESC_TYPE_INTERFACE, 0, 0, 2, 0xFF, 0x42, 0x03, 4 },
  { sizeof (EFI_USB_ENDPOINT_DESCRIPTOR), USB_DESC_TYPE_ENDPOINT, FB_EP_TX, USB_ENDPOINT_BULK, 512, 0 },
  { sizeof (EFI_USB_ENDPOINT_DESCRIPTOR), USB_DESC_TYPE_ENDPOINT, FB_EP_RX, USB_ENDPOINT_BULK, 512, 1 },
};

STATIC FB_SS_CONFIG  mSsConfig = {
  { sizeof (EFI_USB_CONFIG_DESCRIPTOR), USB_DESC_TYPE_CONFIG, sizeof (FB_SS_CONFIG), 1, 1, 0, 0x80, 0x10 },
  { sizeof (EFI_USB_INTERFACE_DESCRIPTOR), USB_DESC_TYPE_INTERFACE, 0, 0, 2, 0xFF, 0x42, 0x03, 4 },
  { sizeof (EFI_USB_ENDPOINT_DESCRIPTOR), USB_DESC_TYPE_ENDPOINT, FB_EP_TX, USB_ENDPOINT_BULK, 1024, 0 },
  { sizeof (EFI_USB_SS_ENDPOINT_COMPANION_DESCRIPTOR), USB_DESC_TYPE_SS_ENDPOINT_COMPANION, 4, 0, 0 },
  { sizeof (EFI_USB_ENDPOINT_DESCRIPTOR), USB_DESC_TYPE_ENDPOINT, FB_EP_RX, USB_ENDPOINT_BULK, 1024, 0 },
  { sizeof (EFI_USB_SS_ENDPOINT_COMPANION_DESCRIPTOR), USB_DESC_TYPE_SS_ENDPOINT_COMPANION, 4, 0, 0 },
};

// Binary device object store: USB 2.0 extension (LPM) and SuperSpeed
// capability, as ABL sends. USB 3 and bcdUSB 2.1 devices must have one.
STATIC UINT8  mBos[] = {
  5, 0x0F, 22, 0, 2,
  7, 0x10, 0x02, 0x06, 0, 0, 0,
  10, 0x10, 0x03, 0x00, 0x0E, 0x00, 0x01, 0x07, 0x65, 0x00
};

STATIC VOID  *mConfigs[1]   = { &mConfig };
STATIC VOID  *mSsConfigs[1] = { &mSsConfig };

// String descriptors: 0 language, 1 manufacturer, 2 product, 3 serial, 4 interface.
STATIC CONST UINT8  mStr0[]       = { 4, USB_DESC_TYPE_STRING, 0x09, 0x04 };
STATIC CONST UINT8  mStrMaker[]   = { 12, USB_DESC_TYPE_STRING, 'p', 0, 'i', 0, 'a', 0, 'n', 0, 'o', 0 };
STATIC CONST UINT8  mStrProduct[] = { 22, USB_DESC_TYPE_STRING, 'p', 0, 'i', 0, 'a', 0, 'n', 0, 'o', 0, '-', 0, 'u', 0, 'e', 0, 'f', 0, 'i', 0 };
STATIC CONST UINT8  mStrIface[]   = { 18, USB_DESC_TYPE_STRING, 'f', 0, 'a', 0, 's', 0, 't', 0, 'b', 0, 'o', 0, 'o', 0, 't', 0 };

STATIC EFI_USB_STRING_DESCRIPTOR  *mStrings[5] = {
  (EFI_USB_STRING_DESCRIPTOR *)mStr0,
  (EFI_USB_STRING_DESCRIPTOR *)mStrMaker,
  (EFI_USB_STRING_DESCRIPTOR *)mStrProduct,
  (EFI_USB_STRING_DESCRIPTOR *)mStrProduct,
  (EFI_USB_STRING_DESCRIPTOR *)mStrIface,
};

STATIC USB_DEVICE_DESCRIPTOR_SET  mDescSet = {
  &mDevice, mConfigs, &mSsDevice, mSsConfigs, &mQualifier, mBos, 5, mStrings
};

typedef enum {
  FbNextCommand,      // queue a receive for the next command
  FbNextDownload,     // receive the download payload
  FbNextUploadData,   // start sending the staged buffer
  FbNextUploadMore,   // send its next piece, or the final OKAY
  FbNextReboot,
  FbNextBoot,
} FB_NEXT;

STATIC EFI_USB_DEVICE_PROTOCOL  *mUsb;
STATIC CHAR8                    *mRx;
STATIC CHAR8                    *mTx;
STATIC EFI_EVENT                mPollEvent;
STATIC EFI_EVENT                mExitEvent;
STATIC FB_NEXT                  mNext;

// Downloaded data ("fastboot stage" / "boot") and data for "get_staged".
STATIC UINT8  *mData;
STATIC UINTN  mDataAlloc;
STATIC UINTN  mDataSize;
STATIC UINTN  mDataDone;

// Copy of everything written to the console, for "oem log".
STATIC CHAR8                            *mLog;
STATIC UINTN                            mLogLen;
STATIC BOOLEAN                          mLogWrapped;
STATIC EFI_TEXT_STRING                  mOrigOutputString;

STATIC
EFI_STATUS
EFIAPI
LogOutputString (
  IN EFI_SIMPLE_TEXT_OUTPUT_PROTOCOL  *This,
  IN CHAR16                           *String
  )
{
  CHAR16  *Char;

  for (Char = String; *Char != L'\0'; Char++) {
    if ((*Char == L'\r') || (*Char > 0x7E)) {
      continue;
    }

    mLog[mLogLen++] = (CHAR8)*Char;
    if (mLogLen == FB_LOG_SIZE) {
      mLogLen     = 0;
      mLogWrapped = TRUE;
    }
  }

  return mOrigOutputString (This, String);
}

STATIC
VOID
LogStart (
  VOID
  )
{
  mLog = AllocatePool (FB_LOG_SIZE);
  if (mLog == NULL) {
    return;
  }

  mOrigOutputString           = gST->ConOut->OutputString;
  gST->ConOut->OutputString   = LogOutputString;
}

// Make room in the transfer buffer for Size bytes.
STATIC
BOOLEAN
DataReserve (
  IN UINTN  Size
  )
{
  if (Size <= mDataAlloc) {
    return TRUE;
  }

  if (mData != NULL) {
    mUsb->FreeTransferBuffer (mData);
    mData      = NULL;
    mDataAlloc = 0;
  }

  if (EFI_ERROR (mUsb->AllocateTransferBuffer (Size, (VOID **)&mData))) {
    mData = NULL;
    return FALSE;
  }

  mDataAlloc = Size;
  return TRUE;
}

STATIC
VOID
FbReply (
  IN CONST CHAR8  *Code,
  IN CONST CHAR8  *Text,
  IN FB_NEXT      Next
  )
{
  mNext = Next;
  AsciiSPrint (mTx, FB_TX_SIZE, "%a%a", Code, Text);
  mUsb->Send (FB_EP_TX, AsciiStrLen (mTx), mTx);
}

STATIC
VOID
FbFail (
  IN CONST CHAR8  *Text
  )
{
  FbReply ("FAIL", Text, FbNextCommand);
}

STATIC
VOID
StageLog (
  VOID
  )
{
  UINTN  Size;

  Size = mLogWrapped ? FB_LOG_SIZE : mLogLen;
  if ((mLog == NULL) || !DataReserve (Size == 0 ? 1 : Size)) {
    FbFail ("no log");
    return;
  }

  if (mLogWrapped) {
    CopyMem (mData, mLog + mLogLen, FB_LOG_SIZE - mLogLen);
    CopyMem (mData + FB_LOG_SIZE - mLogLen, mLog, mLogLen);
  } else {
    CopyMem (mData, mLog, mLogLen);
  }

  mDataSize = Size;
  FbReply ("OKAY", "", FbNextCommand);
}

// Stage the framebuffer as a 24-bit BMP.
STATIC
VOID
StageScreenshot (
  VOID
  )
{
  EFI_GRAPHICS_OUTPUT_PROTOCOL   *Gop;
  EFI_GRAPHICS_OUTPUT_BLT_PIXEL  *Blt;
  UINT32                         Width;
  UINT32                         Height;
  UINT32                         Stride;
  UINT32                         X;
  UINT32                         Y;
  UINT8                          *Row;
  UINT8                          *Head;
  UINTN                          Size;

  if (EFI_ERROR (gBS->HandleProtocol (gST->ConsoleOutHandle, &gEfiGraphicsOutputProtocolGuid, (VOID **)&Gop)) &&
      EFI_ERROR (gBS->LocateProtocol (&gEfiGraphicsOutputProtocolGuid, NULL, (VOID **)&Gop)))
  {
    FbFail ("no display");
    return;
  }

  Width  = Gop->Mode->Info->HorizontalResolution;
  Height = Gop->Mode->Info->VerticalResolution;
  Stride = (Width * 3 + 3) & ~3U;
  Size   = 54 + (UINTN)Stride * Height;
  Blt    = AllocatePool ((UINTN)Width * Height * sizeof (*Blt));
  if ((Blt == NULL) || !DataReserve (Size)) {
    if (Blt != NULL) {
      FreePool (Blt);
    }

    FbFail ("out of memory");
    return;
  }

  if (EFI_ERROR (Gop->Blt (Gop, Blt, EfiBltVideoToBltBuffer, 0, 0, 0, 0, Width, Height, 0))) {
    FreePool (Blt);
    FbFail ("display read failed");
    return;
  }

  Head = mData;
  ZeroMem (Head, 54);
  Head[0] = 'B';
  Head[1] = 'M';
  WriteUnaligned32 ((UINT32 *)(Head + 2), (UINT32)Size);
  Head[10] = 54;
  Head[14] = 40;
  WriteUnaligned32 ((UINT32 *)(Head + 18), Width);
  WriteUnaligned32 ((UINT32 *)(Head + 22), Height);
  Head[26] = 1;
  Head[28] = 24;
  for (Y = 0; Y < Height; Y++) {
    Row = mData + 54 + (UINTN)(Height - 1 - Y) * Stride;
    for (X = 0; X < Width; X++) {
      Row[X * 3]     = Blt[Y * Width + X].Blue;
      Row[X * 3 + 1] = Blt[Y * Width + X].Green;
      Row[X * 3 + 2] = Blt[Y * Width + X].Red;
    }
  }

  FreePool (Blt);
  mDataSize = Size;
  FbReply ("OKAY", "", FbNextCommand);
}

// Stage Size bytes of memory at Address ("oem dump <address> <size>", hex).
STATIC
VOID
StageMemory (
  IN CHAR8  *Args
  )
{
  CHAR8  *End;
  UINTN  Address;
  UINTN  Size;

  Address = AsciiStrHexToUintn (Args);
  End     = AsciiStrStr (Args, " ");
  Size    = (End != NULL) ? AsciiStrHexToUintn (End + 1) : 0;
  if ((Size == 0) || (Size > FB_MAX_DOWNLOAD) || !DataReserve (Size)) {
    FbFail ("usage: oem dump <address> <size>");
    return;
  }

  CopyMem (mData, (VOID *)Address, Size);
  mDataSize = Size;
  FbReply ("OKAY", "", FbNextCommand);
}

// Run the downloaded EFI application. Called from the poll timer, so drop
// to TPL_APPLICATION for it, as the shell or a boot manager would run it.
STATIC
VOID
BootDownloaded (
  VOID
  )
{
  EFI_HANDLE  Image;
  EFI_STATUS  Status;
  EFI_TPL     Tpl;

  VOID        *App;
  UINTN       AppSize;
  UINT32      Page;

  // "fastboot boot app.efi" wraps the file as the kernel of a boot.img.
  App     = mData;
  AppSize = mDataSize;
  if ((mDataSize > 44) && (CompareMem (mData, "ANDROID!", 8) == 0)) {
    Page    = (ReadUnaligned32 ((UINT32 *)(mData + 40)) >= 3) ? 4096 : ReadUnaligned32 ((UINT32 *)(mData + 36));
    AppSize = ReadUnaligned32 ((UINT32 *)(mData + 8));
    App     = mData + Page;
    if ((Page == 0) || (Page + AppSize > mDataSize)) {
      AppSize = 0;
    }
  }

  gBS->SetTimer (mPollEvent, TimerCancel, 0);
  Status = gBS->LoadImage (FALSE, gImageHandle, NULL, App, AppSize, &Image);
  if (!EFI_ERROR (Status)) {
    Tpl = gBS->RaiseTPL (TPL_HIGH_LEVEL);
    gBS->RestoreTPL (TPL_APPLICATION);
    Status = gBS->StartImage (Image, NULL, NULL);
    gBS->RaiseTPL (Tpl);
  }

  Print (L"fastboot: boot returned %r\n", Status);
  gBS->SetTimer (mPollEvent, TimerPeriodic, EFI_TIMER_PERIOD_MILLISECONDS (FB_POLL_MS));
  mUsb->Send (FB_EP_RX, FB_RX_SIZE, mRx);
}

STATIC
VOID
FbCommand (
  IN CHAR8  *Cmd
  )
{
  CHAR8  Text[32];
  UINTN  Size;

  if (AsciiStrCmp (Cmd, "getvar:product") == 0) {
    FbReply ("OKAY", "piano-uefi", FbNextCommand);
  } else if (AsciiStrCmp (Cmd, "getvar:version") == 0) {
    FbReply ("OKAY", __DATE__ " " __TIME__, FbNextCommand);
  } else if (AsciiStrCmp (Cmd, "getvar:max-download-size") == 0) {
    AsciiSPrint (Text, sizeof (Text), "0x%x", FB_MAX_DOWNLOAD);
    FbReply ("OKAY", Text, FbNextCommand);
  } else if (AsciiStrCmp (Cmd, "getvar:is-userspace") == 0) {
    FbReply ("OKAY", "no", FbNextCommand);
  } else if (AsciiStrnCmp (Cmd, "getvar:", 7) == 0) {
    FbFail ("unknown variable");
  } else if (AsciiStrnCmp (Cmd, "download:", 9) == 0) {
    Size = AsciiStrHexToUintn (Cmd + 9);
    if ((Size == 0) || (Size > FB_MAX_DOWNLOAD) || !DataReserve (Size)) {
      FbFail ("bad size");
      return;
    }

    mDataSize = Size;
    mDataDone = 0;
    AsciiSPrint (Text, sizeof (Text), "%08x", Size);
    FbReply ("DATA", Text, FbNextDownload);
  } else if (AsciiStrCmp (Cmd, "upload") == 0) {
    if (mDataSize == 0) {
      FbFail ("nothing staged");
      return;
    }

    AsciiSPrint (Text, sizeof (Text), "%08x", mDataSize);
    FbReply ("DATA", Text, FbNextUploadData);
  } else if (AsciiStrCmp (Cmd, "oem log") == 0) {
    StageLog ();
  } else if (AsciiStrnCmp (Cmd, "oem dump ", 9) == 0) {
    StageMemory (Cmd + 9);
  } else if (AsciiStrCmp (Cmd, "oem screenshot") == 0) {
    StageScreenshot ();
  } else if (AsciiStrCmp (Cmd, "boot") == 0) {
    if (mDataSize == 0) {
      FbFail ("nothing downloaded");
      return;
    }

    FbReply ("OKAY", "", FbNextBoot);
  } else if ((AsciiStrCmp (Cmd, "reboot") == 0) || (AsciiStrCmp (Cmd, "continue") == 0)) {
    FbReply ("OKAY", "", FbNextReboot);
  } else {
    FbFail ("not supported by piano-uefi");
  }
}

// A reply or upload piece went out.
STATIC
VOID
TxDone (
  IN USB_DEVICE_TRANSFER_OUTCOME  *Out
  )
{
  switch (mNext) {
    case FbNextDownload:
      mUsb->Send (FB_EP_RX, MIN (mDataSize, FB_CHUNK), mData);
      break;
    case FbNextUploadData:
      mNext     = FbNextUploadMore;
      mDataDone = 0;
      mUsb->Send (FB_EP_TX, MIN (mDataSize, FB_CHUNK), mData);
      break;
    case FbNextUploadMore:
      mDataDone += Out->BytesCompleted;
      if (mDataDone < mDataSize) {
        mUsb->Send (FB_EP_TX, MIN (mDataSize - mDataDone, FB_CHUNK), mData + mDataDone);
      } else {
        FbReply ("OKAY", "", FbNextCommand);
      }

      break;
    case FbNextReboot:
      gRT->ResetSystem (EfiResetCold, EFI_SUCCESS, 0, NULL);
      break;
    case FbNextBoot:
      mNext = FbNextCommand;
      BootDownloaded ();
      break;
    default:
      mUsb->Send (FB_EP_RX, FB_RX_SIZE, mRx);
      break;
  }
}

STATIC
VOID
RxDone (
  IN USB_DEVICE_TRANSFER_OUTCOME  *Out
  )
{
  UINTN  Len;

  if (mNext == FbNextDownload) {
    mDataDone += Out->BytesCompleted;
    if (mDataDone < mDataSize) {
      mUsb->Send (FB_EP_RX, MIN (mDataSize - mDataDone, FB_CHUNK), mData + mDataDone);
    } else {
      FbReply ("OKAY", "", FbNextCommand);
    }

    return;
  }

  Len = MIN (Out->BytesCompleted, FB_RX_SIZE - 1);
  mRx[Len] = '\0';
  FbCommand (mRx);
}

STATIC
VOID
EFIAPI
FbPoll (
  IN EFI_EVENT  Event,
  IN VOID       *Context
  )
{
  USB_DEVICE_EVENT       Msg;
  USB_DEVICE_EVENT_DATA  Payload;
  UINTN                  Size;

  Size = sizeof (Payload);
  if (EFI_ERROR (mUsb->HandleEvent (&Msg, &Size, &Payload))) {
    return;
  }

  if (Msg == UsbDeviceEventDeviceStateChange) {
    if (Payload.DeviceState == UsbDeviceStateConnected) {
      mNext = FbNextCommand;
      mUsb->Send (FB_EP_RX, FB_RX_SIZE, mRx);
    }

    return;
  }

  if ((Msg != UsbDeviceEventTransferNotification) ||
      (Payload.TransferOutcome.Status != UsbDeviceTransferStatusCompleteOK))
  {
    return;
  }

  // Bit 7 of the endpoint index is the direction: set for device to host.
  if ((Payload.TransferOutcome.EndpointIndex & 0x80) != 0) {
    TxDone (&Payload.TransferOutcome);
  } else {
    RxDone (&Payload.TransferOutcome);
  }
}

STATIC
VOID
EFIAPI
FbNoop (
  IN EFI_EVENT  Event,
  IN VOID       *Context
  )
{
}

// Linux takes the controller over: stop polling it.
STATIC
VOID
EFIAPI
FbExitBootServices (
  IN EFI_EVENT  Event,
  IN VOID       *Context
  )
{
  gBS->SetTimer (mPollEvent, TimerCancel, 0);
}

VOID
PianoFastbootStart (
  VOID
  )
{
  EFI_STATUS  Status;
  EFI_EVENT   InitEvent;

  LogStart ();

  // Ask UsbConfigDxe to bring the controller up, as ABL does.
  if (!EFI_ERROR (gBS->CreateEventEx (EVT_NOTIFY_SIGNAL, TPL_CALLBACK, FbNoop, NULL, &gUsbControllerInitGuid, &InitEvent))) {
    gBS->SignalEvent (InitEvent);
    gBS->CloseEvent (InitEvent);
  }

  Status = gBS->LocateProtocol (&gEfiUsbDeviceProtocolGuid, NULL, (VOID **)&mUsb);
  if (!EFI_ERROR (Status)) {
    Status = mUsb->StartEx (&mDescSet);
  }

  if (!EFI_ERROR (Status)) {
    Status = mUsb->AllocateTransferBuffer (FB_RX_SIZE, (VOID **)&mRx);
  }

  if (!EFI_ERROR (Status)) {
    Status = mUsb->AllocateTransferBuffer (FB_TX_SIZE, (VOID **)&mTx);
  }

  if (!EFI_ERROR (Status)) {
    Status = gBS->CreateEvent (EVT_TIMER | EVT_NOTIFY_SIGNAL, TPL_CALLBACK, FbPoll, NULL, &mPollEvent);
  }

  if (!EFI_ERROR (Status)) {
    Status = gBS->SetTimer (mPollEvent, TimerPeriodic, EFI_TIMER_PERIOD_MILLISECONDS (FB_POLL_MS));
  }

  if (EFI_ERROR (Status)) {
    Print (L"fastboot: not started (%r)\n", Status);
    return;
  }

  gBS->CreateEventEx (EVT_NOTIFY_SIGNAL, TPL_NOTIFY, FbExitBootServices, NULL, &gEfiEventExitBootServicesGuid, &mExitEvent);
  Print (L"fastboot: ready as piano-uefi\n");
}
