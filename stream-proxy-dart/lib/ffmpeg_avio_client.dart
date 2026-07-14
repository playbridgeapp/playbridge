import 'dart:async';
import 'dart:ffi';
import 'dart:io';
import 'dart:isolate';
import 'dart:typed_data';

import 'package:ffi/ffi.dart';

// --- FFI Function Type Signatures ---
typedef AvFormatNetworkInitC = Int32 Function();
typedef AvFormatNetworkInitDart = int Function();

typedef AvDictSetC = Int32 Function(
  Pointer<Pointer<Void>> pm,
  Pointer<Utf8> key,
  Pointer<Utf8> value,
  Int32 flags,
);
typedef AvDictSetDart = int Function(
  Pointer<Pointer<Void>> pm,
  Pointer<Utf8> key,
  Pointer<Utf8> value,
  int flags,
);

typedef AvDictFreeC = Void Function(Pointer<Pointer<Void>> m);
typedef AvDictFreeDart = void Function(Pointer<Pointer<Void>> m);

typedef AvioOpen2C = Int32 Function(
  Pointer<Pointer<Void>> s,
  Pointer<Utf8> url,
  Int32 flags,
  Pointer<Void> intCb,
  Pointer<Pointer<Void>> options,
);
typedef AvioOpen2Dart = int Function(
  Pointer<Pointer<Void>> s,
  Pointer<Utf8> url,
  int flags,
  Pointer<Void> intCb,
  Pointer<Pointer<Void>> options,
);

typedef AvioReadC = Int32 Function(
  Pointer<Void> s,
  Pointer<Uint8> buf,
  Int32 size,
);
typedef AvioReadDart = int Function(
  Pointer<Void> s,
  Pointer<Uint8> buf,
  int size,
);

typedef AvioCloseC = Int32 Function(Pointer<Void> s);
typedef AvioCloseDart = int Function(Pointer<Void> s);

typedef AvStrErrorC = Int32 Function(
  Int32 errnum,
  Pointer<Char> errbuf,
  Size errbufSize,
);
typedef AvStrErrorDart = int Function(
  int errnum,
  Pointer<Char> errbuf,
  int errbufSize,
);

class AvioClient {
  static final AvioClient instance = AvioClient._();
  static String? dyldFrameworkPathOverride;

  AvioClient._() {
    _init();
  }

  late final DynamicLibrary _avutil;
  late final DynamicLibrary _avformat;

  late final AvFormatNetworkInitDart _avformatNetworkInit;
  late final AvDictSetDart _avDictSet;
  late final AvDictFreeDart _avDictFree;
  late final AvioOpen2Dart _avioOpen2;
  late final AvioReadDart _avioRead;
  late final AvioCloseDart _avioClose;
  late final AvStrErrorDart _avStrerror;

  bool _initialized = false;

  void _init() {
    try {
      _avutil = _loadLibrary('Avutil');
      _avformat = _loadLibrary('Avformat');

      _avformatNetworkInit = _avformat
          .lookup<NativeFunction<AvFormatNetworkInitC>>('avformat_network_init')
          .asFunction<AvFormatNetworkInitDart>();

      _avDictSet = _avutil
          .lookup<NativeFunction<AvDictSetC>>('av_dict_set')
          .asFunction<AvDictSetDart>();

      _avDictFree = _avutil
          .lookup<NativeFunction<AvDictFreeC>>('av_dict_free')
          .asFunction<AvDictFreeDart>();

      _avioOpen2 = _avformat
          .lookup<NativeFunction<AvioOpen2C>>('avio_open2')
          .asFunction<AvioOpen2Dart>();

      _avioRead = _avformat
          .lookup<NativeFunction<AvioReadC>>('avio_read')
          .asFunction<AvioReadDart>();

      _avioClose = _avformat
          .lookup<NativeFunction<AvioCloseC>>('avio_close')
          .asFunction<AvioCloseDart>();

      _avStrerror = _avutil
          .lookup<NativeFunction<AvStrErrorC>>('av_strerror')
          .asFunction<AvStrErrorDart>();

      _avformatNetworkInit();
      _initialized = true;
    } catch (e) {
      stderr.writeln('[pb-proxy-avio] Failed to load FFmpeg FFI libraries: $e');
    }
  }

  DynamicLibrary _loadLibrary(String name) {
    // 1. Try override or environment variable DYLD_FRAMEWORK_PATH
    final frameworksPath = dyldFrameworkPathOverride ??
        Platform.environment['DYLD_FRAMEWORK_PATH'];
    if (frameworksPath != null && frameworksPath.isNotEmpty) {
      final path = '$frameworksPath/$name.framework/$name';
      if (File(path).existsSync()) {
        return DynamicLibrary.open(path);
      }
    }

    // 2. For macOS, try Homebrew paths first, then fallback to system bundle
    if (Platform.isMacOS) {
      for (final base in ['/opt/homebrew/lib', '/usr/local/lib']) {
        final path = '$base/lib${name.toLowerCase()}.dylib';
        if (File(path).existsSync()) {
          return DynamicLibrary.open(path);
        }
        for (var v = 61; v >= 58; v--) {
          final verPath = '$base/lib${name.toLowerCase()}.$v.dylib';
          if (File(verPath).existsSync()) {
            return DynamicLibrary.open(verPath);
          }
        }
      }

      final exeDir = File(Platform.resolvedExecutable).parent.path;
      final bundlePath = '$exeDir/../Frameworks/$name.framework/$name';
      if (File(bundlePath).existsSync()) {
        return DynamicLibrary.open(bundlePath);
      }
      try {
        return DynamicLibrary.open('$name.framework/$name');
      } catch (_) {}
    }

    // 3. For Windows, check executable directory and system folders
    if (Platform.isWindows) {
      final exeDir = File(Platform.resolvedExecutable).parent.path;
      final dir = Directory(exeDir);
      if (dir.existsSync()) {
        for (final file in dir.listSync()) {
          final nameLower = file.path.toLowerCase();
          if (nameLower.contains(name.toLowerCase()) &&
              nameLower.endsWith('.dll')) {
            return DynamicLibrary.open(file.path);
          }
        }
      }
      for (var v = 61; v >= 58; v--) {
        try {
          return DynamicLibrary.open('lib${name.toLowerCase()}-$v.dll');
        } catch (_) {}
      }
      try {
        return DynamicLibrary.open('$name.dll');
      } catch (_) {}
    }

    // 4. For Linux, search system libraries
    if (Platform.isLinux) {
      try {
        return DynamicLibrary.open('lib${name.toLowerCase()}.so');
      } catch (_) {}
      for (var v = 61; v >= 58; v--) {
        try {
          return DynamicLibrary.open('lib${name.toLowerCase()}.so.$v');
        } catch (_) {}
      }
    }

    return DynamicLibrary.open(name);
  }

  String getAvErrorString(int errNum) {
    if (!_initialized) return 'FFmpeg not initialized';
    final errBuf = calloc<Char>(1024);
    try {
      final res = _avStrerror(errNum, errBuf, 1024);
      if (res == 0) {
        return errBuf.cast<Utf8>().toDartString();
      }
      return 'Unknown FFmpeg error $errNum';
    } catch (e) {
      return 'Error getting message: $e';
    } finally {
      calloc.free(errBuf);
    }
  }
}

class AvioIsolateStream {
  final String url;
  final Map<String, String> headers;
  final int timeoutSeconds;

  AvioIsolateStream({
    required this.url,
    required this.headers,
    this.timeoutSeconds = 15,
  });

  Stream<Uint8List> start() {
    late final StreamController<Uint8List> controller;
    final receivePort = ReceivePort();
    Isolate? isolate;
    StreamSubscription? sub;
    bool isCancelled = false;

    void cleanup() {
      sub?.cancel();
      receivePort.close();
      isolate?.kill();
    }

    controller = StreamController<Uint8List>(
      onCancel: () {
        isCancelled = true;
        cleanup();
      },
    );

    Isolate.spawn(
      _isolateEntry,
      _IsolateConfig(
        url: url,
        headers: headers,
        timeoutSeconds: timeoutSeconds,
        sendPort: receivePort.sendPort,
        dyldFrameworkPath: AvioClient.dyldFrameworkPathOverride ??
            Platform.environment['DYLD_FRAMEWORK_PATH'],
        resolvedExecutable: Platform.resolvedExecutable,
      ),
    ).then((spawnedIsolate) {
      if (isCancelled) {
        spawnedIsolate.kill();
        receivePort.close();
        return;
      }
      isolate = spawnedIsolate;
      sub = receivePort.listen(
        (message) {
          if (message is Uint8List) {
            controller.add(message);
          } else if (message is Map) {
            final type = message['type'];
            if (type == 'eof') {
              controller.close();
              cleanup();
            } else if (type == 'error') {
              controller.addError(Exception(message['message']));
              controller.close();
              cleanup();
            }
          }
        },
        onError: (e) {
          controller.addError(e);
          controller.close();
          cleanup();
        },
        onDone: () {
          if (!controller.isClosed) {
            controller.close();
          }
          cleanup();
        },
      );
    }).catchError((e) {
      controller.addError(e);
      controller.close();
      cleanup();
    });

    return controller.stream;
  }

  static void _isolateEntry(_IsolateConfig config) {
    if (config.dyldFrameworkPath != null) {
      AvioClient.dyldFrameworkPathOverride = config.dyldFrameworkPath;
    }

    final client = AvioClient.instance;
    if (!client._initialized) {
      config.sendPort.send({
        'type': 'error',
        'message': 'Failed to initialize FFmpeg dynamic libraries in isolate.',
      });
      return;
    }

    final Pointer<Pointer<Void>> optionsPtr = calloc<Pointer<Void>>();
    optionsPtr.value = nullptr;

    final customHeaders = StringBuffer();
    config.headers.forEach((key, value) {
      customHeaders.write('$key: $value\r\n');
    });

    final Pointer<Utf8> headersKey = 'headers'.toNativeUtf8();
    final Pointer<Utf8> headersVal = customHeaders.toString().toNativeUtf8();
    client._avDictSet(optionsPtr, headersKey, headersVal, 0);

    final Pointer<Utf8> timeoutKey = 'timeout'.toNativeUtf8();
    final Pointer<Utf8> timeoutVal =
        '${config.timeoutSeconds * 1000000}'.toNativeUtf8();
    client._avDictSet(optionsPtr, timeoutKey, timeoutVal, 0);

    final Pointer<Pointer<Void>> avioCtxPtr = calloc<Pointer<Void>>();
    avioCtxPtr.value = nullptr;

    final Pointer<Utf8> cUrl = config.url.toNativeUtf8();

    try {
      final openRes = client._avioOpen2(
        avioCtxPtr,
        cUrl,
        1,
        nullptr,
        optionsPtr,
      );

      if (openRes < 0) {
        final errStr = client.getAvErrorString(openRes);
        config.sendPort.send({
          'type': 'error',
          'message': 'avio_open2 failed: error_code=$openRes message="$errStr"',
        });
        return;
      }

      const bufferSize = 32768;
      final Pointer<Uint8> readBuffer = calloc<Uint8>(bufferSize);

      try {
        while (true) {
          final bytesRead = client._avioRead(
            avioCtxPtr.value,
            readBuffer,
            bufferSize,
          );

          if (bytesRead > 0) {
            final chunk = Uint8List.fromList(
              readBuffer.asTypedList(bytesRead),
            );
            config.sendPort.send(chunk);
          } else {
            if (bytesRead != -541478725 && bytesRead < 0) {
              final errStr = client.getAvErrorString(bytesRead);
              config.sendPort.send({
                'type': 'error',
                'message':
                    'avio_read failed: error_code=$bytesRead message="$errStr"',
              });
            } else {
              config.sendPort.send({'type': 'eof'});
            }
            break;
          }
        }
      } finally {
        calloc.free(readBuffer);
        if (avioCtxPtr.value != nullptr) {
          client._avioClose(avioCtxPtr.value);
        }
      }
    } catch (e) {
      config.sendPort.send({
        'type': 'error',
        'message': 'Unexpected exception in AVIO isolate: $e',
      });
    } finally {
      client._avDictFree(optionsPtr);
      calloc.free(optionsPtr);
      calloc.free(avioCtxPtr);
      malloc.free(headersKey);
      malloc.free(headersVal);
      malloc.free(timeoutKey);
      malloc.free(timeoutVal);
      malloc.free(cUrl);
    }
  }
}

class _IsolateConfig {
  final String url;
  final Map<String, String> headers;
  final int timeoutSeconds;
  final SendPort sendPort;
  final String? dyldFrameworkPath;
  final String resolvedExecutable;

  _IsolateConfig({
    required this.url,
    required this.headers,
    required this.timeoutSeconds,
    required this.sendPort,
    this.dyldFrameworkPath,
    required this.resolvedExecutable,
  });
}
