#!/usr/bin/env ruby
# frozen_string_literal: true

require "yaml"

root = File.expand_path("..", __dir__)
path = File.join(root, "asyncapi.yaml")
document = YAML.safe_load(File.read(path), aliases: true)

abort "asyncapi.yaml must use AsyncAPI 3.0.0" unless document["asyncapi"] == "3.0.0"

components = document.fetch("components")
schemas = components.fetch("schemas")
messages = components.fetch("messages")

required_schemas = %w[
  SenderFrame ReceiverFrame PairingCommit PairingChallenge PairingReveal
  PairingConfirmation PairingApproved CredentialBundle Auth AuthResponse
  PlayPayload PlaylistPayload Status Context PlaylistStatus Tracks PlayerSettings
  BrowserHostFrame BrowserClientFrame BrowserMedia BrowserCapabilities
]
missing_schemas = required_schemas.reject { |name| schemas.key?(name) }
abort "missing required schemas: #{missing_schemas.join(', ')}" unless missing_schemas.empty?

required_messages = %w[
  SenderTextFrame ReceiverTextFrame PointerBinaryFrame
  BrowserHostTextFrame BrowserClientTextFrame
]
missing_messages = required_messages.reject { |name| messages.key?(name) }
abort "missing required messages: #{missing_messages.join(', ')}" unless missing_messages.empty?

references = []
walk = lambda do |value|
  case value
  when Hash
    value.each do |key, child|
      references << child if key == "$ref" && child.start_with?("#/components/")
      walk.call(child)
    end
  when Array
    value.each { |child| walk.call(child) }
  end
end
walk.call(document)

missing_refs = references.uniq.reject do |reference|
  parts = reference.delete_prefix("#/").split("/")
  parts.reduce(document) { |node, part| node.is_a?(Hash) ? node[part] : nil }
end
abort "unresolved local references: #{missing_refs.join(', ')}" unless missing_refs.empty?

flow_doc = File.join(root, "docs", "WSS_FLOW.md")
abort "missing docs/WSS_FLOW.md" unless File.file?(flow_doc)

puts "AsyncAPI integrity OK: #{schemas.size} schemas, #{references.uniq.size} local references"
