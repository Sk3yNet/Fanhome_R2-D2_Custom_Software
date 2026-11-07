#!/system/bin/sh
# Installed as /system/etc/install-recovery.sh by tools/install-on-robot.ps1. Android's flash_recovery service
# runs this file as root once per boot; the factory system has no such file. It starts R2's root helper in its
# own session, so nothing that stops this script's caller (init, or an adb shell) takes the helper with it.
/sbin/busybox setsid /system/bin/sh /system/bin/r2-rootd.sh < /dev/null > /dev/null 2>&1 &
