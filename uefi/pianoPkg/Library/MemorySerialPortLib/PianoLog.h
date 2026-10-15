/** @file
  RAM log written by MemorySerialPortLib and read by the debug fastboot.

  SPDX-License-Identifier: BSD-2-Clause-Patent
**/
#ifndef PIANO_LOG_H_
#define PIANO_LOG_H_

// "Piano_Log" in MemoryMapLib: reserved, so Linux leaves it alone.
#define PIANO_LOG_BASE   0xB7F00000
#define PIANO_LOG_SIZE   0x100000
#define PIANO_LOG_MAGIC  0x474F4C50   // "PLOG"

typedef struct {
  UINT32    Magic;
  UINT32    Head;       // next write offset in Data
  UINT32    Wrapped;    // Data is full and Head is the oldest byte
  UINT32    Reserved;
  UINT8     Data[];
} PIANO_LOG;

#define PIANO_LOG_DATA_SIZE  (PIANO_LOG_SIZE - sizeof (PIANO_LOG))

#endif
