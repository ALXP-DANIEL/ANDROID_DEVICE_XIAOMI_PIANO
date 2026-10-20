/** @file
  Piano UEFI menu: a large-text, button-driven menu shown when there is
  nothing to boot. Volume keys move, power selects.

  SPDX-License-Identifier: BSD-2-Clause-Patent
**/

#include <Uefi.h>
#include <Library/BaseLib.h>
#include <Library/UefiBootServicesTableLib.h>
#include <Library/UefiRuntimeServicesTableLib.h>
#include <Protocol/GraphicsOutput.h>

#include "PianoMenu.h"

typedef UINT8 u8;

/* 5x7 font, shared with the boot menu (selector/font.c). */
static const u8 letters[26][7] = {
	{0x0E,0x11,0x11,0x1F,0x11,0x11,0x11}, /* A */
	{0x1E,0x11,0x11,0x1E,0x11,0x11,0x1E}, /* B */
	{0x0E,0x11,0x10,0x10,0x10,0x11,0x0E}, /* C */
	{0x1E,0x11,0x11,0x11,0x11,0x11,0x1E}, /* D */
	{0x1F,0x10,0x10,0x1E,0x10,0x10,0x1F}, /* E */
	{0x1F,0x10,0x10,0x1E,0x10,0x10,0x10}, /* F */
	{0x0E,0x11,0x10,0x17,0x11,0x11,0x0F}, /* G */
	{0x11,0x11,0x11,0x1F,0x11,0x11,0x11}, /* H */
	{0x0E,0x04,0x04,0x04,0x04,0x04,0x0E}, /* I */
	{0x07,0x02,0x02,0x02,0x02,0x12,0x0C}, /* J */
	{0x11,0x12,0x14,0x18,0x14,0x12,0x11}, /* K */
	{0x10,0x10,0x10,0x10,0x10,0x10,0x1F}, /* L */
	{0x11,0x1B,0x15,0x15,0x11,0x11,0x11}, /* M */
	{0x11,0x11,0x19,0x15,0x13,0x11,0x11}, /* N */
	{0x0E,0x11,0x11,0x11,0x11,0x11,0x0E}, /* O */
	{0x1E,0x11,0x11,0x1E,0x10,0x10,0x10}, /* P */
	{0x0E,0x11,0x11,0x11,0x15,0x12,0x0D}, /* Q */
	{0x1E,0x11,0x11,0x1E,0x14,0x12,0x11}, /* R */
	{0x0F,0x10,0x10,0x0E,0x01,0x01,0x1E}, /* S */
	{0x1F,0x04,0x04,0x04,0x04,0x04,0x04}, /* T */
	{0x11,0x11,0x11,0x11,0x11,0x11,0x0E}, /* U */
	{0x11,0x11,0x11,0x11,0x11,0x0A,0x04}, /* V */
	{0x11,0x11,0x11,0x15,0x15,0x15,0x0A}, /* W */
	{0x11,0x11,0x0A,0x04,0x0A,0x11,0x11}, /* X */
	{0x11,0x11,0x0A,0x04,0x04,0x04,0x04}, /* Y */
	{0x1F,0x01,0x02,0x04,0x08,0x10,0x1F}, /* Z */
};

static const u8 digits[10][7] = {
	{0x0E,0x11,0x13,0x15,0x19,0x11,0x0E},
	{0x04,0x0C,0x04,0x04,0x04,0x04,0x0E},
	{0x0E,0x11,0x01,0x02,0x04,0x08,0x1F},
	{0x1F,0x02,0x04,0x02,0x01,0x11,0x0E},
	{0x02,0x06,0x0A,0x12,0x1F,0x02,0x02},
	{0x1F,0x10,0x1E,0x01,0x01,0x11,0x0E},
	{0x06,0x08,0x10,0x1E,0x11,0x11,0x0E},
	{0x1F,0x01,0x02,0x04,0x08,0x08,0x08},
	{0x0E,0x11,0x11,0x0E,0x11,0x11,0x0E},
	{0x0E,0x11,0x11,0x0F,0x01,0x02,0x0C},
};

static const u8 sym_space[7] = {0};
static const u8 sym_dot[7]   = {0,0,0,0,0,0x0C,0x0C};
static const u8 sym_colon[7] = {0,0x0C,0x0C,0,0x0C,0x0C,0};
static const u8 sym_dash[7]  = {0,0,0,0x1F,0,0,0};
static const u8 sym_slash[7] = {0x01,0x01,0x02,0x04,0x08,0x10,0x10};
static const u8 sym_lpar[7]  = {0x02,0x04,0x08,0x08,0x08,0x04,0x02};
static const u8 sym_rpar[7]  = {0x08,0x04,0x02,0x02,0x02,0x04,0x08};
static const u8 sym_gt[7]    = {0x08,0x04,0x02,0x01,0x02,0x04,0x08};

STATIC CONST u8 *font_glyph (CHAR8 c)
{
	if (c >= 'a' && c <= 'z')
		c = (char)(c - 'a' + 'A');
	if (c >= 'A' && c <= 'Z')
		return letters[c - 'A'];
	if (c >= '0' && c <= '9')
		return digits[c - '0'];
	switch (c) {
	case ' ': return sym_space;
	case '.': return sym_dot;
	case ':': return sym_colon;
	case '-': return sym_dash;
	case '/': return sym_slash;
	case '(': return sym_lpar;
	case ')': return sym_rpar;
	case '>': return sym_gt;
	}
	return 0;
}

#define C_BG      0x0D1117
#define C_PANEL   0x1A1F2B
#define C_BORDER  0x5CC8BE
#define C_TEXT    0xF0F0F0
#define C_DIM     0x8A93A3

STATIC EFI_GRAPHICS_OUTPUT_PROTOCOL *mGop;
STATIC UINTN                         mDiv = 1;  /* panel pixels per GOP pixel */

/* Coordinates below are for the 3200x2136 panel. */
#define P(v)  ((v) / mDiv)

STATIC
VOID
Fill (UINTN X, UINTN Y, UINTN W, UINTN H, UINT32 Color)
{
  EFI_GRAPHICS_OUTPUT_BLT_PIXEL Pixel;

  Pixel.Blue     = Color & 0xFF;
  Pixel.Green    = (Color >> 8) & 0xFF;
  Pixel.Red      = (Color >> 16) & 0xFF;
  Pixel.Reserved = 0;
  mGop->Blt (mGop, &Pixel, EfiBltVideoFill, 0, 0, P (X), P (Y), MAX (P (W), 1), MAX (P (H), 1), 0);
}

STATIC
VOID
Text (UINTN X, UINTN Y, UINTN S, UINT32 Color, CONST CHAR8 *Str)
{
  for (; *Str; Str++, X += 6 * S) {
    CONST u8 *G = font_glyph (*Str);

    if (G == NULL) {
      continue;
    }
    for (UINTN R = 0; R < 7; R++) {
      for (UINTN C = 0; C < 5; C++) {
        if (G[R] & (0x10 >> C)) {
          Fill (X + C * S, Y + R * S, S, S, Color);
        }
      }
    }
  }
}

STATIC CONST CHAR8 *mTitle[] = { "Reboot", "UEFI Shell", "Power off" };
STATIC CONST CHAR8 *mNote[]  = {
  "Back to the boot menu and Android",
  "Needs a keyboard to type",
  "Turn the tablet off",
};

STATIC
VOID
DrawItem (UINTN Index, BOOLEAN Selected)
{
  UINTN W = 3200;
  UINTN Y = 500 + Index * 320;

  Fill (120, Y, W - 240, 290, Selected ? C_BORDER : C_PANEL);
  Fill (128, Y + 8, W - 256, 274, C_PANEL);
  Text (220, Y + 50, 12, C_TEXT, mTitle[Index]);
  Text (220, Y + 175, 7, C_DIM, mNote[Index]);
}

PIANO_MENU_CHOICE
PianoMenuRun (VOID)
{
  EFI_STATUS    Status;
  EFI_INPUT_KEY Key;
  UINTN         Sel = 0, Event;

  Status = gBS->LocateProtocol (&gEfiGraphicsOutputProtocolGuid, NULL, (VOID **)&mGop);
  if (EFI_ERROR (Status)) {
    return PianoMenuShell;
  }

  mDiv = MAX (3200 / mGop->Mode->Info->HorizontalResolution, 1);

  UINTN W = 3200;
  UINTN H = 2136;

  gST->ConOut->EnableCursor (gST->ConOut, FALSE);
  Fill (0, 0, W, H, C_BG);
  Text (120, 130, 14, C_TEXT, "PIANO UEFI");
  Fill (120, 300, W - 240, 4, C_PANEL);
  Text (120, 380, 9, C_TEXT, "No operating system found");
  for (UINTN I = 0; I < 3; I++) {
    DrawItem (I, I == Sel);
  }
  Fill (120, 1800, W - 240, 4, C_PANEL);
  Text (120, 1890, 7, C_DIM, "Volume keys to move - Power to select");

  for (;;) {
    gBS->WaitForEvent (1, &gST->ConIn->WaitForKey, &Event);
    if (EFI_ERROR (gST->ConIn->ReadKeyStroke (gST->ConIn, &Key))) {
      continue;
    }
    if (Key.ScanCode == SCAN_UP || Key.ScanCode == SCAN_DOWN ||
        Key.ScanCode == SCAN_LEFT || Key.ScanCode == SCAN_RIGHT) {
      BOOLEAN Down = Key.ScanCode == SCAN_DOWN || Key.ScanCode == SCAN_RIGHT;
      UINTN   Next = (Sel + (Down ? 1 : 2)) % 3;

      DrawItem (Sel, FALSE);
      DrawItem (Next, TRUE);
      Sel = Next;
    } else if (Key.UnicodeChar == CHAR_CARRIAGE_RETURN) {
      break;
    }
  }

  if (Sel == 0) {
    gRT->ResetSystem (EfiResetCold, EFI_SUCCESS, 0, NULL);
  } else if (Sel == 2) {
    gRT->ResetSystem (EfiResetShutdown, EFI_SUCCESS, 0, NULL);
  }

  Fill (0, 0, W, H, 0);
  gST->ConOut->ClearScreen (gST->ConOut);
  gST->ConOut->EnableCursor (gST->ConOut, TRUE);
  return PianoMenuShell;
}
