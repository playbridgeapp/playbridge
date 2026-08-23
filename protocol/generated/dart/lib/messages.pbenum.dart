// This is a generated file - do not edit.
//
// Generated from messages.proto.

// @dart = 3.3

// ignore_for_file: annotate_overrides, camel_case_types, comment_references
// ignore_for_file: constant_identifier_names
// ignore_for_file: curly_braces_in_flow_control_structures
// ignore_for_file: deprecated_member_use_from_same_package, library_prefixes
// ignore_for_file: non_constant_identifier_names

import 'dart:core' as $core;

import 'package:protobuf/protobuf.dart' as $pb;

/// ==================== Binary Mouse Packet ====================
/// High-frequency mouse events skip JSON and use a compact 9-byte frame:
///   [0]   MouseEventType (uint8)
///   [1-4] dx as float32 big-endian
///   [5-8] dy as float32 big-endian
/// This message is for documentation/tooling only — not sent via protobuf encoding.
class MouseEventType extends $pb.ProtobufEnum {
  static const MouseEventType MOUSE_MOVE = MouseEventType._(0, _omitEnumNames ? '' : 'MOUSE_MOVE');
  static const MouseEventType MOUSE_CLICK = MouseEventType._(1, _omitEnumNames ? '' : 'MOUSE_CLICK');
  static const MouseEventType MOUSE_SCROLL = MouseEventType._(2, _omitEnumNames ? '' : 'MOUSE_SCROLL');
  static const MouseEventType MOUSE_DOWN = MouseEventType._(3, _omitEnumNames ? '' : 'MOUSE_DOWN');
  static const MouseEventType MOUSE_UP = MouseEventType._(4, _omitEnumNames ? '' : 'MOUSE_UP');
  static const MouseEventType MOUSE_ZOOM = MouseEventType._(5, _omitEnumNames ? '' : 'MOUSE_ZOOM');
  static const MouseEventType MOUSE_RESET = MouseEventType._(6, _omitEnumNames ? '' : 'MOUSE_RESET');
  static const MouseEventType MOUSE_ROTATE = MouseEventType._(7, _omitEnumNames ? '' : 'MOUSE_ROTATE');
  /// dx/dy are normalized viewport coordinates for subsequent zoom/rotation events;
  /// negative coordinates clear the touch anchor.
  static const MouseEventType MOUSE_TRANSFORM_ANCHOR = MouseEventType._(8, _omitEnumNames ? '' : 'MOUSE_TRANSFORM_ANCHOR');

  static const $core.List<MouseEventType> values = <MouseEventType> [
    MOUSE_MOVE,
    MOUSE_CLICK,
    MOUSE_SCROLL,
    MOUSE_DOWN,
    MOUSE_UP,
    MOUSE_ZOOM,
    MOUSE_RESET,
    MOUSE_ROTATE,
    MOUSE_TRANSFORM_ANCHOR,
  ];

  static final $core.List<MouseEventType?> _byValue = $pb.ProtobufEnum.$_initByValueList(values, 8);
  static MouseEventType? valueOf($core.int value) =>  value < 0 || value >= _byValue.length ? null : _byValue[value];

  const MouseEventType._(super.value, super.name);
}


const $core.bool _omitEnumNames = $core.bool.fromEnvironment('protobuf.omit_enum_names');
