#include "network.h"
#include <array>
#include <cstring>
#include <deque>
#include <mutex>
#include <stdexcept>
// Compile the upstream userspace network backend under private symbol names.
// Only the owning Android process creates it; the VM process uses a bounded
// Ethernet queue with no socket creation or filesystem authority.
extern "C" {
tap_dev_t *user_tap_open(void);
void user_tap_attach(tap_dev_t *, const tap_net_dev_t *);
bool user_tap_send(tap_dev_t *, const void *, size_t);
bool user_tap_get_mac(tap_dev_t *, uint8_t[6]);
bool user_tap_set_mac(tap_dev_t *, const uint8_t[6]);
bool user_tap_portfwd(tap_dev_t *, const char *);
void user_tap_close(tap_dev_t *);
}
struct tap_dev {
    tap_dev_t *user = nullptr;
    tap_net_dev_t nic{};
    std::mutex mutex;
    std::deque<std::vector<uint8_t>> packets;
    std::array<uint8_t,6> mac{2,0,0,0,0,1};
    bool queue(const void *data, size_t size) {
        if (size < 14 || size > TAP_FRAME_SIZE) return false;
        std::lock_guard lock(mutex);
        if (packets.size() >= 256) return false;
        auto *p = static_cast<const uint8_t*>(data);
        packets.emplace_back(p, p + size);
        return true;
    }
};
extern "C" {
tap_dev_t *tap_open() {
    auto *tap = new tap_dev_t;
    tap->user = user_tap_open();
    if (!tap->user) { delete tap; return nullptr; }
    return tap;
}
void tap_attach(tap_dev_t *tap, const tap_net_dev_t *nic) {
    if (tap->user) user_tap_attach(tap->user, nic);
    else tap->nic = *nic;
}
bool tap_send(tap_dev_t *tap, const void *data, size_t size) {
    if (size < 14 || size > TAP_FRAME_SIZE) return false;
    return tap->user ? user_tap_send(tap->user, data, size) : tap->queue(data, size);
}
bool tap_get_mac(tap_dev_t *tap, uint8_t mac[6]) {
    if (tap->user) return user_tap_get_mac(tap->user, mac);
    std::lock_guard lock(tap->mutex); memcpy(mac, tap->mac.data(), 6); return true;
}
bool tap_set_mac(tap_dev_t *tap, const uint8_t mac[6]) {
    if (tap->user) return user_tap_set_mac(tap->user, mac);
    std::lock_guard lock(tap->mutex); memcpy(tap->mac.data(), mac, 6); return true;
}
bool tap_portfwd(tap_dev_t *, const char *) { return false; }
bool tap_ifaddr(tap_dev_t *tap, const char *addr) {
    (void)tap; (void)addr; return false;
}
void tap_close(tap_dev_t *tap) {
    if (!tap) return;
    if (tap->user) user_tap_close(tap->user);
    delete tap;
}
}
tap_dev_t *flybyProxyTap() { return new tap_dev_t; }
std::vector<uint8_t> flybyTapRead(tap_dev_t *tap) {
    std::lock_guard lock(tap->mutex);
    if (tap->packets.empty()) return {};
    auto packet = std::move(tap->packets.front()); tap->packets.pop_front();
    return packet;
}
void flybyTapInput(tap_dev_t *tap, const uint8_t *data, size_t size) {
    if (size < 14 || size > TAP_FRAME_SIZE) throw std::invalid_argument("Invalid Ethernet frame");
    if (tap->user) user_tap_send(tap->user, data, size);
    else if (tap->nic.feed_rx) tap->nic.feed_rx(tap->nic.net_dev, data, size);
}
namespace flyby {
NetworkBroker::NetworkBroker() : tap(tap_open()) {
    if (!tap) throw std::runtime_error("Cannot initialize network broker");
    const uint8_t mac[6] = {2,0,0,0,0,1};
    tap_set_mac(tap, mac);
    tap_net_dev_t nic{};
    nic.net_dev = tap;
    nic.feed_rx = [](void *p, const void *data, size_t size) {
        return static_cast<tap_dev_t*>(p)->queue(data, size);
    };
    tap_attach(tap, &nic);
}
NetworkBroker::~NetworkBroker() { tap_close(tap); }
std::vector<uint8_t> NetworkBroker::read() { return flybyTapRead(tap); }
void NetworkBroker::input(const uint8_t *data, size_t size) { flybyTapInput(tap, data, size); }
}
