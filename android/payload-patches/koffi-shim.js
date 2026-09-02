/**
 * Pure-JS `koffi` replacement for Android.
 *
 * koffi is a native FFI addon; its prebuilt binaries target glibc Linux,
 * macOS, and Windows, so it cannot load under bionic. Every consumer in the
 * harness uses it to reach Win32 (kernel32.dll, advapi32.dll) for the
 * Windows ACL sandbox, the Windows process inspector, and the native
 * directory picker -- none of which is reachable on Android.
 *
 * The problem is that those modules build pointer and struct descriptors at
 * MODULE SCOPE, so an import that merely throws would stop the plugin tree
 * from loading at all. This stub therefore answers every type-construction
 * call with an opaque descriptor carrying correct x64 sizes (the ACL layer
 * asserts on struct size), while every call that would actually cross into
 * native code fails loudly. On Android none of those calls is reachable.
 *
 * This mirrors the stub the repository already ships for its web-worker
 * runtime at packages/experimental/webworker-runtime/src/node/external_packages/koffi.ts
 */

'use strict'

const MODULE = 'koffi'

/** Primitive sizes koffi's own x64 ABI reports. */
const PRIMITIVES = {
  void: 0,
  bool: 1,
  char: 1,
  uchar: 1,
  int8: 1,
  uint8: 1,
  short: 2,
  ushort: 2,
  int16: 2,
  uint16: 2,
  char16: 2,
  int: 4,
  uint: 4,
  int32: 4,
  uint32: 4,
  float: 4,
  float32: 4,
  long: 8,
  ulong: 8,
  longlong: 8,
  ulonglong: 8,
  int64: 8,
  uint64: 8,
  double: 8,
  float64: 8,
  str: 8,
  str16: 8,
}

function token(label, size, alignment) {
  const align = alignment === undefined ? (Math.min(size, 8) || 1) : alignment
  return { __dshKoffiType: label, size, alignment: align }
}

function typeOf(target) {
  if (typeof target === 'string') {
    const size = PRIMITIVES[target]
    if (size === undefined) throw new Error(`android: koffi type "${target}" is unknown to the stub`)
    return token(target, size)
  }
  if (!target || target.__dshKoffiType === undefined) {
    throw new Error(`android: koffi type ${JSON.stringify(target)} is not a stub descriptor`)
  }
  return target
}

function describe(target) {
  if (typeof target === 'string') return target
  return (target && target.__dshKoffiType) || 'anonymous'
}

function notImplemented(name) {
  return function refuse() {
    throw new Error(`android: ${MODULE}.${name}() is unavailable — native FFI is not supported on this platform`)
  }
}

function pointer(target) {
  return token(`pointer(${describe(target)})`, 8)
}

/**
 * Struct descriptor with x64 padding rules; the Windows ACL layer compares
 * the computed size against its own header probe, so the arithmetic must
 * match koffi's rather than merely returning a placeholder.
 */
function struct(name, fields) {
  const members = (typeof name === 'string' ? fields : name) || {}
  let offset = 0
  let alignment = 1
  for (const member of Object.values(members)) {
    const type = typeOf(member)
    alignment = Math.max(alignment, type.alignment)
    offset = Math.ceil(offset / type.alignment) * type.alignment + type.size
  }
  const size = Math.ceil(offset / alignment) * alignment
  return token(`struct(${typeof name === 'string' ? name : 'anonymous'})`, size, alignment)
}

function array(target, length) {
  const element = typeOf(target)
  return token(`array(${element.__dshKoffiType}, ${length})`, element.size * length, element.alignment)
}

function opaque(name) {
  return token(`opaque(${name || 'anonymous'})`, 0, 1)
}

const types = new Proxy({}, {
  get: (_target, property) => typeOf(String(property)),
  has: property => typeof property === 'string' && property in PRIMITIVES,
})

const koffi = {
  pointer,
  struct,
  array,
  opaque,
  types,
  alias: (name, target) => {
    const type = typeOf(target)
    return token(`alias(${name})`, type.size, type.alignment)
  },
  sizeof: target => typeOf(target).size,
  alignof: target => typeOf(target).alignment,
  load: notImplemented('load'),
  alloc: notImplemented('alloc'),
  free: notImplemented('free'),
  decode: notImplemented('decode'),
  encode: notImplemented('encode'),
  address: notImplemented('address'),
  register: notImplemented('register'),
  unregister: notImplemented('unregister'),
  call: notImplemented('call'),
}

module.exports = koffi
module.exports.default = koffi
module.exports.__esModule = true
module.exports.pointer = pointer
module.exports.struct = struct
module.exports.array = array
module.exports.opaque = opaque
module.exports.types = types
