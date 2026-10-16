// In-memory C ABI test double. Never opens sockets or uses credentials.
#include "playbridge_cast_core.h"
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#if PB_ATV_REMOTE_ABI_VERSION != 1
#error Unexpected ATV ABI marker
#endif
struct AtvRemote { char *event; };
static _Atomic unsigned counters[4]; // start, cancel, free, returned-string free
static unsigned mode;
void pb_test_reset(unsigned value) { for (int i = 0; i < 4; ++i) counters[i] = 0; mode = value; }
unsigned pb_test_count(unsigned index) { return counters[index]; }
uint32_t pb_atv_abi_version(void) { return 1; }
AtvRemote *pb_atv_start(void) { ++counters[0]; return calloc(1, sizeof(AtvRemote)); }
bool pb_atv_submit_json(const AtvRemote *handle, const char *command) {
    AtvRemote *remote = (AtvRemote *)handle;
    const char *id = strstr(command, "\"request_id\":\"");
    if (!id) return false;
    id += strlen("\"request_id\":\"");
    const char *end = strchr(id, '"');
    if (!end || end - id > 128) return false;
    free(remote->event);
    if (mode == 2) { remote->event = malloc(1048578); memset(remote->event, 'X', 1048577); remote->event[1048577] = 0; }
    else if (mode == 3) { remote->event = malloc(2); remote->event[0] = (char)255; remote->event[1] = 0; }
    else {
        remote->event = malloc(1024);
        snprintf(remote->event, 1024, "{\"event\":\"identity\",\"request_id\":\"%.*s\",\"cert_pem\":\"test-cert\",\"key_pem\":\"test-key\",\"fingerprint\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}", (int)(end - id), id);
    }
    return true;
}
char *pb_atv_next_json(const AtvRemote *handle, uint64_t wait_ms) {
    AtvRemote *remote = (AtvRemote *)handle;
    if (!remote->event) { usleep((useconds_t)(wait_ms * 1000)); return NULL; }
    char *result = remote->event; remote->event = NULL; return result;
}
void pb_string_free(char *string) { ++counters[3]; free(string); }
void pb_atv_cancel(const AtvRemote *handle) { (void)handle; ++counters[1]; }
void pb_atv_free(const AtvRemote *handle) {
    ++counters[2]; AtvRemote *remote = (AtvRemote *)handle; free(remote->event); free(remote);
}
