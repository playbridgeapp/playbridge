// Guard the private ABI used by the one rebuilt object. Offsets confirmed against
// the pinned 0.41.0-av arm64 AudioUnit object's loads/stores; all supported slices
// are 64-bit. Fail closed on source/ABI drift, rather than mix libmpv versions.
#include "audio/out/internal.h"
#include <stddef.h>
_Static_assert(sizeof(void *) == 8, "64-bit MPVKit required");
_Static_assert(sizeof(struct mp_chmap) == 65, "mp_chmap ABI changed");
_Static_assert(offsetof(struct ao, channels) == 4, "ao channels ABI changed");
_Static_assert(offsetof(struct ao, format) == 0x48, "ao format ABI changed");
_Static_assert(offsetof(struct ao, num_planes) == 0x5c, "ao planes ABI changed");
_Static_assert(offsetof(struct ao, priv) == 0x78, "ao priv ABI changed");
_Static_assert(offsetof(struct ao, log) == 0xa0, "ao log ABI changed");
_Static_assert(offsetof(struct ao, init_flags) == 0xa8, "ao flags ABI changed");
_Static_assert(offsetof(struct ao_driver, init) == 0x20, "driver init ABI changed");
_Static_assert(offsetof(struct ao_driver, priv_size) == 0x78, "driver size ABI changed");
