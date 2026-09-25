"""Guest-side checks shared by the Minimal and Service image acceptance tests."""

# A real OverlayFS copy-up must leave the lower file unchanged. Opening the
# kernel FUSE device checks its availability without adding a userspace daemon.
FILESYSTEM_MODULE_CHECKS = " ".join('''
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
