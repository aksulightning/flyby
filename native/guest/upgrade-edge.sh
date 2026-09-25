#!/bin/sh
# Runs before init only for an explicitly requested in-place SYSTEM upgrade.
set -eu
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
main=https://dl-cdn.alpinelinux.org/alpine/edge/main
community=https://dl-cdn.alpinelinux.org/alpine/edge/community
if grep -qx 'PRETTY_NAME="Alpine Linux edge"' /etc/os-release &&
   grep -qxF "$main" /etc/apk/repositories && grep -qxF "$community" /etc/apk/repositories &&
   ! grep -Eq '/v[0-9]+\.[0-9]+/' /etc/apk/repositories; then
    echo 'This installation already uses Alpine Edge.'
    echo FLYBY_EDGE_ALREADY
    exit 0
fi
echo 'Upgrading the existing Alpine installation to Edge. Do not stop Linux.'
echo FLYBY_EDGE_UPGRADE_STARTED
# Network drivers were loaded from the current initramfs before switch_root.
ip link set lo up
ip link set eth0 up
udhcpc -i eth0 -f -n -q -t 5 -T 3 -s /run/flyby/upgrade-dhcp
backup=/etc/apk/repositories.before-edge
[ -e "$backup" ] || cp -p /etc/apk/repositories "$backup"
printf '%s\n' "$main" "$community" > /etc/apk/repositories.edge-new
mv /etc/apk/repositories.edge-new /etc/apk/repositories
apk update
apk add --upgrade apk-tools
apk upgrade --available
grep -qx 'PRETTY_NAME="Alpine Linux edge"' /etc/os-release
sync
echo 'Alpine Edge upgrade completed. Your existing files are on the same disk.'
echo FLYBY_EDGE_UPGRADE_OK
