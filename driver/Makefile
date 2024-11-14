# Kernel module build system

ifneq ($(KERNELRELEASE),)
    obj-m := pcie-audio.o
    pcie-audio-objs := src/pcie-audio-main.o \
                       src/pcie-audio-pcm.o \
                       src/pcie-audio-control.o \
                       src/pcie-audio-proc.o \
                       src/pcie-audio-hw.o \
                       src/pcie-audio-irq.o

    ccflags-y := -DDEBUG -g -Wall -Werror -I$(src)/include

else

KERNEL_SOURCE ?= /lib/modules/$(shell uname -r)/build
PWD := $(shell pwd)

default:
    $(MAKE) -C $(KERNEL_SOURCE) M=$(PWD) modules

clean:
    $(MAKE) -C $(KERNEL_SOURCE) M=$(PWD) clean
    rm -f modules.order Module.symvers

install:
    $(MAKE) -C $(KERNEL_SOURCE) M=$(PWD) modules_install
    depmod -a

uninstall:
    rm -f /lib/modules/$(shell uname -r)/extra/pcie-audio.ko
    depmod -a

endif

# Development tools
style:
    clang-format -i src/*.c include/*.h

check:
    sparse src/*.c
    $(KERNEL_SOURCE)/scripts/checkpatch.pl --no-tree -f src/*.c include/*.h

tags:
    ctags -R .

.PHONY: default clean install uninstall style check tags