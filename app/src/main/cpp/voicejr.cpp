// JNI bridge to sherpa-onnx for telling the two voices of a one-mix call apart
// (specs/003-voice-labels, contracts/voice-native.md).
//
// Ours, like whisperjr.cpp and llamajr.cpp, and over sherpa-onnx's C API rather than its own
// Kotlin/JNI layer: that one is built for the whole toolkit (ASR, TTS, keyword spotting), and the
// app needs three calls of it.
//
// One handle holds both models: the diariser (pyannote segmentation-3.0 + the embedding model +
// clustering) and a second embedding extractor over the same embedding model, for the owner's print
// and for each voice's mean. Two loads of a 40 MB model, which the A24 holds (R16: 349 MB peak for
// the diariser alone on a 252 s call).
//
// Stop: sherpa-onnx reports progress but ignores the callback's answer, so a run cannot be cut short
// here. VoiceStep hands it one chunk of at most 5 minutes at a time and checks for Stop between
// them: at R16's RTF of 0.30-0.40 on the A24, at most about two minutes after a tap.
//
// Thread-safety: one handle is used from one thread at a time; VoiceStep holds the heavy lock.

#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>
#include "sherpa-onnx/c-api/c-api.h"

#define TAG "voicejr"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

constexpr int32_t kSampleRate = 16000;

struct Voices {
    const SherpaOnnxOfflineSpeakerDiarization *diariser = nullptr;
    const SherpaOnnxSpeakerEmbeddingExtractor *extractor = nullptr;
};

inline Voices *of(jlong p) { return reinterpret_cast<Voices *>(p); }

void destroy(Voices *v) {
    if (!v) return;
    if (v->diariser) SherpaOnnxDestroyOfflineSpeakerDiarization(v->diariser);
    if (v->extractor) SherpaOnnxDestroySpeakerEmbeddingExtractor(v->extractor);
    delete v;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_jemcik_jemrec_transcribe_VoiceNative_open(
        JNIEnv *env, jobject, jstring segmentation, jstring embedding, jint threads) {
    const char *seg = env->GetStringUTFChars(segmentation, nullptr);
    const char *emb = env->GetStringUTFChars(embedding, nullptr);

    SherpaOnnxSpeakerEmbeddingExtractorConfig ex{};
    ex.model = emb;
    ex.num_threads = threads;
    ex.provider = "cpu";

    SherpaOnnxOfflineSpeakerDiarizationConfig dc{};
    dc.segmentation.pyannote.model = seg;
    dc.segmentation.num_threads = threads;
    dc.segmentation.provider = "cpu";
    dc.embedding = ex;
    // Told the count, not a distance threshold: a call has two sides, and a threshold would split
    // one person on a bad line into several (contracts/voice-native.md). A third voice lands in
    // whichever of the two it is nearer, as the spec accepts.
    dc.clustering.num_clusters = 2;
    // sherpa-onnx's own example values: a turn shorter than 0.3 s is dropped, and a gap under
    // 0.5 s within one voice's speech is closed.
    dc.min_duration_on = 0.3f;
    dc.min_duration_off = 0.5f;

    auto *v = new Voices();
    v->diariser = SherpaOnnxCreateOfflineSpeakerDiarization(&dc);
    v->extractor = SherpaOnnxCreateSpeakerEmbeddingExtractor(&ex);

    env->ReleaseStringUTFChars(segmentation, seg);
    env->ReleaseStringUTFChars(embedding, emb);

    if (!v->diariser || !v->extractor) {
        LOGW("could not load the voice models (diariser %s, extractor %s)",
             v->diariser ? "ok" : "failed", v->extractor ? "ok" : "failed");
        destroy(v);
        return 0;
    }
    if (SherpaOnnxOfflineSpeakerDiarizationGetSampleRate(v->diariser) != kSampleRate) {
        LOGW("the segmentation model wants %d Hz, not 16 kHz",
             SherpaOnnxOfflineSpeakerDiarizationGetSampleRate(v->diariser));
        destroy(v);
        return 0;
    }
    return reinterpret_cast<jlong>(v);
}

// Turns as flat (startMs, endMs, voice) triples, sorted by start. Empty on failure or no speech.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_jemcik_jemrec_transcribe_VoiceNative_turns(
        JNIEnv *env, jobject, jlong ptr, jfloatArray pcm) {
    Voices *v = of(ptr);
    const jsize n = env->GetArrayLength(pcm);
    if (!v || n == 0) return env->NewIntArray(0);

    std::vector<float> samples(static_cast<size_t>(n));
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());

    const SherpaOnnxOfflineSpeakerDiarizationResult *r =
            SherpaOnnxOfflineSpeakerDiarizationProcess(v->diariser, samples.data(), n);
    if (!r) {
        LOGW("diarisation returned nothing for %d samples", n);
        return env->NewIntArray(0);
    }
    const int32_t count = SherpaOnnxOfflineSpeakerDiarizationResultGetNumSegments(r);
    std::vector<jint> out;
    out.reserve(static_cast<size_t>(count) * 3);
    if (count > 0) {
        const SherpaOnnxOfflineSpeakerDiarizationSegment *s =
                SherpaOnnxOfflineSpeakerDiarizationResultSortByStartTime(r);
        for (int32_t i = 0; i < count; ++i) {
            out.push_back(static_cast<jint>(std::lround(s[i].start * 1000.0f)));
            out.push_back(static_cast<jint>(std::lround(s[i].end * 1000.0f)));
            out.push_back(s[i].speaker);
        }
        SherpaOnnxOfflineSpeakerDiarizationDestroySegment(s);
    }
    SherpaOnnxOfflineSpeakerDiarizationDestroyResult(r);

    jintArray result = env->NewIntArray(static_cast<jsize>(out.size()));
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

// One embedding of the whole buffer, L2-normalised so a dot product is the cosine. Empty when the
// buffer is too short for the model or it fails.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_jemcik_jemrec_transcribe_VoiceNative_embed(
        JNIEnv *env, jobject, jlong ptr, jfloatArray pcm) {
    Voices *v = of(ptr);
    const jsize n = env->GetArrayLength(pcm);
    if (!v || n == 0) return env->NewFloatArray(0);

    std::vector<float> samples(static_cast<size_t>(n));
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());

    const SherpaOnnxOnlineStream *stream = SherpaOnnxSpeakerEmbeddingExtractorCreateStream(v->extractor);
    SherpaOnnxOnlineStreamAcceptWaveform(stream, kSampleRate, samples.data(), n);
    SherpaOnnxOnlineStreamInputFinished(stream);

    jfloatArray result;
    if (!SherpaOnnxSpeakerEmbeddingExtractorIsReady(v->extractor, stream)) {
        result = env->NewFloatArray(0);
    } else {
        const int32_t dim = SherpaOnnxSpeakerEmbeddingExtractorDim(v->extractor);
        const float *e = SherpaOnnxSpeakerEmbeddingExtractorComputeEmbedding(v->extractor, stream);
        std::vector<float> out(e, e + dim);
        SherpaOnnxSpeakerEmbeddingExtractorDestroyEmbedding(e);
        double norm = 0;
        for (float x : out) norm += static_cast<double>(x) * x;
        norm = std::sqrt(norm);
        if (norm > 0) for (float &x : out) x = static_cast<float>(x / norm);
        result = env->NewFloatArray(dim);
        env->SetFloatArrayRegion(result, 0, dim, out.data());
    }
    SherpaOnnxDestroyOnlineStream(stream);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jemcik_jemrec_transcribe_VoiceNative_close(JNIEnv *, jobject, jlong ptr) {
    destroy(of(ptr));
}
