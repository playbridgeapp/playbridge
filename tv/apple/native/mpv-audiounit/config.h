// Only for the isolated v0.41.0 AudioUnit translation unit, NOT a full mpv config.
// None of these switches change struct ao / ao_driver / mp_chmap layouts.
// HAVE_AVFOUNDATION refers to mpv's AUDIO backend, absent in 0.41.0-av.
#define HAVE_POSIX 1
#define HAVE_DARWIN 1
#define HAVE_COREAUDIO 0
#define HAVE_AVFOUNDATION 0
#define HAVE_WIN32_THREADS 0
#define HAVE_PTHREAD_CONDATTR_SETCLOCK 0
