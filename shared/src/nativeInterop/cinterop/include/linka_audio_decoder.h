#ifndef LINKA_AUDIO_DECODER_H
#define LINKA_AUDIO_DECODER_H
#include <stdint.h>

#define LINKA_AUDIO_OK 0
#define LINKA_AUDIO_UNSUPPORTED 1
#define LINKA_AUDIO_INVALID 2
#define LINKA_AUDIO_LIMIT 3
#define LINKA_AUDIO_IO 4
#define LINKA_AUDIO_CANCELLED 5

#define LINKA_AUDIO_OGG 1
#define LINKA_AUDIO_MP3 2
#define LINKA_AUDIO_AMR 3
#define LINKA_AUDIO_3GP 4
#define LINKA_AUDIO_WAV 5
#define LINKA_AUDIO_MP4 6

typedef int (*LinkaAudioCancelled)(void *context);
typedef struct LinkaAudioResult {
    int status;
    int format;
    int source_rate;
    int source_channels;
    int supplemental_denied;
    int64_t frames;
    int64_t duration_millis;
    int64_t source_bytes;
    int64_t read_bytes;
} LinkaAudioResult;

/* output_fd=-1: full streaming inspect/discard. Otherwise caller owns a fresh,
 * empty, regular, single-link FD opened without following symlinks. Never truncates,
 * unlinks, or closes caller's FD. Source and destination must be distinct inodes.
 * Caller owns path cleanup and must not publish failed/cancelled output.
 * One decoder per process; allocator/log policy is process-wide and fixed. */
int linka_audio_decode(const char *source, int output_fd, LinkaAudioCancelled cancelled,
                      void *context, LinkaAudioResult *result);
const char *linka_audio_version(void);
const char *linka_audio_license(void);
const char *linka_audio_configuration(void);
#endif
