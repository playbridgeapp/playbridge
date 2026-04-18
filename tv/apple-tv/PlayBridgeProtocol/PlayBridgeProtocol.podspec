Pod::Spec.new do |s|
  s.name             = 'PlayBridgeProtocol'
  s.version          = '0.1.0'
  s.summary          = 'Swift implementation of PlayBridge communication protocol.'
  s.description      = <<-DESC
Mirror of the Kotlin protocol for strongly-typed communication between Phone and TV.
                       DESC
  s.homepage         = 'https://github.com/atulmehla/PlayBridge'
  s.license          = { :type => 'MIT', :file => 'LICENSE' }
  s.author           = { 'Atul Mehla' => 'atul@example.com' }
  s.source           = { :git => 'https://github.com/atulmehla/PlayBridge.git', :tag => s.version.to_s }

  s.tvos.deployment_target = '14.0'
  s.swift_version = '5.0'

  s.source_files = 'Sources/PlayBridgeProtocol/**/*'
end
