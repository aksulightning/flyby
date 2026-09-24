#include "terminal.h"
#include <algorithm>
#include <cstring>
#include <stdexcept>
namespace flyby {
const VTermScreenCallbacks Terminal::callbacks = {
    nullptr,
    nullptr,
    [](VTermPos, VTermPos, int visible, void *p) {
        static_cast<Terminal *>(p)->cursorVisible = visible;
        return 1;
    },
    [](VTermProp prop, VTermValue *value, void *p) {
        if (prop == VTERM_PROP_CURSORVISIBLE)
            static_cast<Terminal *>(p)->cursorVisible = value->boolean;
        return 1;
    },
    nullptr,
    nullptr,
    [](int cols, const VTermScreenCell *cells, void *p) {
        auto &t = *static_cast<Terminal *>(p);
        t.history.emplace_back(cells, cells + cols);
        if (t.history.size() > 2000)
            t.history.pop_front();
        return 1;
    },
    [](int cols, VTermScreenCell *cells, void *p) {
        auto &t = *static_cast<Terminal *>(p);
        if (t.history.empty())
            return 0;
        auto line = std::move(t.history.back());
        t.history.pop_back();
        std::memset(cells, 0, sizeof(*cells) * cols);
        std::copy_n(line.begin(), std::min(size_t(cols), line.size()), cells);
        return 1;
    },
    [](void *p) {
        static_cast<Terminal *>(p)->history.clear();
        return 1;
    }};
Terminal::Terminal() {
    vt = vterm_new(24, 80);
    if (!vt)
        throw std::bad_alloc();
    vterm_set_utf8(vt, 1);
    screen = vterm_obtain_screen(vt);
    vterm_screen_enable_altscreen(screen, 1);
    vterm_screen_set_callbacks(screen, &callbacks, this);
    vterm_output_set_callback(
        vt,
        [](const char *s, size_t n, void *p) {
            auto &out = static_cast<Terminal *>(p)->responses;
            out.insert(out.end(), s, s + n);
        },
        this);
    vterm_screen_reset(screen, 1);
}
Terminal::~Terminal() {
    vterm_free(vt);
}
std::vector<uint8_t> Terminal::feed(const uint8_t *bytes, size_t n) {
    std::lock_guard lock(mutex);
    vterm_input_write(vt, reinterpret_cast<const char *>(bytes), n);
    vterm_screen_flush_damage(screen);
    auto out = std::move(responses);
    responses.clear();
    return out;
}
void Terminal::resize(int rows, int cols) {
    if (rows < 2 || rows > 300 || cols < 2 || cols > 500)
        throw std::invalid_argument("Invalid terminal dimensions");
    std::lock_guard lock(mutex);
    vterm_set_size(vt, rows, cols);
}
void Terminal::reset() {
    std::lock_guard lock(mutex);
    history.clear();
    responses.clear();
    vterm_screen_reset(screen, 1);
}
std::vector<uint8_t> Terminal::key(int key, int modifiers) {
    std::lock_guard lock(mutex);
    if (key < 0 || key >= VTERM_KEY_MAX)
        throw std::invalid_argument("Invalid terminal key");
    vterm_keyboard_key(vt, static_cast<VTermKey>(key), static_cast<VTermModifier>(modifiers & 7));
    auto out = std::move(responses);
    responses.clear();
    return out;
}
std::vector<uint8_t> Terminal::text(const uint8_t *bytes, size_t n, bool paste) {
    std::lock_guard lock(mutex);
    if (paste)
        vterm_keyboard_start_paste(vt);
    responses.insert(responses.end(), bytes, bytes + n);
    if (paste)
        vterm_keyboard_end_paste(vt);
    auto out = std::move(responses);
    responses.clear();
    return out;
}
std::vector<int32_t> Terminal::frame(int offset) {
    std::lock_guard lock(mutex);
    int rows, cols;
    vterm_get_size(vt, &rows, &cols);
    VTermPos cursor;
    vterm_state_get_cursorpos(vterm_obtain_state(vt), &cursor);
    offset = std::clamp(offset, 0, int(history.size()));
    std::vector<int32_t> out{
        rows, cols, cursor.row, cursor.col, int32_t(history.size()), cursorVisible && offset == 0};
    out.reserve(6 + rows * cols * 10);
    for (int r = 0; r < rows; ++r)
        for (int c = 0; c < cols; ++c) {
            VTermScreenCell cell{};
            int source = r - offset;
            if (source < 0) {
                auto &line = history[history.size() + source];
                if (c < int(line.size()))
                    cell = line[c];
            } else
                vterm_screen_get_cell(screen, {source, c}, &cell);
            for (auto cp : cell.chars)
                out.push_back(cp);
            vterm_screen_convert_color_to_rgb(screen, &cell.fg);
            vterm_screen_convert_color_to_rgb(screen, &cell.bg);
            auto color = [](const VTermColor &c) {
                return int32_t(0xff000000u | uint32_t(c.rgb.red) << 16 | uint32_t(c.rgb.green) << 8 |
                               c.rgb.blue);
            };
            out.push_back(color(cell.fg));
            out.push_back(color(cell.bg));
            out.push_back((cell.attrs.bold ? 1 : 0) | (cell.attrs.underline ? 2 : 0) |
                          (cell.attrs.reverse ? 4 : 0) | (cell.attrs.italic ? 8 : 0));
            out.push_back(cell.width);
        }
    return out;
}
} // namespace flyby
