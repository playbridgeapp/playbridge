// Exercise the ACTUAL patched v0.41.0 driver with deterministic Apple API doubles.
// No device or HDMI route required; this proves control flow, not audible playback.
#import <Foundation/Foundation.h>
#import <AVFoundation/AVFoundation.h>
#import <AudioToolbox/AudioToolbox.h>
#include <assert.h>
#include <stdlib.h>
#include <string.h>

static NSInteger preferredChannels, routeChannels = 32;
@interface PBTestAudioSession : NSObject
+ (instancetype)sharedInstance;
- (NSInteger)maximumOutputNumberOfChannels;
- (NSInteger)outputNumberOfChannels;
- (double)outputLatency;
- (BOOL)setCategory:(NSString *)category withOptions:(NSUInteger)options error:(NSError **)error;
- (BOOL)setMode:(NSString *)mode error:(NSError **)error;
- (BOOL)setActive:(BOOL)active error:(NSError **)error;
- (BOOL)setActive:(BOOL)active withOptions:(NSUInteger)options error:(NSError **)error;
- (BOOL)setPreferredOutputNumberOfChannels:(NSInteger)channels error:(NSError **)error;
@end
@implementation PBTestAudioSession
+ (instancetype)sharedInstance { static PBTestAudioSession *s; if (!s) s = [self new]; return s; }
- (NSInteger)maximumOutputNumberOfChannels { return 32; }
- (NSInteger)outputNumberOfChannels { return routeChannels; }
- (double)outputLatency { return 0.08; }
- (BOOL)setCategory:(NSString *)c withOptions:(NSUInteger)o error:(NSError **)e { return YES; }
- (BOOL)setMode:(NSString *)m error:(NSError **)e { return YES; }
- (BOOL)setActive:(BOOL)a error:(NSError **)e { return YES; }
- (BOOL)setActive:(BOOL)a withOptions:(NSUInteger)o error:(NSError **)e { return YES; }
- (BOOL)setPreferredOutputNumberOfChannels:(NSInteger)c error:(NSError **)e { preferredChannels = c; return YES; }
@end

static OSStatus infoStatus, getStatus, formatStatus;
static unsigned layoutChannels, formatChannels, disposals, uninitializations, starts, allocations;
static BOOL callbackInstalled, spdif;
static AudioComponent fakeFind(const AudioComponentDescription *d) { return (AudioComponent)1; }
static OSStatus fakeNew(AudioComponent c, AudioComponentInstance *u) { *u = (AudioComponentInstance)2; return noErr; }
static OSStatus fakeInitialize(AudioUnit u) { return noErr; }
static OSStatus fakeUninitialize(AudioUnit u) { uninitializations++; return noErr; }
static OSStatus fakeDispose(AudioComponentInstance u) { disposals++; return noErr; }
static OSStatus fakeStart(AudioUnit u) { starts++; return noErr; }
static OSStatus fakeStop(AudioUnit u) { return noErr; }
static OSStatus fakeInfo(AudioUnit u, AudioUnitPropertyID p, AudioUnitScope s, AudioUnitElement e, UInt32 *size, Boolean *w) {
    assert(p == kAudioUnitProperty_AudioChannelLayout && s == kAudioUnitScope_Output && e == 0);
    *size = offsetof(AudioChannelLayout, mChannelDescriptions) + layoutChannels * sizeof(AudioChannelDescription);
    return infoStatus;
}
static OSStatus fakeGet(AudioUnit u, AudioUnitPropertyID p, AudioUnitScope s, AudioUnitElement e, void *data, UInt32 *size) {
    if (getStatus) return getStatus;
    AudioChannelLayout *l = data;
    l->mChannelLayoutTag = kAudioChannelLayoutTag_UseChannelDescriptions;
    l->mNumberChannelDescriptions = layoutChannels;
    for (unsigned i = 0; i < layoutChannels; i++) l->mChannelDescriptions[i].mChannelLabel = kAudioChannelLabel_Left + i;
    return noErr;
}
static OSStatus fakeSet(AudioUnit u, AudioUnitPropertyID p, AudioUnitScope s, AudioUnitElement e, const void *data, UInt32 size) {
    if (p == kAudioUnitProperty_StreamFormat) {
        assert(s == kAudioUnitScope_Input && e == 0);
        formatChannels = ((const AudioStreamBasicDescription *)data)->mChannelsPerFrame;
        return formatStatus;
    }
    assert(p == kAudioUnitProperty_SetRenderCallback);
    callbackInstalled = ((const AURenderCallbackStruct *)data)->inputProc != NULL;
    return noErr;
}

// Headers were imported before these macros: production driver calls only are redirected.
#define AVAudioSession PBTestAudioSession
#define AVAudioSessionPortDescription NSObject
#define kAudioUnitSubType_RemoteIO 'rioc'
#define AVAudioSessionCategoryOptions NSUInteger
#define AVAudioSessionCategoryOptionMixWithOthers 1
#define AVAudioSessionCategoryPlayback @"playback"
#define AVAudioSessionModeMoviePlayback @"movie"
#define AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation 1
#define AudioComponentFindNext(previous, description) fakeFind(description)
#define AudioComponentInstanceNew fakeNew
#define AudioComponentInstanceDispose fakeDispose
#define AudioUnitInitialize fakeInitialize
#define AudioUnitUninitialize fakeUninitialize
#define AudioUnitGetPropertyInfo fakeInfo
#define AudioUnitGetProperty fakeGet
#define AudioUnitSetProperty fakeSet
#define AudioOutputUnitStart fakeStart
#define AudioOutputUnitStop fakeStop
#include "audio/out/ao_audiounit.m"

// Small mpv dependency doubles; memory checks catch the upstream dangling-pointer
// cleanup bug when GetProperty fails AFTER GetPropertyInfo allocated a layout.
#undef ta_zalloc_size
void *ta_zalloc_size(void *parent, size_t size) { allocations++; return calloc(1, size); }
void ta_free(void *ptr) { if (ptr) { assert(allocations > 0); allocations--; free(ptr); } }
void *ta_dbg_set_loc(void *ptr, const char *name) { return ptr; }
void mp_msg(struct mp_log *log, int level, const char *format, ...) {}
bool check_ca_st(struct ao *ao, int level, OSStatus status, const char *message) { return status == noErr; }
bool af_fmt_is_spdif(int format) { return spdif; }
int ca_label_to_mp_speaker_id(AudioChannelLabel label) { return (int)label - kAudioChannelLabel_Left; }
void ca_fill_asbd(struct ao *ao, AudioStreamBasicDescription *asbd) {
    *asbd = (AudioStreamBasicDescription){.mChannelsPerFrame = ao->channels.num, .mSampleRate = ao->samplerate};
}
int64_t ca_frames_to_ns(struct ao *ao, uint32_t frames) { return 0; }
int64_t ca_get_latency(const AudioTimeStamp *ts) { return 0; }
int64_t mp_time_ns(void) { return 0; }
int ao_read_data(struct ao *ao, void **data, int samples, int64_t time, bool *eof, bool pad, bool block) { return samples; }

static void check(OSStatus info, OSStatus get, OSStatus format, BOOL passthrough,
                  unsigned channels, BOOL success, unsigned expectedChannels) {
    infoStatus = info; getStatus = get; formatStatus = format; spdif = passthrough;
    layoutChannels = channels; formatChannels = disposals = uninitializations = starts = 0;
    callbackInstalled = NO; preferredChannels = 0;
    struct priv privateState = {0};
    struct ao ao = {.samplerate = 48000, .priv = &privateState};
    ao.channels.num = 6;
    assert(init_audiounit(&ao) == success);
    assert(allocations == 0);
    if (success) {
        assert(ao.channels.num == expectedChannels && formatChannels == expectedChannels);
        assert(callbackInstalled && disposals == 0);
        if (info == kAudioUnitErr_InvalidProperty || get == kAudioUnitErr_InvalidProperty) {
            assert(preferredChannels == 2);
            assert(ao.channels.speaker[0] == MP_SPEAKER_ID_FL && ao.channels.speaker[1] == MP_SPEAKER_ID_FR);
        }
        start(&ao); assert(starts == 1);
        uninit(&ao);
    } else {
        assert(!callbackInstalled && starts == 0);
    }
    assert(disposals == 1 && uninitializations == 1);
}

int main(void) {
    @autoreleasepool {
        if (getenv("PB_MPV_EXPECT_ORIGINAL")) {
            check(kAudioUnitErr_InvalidProperty, 0, 0, NO, 6, NO, 0);
            puts("PASS: original driver reproduces fatal init on the reported HDMI property error");
            return 0;
        }
        check(kAudioUnitErr_InvalidProperty, 0, 0, NO, 6, YES, 2);
        check(0, kAudioUnitErr_InvalidProperty, 0, NO, 6, YES, 2);
        check(0, 0, 0, NO, 6, YES, 6); // Preserve valid multichannel routes.
        routeChannels = 2;
        check(0, 0, 0, NO, 2, YES, 2);
        routeChannels = 32;
        check(kAudioUnitErr_InvalidElement, 0, 0, NO, 6, NO, 0);
        check(0, kAudioUnitErr_InvalidElement, 0, NO, 6, NO, 0);
        check(kAudioUnitErr_InvalidProperty, 0, 0, YES, 6, NO, 0); // Never relabel passthrough as PCM.
        check(kAudioUnitErr_InvalidProperty, 0, kAudioUnitErr_FormatNotSupported, NO, 6, NO, 0);
        puts("PASS: actual MPV AudioUnit driver: invalid-property stereo fallback, valid multichannel, fatal errors, PCM-only policy, cleanup and start");
    }
}
