#ifndef RUNNER_SYSTEM_VOLUME_CHANNEL_H_
#define RUNNER_SYSTEM_VOLUME_CHANNEL_H_
#include <flutter/method_channel.h>
#include <flutter/encodable_value.h>
#include <memory>

std::unique_ptr<flutter::MethodChannel<flutter::EncodableValue>>
CreateSystemVolumeChannel(flutter::BinaryMessenger* messenger);
#endif
