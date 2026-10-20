#!/bin/sh
# Runs inside the Arch Linux ARM container; turns it into the piano root.
set -eux
KVER=6.6.143-android15-8-4k

# Only the official Arch Linux ARM repositories.
sed -i '/^\[aur\]/,/^$/d' /etc/pacman.conf
# Docker blocks pacman's download sandbox; turned back on at the end.
sed -i 's/^\[options\]/[options]\nDisableSandbox/' /etc/pacman.conf
# No Malaysian Arch Linux ARM mirror exists; Singapore is fastest from here.
sed -i '0,/^Server/s|^Server|Server = https://mirror.sg.gs/archlinuxarm/$arch/$repo\nServer = http://tw.mirror.archlinuxarm.org/$arch/$repo\nServer = http://de.mirror.archlinuxarm.org/$arch/$repo\n&|' /etc/pacman.d/mirrorlist
pacman-key --init
pacman-key --populate archlinuxarm
pacman -Syu --noconfirm --needed base sudo openssh networkmanager iw \
	wireless-regdb vim less kmod usbutils pciutils dosfstools e2fsprogs htop

# Kernel modules: the Android kernel's second-stage sets, loaded in order.
mkdir -p /tmp/m /usr/lib/modules/$KVER
tar -xf /in/modules.tar -C /tmp/m vendor_dlkm/lib/modules system_dlkm/lib/modules
cp -a /tmp/m/system_dlkm/lib/modules/$KVER/kernel /usr/lib/modules/$KVER/ 2>/dev/null || true
find /tmp/m/system_dlkm -name '*.ko' -exec cp -n {} /usr/lib/modules/$KVER/ \;
cp -n /tmp/m/vendor_dlkm/lib/modules/*.ko /usr/lib/modules/$KVER/
{
	cat /tmp/m/system_dlkm/lib/modules/$KVER/modules.load
	cat /tmp/m/vendor_dlkm/lib/modules/modules.load
} | sed 's|.*/||' > /usr/lib/modules/$KVER/piano-modules.load
depmod $KVER
rm -rf /tmp/m

# Firmware: Android's search order, flattened into /usr/lib/firmware.
mkdir -p /tmp/f /usr/lib/firmware
tar -xf /in/firmware.tar -C /tmp/f
for d in vendor/firmware_mnt/image vendor/bt_firmware/image vendor/dsp \
	 odm/firmware vendor/firmware; do
	[ -d /tmp/f/$d ] && cp -a /tmp/f/$d/. /usr/lib/firmware/
done
# The kernel command line sets firmware_class.path=/odm/firmware/o8.
[ -d /tmp/f/odm/firmware/o8 ] && mkdir -p /odm/firmware && cp -a /tmp/f/odm/firmware/o8 /odm/firmware/
rm -rf /tmp/f

# System setup.
echo piano > /etc/hostname
ln -sf /usr/share/zoneinfo/Asia/Kuala_Lumpur /etc/localtime
echo 'en_US.UTF-8 UTF-8' > /etc/locale.gen && locale-gen
echo 'LANG=en_US.UTF-8' > /etc/locale.conf
cat > /etc/fstab <<EOT
PARTLABEL=linux	/		ext4	rw,noatime	0 1
PARTLABEL=esp	/boot/efi	vfat	rw,noatime,nofail	0 2
EOT
mkdir -p /boot/efi

id alif >/dev/null 2>&1 || useradd -m -G wheel -s /bin/bash alif
passwd -d alif
echo '%wheel ALL=(ALL:ALL) NOPASSWD: ALL' > /etc/sudoers.d/wheel
install -d -m 700 -o alif -g alif /home/alif/.ssh
install -m 600 -o alif -g alif /in/authorized_keys /home/alif/.ssh/authorized_keys
sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config

cp /overlay/piano-modules.service /overlay/piano-usb.service /etc/systemd/system/
install -m 755 /overlay/piano-modules /overlay/piano-usb /usr/local/bin/
systemctl enable sshd NetworkManager piano-modules piano-usb
systemctl set-default multi-user.target
pacman -Scc --noconfirm
sed -i '/^DisableSandbox$/d' /etc/pacman.conf
