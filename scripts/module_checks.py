"""Guest-side checks shared by the Minimal and Service image acceptance tests."""
from guest_modules import GUEST_MODULES

# Exercise alias resolution before the explicit load sweep. The binfmt handler
# ignores an intentionally invalid shell program, proving kernel dispatch rather
# than the shell's ENOEXEC fallback. Everything is removed before the disk test.
COMMON_MODULE_CHECKS = " ".join('''
modprobe char-major-10-200 && test -c /dev/net/tun &&
modprobe rtnl-link-veth && test -d /sys/module/veth &&
'''.splitlines()) + ' for module in ' + ' '.join(GUEST_MODULES) + (
    '; do modprobe "$module" && test -d "/sys/module/$(printf %s "$module" | tr - _)" '
    '|| { echo "FLYBY_MODULE_ERROR $module"; exit 1; }; done && '
)
COMMON_MODULE_CHECKS += " ".join('''
binfmt_test=$(mktemp -d /tmp/flyby-binfmt.XXXXXX) &&
mkdir "$binfmt_test/registry" && mount -t binfmt_misc binfmt_misc "$binfmt_test/registry" &&
printf '#!/bin/sh\\nprintf binfmt-dispatched\\n' > "$binfmt_test/interpreter" &&
printf 'not a shell program\\n' > "$binfmt_test/program.flyby-test" &&
chmod +x "$binfmt_test/interpreter" "$binfmt_test/program.flyby-test" &&
printf ':flyby-test:E::flyby-test::%s/interpreter:\\n' "$binfmt_test" > "$binfmt_test/registry/register" &&
[ "$("$binfmt_test/program.flyby-test")" = binfmt-dispatched ] &&
echo -1 > "$binfmt_test/registry/flyby-test" &&
umount "$binfmt_test/registry" && rm -rf "$binfmt_test" &&
'''.splitlines())

# A real OverlayFS copy-up must leave the lower file unchanged. Opening the
# kernel FUSE device checks its availability without adding a userspace daemon.
FILESYSTEM_MODULE_CHECKS = COMMON_MODULE_CHECKS + " ".join('''
modprobe overlay && modprobe fuse &&
test -d /sys/module/overlay && test -d /sys/module/fuse &&
test -c /dev/fuse && (exec 3<> /dev/fuse) &&
overlay_test=$(mktemp -d /tmp/flyby-overlay.XXXXXX) &&
mkdir "$overlay_test/lower" "$overlay_test/upper" "$overlay_test/work" "$overlay_test/merged" &&
printf base > "$overlay_test/lower/file" &&
mount -t overlay overlay -o "lowerdir=$overlay_test/lower,upperdir=$overlay_test/upper,workdir=$overlay_test/work" "$overlay_test/merged" &&
[ "$(cat "$overlay_test/merged/file")" = base ] &&
printf changed > "$overlay_test/merged/file" &&
[ "$(cat "$overlay_test/lower/file")" = base ] &&
[ "$(cat "$overlay_test/upper/file")" = changed ] &&
umount "$overlay_test/merged" && rm -rf "$overlay_test" &&
'''.splitlines())
