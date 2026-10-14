// Minimal JNI bridge around libwebp's animation encoder / decoder.
// Pixels cross the boundary as ARGB int arrays (non-premultiplied, as Bitmap.getPixels returns).
#include <jni.h>
#include <vector>
#include <cstdint>
#include <cstring>
#include "webp/encode.h"
#include "webp/mux.h"
#include "webp/demux.h"

static void argbToRgba(const jint *src, uint8_t *dst, int count) {
    for (int i = 0; i < count; i++) {
        uint32_t p = (uint32_t) src[i];
        dst[4 * i + 0] = (p >> 16) & 0xff;
        dst[4 * i + 1] = (p >> 8) & 0xff;
        dst[4 * i + 2] = p & 0xff;
        dst[4 * i + 3] = (p >> 24) & 0xff;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_za_co_madbot_stickermaker_WebpNative_encodeAnimated(
        JNIEnv *env, jclass, jobjectArray frames, jint w, jint h,
        jintArray delaysArr, jint quality, jint method) {
    const jsize n = env->GetArrayLength(frames);
    if (n == 0) return nullptr;

    WebPAnimEncoderOptions opts;
    if (!WebPAnimEncoderOptionsInit(&opts)) return nullptr;
    opts.anim_params.loop_count = 0;
    // Lossy only and no exhaustive size search: both make encoding many times slower.
    opts.allow_mixed = 0;
    opts.minimize_size = 0;
    WebPAnimEncoder *enc = WebPAnimEncoderNew(w, h, &opts);
    if (!enc) return nullptr;

    WebPConfig config;
    if (!WebPConfigInit(&config)) { WebPAnimEncoderDelete(enc); return nullptr; }
    config.lossless = 0;
    config.quality = (float) quality;
    config.method = method;
    config.alpha_quality = quality;
    config.alpha_compression = 1;
    config.exact = 0;
    config.thread_level = 1;
    if (!WebPValidateConfig(&config)) { WebPAnimEncoderDelete(enc); return nullptr; }

    jint *delays = env->GetIntArrayElements(delaysArr, nullptr);
    std::vector<uint8_t> rgba((size_t) w * h * 4);
    int timestamp = 0;
    bool ok = true;

    for (jsize i = 0; i < n && ok; i++) {
        jintArray px = (jintArray) env->GetObjectArrayElement(frames, i);
        jint *src = env->GetIntArrayElements(px, nullptr);
        argbToRgba(src, rgba.data(), w * h);
        env->ReleaseIntArrayElements(px, src, JNI_ABORT);
        env->DeleteLocalRef(px);

        WebPPicture pic;
        if (!WebPPictureInit(&pic)) { ok = false; break; }
        pic.width = w;
        pic.height = h;
        pic.use_argb = 1;
        if (!WebPPictureImportRGBA(&pic, rgba.data(), w * 4)) { WebPPictureFree(&pic); ok = false; break; }
        ok = WebPAnimEncoderAdd(enc, &pic, timestamp, &config) != 0;
        WebPPictureFree(&pic);
        timestamp += delays[i];
    }
    env->ReleaseIntArrayElements(delaysArr, delays, JNI_ABORT);

    jbyteArray result = nullptr;
    if (ok && WebPAnimEncoderAdd(enc, nullptr, timestamp, nullptr)) {
        WebPData data;
        WebPDataInit(&data);
        if (WebPAnimEncoderAssemble(enc, &data)) {
            result = env->NewByteArray((jsize) data.size);
            env->SetByteArrayRegion(result, 0, (jsize) data.size, (const jbyte *) data.bytes);
        }
        WebPDataClear(&data);
    }
    WebPAnimEncoderDelete(enc);
    return result;
}

// Returns Array<IntArray>: [0] = {w, h, delay0..delayN-1}, [1..N] = ARGB frames.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_za_co_madbot_stickermaker_WebpNative_decodeAnimated(
        JNIEnv *env, jclass, jbyteArray dataArr, jint maxFrames) {
    jsize len = env->GetArrayLength(dataArr);
    jbyte *bytes = env->GetByteArrayElements(dataArr, nullptr);
    WebPData wd = {(const uint8_t *) bytes, (size_t) len};

    WebPAnimDecoderOptions opts;
    WebPAnimDecoderOptionsInit(&opts);
    opts.color_mode = MODE_RGBA;
    opts.use_threads = 1;
    WebPAnimDecoder *dec = WebPAnimDecoderNew(&wd, &opts);
    if (!dec) { env->ReleaseByteArrayElements(dataArr, bytes, JNI_ABORT); return nullptr; }

    WebPAnimInfo info;
    WebPAnimDecoderGetInfo(dec, &info);
    int total = (int) info.frame_count;
    if (maxFrames > 0 && total > maxFrames) total = maxFrames;
    const int w = (int) info.canvas_width, h = (int) info.canvas_height;

    jclass intArrCls = env->FindClass("[I");
    jobjectArray out = env->NewObjectArray(total + 1, intArrCls, nullptr);
    jintArray head = env->NewIntArray(2 + total);
    std::vector<jint> headVals(2 + total);
    headVals[0] = w;
    headVals[1] = h;

    int prevTs = 0, idx = 0;
    std::vector<jint> argb((size_t) w * h);
    while (idx < total && WebPAnimDecoderHasMoreFrames(dec)) {
        uint8_t *buf;
        int ts;
        if (!WebPAnimDecoderGetNext(dec, &buf, &ts)) break;
        for (int i = 0; i < w * h; i++) {
            uint8_t r = buf[4 * i], g = buf[4 * i + 1], b = buf[4 * i + 2], a = buf[4 * i + 3];
            argb[i] = (jint) (((uint32_t) a << 24) | ((uint32_t) r << 16) | ((uint32_t) g << 8) | b);
        }
        jintArray fr = env->NewIntArray(w * h);
        env->SetIntArrayRegion(fr, 0, w * h, argb.data());
        env->SetObjectArrayElement(out, idx + 1, fr);
        env->DeleteLocalRef(fr);
        headVals[2 + idx] = ts - prevTs;
        prevTs = ts;
        idx++;
    }
    env->SetIntArrayRegion(head, 0, 2 + total, headVals.data());
    env->SetObjectArrayElement(out, 0, head);
    WebPAnimDecoderDelete(dec);
    env->ReleaseByteArrayElements(dataArr, bytes, JNI_ABORT);
    return out;
}
