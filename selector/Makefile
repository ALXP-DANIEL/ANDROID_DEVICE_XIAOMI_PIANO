# SPDX-License-Identifier: BSD-2-Clause-Patent
LLVM ?= /opt/homebrew/opt/llvm/bin
LLD ?= /opt/homebrew/opt/lld/bin
CC := $(LLVM)/clang
LD := $(LLD)/ld.lld
OBJCOPY := $(LLVM)/llvm-objcopy
OUT ?= ../out

# MMU off means Device memory: no unaligned accesses, no FP/SIMD.
CFLAGS := --target=aarch64-none-elf -O2 -std=c11 -ffreestanding -fno-builtin \
	-fno-stack-protector -fno-unwind-tables -fno-asynchronous-unwind-tables \
	-fno-pic -fno-jump-tables -mgeneral-regs-only -mstrict-align -Wall -Wextra -Werror

OBJS := $(addprefix $(OUT)/,entry.o menu.o keys.o font.o mmu.o fdt.o)

all: $(OUT)/selector.bin

$(OUT):
	mkdir -p $@
$(OUT)/%.o: %.c selector.h | $(OUT)
	$(CC) $(CFLAGS) -c $< -o $@
$(OUT)/%.o: %.S | $(OUT)
	$(CC) --target=aarch64-none-elf -c $< -o $@
# The selector runs wherever ABL loads it, so no absolute addresses allowed.
$(OUT)/selector.elf: $(OBJS) selector.ld
	! $(LLVM)/llvm-objdump -r $(OBJS) | grep -E "ABS(64|32)"
	$(LD) -T selector.ld $(OBJS) -o $@
$(OUT)/selector.bin: $(OUT)/selector.elf
	$(OBJCOPY) -O binary $< $@

clean:
	rm -rf $(OUT)
.PHONY: all clean
