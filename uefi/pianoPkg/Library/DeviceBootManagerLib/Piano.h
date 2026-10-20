/** @file
  SPDX-License-Identifier: BSD-2-Clause-Patent
**/
#ifndef PIANO_H_
#define PIANO_H_

/* Publish \EFI\piano\piano.dtb for Linux, if present (PianoDtb.c). */
VOID
PianoDtbInstall (VOID);

/* Keep Reboot and Shut down from hanging (PianoReset.c). */
VOID
PianoResetHookInstall (VOID);

/* Debug fastboot over USB as "piano-uefi" (PianoFastboot.c). */
VOID
PianoFastbootStart (VOID);

#endif
