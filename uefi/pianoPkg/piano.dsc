##
#  Copyright (c) 2011 - 2022, ARM Limited. All rights reserved.
#  Copyright (c) 2014, Linaro Limited. All rights reserved.
#  Copyright (c) 2015 - 2020, Intel Corporation. All rights reserved.
#  Copyright (c) 2018, Bingxing Wang. All rights reserved.
#  Copyright (c) Microsoft Corporation.
#  Copyright (c) 2026, MUHAMMAD ALIF DANIEL BIN MOHD HAIRUL HEZZELIN.
#
#  SPDX-License-Identifier: BSD-2-Clause-Patent
##

################################################################################
#
# Defines Section - statements that will be processed to create a Makefile.
#
################################################################################
[Defines]
  PLATFORM_NAME                  = piano
  PLATFORM_GUID                  = 6D3E1C62-5B7F-4E3A-9B0C-2A71F4C8D915
  PLATFORM_VERSION               = 0.1
  DSC_SPECIFICATION              = 0x00010005
  OUTPUT_DIRECTORY               = Build/pianoPkg
  SUPPORTED_ARCHITECTURES        = AARCH64
  BUILD_TARGETS                  = RELEASE|DEBUG
  SKUID_IDENTIFIER               = DEFAULT
  FLASH_DEFINITION               = pianoPkg/piano.fdf
  USE_CUSTOM_DISPLAY_DRIVER      = 0

  #
  # Qualcomm drivers from the device uefi.img. FALSE builds a generic
  # EDK2 firmware on the inherited framebuffer, for bring-up.
  #
  DEFINE QCOM_DXE                = TRUE

  #
  # 0 = SM8750-AB
  # 1 = SM8750-3-AB
  # 2 = SM8750-AC
  #
  SOC_TYPE                       = 0

!include PakalaPkg/PakalaPkg.dsc.inc

[PcdsFixedAtBuild]
  #
  # DDR Memory
  #
  gArmTokenSpaceGuid.PcdSystemMemoryBase|0x80000000

  #
  # UEFI Stack
  #
  gArmPlatformTokenSpaceGuid.PcdCPUCoresStackBase|0xA760D000
  gArmPlatformTokenSpaceGuid.PcdCPUCorePrimaryStackSize|0x40000

  #
  # SMBIOS
  #
  gSiliciumPkgTokenSpaceGuid.PcdSmbiosSystemManufacturer|"Xiaomi"
  gSiliciumPkgTokenSpaceGuid.PcdSmbiosSystemModel|"Pad 8 Pro"
  gSiliciumPkgTokenSpaceGuid.PcdSmbiosSystemRetailModel|"piano"
  gSiliciumPkgTokenSpaceGuid.PcdSmbiosSystemRetailSku|"25091RP04C"
  gSiliciumPkgTokenSpaceGuid.PcdSmbiosSystemBoardModel|"piano"

  #
  # Simple Frame Buffer
  #
  gSiliciumPkgTokenSpaceGuid.PcdFrameBufferWidth|3200
  gSiliciumPkgTokenSpaceGuid.PcdFrameBufferHeight|2136
  gSiliciumPkgTokenSpaceGuid.PcdFrameBufferColorDepth|32

  #
  # Platform PEI
  #
  gQcomPkgTokenSpaceGuid.PcdPlatformType|"LA"

[LibraryClasses]
  #
  # Memory Libraries
  #
  MemoryMapLib|pianoPkg/Library/MemoryMapLib/MemoryMapLib.inf

  #
  # QCOM Libraries
  #
  ConfigurationMapLib|pianoPkg/Library/ConfigurationMapLib/ConfigurationMapLib.inf

  #
  # Piano Libraries
  #
  KeypadDeviceLib|pianoPkg/Library/KeypadDeviceLib/KeypadDeviceLib.inf
  DeviceBootManagerLib|pianoPkg/Library/DeviceBootManagerLib/DeviceBootManagerLib.inf

#
# DEBUG builds keep their debug output in RAM (Piano_Log) for
# "fastboot -s piano-uefi oem debuglog" instead of drawing it over the
# screen. Runtime drivers stay silent: after ExitBootServices the log is not
# mapped for them.
#
!if $(TARGET) == DEBUG
[LibraryClasses]
  SerialPortLib|pianoPkg/Library/MemorySerialPortLib/MemorySerialPortLib.inf

[LibraryClasses.common.SEC]
  SerialPortLib|pianoPkg/Library/MemorySerialPortLib/MemorySerialPortLibSec.inf

[LibraryClasses.common.DXE_RUNTIME_DRIVER]
  SerialPortLib|MdePkg/Library/BaseSerialPortLibNull/BaseSerialPortLibNull.inf

[PcdsFixedAtBuild]
  # Print errors, warnings, info and image loads; never stop on an ASSERT.
  gEfiMdePkgTokenSpaceGuid.PcdDebugPropertyMask|0x06
  gEfiMdePkgTokenSpaceGuid.PcdDebugClearMemoryValue|0x00
  gEfiMdePkgTokenSpaceGuid.PcdDebugPrintErrorLevel|0x80000046
!endif

[Components]
  #
  # ACPI Tables
  #
  #piano/AcpiTables.inf

[Components]
  #
  # Volume and power keys
  #
  SiliciumPkg/Drivers/KeypadDxe/KeypadDxe.inf
  SiliciumPkg/Drivers/KeypadDeviceDxe/KeypadDeviceDxe.inf

  #
  # Console at half resolution with 2x2 pixels, so text is readable
  #
  pianoPkg/Drivers/ScaledFbDxe/ScaledFbDxe.inf
  pianoPkg/Drivers/TouchBusDxe/TouchBusDxe.inf

  #
  # UEFI Shell, opened when nothing else can boot
  #
  ShellPkg/Application/Shell/Shell.inf {
    <LibraryClasses>
      ShellLib|ShellPkg/Library/UefiShellLib/UefiShellLib.inf
      ShellCommandLib|ShellPkg/Library/UefiShellCommandLib/UefiShellCommandLib.inf
      HandleParsingLib|ShellPkg/Library/UefiHandleParsingLib/UefiHandleParsingLib.inf
      BcfgCommandLib|ShellPkg/Library/UefiShellBcfgCommandLib/UefiShellBcfgCommandLib.inf
      FileHandleLib|MdePkg/Library/UefiFileHandleLib/UefiFileHandleLib.inf
      SortLib|MdeModulePkg/Library/UefiSortLib/UefiSortLib.inf
      OrderedCollectionLib|MdePkg/Library/BaseOrderedCollectionRedBlackTreeLib/BaseOrderedCollectionRedBlackTreeLib.inf
      NULL|ShellPkg/Library/UefiShellLevel1CommandsLib/UefiShellLevel1CommandsLib.inf
      NULL|ShellPkg/Library/UefiShellLevel2CommandsLib/UefiShellLevel2CommandsLib.inf
      NULL|ShellPkg/Library/UefiShellLevel3CommandsLib/UefiShellLevel3CommandsLib.inf
      NULL|ShellPkg/Library/UefiShellDriver1CommandsLib/UefiShellDriver1CommandsLib.inf
      NULL|ShellPkg/Library/UefiShellDebug1CommandsLib/UefiShellDebug1CommandsLib.inf
    <PcdsFixedAtBuild>
      gEfiShellPkgTokenSpaceGuid.PcdShellLibAutoInitialize|FALSE
  }

[PcdsDynamicDefault]
  #
  # 0 = the largest text mode, so the console fills the screen instead of
  # an 80x25 box in the middle
  #
  gEfiMdeModulePkgTokenSpaceGuid.PcdConOutColumn|0
  gEfiMdeModulePkgTokenSpaceGuid.PcdConOutRow|0
