/* Native half of ArmTest: built only for arm64-v8a. Returns the architecture it was
 * compiled for and computes a checksum — to see that the code actually ran. */
#include <jni.h>
#include <stdint.h>
#include <stdio.h>

JNIEXPORT jstring JNICALL
Java_org_wsalineage_armtest_MainActivity_nativeProbe(JNIEnv *env, jclass cls, jint n) {
    uint64_t h = 1469598103934665603ULL;
    for (jint i = 0; i < n; i++) h = (h ^ (uint64_t)i) * 1099511628211ULL;
    char buf[96];
#if defined(__aarch64__)
    const char *arch = "aarch64";
#else
    const char *arch = "not-arm";
#endif
    snprintf(buf, sizeof(buf), "%s fnv=%016llx", arch, (unsigned long long)h);
    return (*env)->NewStringUTF(env, buf);
}
