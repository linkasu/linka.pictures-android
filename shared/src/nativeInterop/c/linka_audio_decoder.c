#include "linka_audio_decoder.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <math.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avconfig.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/log.h>
#include <libavutil/mem.h>
#include <libavutil/samplefmt.h>
#include <libavutil/time.h>
#include <libswresample/swresample.h>

#define SOURCE_CAP (64LL * 1024 * 1024)
#define SECONDS_CAP 600
#define RATE 16000
#define CHANNELS 2
#define CHUNK 65536
#define FRAME_SAMPLES_CAP 32768
#define ALLOCATION_CAP (16 * 1024 * 1024)

/* This private development FFmpeg instance has a single decoder owner. No adaptive
 * codec threads; this lock creates no threads. The per-allocation ceiling is NOT RSS. */
static pthread_mutex_t decoder_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_once_t policy_once = PTHREAD_ONCE_INIT;
static void quiet_log(void *p, int level, const char *format, va_list args) {
    (void)p; (void)level; (void)format; (void)args;
}
static void policy(void) {
    av_max_alloc(ALLOCATION_CAP);
    av_log_set_level(AV_LOG_QUIET);
    av_log_set_callback(quiet_log);
}

typedef struct Decode {
    FILE *input;
    FILE *output;
    LinkaAudioCancelled cancelled;
    void *context;
    LinkaAudioResult *info;
    int64_t deadline;
    int64_t input_samples;
    int stopped_status;
    enum AVSampleFormat sample_format;
    AVChannelLayout layout;
    SwrContext *swr;
    float *floating;
    uint8_t *pcm16;
} Decode;

static int stopped(void *opaque) {
    Decode *state = opaque;
    if (state->cancelled && state->cancelled(state->context)) state->stopped_status = LINKA_AUDIO_CANCELLED;
    else if (av_gettime_relative() >= state->deadline) state->stopped_status = LINKA_AUDIO_LIMIT;
    return state->stopped_status != 0;
}

static int deny_open(AVFormatContext *format, AVIOContext **io, const char *url, int flags, AVDictionary **options) {
    (void)io; (void)url; (void)flags; (void)options;
    Decode *state = format->opaque;
    if (state) state->info->supplemental_denied = 1;
    return AVERROR(EACCES);
}

static int read_packet(void *opaque, uint8_t *data, int count) {
    Decode *state = opaque;
    if (stopped(state)) return AVERROR_EXIT;
    if (state->info->read_bytes >= SOURCE_CAP) {
        if (ftello(state->input) == state->info->source_bytes) return AVERROR_EOF;
        state->stopped_status = LINKA_AUDIO_LIMIT;
        return AVERROR(EFBIG);
    }
    int64_t remaining = SOURCE_CAP - state->info->read_bytes;
    if (count > remaining) count = (int)remaining;
    size_t read = fread(data, 1, (size_t)count, state->input);
    state->info->read_bytes += (int64_t)read;
    if (read) return (int)read;
    return ferror(state->input) ? AVERROR(EIO) : AVERROR_EOF;
}

static int64_t seek_packet(void *opaque, int64_t offset, int whence) {
    Decode *state = opaque;
    if (stopped(state)) return AVERROR_EXIT;
    whence &= ~AVSEEK_FORCE;
    if (whence == AVSEEK_SIZE) return state->info->source_bytes;
    int64_t base = whence == SEEK_SET ? 0 : whence == SEEK_END ? state->info->source_bytes :
                   whence == SEEK_CUR ? ftello(state->input) : -1;
    if (base < 0 || offset < -base || offset > state->info->source_bytes - base) return AVERROR(EINVAL);
    return fseeko(state->input, (off_t)(base + offset), SEEK_SET) ? AVERROR(errno) : ftello(state->input);
}

#define BOX(a,b,c,d) ((uint32_t)(a)<<24 | (uint32_t)(b)<<16 | (uint32_t)(c)<<8 | (uint32_t)(d))
typedef struct Box { uint64_t payload, end; uint32_t type; } Box;
typedef struct Boxes { int fd; unsigned count; Decode *decode; } Boxes;

static uint32_t be32(const uint8_t *value) {
    return (uint32_t)value[0]<<24 | (uint32_t)value[1]<<16 | (uint32_t)value[2]<<8 | value[3];
}

static int read_at(Boxes *state, uint64_t position, void *data, size_t count) {
    if (stopped(state->decode)) return AVERROR_EXIT;
    if (position > (uint64_t)state->decode->info->source_bytes ||
        count > (uint64_t)state->decode->info->source_bytes - position) return AVERROR_INVALIDDATA;
    if (count > (uint64_t)(SOURCE_CAP - state->decode->info->read_bytes)) return AVERROR(EFBIG);
    ssize_t result = pread(state->fd, data, count, (off_t)position);
    if (result > 0) state->decode->info->read_bytes += result;
    return result < 0 ? AVERROR(EIO) : (size_t)result != count ? AVERROR_INVALIDDATA : 0;
}

static int box_header(Boxes *state, uint64_t position, uint64_t end, Box *box) {
    if (++state->count > 4096) return AVERROR(EFBIG);
    if (position > end || end - position < 8) return AVERROR_INVALIDDATA;
    uint8_t header[16];
    int status = read_at(state, position, header, 8);
    if (status < 0) return status;
    uint64_t size = be32(header);
    unsigned length = 8;
    if (size == 1) {
        if (end - position < 16) return AVERROR_INVALIDDATA;
        if ((status = read_at(state, position + 8, header + 8, 8)) < 0) return status;
        size = (uint64_t)be32(header + 8)<<32 | be32(header + 12);
        length = 16;
    } else if (!size) size = end - position;
    if (size < length || size > end - position) return AVERROR_INVALIDDATA;
    box->type = be32(header + 4);
    box->payload = position + length;
    box->end = position + size; /* Both additions are bounded by parent <=64MiB. */
    return 0;
}

static int dref(Boxes *state, Box box) {
    uint8_t fields[8];
    if (box.end - box.payload < 8) return AVERROR_INVALIDDATA;
    int status = read_at(state, box.payload, fields, sizeof(fields));
    if (status < 0) return status;
    if (be32(fields) != 0 || be32(fields + 4) == 0) return AVERROR_INVALIDDATA;
    uint32_t entries = be32(fields + 4);
    if (entries > 4096 - state->count) return AVERROR(EFBIG);
    uint64_t position = box.payload + 8;
    for (uint32_t n = 0; n < entries; ++n) {
        Box entry;
        if ((status = box_header(state, position, box.end, &entry)) < 0) return status;
        if (entry.type != BOX('u','r','l',' ')) return AVERROR_DECODER_NOT_FOUND;
        if (entry.end - entry.payload < 4) return AVERROR_INVALIDDATA;
        if ((status = read_at(state, entry.payload, fields, 4)) < 0) return status;
        uint32_t flags = be32(fields);
        if (flags >> 24) return AVERROR_INVALIDDATA;
        if (flags != 1 || entry.end - entry.payload > 4) return AVERROR_DECODER_NOT_FOUND;
        position = entry.end;
    }
    return position == box.end ? 0 : AVERROR_INVALIDDATA;
}

/* Only atom boundaries under moov are traversed. Unknown payloads (including mdat)
 * are never searched. This is a dref rejection validator, not a movie parser. */
static int walk_boxes(Boxes *state, uint64_t start, uint64_t end, unsigned depth, uint32_t parent) {
    if (depth > 8) return AVERROR(EFBIG);
    for (uint64_t position = start; position < end;) {
        Box box;
        int status = box_header(state, position, end, &box);
        if (status < 0) return status;
        if (box.type == BOX('m','o','o','v') && box.end - position > 8 * 1024 * 1024) return AVERROR(EFBIG);
        if (parent == BOX('m','o','o','v') && box.type == BOX('r','m','r','a')) return AVERROR_DECODER_NOT_FOUND;
        if (!depth && box.type != BOX('m','o','o','v')) {
            if (box.type == BOX('d','r','e','f') || box.type == BOX('t','r','a','k') || box.type == BOX('m','d','i','a') ||
                box.type == BOX('m','i','n','f') || box.type == BOX('d','i','n','f')) return AVERROR_INVALIDDATA;
            position = box.end; continue;
        }
        if (box.type == BOX('d','r','e','f')) {
            if ((status = dref(state, box)) < 0) return status;
        } else if (box.type == BOX('m','o','o','v') || box.type == BOX('t','r','a','k') ||
                   box.type == BOX('m','d','i','a') || box.type == BOX('m','i','n','f') || box.type == BOX('d','i','n','f')) {
            if ((status = walk_boxes(state, box.payload, box.end, depth + 1, box.type)) < 0) return status;
        }
        position = box.end;
    }
    return 0;
}

static int input_format(Decode *state, int fd, const AVInputFormat **format) {
    Boxes boxes = {.fd = fd, .decode = state};
    uint8_t signature[12] = {0};
    size_t count = state->info->source_bytes < 12 ? (size_t)state->info->source_bytes : 12;
    int status = read_at(&boxes, 0, signature, count);
    if (status < 0) return status;
    const char *name = NULL;
    if (count >= 4 && !memcmp(signature, "OggS", 4)) name = "ogg";
    else if (count >= 5 && !memcmp(signature, "#!AMR", 5)) name = "amr";
    else if (count >= 12 && (!memcmp(signature, "RIFF", 4) || !memcmp(signature, "RF64", 4)) &&
             !memcmp(signature + 8, "WAVE", 4)) name = "wav";
    else if ((count >= 3 && !memcmp(signature, "ID3", 3)) ||
             (count >= 2 && signature[0] == 0xff && (signature[1] & 0xe0) == 0xe0)) name = "mp3";
    else {
        /* A valid <=64MiB BMFF header cannot have these audio prefixes. Force the
         * chosen demuxer, so a fake audio prefix never bypasses the MOV gate. */
        if ((status = walk_boxes(&boxes, 0, (uint64_t)state->info->source_bytes, 0, 0)) < 0) return status;
        name = "mov";
    }
    *format = av_find_input_format(name);
    return *format ? 0 : AVERROR_DEMUXER_NOT_FOUND;
}

static void le16(uint8_t *out, unsigned value) { out[0] = value; out[1] = value >> 8; }
static void le32(uint8_t *out, uint32_t value) { for (int n = 0; n < 4; ++n) out[n] = value >> (n * 8); }
static int wave_header(FILE *file, int64_t frames) {
    uint8_t header[44] = {0};
    uint32_t bytes = (uint32_t)(frames * CHANNELS * 2);
    memcpy(header, "RIFF", 4); le32(header + 4, 36 + bytes); memcpy(header + 8, "WAVEfmt ", 8);
    le32(header + 16, 16); le16(header + 20, 1); le16(header + 22, CHANNELS);
    le32(header + 24, RATE); le32(header + 28, RATE * CHANNELS * 2);
    le16(header + 32, CHANNELS * 2); le16(header + 34, 16); memcpy(header + 36, "data", 4); le32(header + 40, bytes);
    if (fseeko(file, 0, SEEK_SET) || fwrite(header, 1, sizeof(header), file) != sizeof(header)) return AVERROR(EIO);
    return 0;
}

static int emit(Decode *state, int count) {
    if (stopped(state)) return AVERROR_EXIT;
    if (count < 0 || count > CHUNK || count > (int64_t)RATE * SECONDS_CAP - state->info->frames) return AVERROR(EFBIG);
    for (int n = 0; n < count * CHANNELS; ++n) {
        float value = state->floating[n];
        if (!isfinite(value)) return AVERROR_INVALIDDATA;
        long rounded = lrintf(fmaxf(-1, fminf(1, value)) * 32768);
        int sample = rounded > 32767 ? 32767 : rounded < -32768 ? -32768 : (int)rounded;
        le16(state->pcm16 + n * 2, (unsigned)(uint16_t)sample);
    }
    if (state->output && fwrite(state->pcm16, CHANNELS * 2, (size_t)count, state->output) != (size_t)count) return AVERROR(EIO);
    state->info->frames += count;
    return 0;
}

static int stream_parameters(AVFormatContext *format, int *audio) {
    int count = 0;
    *audio = -1;
    for (unsigned n = 0; n < format->nb_streams; ++n) if (format->streams[n]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
        *audio = (int)n; count++;
    }
    if (count != 1) return AVERROR_DECODER_NOT_FOUND;
    AVCodecParameters *parameters = format->streams[*audio]->codecpar;
    enum AVCodecID id = parameters->codec_id;
    if (id != AV_CODEC_ID_VORBIS && id != AV_CODEC_ID_AMR_NB && id != AV_CODEC_ID_PCM_S16LE && id != AV_CODEC_ID_MP3)
        return AVERROR_DECODER_NOT_FOUND;
    if (parameters->sample_rate < 0 || parameters->sample_rate > 192000 || parameters->ch_layout.nb_channels < 0 ||
        parameters->ch_layout.nb_channels > 8) return AVERROR(EFBIG);
    return 0;
}

static int convert(Decode *state, AVFrame *frame) {
    if (stopped(state)) return AVERROR_EXIT;
    if (frame->sample_rate <= 0 || frame->sample_rate > 192000 || frame->nb_samples <= 0 ||
        frame->nb_samples > FRAME_SAMPLES_CAP || frame->ch_layout.nb_channels <= 0 || frame->ch_layout.nb_channels > 8)
        return AVERROR(EFBIG);
    if (!av_channel_layout_check(&frame->ch_layout) || !frame->extended_data) return AVERROR_INVALIDDATA;
    int status;
    if (!state->swr) {
        AVChannelLayout stereo = AV_CHANNEL_LAYOUT_STEREO;
        state->info->source_rate = frame->sample_rate;
        state->info->source_channels = frame->ch_layout.nb_channels;
        state->sample_format = frame->format;
        if ((status = av_channel_layout_copy(&state->layout, &frame->ch_layout)) < 0) return status;
        if ((status = swr_alloc_set_opts2(&state->swr, &stereo, AV_SAMPLE_FMT_FLT, RATE, &state->layout,
                                        state->sample_format, frame->sample_rate, 0, NULL)) < 0) return status;
        if ((status = swr_init(state->swr)) < 0) return status;
    }
    if (frame->sample_rate != state->info->source_rate || frame->format != state->sample_format ||
        av_channel_layout_compare(&frame->ch_layout, &state->layout)) return AVERROR_DECODER_NOT_FOUND;
    if (frame->nb_samples > (int64_t)state->info->source_rate * SECONDS_CAP - state->input_samples) return AVERROR(EFBIG);
    state->input_samples += frame->nb_samples;
    int bytes = av_get_bytes_per_sample(state->sample_format);
    if (bytes <= 0 || bytes > 8) return AVERROR_INVALIDDATA;
    int planar = av_sample_fmt_is_planar(state->sample_format);
    int planes = planar ? state->info->source_channels : 1;
    const uint8_t *input[8];
    for (int offset = 0; offset < frame->nb_samples;) {
        if (stopped(state)) return AVERROR_EXIT;
        int chunk = state->info->source_rate / 4;
        if (chunk < 1) chunk = 1;
        if (chunk > frame->nb_samples - offset) chunk = frame->nb_samples - offset;
        uint64_t stride = (uint64_t)bytes * (planar ? 1 : state->info->source_channels);
        uint64_t position = (uint64_t)offset * stride;
        uint64_t end = (uint64_t)(offset + chunk) * stride;
        if (end > SIZE_MAX || end > (uint64_t)FRAME_SAMPLES_CAP * 8 * 8 || position > end) return AVERROR(EFBIG);
        for (int n = 0; n < planes; ++n) {
            if (!frame->extended_data[n]) return AVERROR_INVALIDDATA;
            input[n] = frame->extended_data[n] + (size_t)position;
        }
        int bound = swr_get_out_samples(state->swr, chunk);
        if (bound < 0 || bound > CHUNK) return AVERROR(EFBIG);
        uint8_t *output[] = {(uint8_t *)state->floating};
        int count = swr_convert(state->swr, output, CHUNK, input, chunk);
        if (count < 0) return count;
        if ((status = emit(state, count)) < 0) return status;
        offset += chunk;
    }
    return 0;
}

static int drain(Decode *state, AVCodecContext *decoder, AVFrame *frame) {
    int status;
    while ((status = avcodec_receive_frame(decoder, frame)) >= 0) {
        status = convert(state, frame);
        av_frame_unref(frame);
        if (status < 0) return status;
    }
    return status == AVERROR_EOF || status == AVERROR(EAGAIN) ? 0 : status;
}

static int classify(int error, Decode *state) {
    if (state->stopped_status) return state->stopped_status;
    if (state->info->supplemental_denied) return LINKA_AUDIO_UNSUPPORTED;
    if (error >= 0) return LINKA_AUDIO_OK;
    if (error == AVERROR_DECODER_NOT_FOUND || error == AVERROR_DEMUXER_NOT_FOUND) return LINKA_AUDIO_UNSUPPORTED;
    if (error == AVERROR(ENOMEM) || error == AVERROR(EFBIG) || error == AVERROR(EOVERFLOW)) return LINKA_AUDIO_LIMIT;
    if (error == AVERROR(EIO) || error == AVERROR(EACCES) || error == AVERROR(ENOENT) || error == AVERROR(EEXIST)) return LINKA_AUDIO_IO;
    return LINKA_AUDIO_INVALID;
}

int linka_audio_decode(const char *source, int output_fd, LinkaAudioCancelled cancelled, void *context, LinkaAudioResult *info) {
    if (!info) return LINKA_AUDIO_INVALID;
    memset(info, 0, sizeof(*info));
    info->status = LINKA_AUDIO_INVALID;
    if (!source) return info->status;
    if (pthread_mutex_trylock(&decoder_lock)) return info->status = LINKA_AUDIO_IO;
    pthread_once(&policy_once, policy);
    Decode state = {.cancelled = cancelled, .context = context, .info = info,
                    .deadline = av_gettime_relative() + 30LL * AV_TIME_BASE};
    AVFormatContext *format = NULL;
    AVIOContext *io = NULL;
    AVCodecContext *decoder = NULL;
    AVFrame *frame = NULL;
    AVPacket *packet = NULL;
    AVDictionary *options = NULL;
    AVDictionary **probe = NULL;
    unsigned probe_count = 0;
    int fd = -1, copy_fd = -1, status = AVERROR_INVALIDDATA;
    struct stat metadata, output_metadata;
    if (stopped(&state)) { status = AVERROR_EXIT; goto done; }
    fd = open(source, O_RDONLY | O_NOFOLLOW);
    if (fd < 0 || fstat(fd, &metadata)) { status = AVERROR(EIO); goto done; }
    info->source_bytes = metadata.st_size;
    if (!S_ISREG(metadata.st_mode) || metadata.st_size <= 0) goto done;
    if (metadata.st_size > SOURCE_CAP) { status = AVERROR(EFBIG); goto done; }
    const AVInputFormat *forced_format = NULL;
    if ((status = input_format(&state, fd, &forced_format)) < 0) goto done;
    if (output_fd >= 0) {
        if (fstat(output_fd, &output_metadata) || !S_ISREG(output_metadata.st_mode) || output_metadata.st_size != 0 ||
            output_metadata.st_nlink != 1 || (metadata.st_dev == output_metadata.st_dev && metadata.st_ino == output_metadata.st_ino)) {
            status = AVERROR(EIO); goto done;
        }
        copy_fd = dup(output_fd);
        if (copy_fd < 0) { status = AVERROR(EIO); goto done; }
        state.output = fdopen(copy_fd, "wb");
        if (!state.output) { status = AVERROR(EIO); goto done; }
        copy_fd = -1;
        if ((status = wave_header(state.output, 0)) < 0) goto done;
    }
    state.input = fdopen(fd, "rb");
    if (!state.input) { status = AVERROR(EIO); goto done; }
    fd = -1;
    uint8_t *buffer = av_malloc(32768);
    if (!buffer) { status = AVERROR(ENOMEM); goto done; }
    io = avio_alloc_context(buffer, 32768, 0, &state, read_packet, NULL, seek_packet);
    if (!io) { av_free(buffer); status = AVERROR(ENOMEM); goto done; }
    format = avformat_alloc_context();
    if (!format) { status = AVERROR(ENOMEM); goto done; }
    format->pb = io; format->flags |= AVFMT_FLAG_CUSTOM_IO;
    format->opaque = &state; format->io_open = deny_open;
    format->interrupt_callback = (AVIOInterruptCB){stopped, &state};
    format->probesize = 1024 * 1024;
    format->max_analyze_duration = 3LL * AV_TIME_BASE;
    format->max_probe_packets = 128; format->max_streams = 8;
    av_dict_set(&options, "format_whitelist", "ogg,amr,mov,wav,mp3", 0);
    av_dict_set(&options, "codec_whitelist", "vorbis,amrnb,pcm_s16le,mp3", 0);
    av_dict_set(&options, "protocol_whitelist", "file", 0);
    /* Do not silently skip external MOV tracks: force the attempt through deny_open. */
    av_dict_set(&options, "enable_drefs", "1", 0);
    if ((status = avformat_open_input(&format, NULL, forced_format, &options)) < 0) goto done;
    int audio;
    if ((status = stream_parameters(format, &audio)) < 0) goto done;
    probe_count = format->nb_streams;
    probe = av_calloc(probe_count, sizeof(*probe));
    if (!probe) { status = AVERROR(ENOMEM); goto done; }
    for (unsigned n = 0; n < probe_count; ++n) av_dict_set(&probe[n], "threads", "1", 0);
    if ((status = avformat_find_stream_info(format, probe)) < 0) goto done;
    if ((status = stream_parameters(format, &audio)) < 0) goto done;
    if (info->supplemental_denied) { status = AVERROR(EACCES); goto done; }
    if (format->duration != AV_NOPTS_VALUE && format->duration > SECONDS_CAP * AV_TIME_BASE) { status = AVERROR(EFBIG); goto done; }
    AVCodecParameters *parameters = format->streams[audio]->codecpar;
    if (parameters->sample_rate <= 0 || parameters->ch_layout.nb_channels <= 0) { status = AVERROR_INVALIDDATA; goto done; }
    const AVCodec *codec = avcodec_find_decoder(parameters->codec_id);
    if (!codec) { status = AVERROR_DECODER_NOT_FOUND; goto done; }
    decoder = avcodec_alloc_context3(codec);
    if (!decoder) { status = AVERROR(ENOMEM); goto done; }
    if ((status = avcodec_parameters_to_context(decoder, parameters)) < 0) goto done;
    decoder->thread_count = 1; decoder->thread_type = 0;
    if ((status = avcodec_open2(decoder, codec, NULL)) < 0) goto done;
    const char *name = format->iformat->name;
    AVDictionaryEntry *brand = av_dict_get(format->metadata, "major_brand", NULL, 0);
    info->format = !strcmp(name, "ogg") ? LINKA_AUDIO_OGG : !strcmp(name, "mp3") ? LINKA_AUDIO_MP3 :
        !strcmp(name, "wav") ? LINKA_AUDIO_WAV : !strcmp(name, "amr") ? LINKA_AUDIO_AMR :
        brand && !strncmp(brand->value, "3g", 2) ? LINKA_AUDIO_3GP : LINKA_AUDIO_MP4;
    state.floating = av_malloc_array(CHUNK * CHANNELS, sizeof(float));
    state.pcm16 = av_malloc_array(CHUNK * CHANNELS, 2);
    frame = av_frame_alloc(); packet = av_packet_alloc();
    if (!state.floating || !state.pcm16 || !frame || !packet) { status = AVERROR(ENOMEM); goto done; }
    while ((status = av_read_frame(format, packet)) >= 0) {
        int current;
        if (stopped(&state)) { status = AVERROR_EXIT; break; }
        if ((status = stream_parameters(format, &current)) < 0 || current != audio) { status = AVERROR_DECODER_NOT_FOUND; break; }
        if (packet->size < 0 || packet->size > 2 * 1024 * 1024) { status = AVERROR(EFBIG); break; }
        if (packet->stream_index == audio) {
            status = avcodec_send_packet(decoder, packet);
            if (status >= 0) status = drain(&state, decoder, frame);
        }
        av_packet_unref(packet);
        if (status < 0 || info->supplemental_denied) break;
    }
    if (status != AVERROR_EOF) goto done;
    if ((status = avcodec_send_packet(decoder, NULL)) < 0 || (status = drain(&state, decoder, frame)) < 0) goto done;
    if (state.swr) for (;;) {
        uint8_t *output[] = {(uint8_t *)state.floating};
        int count = swr_convert(state.swr, output, CHUNK, NULL, 0);
        if (count < 0) { status = count; goto done; }
        if (!count) break;
        if ((status = emit(&state, count)) < 0) goto done;
    }
    if (!info->frames) { status = AVERROR_INVALIDDATA; goto done; }
    if (state.output && (status = wave_header(state.output, info->frames)) < 0) goto done;
    info->duration_millis = (info->frames * 1000 + RATE - 1) / RATE;
    status = 0;
done:
    if (probe) for (unsigned n = 0; n < probe_count; ++n) av_dict_free(&probe[n]);
    av_free(probe); av_dict_free(&options);
    av_packet_free(&packet); av_frame_free(&frame); avcodec_free_context(&decoder);
    swr_free(&state.swr); av_channel_layout_uninit(&state.layout);
    avformat_close_input(&format);
    if (io) av_freep(&io->buffer);
    avio_context_free(&io);
    av_free(state.floating); av_free(state.pcm16);
    if (state.input) fclose(state.input);
    if (fd >= 0) close(fd);
    if (state.output && fclose(state.output)) status = AVERROR(EIO);
    if (copy_fd >= 0) close(copy_fd);
    info->status = classify(status, &state);
    pthread_mutex_unlock(&decoder_lock);
    return info->status;
}

const char *linka_audio_version(void) { return av_version_info(); }
const char *linka_audio_license(void) { return avcodec_license(); }
const char *linka_audio_configuration(void) { return avcodec_configuration(); }
