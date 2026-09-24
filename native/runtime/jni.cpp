#include "terminal.h"
#include "vm.h"
#include <android/log.h>
#include <atomic>
#include <jni.h>
#include <mutex>
#include <stdexcept>
#include <unordered_map>
namespace {
std::mutex registryMutex;
std::atomic<jlong> sequence{1};
std::unordered_map<jlong, std::shared_ptr<flyby::Vm>> vms;
std::unordered_map<jlong, std::shared_ptr<flyby::Terminal>> terms;
template <class T> std::shared_ptr<T> get(std::unordered_map<jlong, std::shared_ptr<T>> &map, jlong id) {
    std::lock_guard lock(registryMutex);
    auto i = map.find(id);
    if (i == map.end())
        throw std::invalid_argument("Native handle is closed");
    return i->second;
}
template <class T> jlong put(std::unordered_map<jlong, std::shared_ptr<T>> &map, std::shared_ptr<T> p) {
    std::lock_guard lock(registryMutex);
    auto id = sequence++;
    map.emplace(id, std::move(p));
    return id;
}
void error(JNIEnv *env, const char *text) {
    __android_log_print(ANDROID_LOG_ERROR, "FlybyVM", "NATIVE_ERROR %s", text);
    if (!env->ExceptionCheck())
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), text);
}
std::vector<uint8_t> bytes(JNIEnv *env, jbyteArray array) {
    if (!array)
        throw std::invalid_argument("Null byte array");
    auto n = env->GetArrayLength(array);
    if (n > 1024 * 1024)
        throw std::invalid_argument("Native input too large");
    std::vector<uint8_t> b(n);
    env->GetByteArrayRegion(array, 0, n, reinterpret_cast<jbyte *>(b.data()));
    return b;
}
jbyteArray array(JNIEnv *env, const std::vector<uint8_t> &b) {
    auto a = env->NewByteArray(b.size());
    if (a)
        env->SetByteArrayRegion(a, 0, b.size(), reinterpret_cast<const jbyte *>(b.data()));
    return a;
}
std::string string(JNIEnv *env, jstring s) {
    if (!s)
        throw std::invalid_argument("Missing guest path");
    const char *p = env->GetStringUTFChars(s, nullptr);
    if (!p)
        throw std::bad_alloc();
    std::string value;
    try {
        value = p;
    } catch (...) {
        env->ReleaseStringUTFChars(s, p);
        throw;
    }
    env->ReleaseStringUTFChars(s, p);
    return value;
}
} // namespace
#define JNI(name) Java_io_github_aksulightning_flyby_nativebridge_NativeBridge_##name
#define CATCH_RET(value)                                                                                     \
    catch (const std::exception &e) {                                                                        \
        error(env, e.what());                                                                                \
        return value;                                                                                        \
    }                                                                                                        \
    catch (...) {                                                                                            \
        error(env, "Unknown native exception");                                                              \
        return value;                                                                                        \
    }
#define CATCH_VOID                                                                                           \
    catch (const std::exception &e) {                                                                        \
        error(env, e.what());                                                                                \
    }                                                                                                        \
    catch (...) {                                                                                            \
        error(env, "Unknown native exception");                                                              \
    }
extern "C" {
JNIEXPORT jlong JNICALL JNI(createVm)(JNIEnv *env, jobject, jstring path, jint ram, jint cpus) {
    try {
        return put(vms, std::make_shared<flyby::Vm>(string(env, path), ram, cpus));
    }
    CATCH_RET(0)
}
JNIEXPORT void JNICALL JNI(startVm)(JNIEnv *env, jobject, jlong id) {
    try {
        get(vms, id)->start();
    }
    CATCH_VOID
}
JNIEXPORT jboolean JNICALL JNI(runningVm)(JNIEnv *env, jobject, jlong id) {
    try {
        return get(vms, id)->running();
    }
    CATCH_RET(false)
}
JNIEXPORT void JNICALL JNI(requestStopVm)(JNIEnv *env, jobject, jlong id) {
    try {
        get(vms, id)->requestStop();
    }
    CATCH_VOID
}
JNIEXPORT void JNICALL JNI(destroyVm)(JNIEnv *env, jobject, jlong id) {
    try {
        std::shared_ptr<flyby::Vm> vm;
        {
            std::lock_guard lock(registryMutex);
            auto i = vms.find(id);
            if (i == vms.end())
                return;
            vm = std::move(i->second);
            vms.erase(i);
        }
        vm->stop();
    }
    CATCH_VOID
}
JNIEXPORT void JNICALL JNI(inputVm)(JNIEnv *env, jobject, jlong id, jbyteArray data) {
    try {
        auto b = bytes(env, data);
        get(vms, id)->input(b.data(), b.size());
    }
    CATCH_VOID
}
JNIEXPORT jbyteArray JNICALL JNI(readVm)(JNIEnv *env, jobject, jlong id, jint timeout) {
    try {
        return array(env, get(vms, id)->output(timeout));
    }
    CATCH_RET(nullptr)
}
JNIEXPORT void JNICALL JNI(resizeVm)(JNIEnv *env, jobject, jlong id, jint rows, jint cols) {
    try {
        get(vms, id)->resize(rows, cols);
    }
    CATCH_VOID
}
JNIEXPORT jlong JNICALL JNI(createTerminal)(JNIEnv *env, jobject) {
    try {
        return put(terms, std::make_shared<flyby::Terminal>());
    }
    CATCH_RET(0)
}
JNIEXPORT void JNICALL JNI(destroyTerminal)(JNIEnv *env, jobject, jlong id) {
    try {
        std::lock_guard lock(registryMutex);
        terms.erase(id);
    }
    CATCH_VOID
}
JNIEXPORT void JNICALL JNI(resetTerminal)(JNIEnv *env, jobject, jlong id) {
    try {
        get(terms, id)->reset();
    }
    CATCH_VOID
}
JNIEXPORT void JNICALL JNI(resizeTerminal)(JNIEnv *env, jobject, jlong id, jint rows, jint cols) {
    try {
        get(terms, id)->resize(rows, cols);
    }
    CATCH_VOID
}
JNIEXPORT jbyteArray JNICALL JNI(feedTerminal)(JNIEnv *env, jobject, jlong id, jbyteArray data) {
    try {
        auto b = bytes(env, data);
        return array(env, get(terms, id)->feed(b.data(), b.size()));
    }
    CATCH_RET(nullptr)
}
JNIEXPORT jbyteArray JNICALL JNI(keyTerminal)(JNIEnv *env, jobject, jlong id, jint key, jint modifiers) {
    try {
        return array(env, get(terms, id)->key(key, modifiers));
    }
    CATCH_RET(nullptr)
}
JNIEXPORT jbyteArray JNICALL JNI(textTerminal)(JNIEnv *env, jobject, jlong id, jbyteArray text,
                                               jboolean paste) {
    try {
        auto b = bytes(env, text);
        return array(env, get(terms, id)->text(b.data(), b.size(), paste));
    }
    CATCH_RET(nullptr)
}
JNIEXPORT jintArray JNICALL JNI(frameTerminal)(JNIEnv *env, jobject, jlong id, jint offset) {
    try {
        auto f = get(terms, id)->frame(offset);
        auto a = env->NewIntArray(f.size());
        if (a)
            env->SetIntArrayRegion(a, 0, f.size(), f.data());
        return a;
    }
    CATCH_RET(nullptr)
}
}
