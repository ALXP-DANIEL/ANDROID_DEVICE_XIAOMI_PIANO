/** @file
  Enable the touch bus before the Qualcomm drivers read their settings.

  XBL's configuration tree marks QUP1 SE2, the SPI bus of the NT36532
  touch controller, disabled and points its pins at GPIO 30-33. This
  driver runs first, enables the bus in the RAM copy of the tree and moves
  the pins to the touch lines, GPIO 40-43. Nothing is written to storage.

  Copyright (c) 2026, MUHAMMAD ALIF DANIEL BIN MOHD HAIRUL HEZZELIN.

  SPDX-License-Identifier: BSD-2-Clause-Patent
**/

#include <Uefi.h>
#include <Library/BaseLib.h>
#include <Library/BaseMemoryLib.h>
#include <Library/CacheMaintenanceLib.h>
#include <Library/DebugLib.h>
#include <Library/FdtLib.h>

#define XBL_DT_BASE  0x81A00000
#define XBL_DT_SIZE  0x40000

STATIC
UINT32
Be32 (
  IN CONST UINT8  *P
  )
{
  return ((UINT32)P[0] << 24) | ((UINT32)P[1] << 16) | ((UINT32)P[2] << 8) | P[3];
}

STATIC
VOID
PutBe32 (
  OUT UINT8   *P,
  IN  UINT32  Value
  )
{
  P[0] = (UINT8)(Value >> 24);
  P[1] = (UINT8)(Value >> 16);
  P[2] = (UINT8)(Value >> 8);
  P[3] = (UINT8)Value;
}

/* Point a pinctrl state's four "config" pins at GPIO 40-43. */
STATIC
BOOLEAN
MovePins (
  IN VOID          *Fdt,
  IN INT32         Se,
  IN CONST CHAR8   *State
  )
{
  CONST UINT8  *Ref;
  UINT8        *Config;
  INT32        Len;
  INT32        Node;
  UINTN        Index;

  Ref = FdtGetProp (Fdt, Se, State, &Len);
  if ((Ref == NULL) || (Len != 4)) {
    return FALSE;
  }

  Node = FdtNodeOffsetByPhandle (Fdt, Be32 (Ref));
  if (Node < 0) {
    return FALSE;
  }

  Config = (UINT8 *)FdtGetProp (Fdt, Node, "config", &Len);
  if ((Config == NULL) || (Len != 32)) {
    return FALSE;
  }

  for (Index = 0; Index < 4; Index++) {
    PutBe32 (Config + 8 * Index, 40 + (UINT32)Index);
  }

  return TRUE;
}

/* Enable QUP1 SE2 in the RAM copy of XBL's configuration tree. */
STATIC
EFI_STATUS
EnableTouchBus (
  VOID
  )
{
  UINTN        Offset;
  UINT8        *Fdt;
  INT32        Se;
  INT32        Len;
  CONST UINT8  *Status;

  for (Offset = 0; Offset + 40 <= XBL_DT_SIZE; Offset += 4) {
    Fdt = (UINT8 *)(UINTN)(XBL_DT_BASE + Offset);
    if ((Be32 (Fdt) != 0xD00DFEED) || (Be32 (Fdt + 4) > XBL_DT_SIZE - Offset) ||
        (FdtCheckHeader (Fdt) != 0))
    {
      continue;
    }

    Se = FdtPathOffset (Fdt, "/soc/TOP_QUP_1/TOP_QUP_1_SE_2");
    if (Se < 0) {
      continue;
    }

    Status = FdtGetProp (Fdt, Se, "status", &Len);
    if ((Status == NULL) || (Len != 9) || (CompareMem (Status, "disabled", 9) != 0)) {
      return (Status != NULL) && (AsciiStrCmp ((CONST CHAR8 *)Status, "okay") == 0) ? EFI_SUCCESS : EFI_UNSUPPORTED;
    }

    if (!MovePins (Fdt, Se, "pinctrl-2") || !MovePins (Fdt, Se, "pinctrl-3")) {
      return EFI_COMPROMISED_DATA;
    }

    // "okay" fits in the 9-byte "disabled" slot; the rest becomes NULs.
    ZeroMem ((VOID *)Status, 9);
    CopyMem ((VOID *)Status, "okay", 5);
    WriteBackDataCacheRange (Fdt, FdtTotalSize (Fdt));
    return EFI_SUCCESS;
  }

  return EFI_NOT_FOUND;
}

EFI_STATUS
EFIAPI
TouchBusInitialize (
  IN EFI_HANDLE        ImageHandle,
  IN EFI_SYSTEM_TABLE  *SystemTable
  )
{
  EFI_STATUS  Status;

  Status = EnableTouchBus ();
  DEBUG ((DEBUG_INFO, "TouchBus: %r\n", Status));

  // Nothing to keep resident.
  return EFI_ABORTED;
}
