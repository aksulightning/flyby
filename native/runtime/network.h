#pragma once
#include <cstdint>
#include <memory>
#include <vector>
extern "C" {
#include <devices/tap_api.h>
}
tap_dev_t *flybyProxyTap();
std::vector<uint8_t> flybyTapRead(tap_dev_t *);
void flybyTapInput(tap_dev_t *, const uint8_t *, size_t);
namespace flyby {
class NetworkBroker {
public:
    NetworkBroker();
    ~NetworkBroker();
    std::vector<uint8_t> read();
    void input(const uint8_t *, size_t);
private:
    tap_dev_t *tap;
};
}
