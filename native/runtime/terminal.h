#pragma once
#include <cstdint>
#include <deque>
#include <mutex>
#include <vector>
#include <vterm.h>
namespace flyby {
class Terminal {
  public:
    Terminal();
    ~Terminal();
    std::vector<uint8_t> feed(const uint8_t *bytes, size_t count);
    void resize(int rows, int cols);
    void reset();
    // Header: rows, cols, cursor row/col, scrollback count, visible cursor.
    // Each cell: six Unicode codepoints, foreground ARGB, background ARGB, flags, width.
    std::vector<int32_t> frame(int scrollback);
    std::vector<uint8_t> key(int key, int modifiers);
    std::vector<uint8_t> text(const uint8_t *bytes, size_t count, bool paste);

  private:
    std::mutex mutex;
    VTerm *vt = nullptr;
    VTermScreen *screen = nullptr;
    std::deque<std::vector<VTermScreenCell>> history;
    std::vector<uint8_t> responses;
    bool cursorVisible = true;
    static const VTermScreenCallbacks callbacks;
};
} // namespace flyby
