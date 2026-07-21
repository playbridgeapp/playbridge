# Cast Core desktop libraries

Run `cast/build-desktop.sh` from the repository root on each target operating
system. The script
places release libraries here:

- `macos/libplaybridge_cast_core_ffi.dylib` (universal arm64 + x86_64)
- `macos/playbridge` (universal arm64 + x86_64 CLI)
- `linux/libplaybridge_cast_core_ffi.so` (x86_64)
- `linux/playbridge` (x86_64 CLI)
- `windows/playbridge_cast_core_ffi.dll` (x86_64)
- `windows/playbridge.exe` (x86_64 CLI)

Flutter's platform build files package the corresponding library. Linux and
Windows release artifacts must therefore be produced on their respective CI
runners before building the Flutter bundle.
