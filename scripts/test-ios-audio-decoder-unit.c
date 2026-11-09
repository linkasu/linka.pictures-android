/* Test-only same translation unit: actual emit/cleanup, no copied production algorithm. */
#include <assert.h>
#include <libavutil/mem.h>
static int fail_calloc;
static void *controlled_calloc(size_t count, size_t size) {
    if (fail_calloc) { fail_calloc = 0; return NULL; }
    return av_calloc(count, size);
}
#define av_calloc controlled_calloc
#include "../shared/src/nativeInterop/c/linka_audio_decoder.c"
#undef av_calloc

static int sample(float value) {
    LinkaAudioResult info = {0};
    float floating[2] = {value, value};
    uint8_t pcm[4] = {0};
    Decode state = {.info = &info, .deadline = av_gettime_relative() + 30LL * AV_TIME_BASE,
                    .floating = floating, .pcm16 = pcm};
    assert(emit(&state, 1) == 0 && info.frames == 1);
    return (int16_t)((unsigned)pcm[0] | (unsigned)pcm[1] << 8);
}

int main(int argc, char **argv) {
    assert(argc == 2);
    assert(sample(32767.75f / 32768) == 32767);
    assert(sample(nextafterf(1, 0)) == 32767);
    assert(sample(1) == 32767 && sample(-1) == -32768);
    assert(sample(2) == 32767 && sample(-2) == -32768);
    assert(sample(0) == 0 && sample(-0.0f) == 0);
    for (int n = 0; n < 3; ++n) {
        float bad[] = {NAN, INFINITY, -INFINITY};
        float floating[2] = {bad[n], bad[n]};
        uint8_t pcm[4] = {0};
        LinkaAudioResult info = {0};
        Decode state = {.info = &info, .deadline = av_gettime_relative() + 30LL * AV_TIME_BASE,
                        .floating = floating, .pcm16 = pcm};
        assert(emit(&state, 1) == AVERROR_INVALIDDATA && info.frames == 0);
    }
    LinkaAudioResult info;
    fail_calloc = 1;
    assert(linka_audio_decode(argv[1], -1, NULL, NULL, &info) == LINKA_AUDIO_LIMIT);
    assert(fail_calloc == 0 && info.frames == 0);
    assert(linka_audio_decode(argv[1], -1, NULL, NULL, &info) == LINKA_AUDIO_OK);
    puts("same-TU saturation/nonfinite/calloc-failure/reuse PASS");
    return 0;
}
