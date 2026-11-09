/* Test-only same translation unit: actual emit/cleanup, no copied production algorithm. */
#include <assert.h>
#include <stdlib.h>
#include <libavutil/mem.h>
static int fail_calloc;
static int policy_calls;
static void controlled_max_alloc(size_t size) {
    ++policy_calls;
    av_max_alloc(size);
}
static void *controlled_calloc(size_t count, size_t size) {
    if (fail_calloc) { fail_calloc = 0; return NULL; }
    return av_calloc(count, size);
}
#define av_calloc controlled_calloc
#define av_max_alloc controlled_max_alloc
#include "../shared/src/nativeInterop/c/linka_audio_decoder.c"
#undef av_calloc
#undef av_max_alloc

static int cancelled_after_signature(void *opaque) {
    int *calls = opaque;
    return ++*calls >= 4;
}

static int temporary(char *path, size_t capacity) {
    const char *directory = getenv("TMPDIR");
    int length = snprintf(path, capacity, "%s/linka-audio-unit-XXXXXX", directory ? directory : "/tmp");
    assert(length > 0 && (size_t)length < capacity);
    int fd = mkstemp(path);
    assert(fd >= 0);
    return fd;
}

static void truncated(const char *source) {
    int input = open(source, O_RDONLY | O_NOFOLLOW);
    struct stat metadata;
    assert(input >= 0 && !fstat(input, &metadata) && metadata.st_size > 12);
    char path[PATH_MAX];
    int output = temporary(path, sizeof(path));
    uint8_t buffer[4096];
    for (off_t left = metadata.st_size - 1; left > 0;) {
        size_t count = left < (off_t)sizeof(buffer) ? (size_t)left : sizeof(buffer);
        assert(read(input, buffer, count) == (ssize_t)count);
        assert(write(output, buffer, count) == (ssize_t)count);
        left -= (off_t)count;
    }
    assert(!close(output) && !close(input));
    LinkaAudioResult info;
    assert(linka_audio_decode(path, -1, NULL, NULL, &info) == LINKA_AUDIO_INVALID);
    assert(info.native_playback == 0 && policy_calls == 0);
    assert(!unlink(path));
}

static void native_cases(const char *wav, const char *mp3) {
    const char *paths[] = {wav, mp3};
    const int formats[] = {LINKA_AUDIO_WAV, LINKA_AUDIO_MP3};
    LinkaAudioResult info;
    fail_calloc = 1;
    for (int n = 0; n < 2; ++n) {
        assert(linka_audio_decode(paths[n], -1, NULL, NULL, &info) == LINKA_AUDIO_OK);
        assert(info.native_playback == 1 && info.format == formats[n]);
        assert(info.frames > 0 && info.duration_millis > 0 && info.source_rate > 0 && info.source_channels > 0);
        assert(info.source_bytes > 0 && info.read_bytes > 0 && info.read_bytes <= SOURCE_CAP);
        assert(policy_calls == 0 && fail_calloc == 1);
        truncated(paths[n]);
    }
    fail_calloc = 0;
    int calls = 0;
    assert(linka_audio_decode(wav, -1, cancelled_after_signature, &calls, &info) == LINKA_AUDIO_CANCELLED);
    assert(calls >= 4 && info.native_playback == 0 && policy_calls == 0);

    int fd = open(wav, O_RDONLY | O_NOFOLLOW);
    struct stat metadata;
    assert(fd >= 0 && !fstat(fd, &metadata));
    info = (LinkaAudioResult){.source_bytes = metadata.st_size, .read_bytes = SOURCE_CAP - 1};
    NativeInspect state = {.fd = fd, .info = &info};
    assert(!clock_gettime(CLOCK_MONOTONIC, &state.started));
    uint8_t buffer[12];
    UInt32 actual = 123;
    assert(native_read(&state, 0, 2, buffer, &actual) != noErr);
    assert(actual == 0 && state.status == LINKA_AUDIO_LIMIT && info.read_bytes == SOURCE_CAP - 1);
    assert(fcntl(fd, F_GETFD) >= 0);
    state.status = 0;
    info.read_bytes = 0;
    assert(native_read(&state, metadata.st_size - 1, 2, buffer, &actual) == noErr && actual == 1);
    assert(info.read_bytes == 1);
    assert(native_read(&state, metadata.st_size, 2, buffer, &actual) == noErr && actual == 0);
    state.started.tv_sec -= 31;
    assert(native_read(&state, 0, 1, buffer, &actual) != noErr && state.status == LINKA_AUDIO_LIMIT);
    assert(!close(fd) && policy_calls == 0);

    /* Deliberately unsupported PCM8 must not gain native playback through fallback. */
    uint8_t pcm8[46] = {0};
    memcpy(pcm8, "RIFF", 4); le32(pcm8 + 4, sizeof(pcm8) - 8);
    memcpy(pcm8 + 8, "WAVEfmt ", 8); le32(pcm8 + 16, 16); le16(pcm8 + 20, 1);
    le16(pcm8 + 22, 1); le32(pcm8 + 24, 8000); le32(pcm8 + 28, 8000);
    le16(pcm8 + 32, 1); le16(pcm8 + 34, 8); memcpy(pcm8 + 36, "data", 4); le32(pcm8 + 40, 2);
    char path[PATH_MAX];
    fd = temporary(path, sizeof(path));
    assert(write(fd, pcm8, sizeof(pcm8)) == (ssize_t)sizeof(pcm8));
    info = (LinkaAudioResult){.source_bytes = sizeof(pcm8)};
    state = (NativeInspect){.fd = fd, .info = &info};
    assert(!clock_gettime(CLOCK_MONOTONIC, &state.started));
    assert(native_inspect(&state, LINKA_AUDIO_WAV, pcm8) == LINKA_AUDIO_UNSUPPORTED);
    assert(info.native_playback == 0 && policy_calls == 0);
    int64_t native_reads = info.read_bytes;
    assert(!close(fd));
    assert(linka_audio_decode(path, -1, NULL, NULL, &info) == LINKA_AUDIO_UNSUPPORTED);
    assert(info.native_playback == 0 && info.frames == 0 && info.duration_millis == 0);
    assert(policy_calls == 1 && info.read_bytes > native_reads && info.read_bytes <= SOURCE_CAP);
    assert(!unlink(path));
}

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
    /* argv: existing Vorbis fixture, native PCM16 WAV fixture, native MP3 fixture. */
    assert(argc == 4);
    native_cases(argv[2], argv[3]);
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
    assert(fail_calloc == 0 && info.frames == 0 && info.native_playback == 0);
    assert(linka_audio_decode(argv[1], -1, NULL, NULL, &info) == LINKA_AUDIO_OK);
    assert(info.native_playback == 0 && policy_calls == 1);
    puts("same-TU native/policy/truncation/cancel/bounds/unsupported/saturation/nonfinite/calloc-failure/reuse PASS");
    return 0;
}
