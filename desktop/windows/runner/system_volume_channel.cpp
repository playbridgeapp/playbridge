#include "system_volume_channel.h"

#include <windows.h>
#include <endpointvolume.h>
#include <mmdeviceapi.h>
#include <wrl/client.h>
#include <flutter/standard_method_codec.h>
#include <cmath>

using flutter::EncodableMap;
using flutter::EncodableValue;
using Microsoft::WRL::ComPtr;

namespace {
struct ComApartment {
  HRESULT status = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
  ~ComApartment() { if (SUCCEEDED(status)) CoUninitialize(); }
};
}

std::unique_ptr<flutter::MethodChannel<EncodableValue>>
CreateSystemVolumeChannel(flutter::BinaryMessenger* messenger) {
  auto channel = std::make_unique<flutter::MethodChannel<EncodableValue>>(
      messenger, "playbridge/system_volume",
      &flutter::StandardMethodCodec::GetInstance());
  channel->SetMethodCallHandler([](const auto& call, auto result) {
    const auto& method = call.method_name();
    if (method != "get" && method != "set" && method != "step" && method != "mute") {
      result->NotImplemented();
      return;
    }
    ComApartment apartment;
    ComPtr<IMMDeviceEnumerator> enumerator;
    ComPtr<IMMDevice> device;
    ComPtr<IAudioEndpointVolume> endpoint;
    HRESULT hr = apartment.status;
    if (SUCCEEDED(hr) || hr == RPC_E_CHANGED_MODE) {
      hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                            IID_PPV_ARGS(enumerator.GetAddressOf()));
    }
    if (SUCCEEDED(hr)) hr = enumerator->GetDefaultAudioEndpoint(eRender, eMultimedia, device.GetAddressOf());
    if (SUCCEEDED(hr)) hr = device->Activate(__uuidof(IAudioEndpointVolume), CLSCTX_ALL, nullptr,
                                             reinterpret_cast<void**>(endpoint.GetAddressOf()));
    if (FAILED(hr)) { result->Error("unavailable", "System volume unavailable"); return; }
    const auto* args = call.arguments() ? std::get_if<EncodableMap>(call.arguments()) : nullptr;
    if (method == "set") {
      const double* level = nullptr;
      if (args) {
        auto it = args->find(EncodableValue("level"));
        if (it != args->end()) level = std::get_if<double>(&it->second);
      }
      if (!level || !std::isfinite(*level) || *level < 0 || *level > 1) {
        result->Error("invalid", "Expected normalized level"); return;
      }
      hr = endpoint->SetMasterVolumeLevelScalar(static_cast<float>(*level), nullptr);
    } else if (method == "step") {
      const bool* up = nullptr;
      if (args) {
        auto it = args->find(EncodableValue("up"));
        if (it != args->end()) up = std::get_if<bool>(&it->second);
      }
      if (!up) { result->Error("invalid", "Expected direction"); return; }
      hr = *up ? endpoint->VolumeStepUp(nullptr) : endpoint->VolumeStepDown(nullptr);
    } else if (method == "mute") {
      BOOL muted = FALSE;
      hr = endpoint->GetMute(&muted);
      if (SUCCEEDED(hr)) hr = endpoint->SetMute(!muted, nullptr);
    }
    if (FAILED(hr)) { result->Error("unavailable", "System volume command unavailable"); return; }
    if (method == "get") {
      float level = 0;
      BOOL muted = FALSE;
      hr = endpoint->GetMasterVolumeLevelScalar(&level);
      if (SUCCEEDED(hr)) hr = endpoint->GetMute(&muted);
      if (FAILED(hr)) { result->Error("unavailable", "System volume unavailable"); return; }
      result->Success(EncodableValue(EncodableMap{
          {EncodableValue("level"), EncodableValue(static_cast<double>(level))},
          {EncodableValue("muted"), EncodableValue(muted != FALSE)},
      }));
    } else {
      result->Success();
    }
  });
  return channel;
}
