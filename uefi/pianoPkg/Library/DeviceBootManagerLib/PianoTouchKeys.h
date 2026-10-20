/** @file
  Touch screen gestures as keys, from the boot manager's touch driver to
  KeypadDeviceLib.

  Copyright (c) 2026, MUHAMMAD ALIF DANIEL BIN MOHD HAIRUL HEZZELIN.

  SPDX-License-Identifier: BSD-2-Clause-Patent
**/

#ifndef PIANO_TOUCH_KEYS_H_
#define PIANO_TOUCH_KEYS_H_

#include <Protocol/SimpleTextInEx.h>

#define PIANO_TOUCH_KEYS_REVISION  1

STATIC EFI_GUID  gPianoTouchKeysProtocolGuid = {
  0x7D2E41C8, 0x3F95, 0x4B60, { 0xA1, 0x7C, 0x58, 0xE0, 0x93, 0x2B, 0x6D, 0x14 }
};

/* Read the touch screen once; store up to Max keys and return how many. */
typedef
UINTN
(EFIAPI *PIANO_TOUCH_KEYS_POLL)(
  OUT EFI_KEY_DATA  *Keys,
  IN  UINTN         Max
  );

typedef struct {
  UINT64                   Revision;
  PIANO_TOUCH_KEYS_POLL    Poll;
} PIANO_TOUCH_KEYS_PROTOCOL;

#endif
