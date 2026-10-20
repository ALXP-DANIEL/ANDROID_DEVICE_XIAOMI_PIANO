"""Decode Android binary XML for immutable stock-input validation."""
import struct
import xml.etree.ElementTree as ET

def decode_manifest(data):
    strings = []
    stack = []
    root = None
    def u16(o): return struct.unpack_from("<H", data, o)[0]
    def u32(o): return struct.unpack_from("<I", data, o)[0]
    def text(i): return strings[i] if i != 0xffffffff else ""
    offset = 8
    while offset < len(data):
        kind, header, size = struct.unpack_from("<HHI", data, offset)
        if size < header or offset + size > len(data): raise ValueError("bad manifest chunk")
        if kind == 1:
            count, flags, start = u32(offset + 8), u32(offset + 16), u32(offset + 20)
            for i in range(count):
                pos = offset + start + u32(offset + header + i * 4)
                if flags & 0x100:
                    def length8(p):
                        first = data[p]
                        return (((first & 127) << 8) | data[p + 1], p + 2) if first & 128 else (first, p + 1)
                    _, pos = length8(pos)
                    length, pos = length8(pos)
                    strings.append(data[pos:pos + length].decode("utf8"))
                else:
                    length = u16(pos); pos += 2
                    if length & 0x8000:
                        length = ((length & 0x7fff) << 16) | u16(pos); pos += 2
                    strings.append(data[pos:pos + length * 2].decode("utf-16le"))
        elif kind == 0x102:
            element = ET.Element(text(u32(offset + 20)))
            attr_start, count = u16(offset + 24), u16(offset + 28)
            for i in range(count):
                pos = offset + 16 + attr_start + i * 20
                namespace, name, raw = u32(pos), u32(pos + 4), u32(pos + 8)
                dtype, value = data[pos + 15], u32(pos + 16)
                if raw != 0xffffffff: value = text(raw)
                elif dtype == 3: value = text(value)
                elif dtype == 0x12: value = "true" if value else "false"
                elif dtype == 1: value = "@0x%08x" % value
                elif dtype == 0x11: value = "0x%x" % value
                else: value = str(value)
                key = "{%s}%s" % (text(namespace), text(name)) if namespace != 0xffffffff else text(name)
                element.set(key, value)
            if stack: stack[-1].append(element)
            else: root = element
            stack.append(element)
        elif kind == 0x103:
            if not stack: raise ValueError("unbalanced manifest")
            stack.pop()
        offset += size
    if root is None or stack: raise ValueError("incomplete manifest")
    return root
