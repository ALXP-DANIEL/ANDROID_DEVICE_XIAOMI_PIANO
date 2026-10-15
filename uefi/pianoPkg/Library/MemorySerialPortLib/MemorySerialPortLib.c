/** @file
  SerialPortLib that keeps debug output in a RAM ring buffer, so a DEBUG
  build can be read back with "fastboot -s piano-uefi oem debuglog" instead
  of being drawn over the screen.

  SEC links the PIANO_LOG_RESET variant, which starts a new log each boot;
  every later module only appends.

  SPDX-License-Identifier: BSD-2-Clause-Patent
**/
#include <Base.h>
#include <Library/SerialPortLib.h>

#include "PianoLog.h"

STATIC volatile PIANO_LOG  *mLog = (volatile PIANO_LOG *)(UINTN)PIANO_LOG_BASE;

RETURN_STATUS
EFIAPI
SerialPortInitialize (
  VOID
  )
{
#ifdef PIANO_LOG_RESET
  mLog->Magic = 0;
#endif
  if (mLog->Magic != PIANO_LOG_MAGIC) {
    mLog->Head    = 0;
    mLog->Wrapped = 0;
    mLog->Magic   = PIANO_LOG_MAGIC;
  }

  return RETURN_SUCCESS;
}

UINTN
EFIAPI
SerialPortWrite (
  IN UINT8  *Buffer,
  IN UINTN  NumberOfBytes
  )
{
  UINTN   Index;
  UINT32  Head;

  if (mLog->Magic != PIANO_LOG_MAGIC) {
    SerialPortInitialize ();
  }

  Head = mLog->Head;
  for (Index = 0; Index < NumberOfBytes; Index++) {
    if (Head >= PIANO_LOG_DATA_SIZE) {
      Head          = 0;
      mLog->Wrapped = 1;
    }

    mLog->Data[Head++] = Buffer[Index];
  }

  mLog->Head = Head;
  return NumberOfBytes;
}

UINTN
EFIAPI
SerialPortRead (
  OUT UINT8  *Buffer,
  IN  UINTN  NumberOfBytes
  )
{
  return 0;
}

BOOLEAN
EFIAPI
SerialPortPoll (
  VOID
  )
{
  return FALSE;
}

RETURN_STATUS
EFIAPI
SerialPortSetControl (
  IN UINT32  Control
  )
{
  return RETURN_UNSUPPORTED;
}

RETURN_STATUS
EFIAPI
SerialPortGetControl (
  OUT UINT32  *Control
  )
{
  return RETURN_UNSUPPORTED;
}

RETURN_STATUS
EFIAPI
SerialPortSetAttributes (
  IN OUT UINT64              *BaudRate,
  IN OUT UINT32              *ReceiveFifoDepth,
  IN OUT UINT32              *Timeout,
  IN OUT EFI_PARITY_TYPE     *Parity,
  IN OUT UINT8               *DataBits,
  IN OUT EFI_STOP_BITS_TYPE  *StopBits
  )
{
  return RETURN_UNSUPPORTED;
}
