/**
  Piano volume and power keys for the Silicium keypad driver.

  The key state machine comes from Mu-Silicium's Samsung a7 KeypadDeviceLib
  (BSD-2-Clause, see Mu-Silicium's LICENSE); the SPMI key reading is piano's.

  SPDX-License-Identifier: BSD-2-Clause
**/
#include <Uefi.h>

#include <Library/BaseMemoryLib.h>
#include <Library/BitmapLib.h>
#include <Library/KeypadDeviceLib.h>
#include <Library/TimerLib.h>
#include <Library/DebugLib.h>
#include <Library/IoLib.h>
#include <Library/UefiBootServicesTableLib.h>




typedef struct {
  KEY_CONTEXT EfiKeyContext;
  UINT8       Mask;
} KEY_CONTEXT_PRIVATE;

UINTN gBitmapScanCodes[BITMAP_NUM_WORDS(0x18)]    = {0};
UINTN gBitmapUnicodeChars[BITMAP_NUM_WORDS(0x7f)] = {0};


#define MS2NS(ms) (((UINT64)(ms)) * 1000000ULL)

STATIC
inline
VOID
KeySetState (
  UINT16  ScanCode,
  CHAR16  UnicodeChar,
  BOOLEAN Value)
{
  if (ScanCode && ScanCode < 0x18) {
    if (Value) {
      BitmapSet(gBitmapScanCodes, ScanCode);
    } else {
      BitmapClear(gBitmapScanCodes, ScanCode);
    }
  }

  if (UnicodeChar && UnicodeChar < 0x7f) {
    if (Value) {
      BitmapSet(gBitmapUnicodeChars, ScanCode);
    } else {
      BitmapClear(gBitmapUnicodeChars, ScanCode);
    }
  }
}

STATIC
inline
VOID
LibKeyInitializeKeyContext (KEY_CONTEXT *Context)
{
  SetMem(&Context->KeyData, sizeof(Context->KeyData), 0);

  Context->Time      = 0;
  Context->State     = KEYSTATE_RELEASED;
  Context->Repeat    = FALSE;
  Context->Longpress = FALSE;
}

STATIC
inline
VOID
LibKeyUpdateKeyStatus (
  KEY_CONTEXT       *Context,
  KEYPAD_RETURN_API *KeypadReturnApi,
  BOOLEAN            IsPressed,
  UINT64             Delta)
{
  // Keep Track of the Actual State
  KeySetState (Context->KeyData.Key.ScanCode, Context->KeyData.Key.UnicodeChar, IsPressed);

  // Update Key Time
  Context->Time += Delta;

  switch (Context->State) {
    case KEYSTATE_RELEASED:
      if (IsPressed) {
        // Change to Pressed
        Context->Time  = 0;
        Context->State = KEYSTATE_PRESSED;
      }

      break;

    case KEYSTATE_PRESSED:
      if (IsPressed) {
        // Key Repeat
        if (Context->Repeat && Context->Time >= MS2NS(100)) {
          KeypadReturnApi->PushEfikeyBufTail (KeypadReturnApi, &Context->KeyData);
          Context->Time   = 0;
          Context->Repeat = TRUE;
        } else if (!Context->Longpress && Context->Time >= MS2NS(500)) {
          // Power held reports nothing (Escape would leave Visor); the
          // volume keys start repeating.
          if (Context->KeyData.Key.UnicodeChar != CHAR_CARRIAGE_RETURN) {
            KeypadReturnApi->PushEfikeyBufTail (KeypadReturnApi, &Context->KeyData);
            Context->Time   = 0;
            Context->Repeat = TRUE;
          }

          Context->Longpress = TRUE;
        }
      } else {
        if (!Context->Longpress) {
          // We Supressed Down, so Report it Now
          KeypadReturnApi->PushEfikeyBufTail (KeypadReturnApi, &Context->KeyData);
          Context->State = KEYSTATE_LONGPRESS_RELEASE;
        } else if (Context->Time >= MS2NS(10)) {
          // We Reported another Key Already
          Context->Time      = 0;
          Context->Repeat    = FALSE;
          Context->Longpress = FALSE;
          Context->State     = KEYSTATE_RELEASED;
        }
      }

      break;

    case KEYSTATE_LONGPRESS_RELEASE:
      // Change to Released
      Context->Time      = 0;
      Context->Repeat    = FALSE;
      Context->Longpress = FALSE;
      Context->State     = KEYSTATE_RELEASED;
      break;

    default:
      ASSERT(FALSE);
      break;
  }
}

//
// Piano keys are read from the PMIC through the SPMI arbiter's observer
// channel (arbiter v7, EE0), the same way the selector reads them:
//   power and volume down: PON peripheral (PPID 0x013), register 0x10 bits 7 and 6
//   volume up:             GPIO peripheral (PPID 0x18D), register 0x10 bit 0, active low
// Only one-byte reads are issued.
//
#define ARB_CORE   0x0C400000
#define ARB_CNFG   0x0C42D000
#define ARB_OBSV   0x0C440000

#define KEY_UP     BIT0
#define KEY_DOWN   BIT1
#define KEY_POWER  BIT2

STATIC UINT16  mPonApid;
STATIC UINT16  mGpioApid;
STATIC BOOLEAN mKeysReady;

STATIC KEY_CONTEXT_PRIVATE KeyContextVolumeUp   = { .Mask = KEY_UP };
STATIC KEY_CONTEXT_PRIVATE KeyContextVolumeDown = { .Mask = KEY_DOWN };
STATIC KEY_CONTEXT_PRIVATE KeyContextPower      = { .Mask = KEY_POWER };
STATIC KEY_CONTEXT_PRIVATE *KeyList[] = { &KeyContextVolumeUp, &KeyContextVolumeDown, &KeyContextPower };

STATIC
BOOLEAN
FindApid (
  UINT16  Ppid,
  UINT32  Count,
  UINT16 *Apid)
{
  for (UINT32 Index = 0; Index < Count; Index++) {
    UINT32 Map = MmioRead32 (ARB_CORE + 0x2000 + 4 * Index);

    if (Map == 0 || ((Map >> 8) & 0xFFF) != Ppid) {
      continue;
    }

    if ((MmioRead32 (ARB_CNFG + 4 * Index) & 7) == 0) {
      *Apid = (UINT16)Index;
      return TRUE;
    }
  }

  return FALSE;
}

STATIC
BOOLEAN
ReadByte (
  UINT16  Apid,
  UINT8   Address,
  UINT8  *Value)
{
  UINTN Channel = ARB_OBSV + 0x20 * Apid;

  // EXT_READL, one byte
  MmioWrite32 (Channel, (1U << 27) | ((UINT32)Address << 4));

  for (UINTN Try = 0; Try < 1000; Try++) {
    UINT32 Status = MmioRead32 (Channel + 0x08);

    if (Status & 1) {
      if (Status & 0xE) {
        return FALSE;
      }

      *Value = (UINT8)MmioRead32 (Channel + 0x18);
      return TRUE;
    }

    MicroSecondDelay (1);
  }

  return FALSE;
}

STATIC
UINT32
ReadKeys ()
{
  UINT8 Pon, Gpio;

  if (!mKeysReady || !ReadByte (mPonApid, 0x10, &Pon) || !ReadByte (mGpioApid, 0x10, &Gpio)) {
    return 0;
  }

  return ((Pon & 0x80) ? KEY_POWER : 0) | ((Pon & 0x40) ? KEY_DOWN : 0) | ((Gpio & 1) ? 0 : KEY_UP);
}

RETURN_STATUS
EFIAPI
KeypadDeviceConstructor ()
{
  UINT32 Version = MmioRead32 (ARB_CORE);
  UINT32 Count   = MmioRead32 (ARB_CORE + 4) & 0x7FF;

  if (Version < 0x70000000 || Version >= 0x80000000 || Count == 0 || Count > 1024) {
    DEBUG ((EFI_D_ERROR, "%a: Unexpected SPMI Arbiter! Version = 0x%x\n", __FUNCTION__, Version));
    return RETURN_SUCCESS;
  }

  mKeysReady = FindApid (0x013, Count, &mPonApid) && FindApid (0x18D, Count, &mGpioApid);
  if (!mKeysReady) {
    DEBUG ((EFI_D_ERROR, "%a: Failed to Find the Key Peripherals!\n", __FUNCTION__));
  }

  return RETURN_SUCCESS;
}

EFI_STATUS
EFIAPI
KeypadDeviceReset (KEYPAD_DEVICE_PROTOCOL *This)
{
  // Volume keys move along a row (Visor's icons), as Left and Right.
  LibKeyInitializeKeyContext (&KeyContextVolumeUp.EfiKeyContext);
  KeyContextVolumeUp.EfiKeyContext.KeyData.Key.ScanCode   = SCAN_LEFT;

  LibKeyInitializeKeyContext (&KeyContextVolumeDown.EfiKeyContext);
  KeyContextVolumeDown.EfiKeyContext.KeyData.Key.ScanCode = SCAN_RIGHT;

  LibKeyInitializeKeyContext (&KeyContextPower.EfiKeyContext);
  KeyContextPower.EfiKeyContext.KeyData.Key.UnicodeChar   = CHAR_CARRIAGE_RETURN;

  return EFI_SUCCESS;
}

EFI_STATUS
KeypadDeviceGetKeys (
  KEYPAD_DEVICE_PROTOCOL *This,
  KEYPAD_RETURN_API      *KeypadReturnApi,
  UINT64                  Delta)
{
  UINT32  Held = ReadKeys ();

  for (UINTN Index = 0; Index < ARRAY_SIZE (KeyList); Index++) {
    LibKeyUpdateKeyStatus (&KeyList[Index]->EfiKeyContext, KeypadReturnApi, (Held & KeyList[Index]->Mask) != 0, Delta);
  }

  return EFI_SUCCESS;
}
