// SPDX-License-Identifier: GPL-3.0-only
// Exercise the production UTF-8 boundary with a real host JVM and checked JNI calls.
#include "java_injection.hpp"
#include "observation.hpp"
#include "transport.hpp"
#include <cassert>
#include <cstdarg>
#include <cstdio>
#include <string>
#include <sys/system_properties.h>

static std::string observation;
static std::string propertyValue="readback";
int __android_log_print(int, const char *tag, const char *format, ...) {
    if(std::string_view(tag)!="PIF/Observation") return 0;
    char buffer[4096];va_list args;va_start(args,format);vsnprintf(buffer,sizeof(buffer),format,args);va_end(args);
    observation=buffer;return 0;
}
const prop_info *__system_property_find(const char *) {return reinterpret_cast<const prop_info *>(1);}
void __system_property_read_callback(const prop_info *,void (*callback)(void *,const char *,const char *,uint32_t),void *cookie) {
    callback(cookie,"property",propertyValue.c_str(),1);
}

static void check(JNIEnv *env, std::string_view input, std::u16string_view expected) {
    auto value = pif::javaString(env, input);
    assert(value && !env->ExceptionCheck());
    assert(env->GetStringLength(value) == static_cast<jsize>(expected.size()));
    const auto *characters = env->GetStringChars(value, nullptr);
    assert(characters && !env->ExceptionCheck());
    for (size_t i = 0; i < expected.size(); ++i) assert(characters[i] == expected[i]);
    env->ReleaseStringChars(value, characters);
    env->DeleteLocalRef(value);
}

int main(int argc,char **argv) {
    assert(argc==2);
    JavaVM *vm = nullptr;
    JNIEnv *env = nullptr;
    std::string classpath=std::string("-Djava.class.path=")+argv[1];
    JavaVMOption options[]={{const_cast<char *>("-Xcheck:jni"),nullptr},{classpath.data(),nullptr}};
    JavaVMInitArgs args{};
    args.version = JNI_VERSION_1_8;
    args.nOptions = 2;
    args.options = options;
    assert(JNI_CreateJavaVM(&vm, reinterpret_cast<void **>(&env), &args) == JNI_OK);
    check(env, "", u"");
    check(env, "google/device:16/BUILD:user/release-keys", u"google/device:16/BUILD:user/release-keys");
    check(env, "\xe4\xb8\xad\xe6\x96\x87", u"\u4e2d\u6587");
    check(env, "A\xf0\x9f\x98\x80Z", u"A\U0001f600Z");
    check(env, std::string("a\0b", 3), std::u16string(u"a\0b", 3));
    for (int i = 0; i < 1000; ++i) check(env, "\xf0\x9f\x98\x80", u"\U0001f600");
    pif::observe(env,"abc","com.android.vending","boot","start",true);
    assert(!env->ExceptionCheck());
    auto record=nlohmann::json::parse(observation);
    assert(record["revision"]=="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    assert(record["build"]["MODEL"]=="Pixel \xf0\x9f\x98\x80");
    assert(record["build"]["SDK_INT"]=="36");
    assert(record["properties"]["ro.product.model"]=="readback");
    propertyValue="\xff";
    for(int i=0;i<100;++i) {
        pif::observe(env,"abc","com.android.vending","boot","start",false);
        assert(!env->ExceptionCheck());
        assert(nlohmann::json::parse(observation)["properties"].empty());
    }
    propertyValue=std::string(257,'a');
    pif::observe(env,"abc","com.android.vending","boot","start",false);
    assert(nlohmann::json::parse(observation)["properties"].empty());
    assert(vm->DestroyJavaVM() == JNI_OK);
}
