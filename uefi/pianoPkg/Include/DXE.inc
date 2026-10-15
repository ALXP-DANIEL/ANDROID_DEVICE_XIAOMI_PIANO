  INF MdeModulePkg/Core/Dxe/DxeMain.inf
  INF MdeModulePkg/Universal/PCD/Dxe/Pcd.inf

!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/EnvDxeEnhanced/EnvDxeEnhanced.inf
!endif

  INF MdeModulePkg/Universal/ReportStatusCodeRouter/RuntimeDxe/ReportStatusCodeRouterRuntimeDxe.inf
  INF MdeModulePkg/Universal/StatusCodeHandler/RuntimeDxe/StatusCodeHandlerRuntimeDxe.inf
  INF ArmPkg/Drivers/CpuDxe/CpuDxe.inf
  INF MdeModulePkg/Core/RuntimeDxe/RuntimeDxe.inf
  INF MdeModulePkg/Universal/SecurityStubDxe/SecurityStubDxe.inf

!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/ShmBridgeDxeLA/ShmBridgeDxeLA.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/ScmDxeCompat/ScmDxeCompat.inf
!endif
!if $(QCOM_DXE) == TRUE
  # TzDxeLA hangs once UFS is up.
  #INF Binaries/piano/QcomPkg/Drivers/TzDxeLA/TzDxeLA.inf
!endif

  INF MdeModulePkg/Universal/WatchdogTimerDxe/WatchdogTimer.inf
  INF MdeModulePkg/Universal/CapsuleRuntimeDxe/CapsuleRuntimeDxe.inf
  INF MdeModulePkg/Universal/Variable/RuntimeDxe/VariableRuntimeDxe.inf
  INF EmbeddedPkg/EmbeddedMonotonicCounter/EmbeddedMonotonicCounter.inf
  INF MdeModulePkg/Universal/ResetSystemRuntimeDxe/ResetSystemRuntimeDxe.inf
  INF EmbeddedPkg/RealTimeClockRuntimeDxe/RealTimeClockRuntimeDxe.inf
  INF EmbeddedPkg/MetronomeDxe/MetronomeDxe.inf
  INF MdeModulePkg/Universal/HiiDatabaseDxe/HiiDatabaseDxe.inf
  INF ArmPkg/Drivers/ArmGicDxe/ArmGicDxe.inf
  INF ArmPkg/Drivers/TimerDxe/TimerDxe.inf

!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/ChipInfo/ChipInfo.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/PlatformInfoDxeDriver/PlatformInfoDxeDriver.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/DALSys/DALSys.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/HALIOMMU/HALIOMMU.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/HWIODxeDriver/HWIODxeDriver.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/I2C/I2C.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/DALTLMM/DALTLMM.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/Qup/Qup.inf
!endif
!if $(QCOM_DXE) == TRUE
  # PmicDxe faults in SPMI on channel 690, which ABL no longer gives the apps EE.
  #INF Binaries/piano/QcomPkg/Drivers/PmicDxe/PmicDxe.inf
!endif

  INF Binaries/dodge/MdeModulePkg/Universal/DevicePathDxe/DevicePathDxe.inf
  INF Binaries/dodge/MdeModulePkg/Universal/Disk/DiskIoDxe/DiskIoDxe.inf
  INF Binaries/dodge/MdeModulePkg/Universal/Disk/PartitionDxe/PartitionDxe.inf
  INF FatPkg/EnhancedFatDxe/Fat.inf
  INF MdeModulePkg/Universal/Disk/UnicodeCollation/EnglishDxe/EnglishDxe.inf

!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/UFSDxe/UFSDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/CmdDbDxe/CmdDbDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/PwrUtilsDxe/PwrUtilsDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/RpmhDxe/RpmhDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/NpaDxe/NpaDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/ULogDxe/ULogDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/MailboxDxe/MailboxDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/QcomScmiDxe/QcomScmiDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/VcsDxe/VcsDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/ClockDxe/ClockDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/SPMI/SPMI.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/ICBDxe/ICBDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/SmemDxe/SmemDxe.inf
!endif
  INF MdeModulePkg/Universal/BdsDxe/BdsDxe.inf

!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/RscDxe/RscDxe.inf
!endif

  INF EmbeddedPkg/SimpleTextInOutSerial/SimpleTextInOutSerial.inf
  INF MdeModulePkg/Universal/PrintDxe/PrintDxe.inf
  INF MdeModulePkg/Universal/Console/ConPlatformDxe/ConPlatformDxe.inf
  INF MdeModulePkg/Universal/Console/ConSplitterDxe/ConSplitterDxe.inf
  INF MdeModulePkg/Universal/Console/GraphicsConsoleDxe/GraphicsConsoleDxe.inf

!if $(USE_CUSTOM_DISPLAY_DRIVER) == 1
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/CPRDxe/CPRDxe.inf
!endif
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/SPI/SPI.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/SdccDxe/SdccDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/FeatureEnablerDxe/FeatureEnablerDxe.inf
!endif

!if $(USE_CUSTOM_DISPLAY_DRIVER) == 1
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/DisplayDxe/DisplayDxe.inf
!endif
!else
  INF pianoPkg/Drivers/ScaledFbDxe/ScaledFbDxe.inf
!endif

!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/ButtonsDxe/ButtonsDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/ChargerExDxe/ChargerExDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/IPCCDxe/IPCCDxe.inf
!endif
  #INF Binaries/piano/QcomPkg/Drivers/GlinkDxe/GlinkDxe.inf
#INF Binaries/dodge/QcomPkg/Drivers/OcdtDxe/OplusProject.inf  # not in piano uefi.img
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/PmicGlinkDxe/PmicGlinkDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/QcomChargerDxeLA/QcomChargerDxeLA.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/UsbPwrCtrlDxe/UsbPwrCtrlDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/TsensDxe/TsensDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/DDRInfoDxe/DDRInfoDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/UsbfnDwc3Dxe/UsbfnDwc3Dxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/XhciPciEmulation/XhciPciEmulation.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/XhciDxe/XhciDxe.inf
!endif

  INF MdeModulePkg/Bus/Usb/UsbBusDxe/UsbBusDxe.inf
  INF MdeModulePkg/Bus/Usb/UsbKbDxe/UsbKbDxe.inf
  INF MdeModulePkg/Bus/Usb/UsbMassStorageDxe/UsbMassStorageDxe.inf

!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/UsbMsdDxe/UsbMsdDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/UsbDeviceDxe/UsbDeviceDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/UsbConfigDxe/UsbConfigDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/UsbInitDxe/UsbInitDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/UCDxe/UCDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/RngDxe/RngDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/PILDxe/PILDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/PILProxyDxe/PILProxyDxe.inf
!endif
!if $(QCOM_DXE) == TRUE
  INF Binaries/piano/QcomPkg/Drivers/SPSSDxe/SPSSDxe.inf
!endif
