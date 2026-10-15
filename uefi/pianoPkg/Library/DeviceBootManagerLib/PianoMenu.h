/** @file
  SPDX-License-Identifier: BSD-2-Clause-Patent
**/
#ifndef PIANO_MENU_H_
#define PIANO_MENU_H_

typedef enum {
  PianoMenuShell,
} PIANO_MENU_CHOICE;

/* Returns only when the shell was chosen; reboot and power off do not return. */
PIANO_MENU_CHOICE
PianoMenuRun (VOID);

/* Start the touch screen and offer its gestures as keys (PianoTouch.c). */
VOID
PianoTouchStart (VOID);

/* Publish \EFI\piano\piano.dtb for Linux, if present (PianoDtb.c). */
VOID
PianoDtbInstall (VOID);

#endif
