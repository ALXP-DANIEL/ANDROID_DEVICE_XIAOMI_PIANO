/** @file
  Touch screen: the NT36532 controller on QUP1 SE2, read over GSI DMA.

  SE2 runs GSI-only firmware and the stock SPI driver only does FIFO, so
  transfers go through the Qualcomm GPI DMA driver (GpiDxe) instead:

   - GpiDxe is stored raw and started here, after the partitions are
     connected. Its firmware load (TrustZone authentication) hangs and is
     not needed, as the boot loader already loaded the GSI firmware; it is
     skipped by patching one branch in the loaded image.
   - The apps SMMU faults on streams the boot loader did not match; the GPI
     (0xB6) and QUP1 (0xA3) streams are routed like USB, pass-through.
   - GPIO 40-43 are muxed to SE2 through the TLMM driver.
   - SPI transfers are CONFIG0/GO/DMA ring elements as in Linux gpi.c.

  The controller has no flash: its firmware (per panel vendor, in the
  firmware volume) is downloaded at every boot. It reports no coordinates,
  only sensor frames (THP), so contacts are found here from the frame's
  60x40 matrix against an untouched reference.

  The touches become keys: a swipe moves the selection, a double tap is
  Enter. KeypadDeviceLib collects them through PIANO_TOUCH_KEYS.

  Copyright (c) 2026, MUHAMMAD ALIF DANIEL BIN MOHD HAIRUL HEZZELIN.

  SPDX-License-Identifier: BSD-2-Clause-Patent
**/

#include <Uefi.h>
#include <Library/BaseLib.h>
#include <Library/BaseMemoryLib.h>
#include <Library/CacheMaintenanceLib.h>
#include <Library/DebugLib.h>
#include <Library/DxeServicesLib.h>
#include <Library/IoLib.h>
#include <Library/MemoryAllocationLib.h>
#include <Library/TimerLib.h>
#include <Library/UefiBootManagerLib.h>
#include <Library/UefiBootServicesTableLib.h>
#include <Library/UefiLib.h>
#include <Protocol/SimpleFileSystem.h>
#include <Guid/FileInfo.h>
#include <Protocol/EFIClock.h>
#include <Protocol/LoadedImage.h>
#include <Protocol/SimpleTextInEx.h>

#include "PianoMenu.h"
#include "PianoTouchKeys.h"

//
// GpiDxe (revision 0x10002), learned from the binary.
//
typedef UINT32 (EFIAPI *GPI_REG)(VOID *Params);
typedef UINT32 (EFIAPI *GPI_HANDLE_FN)(VOID *Handle);
typedef UINT32 (EFIAPI *GPI_TRE)(VOID *Request);
typedef UINT32 (EFIAPI *GPI_CMD)(VOID *Handle, UINT32 Chan, UINT32 Cmd, UINT64 Unused, VOID *UserData);

typedef struct {
  UINT64           Revision;
  GPI_REG          Register;
  VOID             *Fn1;
  VOID             *QueryChan;
  GPI_HANDLE_FN    Poll;
  GPI_CMD          IssueCmd;
  GPI_TRE          ProcessTre;
  GPI_HANDLE_FN    Deregister;
  VOID             *Active;
} GPI_IFACE;

typedef struct {
  UINT8     RingSize;
  UINT8     Pad[7];
  VOID      *RingVa;
  UINT64    RingPa;
} GPI_CHAN_PARAMS;

typedef struct {
  VOID               *Handle;      // out
  UINT8              GpiiId;
  UINT8              Pad0[3];
  UINT32             QupType;      // GpiDxe target table index
  UINT32             Ee;
  UINT8              ChType;       // 2 = GPI
  UINT8              Pad1[3];
  GPI_CHAN_PARAMS    Chan[2];
  UINT32             IntModT;
  UINT32             IntModC;
  UINT8              IrqMode;      // 0 = polled
  UINT8              Pad2[7];
  VOID               *Callback;
  VOID               *UserData;
} GPI_PARAMS;

typedef struct {
  VOID      *Handle;
  UINT8     Chan;
  UINT8     Pad[7];
  UINT32    *Tre;                  // first element, in place in the ring
  UINT32    Count;
  UINT32    Pad1;
  VOID      *UserData;
} GPI_TRE_REQUEST;

STATIC EFI_GUID  mGpiDxeRaw = {
  0x3E7B5A2C, 0x91D4, 0x4F68, { 0xB0, 0xA3, 0x5C, 0x2E, 0x8D, 0x71, 0xF9, 0x46 }
};
STATIC EFI_GUID  mGpiProtocol = {
  0x569EA0DE, 0xB557, 0x4043, { 0x84, 0xCF, 0x01, 0x10, 0x3F, 0xE5, 0x16, 0xE5 }
};

#define GPI_FWLOAD_BRANCH  0x4AF4          // b.eq to fwload in init_all
#define GPI_GSI_BASE       0xA04000        // QUP1 GSI, UEFI execution environment
#define GPI_GPII           1
#define GPI_DOORBELL(Ch)   (GPI_GSI_BASE + (GPI_GPII << 14) + 0xE000 + (Ch) * 8)

// Linux gpi.c ring elements.
#define TRE_TYPE(t)        ((UINT32)(t) << 16)
#define TRE_DMA            0x10
#define TRE_GO             0x20
#define TRE_CONFIG0        0x22
#define TRE_CHAIN          BIT0
#define TRE_IEOT           BIT9
#define TRE_LINK           BIT11
#define SPI_TX             1
#define SPI_DUPLEX         3

#define RING_TRES          16
#define XFER_MAX           (64 * 1024)
#define XFER_PAGES         EFI_SIZE_TO_PAGES (XFER_MAX + SIZE_4KB)

//
// TLMM driver (DALTLMM).
//
typedef EFI_STATUS (EFIAPI *TLMM_CONFIG_GPIO)(UINT32 Config, UINT32 Enable);
typedef EFI_STATUS (EFIAPI *TLMM_GPIO_IN)(UINT32 Config, UINT32 *Value);

typedef struct {
  UINT64              Revision;
  TLMM_CONFIG_GPIO    ConfigGpio;
  TLMM_GPIO_IN        GpioIn;
} QCOM_TLMM;

STATIC EFI_GUID  mTlmmGuid = {
  0xad9aec18, 0x7bf0, 0x4809, { 0x9e, 0x96, 0x30, 0x12, 0x30, 0x9f, 0x3d, 0xf7 }
};

#define LCD_ID_GPIO  100                  // panel vendor: 0 = CSOT, 1 = BOE

#define TLMM_CFG(Gpio, Func, Drive)  ((((Gpio) & 0x3FF) << 4) | ((Func) & 0xF) | (((Drive) & 0xF) << 17))

//
// Apps SMMU.
//
#define SMMU_BASE  0x15000000
#define SMMU_SMR   0x800
#define SMMU_S2CR  0xC00

//
// NT36532 (cascade) memory map, from the stock nt36532_touch.ko.
//
#define NVT_CHIP_ID      0x1FB104
#define NVT_EVENT_BUF    0x11C400

// Controller firmware per panel, stored raw in the firmware volume (DXE.inc).
STATIC EFI_GUID  mNvtFwCsot = {
  0x5B0C7E21, 0x6A4D, 0x4E83, { 0x9F, 0x12, 0x3D, 0x7A, 0xC4, 0x58, 0xB1, 0x06 }
};
STATIC EFI_GUID  mNvtFwBoe = {
  0x5B0C7E21, 0x6A4D, 0x4E83, { 0x9F, 0x12, 0x3D, 0x7A, 0xC4, 0x58, 0xB1, 0x07 }
};

STATIC CONST UINT8  mNvtChipId[6] = { 0x0E, 0x00, 0x04, 0x32, 0x65, 0x03 };

STATIC CONST CHAR8  *mClockNames[] = {
  "gcc_qupv3_wrap1_core_clk",
  "gcc_qupv3_wrap1_core_2x_clk",
  "gcc_qupv3_wrap_1_m_ahb_clk",
  "gcc_qupv3_wrap_1_s_ahb_clk",
  "gcc_qupv3_wrap1_s2_clk",
};

STATIC GPI_IFACE  *mGpi;
STATIC VOID       *mGpii;
STATIC UINT32     (*mRing[2])[4];
STATIC UINTN      mRingWp[2];
STATIC UINT8      *mTxBuf;
STATIC UINT8      *mRxBuf;
STATIC UINTN      mEvents;
STATIC UINT32     mPage = MAX_UINT32;

STATIC
VOID
EFIAPI
GpiCallback (
  IN VOID  *Result
  )
{
  mEvents++;
}

/* Route Sid like the boot loader routes USB (0x40): a pass-through context bank. */
STATIC
EFI_STATUS
SmmuAllowStream (
  IN UINT32  Sid
  )
{
  UINT32  Count;
  UINT32  Index;
  UINT32  Smr;
  UINT32  S2cr;
  INTN    Usb;
  INTN    Free;

  if ((MmioRead32 (SMMU_BASE) & BIT0) != 0) {
    return EFI_SUCCESS;                           // sCR0.CLIENTPD: SMMU off
  }

  Count = MmioRead32 (SMMU_BASE + 0x20) & 0xFF;     // IDR0.NUMSMRG
  if ((Count == 0) || (Count > 128)) {
    return EFI_DEVICE_ERROR;
  }

  Usb  = -1;
  Free = -1;
  for (Index = 0; Index < Count; Index++) {
    Smr = MmioRead32 (SMMU_BASE + SMMU_SMR + 4 * Index);
    if ((Smr & BIT31) == 0) {
      if (Free < 0) {
        Free = Index;
      }

      continue;
    }

    if (((Sid ^ Smr) & 0xFFFF & ~((Smr >> 16) & 0x7FFF)) == 0) {
      return EFI_SUCCESS;                         // already matched
    }

    if (((0x40 ^ Smr) & 0xFFFF & ~((Smr >> 16) & 0x7FFF)) == 0) {
      Usb = Index;
    }
  }

  if ((Usb < 0) || (Free < 0)) {
    return EFI_NOT_FOUND;
  }

  S2cr = MmioRead32 (SMMU_BASE + SMMU_S2CR + 4 * (UINT32)Usb);
  if ((((S2cr >> 16) & 3) != 0) ||
      ((MmioRead32 (SMMU_BASE + 0x80000 + 0x1000 * (S2cr & 0xFF)) & BIT0) != 0))
  {
    return EFI_UNSUPPORTED;                       // USB does not pass through
  }

  // Route first, then make the match valid.
  MmioWrite32 (SMMU_BASE + SMMU_S2CR + 4 * (UINT32)Free, S2cr);
  MmioWrite32 (SMMU_BASE + SMMU_SMR + 4 * (UINT32)Free, BIT31 | Sid);
  return EFI_SUCCESS;
}

STATIC
EFI_STATUS
EnableClocks (
  VOID
  )
{
  EFI_CLOCK_PROTOCOL  *Clock;
  EFI_STATUS          Status;
  UINTN               ClockId;
  UINTN               Index;

  Status = gBS->LocateProtocol (&gEfiClockProtocolGuid, NULL, (VOID **)&Clock);
  for (Index = 0; !EFI_ERROR (Status) && (Index < ARRAY_SIZE (mClockNames)); Index++) {
    Status = Clock->GetClockID (Clock, mClockNames[Index], &ClockId);
    if (!EFI_ERROR (Status)) {
      Status = Clock->EnableClock (Clock, ClockId);
    }
  }

  return Status;
}

STATIC
EFI_STATUS
MuxPins (
  VOID
  )
{
  QCOM_TLMM   *Tlmm;
  EFI_STATUS  Status;
  UINT32      Gpio;

  Status = gBS->LocateProtocol (&mTlmmGuid, NULL, (VOID **)&Tlmm);
  for (Gpio = 40; !EFI_ERROR (Status) && (Gpio <= 43); Gpio++) {
    // Function 1 = qup1_se2, no pull, 6 mA.
    Status = Tlmm->ConfigGpio (TLMM_CFG (Gpio, 1, 2), 1);
  }

  return Status;
}

/* Panel vendor from the LCD ID pin: TRUE for BOE. */
STATIC
BOOLEAN
PanelIsBoe (
  VOID
  )
{
  QCOM_TLMM  *Tlmm;
  UINT32     Config;
  UINT32     Value;

  if (EFI_ERROR (gBS->LocateProtocol (&mTlmmGuid, NULL, (VOID **)&Tlmm))) {
    return TRUE;
  }

  Config = TLMM_CFG (LCD_ID_GPIO, 0, 0);          // GPIO input, no pull
  Value  = 1;
  Tlmm->ConfigGpio (Config, 1);
  if (EFI_ERROR (Tlmm->GpioIn (Config, &Value))) {
    return TRUE;
  }

  return Value != 0;
}

/* Start GpiDxe with its firmware load skipped. */
STATIC
EFI_STATUS
StartGpiDxe (
  VOID
  )
{
  EFI_LOADED_IMAGE_PROTOCOL  *Loaded;
  EFI_HANDLE                 Handle;
  EFI_STATUS                 Status;
  VOID                       *Image;
  UINTN                      Size;
  UINT32                     *Branch;

  Status = GetSectionFromAnyFv (&mGpiDxeRaw, EFI_SECTION_RAW, 0, &Image, &Size);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  Handle = NULL;
  Status = gBS->LoadImage (FALSE, gImageHandle, NULL, Image, Size, &Handle);
  FreePool (Image);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  Status = gBS->HandleProtocol (Handle, &gEfiLoadedImageProtocolGuid, (VOID **)&Loaded);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  Branch = (UINT32 *)((UINT8 *)Loaded->ImageBase + GPI_FWLOAD_BRANCH);
  if (*Branch != 0x54000180) {                    // b.eq 0x4b24
    gBS->UnloadImage (Handle);
    return EFI_INCOMPATIBLE_VERSION;
  }

  *Branch = 0xD503201F;                           // nop
  InvalidateInstructionCacheRange (Branch, sizeof (*Branch));
  return gBS->StartImage (Handle, NULL, NULL);
}

/* Register GPII 1 on QUP1, then allocate and start its event ring and both channels. */
STATIC
EFI_STATUS
StartGpii (
  VOID
  )
{
  STATIC CONST UINT32  Steps[][2] = {
    { 2, 0 }, { 0, 0 }, { 1, 0 }, { 0, 1 }, { 1, 1 }   // { channel, command }
  };
  STATIC GPI_PARAMS    Params;
  UINTN                Ch;
  UINTN                Index;
  UINTN                Wait;
  UINTN                Events;

  if (EFI_ERROR (gBS->LocateProtocol (&mGpiProtocol, NULL, (VOID **)&mGpi)) || (mGpi->Revision != 0x10002)) {
    return EFI_UNSUPPORTED;
  }

  ZeroMem (&Params, sizeof (Params));
  Params.GpiiId  = GPI_GPII;
  Params.QupType = 1;
  Params.Ee      = 1;
  Params.ChType  = 2;
  for (Ch = 0; Ch < 2; Ch++) {
    mRing[Ch] = AllocateAlignedPages (1, SIZE_4KB);
    if (mRing[Ch] == NULL) {
      return EFI_OUT_OF_RESOURCES;
    }

    ZeroMem (mRing[Ch], SIZE_4KB);
    Params.Chan[Ch].RingSize = RING_TRES;
    Params.Chan[Ch].RingVa   = mRing[Ch];
    Params.Chan[Ch].RingPa   = (UINT64)(UINTN)mRing[Ch];
  }

  Params.Callback = GpiCallback;
  if ((mGpi->Register (&Params) != 0) || (Params.Handle == NULL)) {
    return EFI_DEVICE_ERROR;
  }

  mGpii = Params.Handle;
  for (Index = 0; Index < ARRAY_SIZE (Steps); Index++) {
    Events = mEvents;
    if (mGpi->IssueCmd (mGpii, Steps[Index][0], Steps[Index][1], 0, NULL) != 0) {
      return EFI_DEVICE_ERROR;
    }

    for (Wait = 0; (Wait < 100) && (mEvents == Events); Wait++) {
      gBS->Stall (1000);
      mGpi->Poll (mGpii);
    }

    if (mEvents == Events) {
      return EFI_TIMEOUT;
    }
  }

  return EFI_SUCCESS;
}

/* Copy ring elements in place at the write position; returns the first one. */
STATIC
UINT32 *
RingPut (
  IN UINTN         Ch,
  IN CONST UINT32  (*Tre)[4],
  IN UINTN         Count
  )
{
  UINT32  *Start;
  UINTN   Index;

  Start = mRing[Ch][mRingWp[Ch]];
  for (Index = 0; Index < Count; Index++) {
    CopyMem (mRing[Ch][mRingWp[Ch]], Tre[Index], 16);
    mRingWp[Ch] = (mRingWp[Ch] + 1) % RING_TRES;
  }

  WriteBackDataCacheRange (mRing[Ch], RING_TRES * 16);
  return Start;
}

/*
  One SPI transfer: TX gets CONFIG0 (8-bit words, packed, mode 0, clock
  source 0 divided by 6), GO and the data; a duplex read also queues one
  RX element. TX is rung directly (GpiDxe's process_tre hangs on it), RX
  goes through process_tre.
*/
STATIC
EFI_STATUS
SpiXfer (
  IN  CONST UINT8  *Tx,
  IN  UINT32       Len,
  OUT UINT8        *Rx OPTIONAL
  )
{
  UINT32           TxTre[3][4];
  UINT32           RxTre[1][4];
  GPI_TRE_REQUEST  Req;
  UINTN            Events;
  UINTN            Wait;
  UINT64           Wp;

  if (Len > XFER_MAX) {
    return EFI_INVALID_PARAMETER;
  }

  CopyMem (mTxBuf, Tx, Len);
  WriteBackInvalidateDataCacheRange (mTxBuf, Len);
  if (Rx != NULL) {
    WriteBackInvalidateDataCacheRange (mRxBuf, Len);
  }

  TxTre[0][0] = 4 | BIT24 | BIT25;
  TxTre[0][1] = 0;
  TxTre[0][2] = 6;
  TxTre[0][3] = TRE_TYPE (TRE_CONFIG0) | TRE_CHAIN;
  TxTre[1][0] = (Rx != NULL) ? SPI_DUPLEX : SPI_TX;
  TxTre[1][1] = 0;
  TxTre[1][2] = (Rx != NULL) ? Len : 0;
  TxTre[1][3] = TRE_TYPE (TRE_GO) | TRE_CHAIN | ((Rx != NULL) ? TRE_LINK : 0);
  TxTre[2][0] = (UINT32)(UINTN)mTxBuf;
  TxTre[2][1] = (UINT32)((UINT64)(UINTN)mTxBuf >> 32);
  TxTre[2][2] = Len;
  TxTre[2][3] = TRE_TYPE (TRE_DMA) | TRE_IEOT;

  Events = mEvents;
  if (Rx != NULL) {
    RxTre[0][0] = (UINT32)(UINTN)mRxBuf;
    RxTre[0][1] = (UINT32)((UINT64)(UINTN)mRxBuf >> 32);
    RxTre[0][2] = Len;
    RxTre[0][3] = TRE_TYPE (TRE_DMA) | TRE_IEOT;
    ZeroMem (&Req, sizeof (Req));
    Req.Handle = mGpii;
    Req.Chan   = 1;
    Req.Tre    = RingPut (1, (CONST UINT32 (*)[4])RxTre, 1);
    Req.Count  = 1;
    if (mGpi->ProcessTre (&Req) != 0) {
      return EFI_DEVICE_ERROR;
    }
  }

  RingPut (0, (CONST UINT32 (*)[4])TxTre, 3);
  Wp = (UINT64)(UINTN)mRing[0][mRingWp[0]];
  MmioWrite32 (GPI_DOORBELL (0) + 4, (UINT32)(Wp >> 32));
  MmioWrite32 (GPI_DOORBELL (0), (UINT32)Wp);

  // Up to 2 s: a 63 KB firmware chunk takes a while at the bus clock.
  for (Wait = 0; Wait < 8000; Wait++) {
    mGpi->Poll (mGpii);
    if (mEvents >= Events + ((Rx != NULL) ? 2 : 1)) {
      break;
    }

    gBS->Stall (250);
  }

  if (Rx != NULL) {
    InvalidateDataCacheRange (mRxBuf, Len);
    CopyMem (Rx, mRxBuf, Len);
  }

  return (Wait < 8000) ? EFI_SUCCESS : EFI_TIMEOUT;
}

/*
  Read from the controller: select the 128-byte page of Address, then a
  duplex transfer whose first byte is the offset (read: bit 7 clear). The
  reply starts after two bytes.
*/
STATIC
EFI_STATUS
NvtRead (
  IN  UINT32  Address,
  OUT UINT8   *Out,
  IN  UINT32  Len
  )
{
  UINT8       Buf[128];
  EFI_STATUS  Status;

  if (Len + 2 > sizeof (Buf)) {
    return EFI_INVALID_PARAMETER;
  }

  if ((Address >> 7) != mPage) {
    Buf[0] = 0xFF;
    Buf[1] = (UINT8)(Address >> 15);
    Buf[2] = (UINT8)(Address >> 7);
    Status = SpiXfer (Buf, 3, NULL);
    if (EFI_ERROR (Status)) {
      return Status;
    }

    mPage = Address >> 7;
  }

  ZeroMem (Buf, sizeof (Buf));
  Buf[0] = (UINT8)(Address & 0x7F);
  Status = SpiXfer (Buf, Len + 2, Buf);
  CopyMem (Out, Buf + 2, Len);
  return Status;
}

/* Read Len bytes from Address into mFrame + 2 (large reads). */
STATIC UINT8  mFrame[XFER_MAX];

STATIC
EFI_STATUS
NvtReadFrame (
  IN UINT32  Address,
  IN UINT32  Len
  )
{
  EFI_STATUS  Status;
  UINT8       Page[3];

  Page[0] = 0xFF;
  Page[1] = (UINT8)(Address >> 15);
  Page[2] = (UINT8)(Address >> 7);
  Status  = SpiXfer (Page, 3, NULL);
  mPage   = Address >> 7;
  if (EFI_ERROR (Status)) {
    return Status;
  }

  ZeroMem (mFrame, Len + 2);
  mFrame[0] = (UINT8)(Address & 0x7F);
  return SpiXfer (mFrame, Len + 2, mFrame);
}

/* Select the 128-byte page of Address, if it is not selected already. */
STATIC
EFI_STATUS
NvtSetPage (
  IN UINT32  Address
  )
{
  UINT8       Buf[3];
  EFI_STATUS  Status;

  if ((Address >> 7) == mPage) {
    return EFI_SUCCESS;
  }

  Buf[0] = 0xFF;
  Buf[1] = (UINT8)(Address >> 15);
  Buf[2] = (UINT8)(Address >> 7);
  Status = SpiXfer (Buf, 3, NULL);
  if (!EFI_ERROR (Status)) {
    mPage = Address >> 7;
  }

  return Status;
}

/* Write Len bytes at Address (write: bit 7 of the offset byte set). */
STATIC
EFI_STATUS
NvtWrite (
  IN UINT32       Address,
  IN CONST UINT8  *Data,
  IN UINT32       Len
  )
{
  STATIC UINT8  Buf[XFER_MAX];
  EFI_STATUS    Status;

  if (Len + 1 > XFER_MAX) {
    return EFI_INVALID_PARAMETER;
  }

  Status = NvtSetPage (Address);
  if (EFI_ERROR (Status)) {
    return Status;
  }

  Buf[0] = (UINT8)(Address & 0x7F) | 0x80;
  CopyMem (Buf + 1, Data, Len);
  return SpiXfer (Buf, Len + 1, NULL);
}

STATIC
EFI_STATUS
NvtWriteByte (
  IN UINT32  Address,
  IN UINT8   Value
  )
{
  return NvtWrite (Address, &Value, 1);
}

//
// Firmware download (NT36532E cascade, hardware CRC with 3-byte lengths,
// TX auto copy). The controller has no flash: the host loads the firmware
// into its SRAM after every power-up. Register addresses from the
// controller's memory map; the sequence follows Novatek's boot download.
//
#define NVT_SWRST_SIF      0x1FB43E
#define NVT_BOOT_RDY       0x1FB50D
#define NVT_TX_AUTO_COPY   0x1FC925
#define NVT_ILM_DES        0x1FB528
#define NVT_ILM_LEN        0x1FB518
#define NVT_ILM_GCRC       0x1FB500
#define NVT_DLM_DES        0x1FB52C
#define NVT_DLM_LEN        0x1FB530
#define NVT_DLM_GCRC       0x1FB504
#define NVT_EV_RESET       0x60          // event buffer: reset state
#define NVT_EV_HOST_CMD    0x50          // event buffer: host command
#define NVT_CHUNK          (63 * 1024)

typedef struct {
  UINT32    BinAddr;
  UINT32    SramAddr;
  UINT32    Size;
  UINT32    Crc;
} NVT_PARTITION;

STATIC
UINT32
Word (
  IN CONST UINT8  *P
  )
{
  return P[0] | ((UINT32)P[1] << 8) | ((UINT32)P[2] << 16) | ((UINT32)P[3] << 24);
}

/* The controller's CRC-32 (MSB first, no reflection) over whole words. */
STATIC
UINT32
NvtCrc32 (
  IN CONST UINT8  *Data,
  IN UINT32       Len
  )
{
  UINT32  Crc;
  UINT32  Index;
  UINT32  Bit;

  Crc = 0xFFFFFFFF;
  for (Index = 0; Index < (Len + 4) / 4; Index++) {
    Crc ^= Word (Data + 4 * Index);
    for (Bit = 0; Bit < 32; Bit++) {
      Crc = (Crc & BIT31) ? ((Crc << 1) ^ 0x04C11DB7) : (Crc << 1);
    }
  }

  return Crc;
}

/*
  Partitions from the firmware header: ILM and DLM (12-byte entries with a
  computed CRC), then the info sections from 0x30 (16-byte entries with
  their CRC), then any DLM overlay sections.
*/
STATIC
UINTN
NvtParse (
  IN  CONST UINT8    *Fw,
  IN  UINTN          FwSize,
  OUT NVT_PARTITION  *Part,
  IN  UINTN          Max
  )
{
  UINT32   End;
  UINT32   Pos;
  UINTN    Info;
  UINTN    Ovly;
  UINTN    Count;
  UINTN    Index;
  BOOLEAN  Cascade;
  BOOLEAN  FoundHeader;

  End     = Word (Fw);
  Cascade = (Fw[0x20] & 0x02) != 0;
  Info    = 0;
  for (Pos = 0x30; Pos < (Cascade ? End / 2 : End); Pos += 0x10) {
    Info++;
  }

  if (Cascade) {
    Info++;                                       // the next header
  }

  Ovly  = (Fw[0x28] & 0x10) ? (Fw[0x28] & 0x0F) : 0;
  Count = 2 + Info + Ovly;
  if (Count > Max) {
    return 0;
  }

  FoundHeader = FALSE;
  for (Index = 0; Index < Count; Index++) {
    if (Index < 2) {
      Part[Index].BinAddr  = Word (Fw + 12 * Index);
      Part[Index].SramAddr = Word (Fw + 12 * Index + 4);
      Part[Index].Size     = Word (Fw + 12 * Index + 8);
      if (Part[Index].BinAddr + Part[Index].Size + 4 > FwSize) {
        return 0;
      }

      Part[Index].Crc = NvtCrc32 (Fw + Part[Index].BinAddr, Part[Index].Size);
      continue;
    }

    if (Index < 2 + Info) {
      Pos = (FoundHeader && Cascade) ? End - 0x10 : 0x30 + 0x10 * (UINT32)(Index - 2);
    } else {
      Pos = Part[1].BinAddr + 0x10 * (UINT32)(Index - 2 - Info);
    }

    Part[Index].SramAddr = Word (Fw + Pos);
    Part[Index].Size     = Word (Fw + Pos + 4);
    Part[Index].BinAddr  = Word (Fw + Pos + 8);
    Part[Index].Crc      = Word (Fw + Pos + 12);
    if ((Index < 2 + Info) && (Part[Index].BinAddr < End) && (Part[Index].Size != 0)) {
      FoundHeader = TRUE;
    }

    if (Part[Index].BinAddr + Part[Index].Size > FwSize) {
      return 0;
    }
  }

  return Count;
}

/* Golden CRC bank: SRAM start, length (3 bytes) and CRC of ILM or DLM. */
STATIC
EFI_STATUS
NvtSetCrcBank (
  IN UINT32               Des,
  IN UINT32               LenReg,
  IN UINT32               CrcReg,
  IN CONST NVT_PARTITION  *Part
  )
{
  UINT8       Buf[4];
  EFI_STATUS  Status;

  Buf[0] = (UINT8)Part->SramAddr;
  Buf[1] = (UINT8)(Part->SramAddr >> 8);
  Buf[2] = (UINT8)(Part->SramAddr >> 16);
  Status = NvtWrite (Des, Buf, 3);
  if (!EFI_ERROR (Status)) {
    Buf[0] = (UINT8)Part->Size;
    Buf[1] = (UINT8)(Part->Size >> 8);
    Buf[2] = (UINT8)(Part->Size >> 16);
    Status = NvtWrite (LenReg, Buf, 3);
  }

  if (!EFI_ERROR (Status)) {
    Buf[0] = (UINT8)Part->Crc;
    Buf[1] = (UINT8)(Part->Crc >> 8);
    Buf[2] = (UINT8)(Part->Crc >> 16);
    Buf[3] = (UINT8)(Part->Crc >> 24);
    Status = NvtWrite (CrcReg, Buf, 4);
  }

  return Status;
}

STATIC
EFI_STATUS
NvtDownload (
  IN CONST UINT8  *Fw,
  IN UINTN        FwSize
  )
{
  NVT_PARTITION  Part[24];
  UINTN          Count;
  UINTN          Index;
  UINT32         Sram;
  UINT32         Bin;
  UINT32         Left;
  UINT32         Len;
  UINTN          Try;
  UINT8          State;
  UINT8          Zero[6];
  UINT8          Cmd[2];
  EFI_STATUS     Status;

  Count = NvtParse (Fw, FwSize, Part, ARRAY_SIZE (Part));
  if (Count < 2) {
    return EFI_VOLUME_CORRUPTED;
  }

  // Boot loader reset: the MCU restarts and waits for firmware.
  Status = NvtWriteByte (NVT_SWRST_SIF, 0x69);
  gBS->Stall (5000);
  mPage = MAX_UINT32;
  if (!EFI_ERROR (Status)) {
    Status = NvtSetCrcBank (NVT_ILM_DES, NVT_ILM_LEN, NVT_ILM_GCRC, &Part[0]);
  }

  if (!EFI_ERROR (Status)) {
    Status = NvtSetCrcBank (NVT_DLM_DES, NVT_DLM_LEN, NVT_DLM_GCRC, &Part[1]);
  }

  // Cascade: the master copies what it receives to the slave.
  if (!EFI_ERROR (Status)) {
    Status = NvtWriteByte (NVT_TX_AUTO_COPY, 0x56);
  }

  for (Index = 0; !EFI_ERROR (Status) && (Index < Count); Index++) {
    if (Part[Index].Size == 0) {
      continue;
    }

    Sram = Part[Index].SramAddr;
    Bin  = Part[Index].BinAddr;
    Left = Part[Index].Size + 1;                  // as the vendor driver sends it
    while (!EFI_ERROR (Status) && (Left > 0)) {
      Len    = MIN (Left, NVT_CHUNK);
      Len    = MIN (Len, (UINT32)(FwSize - Bin));
      Status = NvtWrite (Sram, Fw + Bin, Len);
      mPage  = MAX_UINT32;                        // the write moved the index
      Sram  += NVT_CHUNK;
      Bin   += NVT_CHUNK;
      Left  -= MIN (Left, NVT_CHUNK);
      if (Bin >= FwSize) {
        break;
      }
    }
  }

  // Wait for the auto copy to finish (the register reads 0).
  for (Try = 0; !EFI_ERROR (Status) && (Try < 200); Try++) {
    Status = NvtRead (NVT_TX_AUTO_COPY, &State, 1);
    if (!EFI_ERROR (Status) && (State == 0)) {
      break;
    }

    gBS->Stall (1000);
  }

  if (!EFI_ERROR (Status) && (Try == 200)) {
    Status = EFI_TIMEOUT;
  }

  // Clear the reset state, enable the firmware CRC check, then boot.
  if (!EFI_ERROR (Status)) {
    ZeroMem (Zero, sizeof (Zero));
    Status = NvtWrite (NVT_EVENT_BUF + NVT_EV_RESET, Zero, sizeof (Zero));
  }

  if (!EFI_ERROR (Status)) {
    Cmd[0] = 0xAE;
    Cmd[1] = 0x00;
    Status = NvtWrite (NVT_EVENT_BUF + NVT_EV_HOST_CMD, Cmd, sizeof (Cmd));
  }

  if (!EFI_ERROR (Status)) {
    Status = NvtWriteByte (NVT_BOOT_RDY, 1);
    gBS->Stall (5000);
  }

  // The firmware reports its reset state 0xA0-0xAF once it runs.
  for (Try = 0; !EFI_ERROR (Status) && (Try < 50); Try++) {
    Status = NvtRead (NVT_EVENT_BUF + NVT_EV_RESET, &State, 1);
    if (!EFI_ERROR (Status) && (State >= 0xA0) && (State <= 0xAF)) {
      return EFI_SUCCESS;
    }

    gBS->Stall (10000);
  }

  return EFI_ERROR (Status) ? Status : EFI_TIMEOUT;
}

//
// Gestures.
//
#define KEY_QUEUE        8
#define DOUBLE_TAP_NS    600000000ULL     // from one tap's lift to the next
#define TAP_MAX_NS       300000000ULL
#define LIFT_FRAMES      2

STATIC UINT32   mMaxX;
STATIC UINT32   mMaxY;
STATIC UINT32   mStep;            // drag distance per key
STATIC BOOLEAN  mDown;
STATIC BOOLEAN  mMoved;
STATIC INT32    mAnchorX;
STATIC INT32    mAnchorY;
STATIC INT32    mStartX;
STATIC INT32    mStartY;
STATIC UINT64   mLastTap;
STATIC UINT64   mDownAt;
STATIC UINT64   mLastSeen;
STATIC UINTN    mDownFrames;
STATIC UINTN    mGap;
STATIC INT32    mTapX;
STATIC INT32    mTapY;

STATIC
UINT64
NowNs (
  VOID
  )
{
  return GetTimeInNanoSecond (GetPerformanceCounter ());
}

STATIC
VOID
Emit (
  OUT EFI_KEY_DATA  *Keys,
  IN OUT UINTN      *Count,
  IN  UINTN         Max,
  IN  UINT16        Scan,
  IN  CHAR16        Char
  )
{
  if (*Count < Max) {
    ZeroMem (&Keys[*Count], sizeof (Keys[*Count]));
    Keys[*Count].Key.ScanCode    = Scan;
    Keys[*Count].Key.UnicodeChar = Char;
    (*Count)++;
  }
}

//
// THP frames. This firmware reports no finger coordinates: every scan it
// fills the event buffer with a 257-byte transport block followed by the
// host touch computing payload, a 16-bit little endian sensor matrix that
// the host turns into contacts. Payload layout (from the piano Linux
// bring-up notes): +4 checksum, +8 checksummed dwords from +20, +12 and
// +16 their inverses, +48 columns, +49 rows, +56 frame type, +64 matrix.
// Pen frame types are mixed into the stream and skipped.
//
#define THP_TRANSPORT     257
#define THP_PAYLOAD       5160
#define THP_FRAME         (THP_TRANSPORT + THP_PAYLOAD)
#define THP_MATRIX        64
#define THP_MAX_NODES     (60 * 40)
#define THP_REF_FRAMES    16
#define THP_THRESHOLD     200
#define THP_SCREEN_W      3200
#define THP_SCREEN_H      2136

STATIC INT32   mRef[THP_MAX_NODES];
STATIC INT32   mDelta[THP_MAX_NODES];
STATIC UINTN   mRefCount;
STATIC INT32   mType = -1;
STATIC UINT8   mRows;
STATIC UINT8   mCols;

STATIC
UINT32
Le16 (
  IN CONST UINT8  *P
  )
{
  return P[0] | ((UINT32)P[1] << 8);
}

/*
  Check the payload header: checksum and checksummed length each stored
  with their inverse. The sum itself is not checked: it does not match on
  polled frames, likely because the controller rewrites the buffer while
  it is read (the frame-ready interrupt is not used).
*/
STATIC
BOOLEAN
ThpHeaderOk (
  IN CONST UINT8  *P
  )
{
  UINT32  Len;

  Len = Le16 (P + 8) | (Le16 (P + 10) << 16);
  return (Le16 (P + 12) == (~Le16 (P + 4) & 0xFFFF)) &&
         ((Le16 (P + 16) | (Le16 (P + 18) << 16)) == ~Len) &&
         (Len != 0) && (20 + (UINTN)Len * 4 <= THP_PAYLOAD);
}

/*
  Read one frame. Returns FALSE if it is not a usable touch matrix, else
  TRUE with *Touch and, when touching, the strongest contact in screen
  pixels (3x3 weighted centroid). Sensor columns run along screen x, rows
  along screen y reversed.
*/
STATIC
BOOLEAN
ThpRead (
  OUT BOOLEAN  *Touch,
  OUT INT32    *X,
  OUT INT32    *Y
  )
{
  CONST UINT8  *P;
  UINTN        Nodes;
  UINTN        N;
  INT32        Type;
  INT32        V;
  INT32        Max;
  UINTN        At;
  INT32        R;
  INT32        C;
  INT32        Dr;
  INT32        Dc;
  INT64        Sw;
  INT64        Sx;
  INT64        Sy;

  *Touch = FALSE;
  // The whole frame, from the start of the event buffer.
  if (EFI_ERROR (NvtReadFrame (NVT_EVENT_BUF, THP_FRAME))) {
    return FALSE;
  }

  P    = mFrame + 2 + THP_TRANSPORT - 1;
  Type = P[56];
  if ((Type == 6) || (Type == 7) || (Type == 9) || (Type == 0x1D)) {
    return FALSE;
  }

  if (!ThpHeaderOk (P)) {
    return FALSE;
  }

  Nodes = (UINTN)P[48] * P[49];
  if ((Nodes == 0) || (Nodes > THP_MAX_NODES)) {
    return FALSE;
  }

  // Lock onto the first matrix type and size; keep fingers off meanwhile.
  if (mType < 0) {
    mType = Type;
    mCols = P[48];
    mRows = P[49];
  }

  if ((Type != mType) || (P[48] != mCols) || (P[49] != mRows)) {
    return FALSE;
  }

  if (mRefCount < THP_REF_FRAMES) {
    for (N = 0; N < Nodes; N++) {
      mRef[N] += (INT16)Le16 (P + THP_MATRIX + N * 2);
    }

    if (++mRefCount == THP_REF_FRAMES) {
      for (N = 0; N < Nodes; N++) {
        mRef[N] /= THP_REF_FRAMES;
      }
    }

    return FALSE;
  }

  Max = 0;
  At  = 0;
  for (N = 0; N < Nodes; N++) {
    V         = (INT16)Le16 (P + THP_MATRIX + N * 2);
    mDelta[N] = V - mRef[N];
    if (mDelta[N] > Max) {
      Max = mDelta[N];
      At  = N;
    }
  }

  // Follow slow drift while nothing is near the threshold.
  if (Max < THP_THRESHOLD / 2) {
    for (N = 0; N < Nodes; N++) {
      mRef[N] += mDelta[N] / 8;
    }
  }

  if (Max < THP_THRESHOLD) {
    return TRUE;
  }

  R  = (INT32)(At / mCols);
  C  = (INT32)(At % mCols);
  Sw = 0;
  Sx = 0;
  Sy = 0;
  for (Dr = -1; Dr <= 1; Dr++) {
    for (Dc = -1; Dc <= 1; Dc++) {
      if ((R + Dr < 0) || (C + Dc < 0) || (R + Dr >= mRows) || (C + Dc >= mCols)) {
        continue;
      }

      V = mDelta[(R + Dr) * mCols + C + Dc];
      if (V > 0) {
        Sw += V;
        Sx += (INT64)V * (2 * (C + Dc) + 1);
        Sy += (INT64)V * (2 * (R + Dr) + 1);
      }
    }
  }

  // Centroid in half nodes, scaled to the screen.
  *X     = (INT32)(Sx * THP_SCREEN_W / (Sw * 2 * mCols));
  *Y     = THP_SCREEN_H - 1 - (INT32)(Sy * THP_SCREEN_H / (Sw * 2 * mRows));
  *Touch = TRUE;
  return TRUE;
}

/*
  Read the strongest contact and turn it into keys. A swipe of mStep along
  the main axis is one arrow key per touch; a short touch is a tap, and two
  taps close together are Enter.
*/
STATIC
UINTN
EFIAPI
TouchPoll (
  OUT EFI_KEY_DATA  *Keys,
  IN  UINTN         Max
  )
{
  UINTN    Count;
  BOOLEAN  Found;
  INT32    X;
  INT32    Y;
  INT32    Dx;
  INT32    Dy;
  UINT64   Now;

  STATIC BOOLEAN  Skip;

  // Every other keypad tick (40 ms): a frame read keeps the bus busy.
  Skip = !Skip;
  if (Skip) {
    return 0;
  }

  Count = 0;
  X     = 0;
  Y     = 0;
  if (!ThpRead (&Found, &X, &Y)) {
    return 0;
  }

  if (Found) {
    mGap = 0;
    mDownFrames++;
    mLastSeen = NowNs ();
    if (!mDown) {
      mDown       = TRUE;
      mMoved      = FALSE;
      mDownFrames = 1;
      mDownAt     = mLastSeen;
      mAnchorX    = mStartX = X;
      mAnchorY    = mStartY = Y;
      return 0;
    }

    // One swipe is one step: after the first key, wait for the lift.
    if (mMoved) {
      return 0;
    }

    Dx = X - mAnchorX;
    Dy = Y - mAnchorY;
    if ((UINT32)ABS (Dx) >= mStep && ABS (Dx) >= ABS (Dy)) {
      Emit (Keys, &Count, Max, (Dx > 0) ? SCAN_RIGHT : SCAN_LEFT, 0);
      mMoved = TRUE;
    } else if ((UINT32)ABS (Dy) >= mStep) {
      Emit (Keys, &Count, Max, (Dy > 0) ? SCAN_DOWN : SCAN_UP, 0);
      mMoved = TRUE;
    }

    return Count;
  }

  if (!mDown) {
    return 0;
  }

  // A held finger can drop out for a frame: lift only after LIFT_FRAMES
  // empty frames in a row.
  if (++mGap < LIFT_FRAMES) {
    return 0;
  }

  mDown = FALSE;
  if (mMoved) {
    return 0;
  }

  // A tap is short and seen on more than one frame; anything else (a
  // long press, a one-frame blip) is ignored.
  if ((mDownFrames < 2) || (mLastSeen - mDownAt > TAP_MAX_NS)) {
    mLastTap = 0;
    return 0;
  }

  // A tap: Enter on the second one, if it is soon and close to the first.
  Now = NowNs ();
  if ((mLastTap != 0) && (Now - mLastTap < DOUBLE_TAP_NS) &&
      ((UINT32)ABS (mStartX - mTapX) < mStep) && ((UINT32)ABS (mStartY - mTapY) < mStep))
  {
    Emit (Keys, &Count, Max, SCAN_NULL, CHAR_CARRIAGE_RETURN);
    mLastTap = 0;
  } else {
    mLastTap = Now;
    mTapX    = mStartX;
    mTapY    = mStartY;
  }

  return Count;
}

STATIC PIANO_TOUCH_KEYS_PROTOCOL  mTouchKeys = { PIANO_TOUCH_KEYS_REVISION, TouchPoll };

VOID
PianoTouchStart (
  VOID
  )
{
  EFI_HANDLE  Handle;
  EFI_STATUS  Status;
  UINT8       Id[6];

  EfiBootManagerConnectAll ();

  Status = EnableClocks ();
  if (!EFI_ERROR (Status)) {
    Status = StartGpiDxe ();
  }

  if (!EFI_ERROR (Status)) {
    SmmuAllowStream (0xB6);   // QUP1 GPI DMA
    SmmuAllowStream (0xA3);   // QUP1 serial engines
    Status = MuxPins ();
  }

  if (!EFI_ERROR (Status)) {
    Status = StartGpii ();
  }

  if (!EFI_ERROR (Status)) {
    mTxBuf = AllocateAlignedPages (XFER_PAGES, SIZE_4KB);
    mRxBuf = AllocateAlignedPages (XFER_PAGES, SIZE_4KB);
    Status = ((mTxBuf != NULL) && (mRxBuf != NULL)) ? EFI_SUCCESS : EFI_OUT_OF_RESOURCES;
  }

  if (!EFI_ERROR (Status)) {
    Status = NvtRead (NVT_CHIP_ID, Id, sizeof (Id));
    if (!EFI_ERROR (Status) && (CompareMem (Id, mNvtChipId, sizeof (Id)) != 0)) {
      Status = EFI_NOT_FOUND;
    }
  }

  // Load the controller firmware for this panel.
  if (!EFI_ERROR (Status)) {
    VOID     *Fw;
    UINTN    FwSize;
    BOOLEAN  Boe;

    Boe    = PanelIsBoe ();
    Status = GetSectionFromAnyFv (Boe ? &mNvtFwBoe : &mNvtFwCsot, EFI_SECTION_RAW, 0, &Fw, &FwSize);
    if (!EFI_ERROR (Status)) {
      Status = NvtDownload (Fw, FwSize);
      FreePool (Fw);
    }
  }

  if (!EFI_ERROR (Status)) {
    mMaxX = THP_SCREEN_W;
    mMaxY = THP_SCREEN_H;
    mStep = MAX (mMaxX, mMaxY) / 16;
  }


  DEBUG ((DEBUG_INFO, "PianoTouch: %r, %ux%u\n", Status, mMaxX, mMaxY));

  // Take the untouched reference (about 16 frames) before handing over.
  if (!EFI_ERROR (Status)) {
    BOOLEAN  Touch;
    INT32    X;
    INT32    Y;
    UINTN    T;

    // Let the freshly started firmware settle: its first frames are not
    // a usable reference.
    gBS->Stall (300000);
    for (T = 0; T < 8; T++) {
      NvtReadFrame (NVT_EVENT_BUF, THP_FRAME);
      gBS->Stall (10000);
    }

    for (T = 0; (T < 100) && (mRefCount < THP_REF_FRAMES); T++) {
      ThpRead (&Touch, &X, &Y);
      gBS->Stall (20000);
    }

    if (mRefCount < THP_REF_FRAMES) {
      Status = EFI_NOT_READY;
    }
  }

  // Hand the touch screen to the keypad driver only now: it polls from a
  // timer, and two readers at once would break a transfer.
  if (!EFI_ERROR (Status)) {
    Handle = NULL;
    Status = gBS->InstallMultipleProtocolInterfaces (&Handle, &gPianoTouchKeysProtocolGuid, &mTouchKeys, NULL);
  }
}
