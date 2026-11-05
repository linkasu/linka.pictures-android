#include "linka_audio_decoder.h"
#include <fcntl.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef struct Request { int calls; int stop_after; } Request;
static int cancel(void *opaque) {
    Request *request = opaque;
    return request->stop_after && ++request->calls >= request->stop_after;
}

int main(int argc, char **argv) {
    if (argc == 2 && !strcmp(argv[1], "info")) {
        printf("version=%s\nlicense=%s\nconfiguration=%s\n", linka_audio_version(), linka_audio_license(), linka_audio_configuration());
        return 0;
    }
    if (argc < 3 || argc > 4) return 64;
    int fd = -1, created = 0;
    Request request = {.stop_after = !strcmp(argv[1], "cancel") ? 20 : 0};
    if (argc == 4) {
        int flags = O_WRONLY | O_NOFOLLOW;
        if (strcmp(argv[1], "foreign")) flags |= O_CREAT | O_EXCL;
        fd = open(argv[3], flags, 0600);
        if (fd < 0) return 65;
        created = strcmp(argv[1], "foreign") != 0;
    }
    LinkaAudioResult result;
    int status = linka_audio_decode(argv[2], fd, cancel, &request, &result);
    if (fd >= 0) close(fd);
    if (status && created) unlink(argv[3]);
    printf("{\"status\":%d,\"format\":%d,\"frames\":%" PRId64 ",\"duration_millis\":%" PRId64
           ",\"rate\":%d,\"channels\":%d,\"source_bytes\":%" PRId64 ",\"read_bytes\":%" PRId64
           ",\"supplemental_denied\":%d,\"cancel_checks\":%d}\n", status, result.format, result.frames,
           result.duration_millis, result.source_rate, result.source_channels, result.source_bytes,
           result.read_bytes, result.supplemental_denied, request.calls);
    return status;
}
