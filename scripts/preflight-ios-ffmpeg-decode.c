/* Experimental file-only decoder proof; not a product adapter. No audio session/playback. */
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <math.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avconfig.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/mem.h>
#include <libavutil/samplefmt.h>
#include <libavutil/time.h>
#include <libavutil/version.h>
#include <libswresample/swresample.h>

#if AV_HAVE_BIGENDIAN
#error This proof emits little-endian float PCM only.
#endif

#define INPUT_CAP (64LL * 1024 * 1024)
#define OUTPUT_RATE 16000
#define OUTPUT_CHANNELS 2
#define SECONDS_CAP 600
#define OUTPUT_CHUNK 65536

static volatile sig_atomic_t cancelled;
static void cancel_signal(int signal_number) { (void)signal_number; cancelled = 1; }

typedef struct State {
    FILE *input;
    FILE *output;
    int64_t input_size;
    int64_t bytes_read;
    int64_t deadline;
    int64_t frames;
    int64_t source_samples;
    int64_t cancel_after;
    int source_rate;
    int source_channels;
    enum AVSampleFormat source_format;
    AVChannelLayout source_layout;
    SwrContext *resampler;
    float *pcm;
    double squares;
    const char *reason;
} State;

static int stopped(void *opaque) {
    State *state = opaque;
    if (cancelled) { state->reason = "cancelled"; return 1; }
    if (av_gettime_relative() >= state->deadline) { state->reason = "deadline"; return 1; }
    return 0;
}

static int read_packet(void *opaque, uint8_t *buffer, int size) {
    State *state = opaque;
    if (stopped(state)) return AVERROR_EXIT;
    if (state->bytes_read >= INPUT_CAP) { state->reason = "read-byte-limit"; return AVERROR(EFBIG); }
    int64_t remaining = INPUT_CAP - state->bytes_read;
    if (size > remaining) size = (int)remaining;
    size_t read = fread(buffer, 1, (size_t)size, state->input);
    state->bytes_read += (int64_t)read;
    if (read) return (int)read;
    return ferror(state->input) ? AVERROR(EIO) : AVERROR_EOF;
}

static int64_t seek_packet(void *opaque, int64_t offset, int whence) {
    State *state = opaque;
    if (stopped(state)) return AVERROR_EXIT;
    whence &= ~AVSEEK_FORCE;
    if (whence == AVSEEK_SIZE) return state->input_size;
    int64_t base = whence == SEEK_SET ? 0 : whence == SEEK_END ? state->input_size :
                   whence == SEEK_CUR ? ftello(state->input) : -1;
    if (base < 0 || offset < -base || offset > state->input_size - base) return AVERROR(EINVAL);
    if (fseeko(state->input, (off_t)(base + offset), SEEK_SET)) return AVERROR(errno);
    return ftello(state->input);
}

static int emit(State *state, int count) {
    if (stopped(state)) return AVERROR_EXIT;
    if (count < 0 || count > OUTPUT_CHUNK || count > (int64_t)OUTPUT_RATE * SECONDS_CAP - state->frames) {
        state->reason = "decoded-duration-limit";
        return AVERROR(EFBIG);
    }
    double squares = 0;
    for (int index = 0; index < count * OUTPUT_CHANNELS; ++index) {
        if (!isfinite(state->pcm[index])) { state->reason = "nonfinite-pcm"; return AVERROR_INVALIDDATA; }
        squares += (double)state->pcm[index] * state->pcm[index];
    }
    if (fwrite(state->pcm, sizeof(float) * OUTPUT_CHANNELS, (size_t)count, state->output) != (size_t)count)
        return AVERROR(EIO);
    state->squares += squares;
    state->frames += count;
    if (state->cancel_after && state->frames >= state->cancel_after) {
        raise(SIGINT); /* Deterministic self-signal exercises the same cooperative cancellation path. */
        if (stopped(state)) return AVERROR_EXIT;
    }
    return 0;
}

static int convert(State *state, const AVFrame *frame) {
    if (stopped(state)) return AVERROR_EXIT;
    if (frame->sample_rate <= 0 || frame->nb_samples <= 0 || !av_channel_layout_check(&frame->ch_layout))
        return AVERROR_INVALIDDATA;
    int result;
    if (!state->resampler) {
        AVChannelLayout stereo = AV_CHANNEL_LAYOUT_STEREO;
        state->source_rate = frame->sample_rate;
        state->source_channels = frame->ch_layout.nb_channels;
        state->source_format = (enum AVSampleFormat)frame->format;
        if ((result = av_channel_layout_copy(&state->source_layout, &frame->ch_layout)) < 0) return result;
        if ((result = swr_alloc_set_opts2(&state->resampler, &stereo, AV_SAMPLE_FMT_FLT, OUTPUT_RATE,
                &state->source_layout, state->source_format, state->source_rate, 0, NULL)) < 0) return result;
        if ((result = swr_init(state->resampler)) < 0) return result;
    }
    if (frame->sample_rate != state->source_rate || frame->format != state->source_format ||
            av_channel_layout_compare(&frame->ch_layout, &state->source_layout)) {
        state->reason = "changing-audio-parameters-unsupported-in-preflight";
        return AVERROR_INVALIDDATA;
    }
    if (frame->nb_samples > (int64_t)state->source_rate * SECONDS_CAP - state->source_samples) {
        state->reason = "source-duration-limit";
        return AVERROR(EFBIG);
    }
    state->source_samples += frame->nb_samples;
    int bytes = av_get_bytes_per_sample(state->source_format);
    if (bytes <= 0) return AVERROR_INVALIDDATA;
    int planar = av_sample_fmt_is_planar(state->source_format);
    int planes = planar ? state->source_channels : 1;
    const uint8_t **input = av_calloc((size_t)planes, sizeof(*input));
    if (!input) return AVERROR(ENOMEM);
    result = 0;
    for (int offset = 0; offset < frame->nb_samples;) {
        if (stopped(state)) { result = AVERROR_EXIT; break; }
        int chunk = state->source_rate / 4;
        if (chunk < 1) chunk = 1;
        if (chunk > frame->nb_samples - offset) chunk = frame->nb_samples - offset;
        for (int plane = 0; plane < planes; ++plane)
            input[plane] = frame->extended_data[plane] + (size_t)offset * bytes * (planar ? 1 : state->source_channels);
        int bound = swr_get_out_samples(state->resampler, chunk);
        if (bound < 0 || bound > OUTPUT_CHUNK) { state->reason = "resampler-chunk-limit"; result = AVERROR(EFBIG); break; }
        uint8_t *output[] = {(uint8_t *)state->pcm};
        int count = swr_convert(state->resampler, output, OUTPUT_CHUNK, input, chunk);
        if (count < 0) { result = count; break; }
        if ((result = emit(state, count)) < 0) break;
        offset += chunk;
    }
    av_free(input);
    return result;
}

static int drain(State *state, AVCodecContext *decoder, AVFrame *frame) {
    int result;
    while ((result = avcodec_receive_frame(decoder, frame)) >= 0) {
        result = convert(state, frame);
        av_frame_unref(frame);
        if (result < 0) return result;
    }
    return result == AVERROR(EAGAIN) || result == AVERROR_EOF ? 0 : result;
}

int main(int argc, char **argv) {
    if (argc == 2 && !strcmp(argv[1], "--info")) {
        printf("version=%s\nlicense=%s\nconfiguration=%s\navformat_configuration=%s\nswresample_configuration=%s\n",
               av_version_info(), avcodec_license(), avcodec_configuration(), avformat_configuration(), swresample_configuration());
        return 0;
    }
    if (argc != 3 && argc != 5) return 64;
    State state = {.deadline = av_gettime_relative() + 30LL * AV_TIME_BASE};
    if (argc == 5) {
        char *end;
        if (strcmp(argv[3], "--cancel-after-frames")) return 64;
        state.cancel_after = strtoll(argv[4], &end, 10);
        if (*end || state.cancel_after <= 0 || state.cancel_after > (int64_t)OUTPUT_RATE * SECONDS_CAP) return 64;
    }
    signal(SIGINT, cancel_signal);
    signal(SIGTERM, cancel_signal);
    av_log_set_level(AV_LOG_ERROR);
    AVFormatContext *format = NULL;
    AVIOContext *io = NULL;
    AVCodecContext *decoder = NULL;
    AVFrame *frame = NULL;
    AVPacket *packet = NULL;
    AVDictionary *options = NULL;
    int result = AVERROR(EIO), created = 0, source_fd = -1, output_fd = -1;
    struct stat metadata;
    const char *codec_name = "unknown", *container = "unknown";
    source_fd = open(argv[1], O_RDONLY | O_NOFOLLOW);
    if (source_fd < 0 || fstat(source_fd, &metadata)) { result = AVERROR(errno); goto done; }
    state.input_size = metadata.st_size;
    if (!S_ISREG(metadata.st_mode) || metadata.st_size <= 0 || metadata.st_size > INPUT_CAP) {
        state.reason = "input-size-limit"; result = AVERROR(EFBIG); goto done;
    }
    state.input = fdopen(source_fd, "rb");
    if (!state.input) { result = AVERROR(errno); goto done; }
    source_fd = -1;
    uint8_t *buffer = av_malloc(32768);
    if (!buffer) { result = AVERROR(ENOMEM); goto done; }
    io = avio_alloc_context(buffer, 32768, 0, &state, read_packet, NULL, seek_packet);
    if (!io) { av_free(buffer); result = AVERROR(ENOMEM); goto done; }
    format = avformat_alloc_context();
    if (!format) { result = AVERROR(ENOMEM); goto done; }
    format->pb = io;
    format->flags |= AVFMT_FLAG_CUSTOM_IO;
    format->interrupt_callback = (AVIOInterruptCB){stopped, &state};
    av_dict_set(&options, "format_whitelist", "ogg,amr,mov,wav,mp3", 0);
    av_dict_set(&options, "protocol_whitelist", "file", 0);
    if ((result = avformat_open_input(&format, NULL, NULL, &options)) < 0) goto done;
    if ((result = avformat_find_stream_info(format, NULL)) < 0) goto done;
    if (format->duration != AV_NOPTS_VALUE && format->duration > SECONDS_CAP * AV_TIME_BASE) {
        state.reason = "metadata-duration-limit"; result = AVERROR(EFBIG); goto done;
    }
    const AVCodec *codec = NULL;
    int stream = av_find_best_stream(format, AVMEDIA_TYPE_AUDIO, -1, -1, &codec, 0);
    if (stream < 0 || !codec) { result = stream < 0 ? stream : AVERROR_DECODER_NOT_FOUND; goto done; }
    enum AVCodecID id = codec->id;
    if (id != AV_CODEC_ID_VORBIS && id != AV_CODEC_ID_AMR_NB && id != AV_CODEC_ID_PCM_S16LE && id != AV_CODEC_ID_MP3) {
        result = AVERROR_DECODER_NOT_FOUND; goto done;
    }
    codec_name = avcodec_get_name(id);
    container = format->iformat->name;
    decoder = avcodec_alloc_context3(codec);
    if (!decoder) { result = AVERROR(ENOMEM); goto done; }
    if ((result = avcodec_parameters_to_context(decoder, format->streams[stream]->codecpar)) < 0) goto done;
    decoder->thread_count = 1;
    if ((result = avcodec_open2(decoder, codec, NULL)) < 0) goto done;
    output_fd = open(argv[2], O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0600);
    if (output_fd < 0) { result = AVERROR(errno); goto done; }
    created = 1;
    state.output = fdopen(output_fd, "wb");
    if (!state.output) { result = AVERROR(errno); goto done; }
    output_fd = -1;
    state.pcm = av_malloc_array(OUTPUT_CHUNK * OUTPUT_CHANNELS, sizeof(float));
    frame = av_frame_alloc(); packet = av_packet_alloc();
    if (!state.pcm || !frame || !packet) { result = AVERROR(ENOMEM); goto done; }
    while ((result = av_read_frame(format, packet)) >= 0) {
        if (stopped(&state)) { result = AVERROR_EXIT; break; }
        if (packet->stream_index == stream) {
            result = avcodec_send_packet(decoder, packet);
            if (result >= 0) result = drain(&state, decoder, frame);
        }
        av_packet_unref(packet);
        if (result < 0) break;
    }
    if (result != AVERROR_EOF) goto done;
    if ((result = avcodec_send_packet(decoder, NULL)) < 0) goto done;
    if ((result = drain(&state, decoder, frame)) < 0) goto done;
    if (state.resampler) {
        for (;;) {
            uint8_t *output[] = {(uint8_t *)state.pcm};
            int count = swr_convert(state.resampler, output, OUTPUT_CHUNK, NULL, 0);
            if (count < 0) { result = count; goto done; }
            if (!count) break;
            if ((result = emit(&state, count)) < 0) goto done;
        }
    }
    result = state.frames ? 0 : AVERROR_INVALIDDATA;
done:
    av_dict_free(&options);
    av_packet_free(&packet); av_frame_free(&frame);
    avcodec_free_context(&decoder);
    swr_free(&state.resampler); av_channel_layout_uninit(&state.source_layout);
    avformat_close_input(&format);
    if (io) av_freep(&io->buffer);
    avio_context_free(&io);
    av_free(state.pcm);
    if (state.input) fclose(state.input);
    if (source_fd >= 0) close(source_fd);
    if (state.output && fclose(state.output)) result = AVERROR(EIO);
    if (output_fd >= 0) close(output_fd);
    if (result < 0 && created) unlink(argv[2]);
    if (result < 0) {
        char error[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(result, error, sizeof(error));
        fprintf(stderr, "decode error: %s (%s)\n", state.reason ? state.reason : "native", error);
    }
    printf("{\"status\":\"%s\",\"reason\":\"%s\",\"codec\":\"%s\",\"container\":\"%s\","
           "\"input_bytes\":%" PRId64 ",\"read_bytes\":%" PRId64 ",\"frames\":%" PRId64 ",\"seconds\":%.9f,"
           "\"source_rate\":%d,\"source_channels\":%d,\"rms\":%.12f}\n",
           cancelled ? "cancelled" : result < 0 ? "failed" : "ok", state.reason ? state.reason : "none",
           codec_name, container, state.input_size, state.bytes_read, state.frames, (double)state.frames / OUTPUT_RATE,
           state.source_rate, state.source_channels, state.frames ? sqrt(state.squares / (state.frames * OUTPUT_CHANNELS)) : 0);
    return cancelled ? 130 : result < 0 ? 2 : 0;
}
