# Lineage's generated_kernel_includes copies only Makefile and include/** of
# TARGET_KERNEL_SOURCE into its sandbox, so the header export lives in include/.
KERNEL_HEADERS_TARBALL := $(abspath $(CURDIR)/include/prebuilt_kernel_headers.tar.gz)
# make -C enters this directory, but O= is relative to the tree root
# (device/xiaomi/piano-kernel/kernel-headers is four levels below it).
HEADERS_OUT := $(if $(filter /%,$(O)),$(O),$(abspath $(CURDIR)/../../../../$(O)))

.PHONY: all headers_install

headers_install:
	@mkdir -p $(HEADERS_OUT)
	@gzip -dc $(KERNEL_HEADERS_TARBALL) | tar -xf - -C $(HEADERS_OUT)

all:
	@true
