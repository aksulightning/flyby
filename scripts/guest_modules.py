"""Explicit module set for the pinned Alpine kernel; dependencies are added at build time."""

MODULE_GROUPS = {
    'base': ('ext4', 'overlay', 'fuse', 'realtek', 'r8169', 'af_packet', '9p', '9pnet', '9pnet_fd'),
    'execution': ('binfmt_misc',),
    'display': ('evdev', 'uhid', 'hid-generic'),
    'storage': ('loop', 'squashfs', 'vfat', 'exfat', 'nls_cp437', 'nls_utf8'),
    'network': ('tun', 'veth', 'bridge', 'br_netfilter', '8021q', 'dummy', 'wireguard'),
    'firewall': (
        'nf_conntrack', 'nf_nat', 'nf_tables', 'nft_ct', 'nft_chain_nat', 'nft_nat',
        'nft_masq', 'nft_redir', 'nft_reject', 'nft_reject_inet', 'nft_log', 'nft_limit',
        'nf_log_syslog', 'nft_compat', 'xt_conntrack', 'xt_MASQUERADE', 'xt_addrtype', 'xt_comment', 'xt_tcpudp',
    ),
}
GUEST_MODULES = tuple(module for group in MODULE_GROUPS.values() for module in group)


def select_modules(dependencies):
    """Fail the build if any requested module is missing, rather than silently omitting it."""
    selected = set()

    def include(name):
        if name in selected:
            return
        selected.add(name)
        for dependency in dependencies[name].split():
            include(dependency)

    for module in GUEST_MODULES:
        matches = [name for name in dependencies if name.endswith('/'+module+'.ko.gz')]
        if len(matches) != 1:
            raise ValueError(f'Expected exactly one kernel module: {module}; found {matches}')
        include(matches[0])
    return selected
