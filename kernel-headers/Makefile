# Stand-in kernel source for vendor/twrp generated_kernel_includes.
# The recovery uses a prebuilt kernel, so there is no kernel source tree.
# The only user is hardware/qcom/bootctrl (gpt-utils), which in its default
# BSG mode needs just linux/bsg.h and scsi/scsi_bsg_ufs.h - both already
# provided by bionic uapi. Install an empty header set.

headers_install:
	@mkdir -p $(O)/usr/include

all:
	@true
