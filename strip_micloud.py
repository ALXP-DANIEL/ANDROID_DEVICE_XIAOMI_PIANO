"""Remove exactly one mandatory micloud-sdk element from binary Android XML."""
import struct

from manifest import decode_manifest

ANDROID = "{http://schemas.android.com/apk/res/android}"


def _strings(data):
    offset = 8
    while offset < len(data):
        kind, header, size = struct.unpack_from("<HHI", data, offset)
        if kind == 1:
            count = struct.unpack_from("<I", data, offset + 8)[0]
            flags = struct.unpack_from("<I", data, offset + 16)[0]
            start = struct.unpack_from("<I", data, offset + 20)[0]
            result = []
            for index in range(count):
                relative = struct.unpack_from("<I", data, offset + header + 4 * index)[0]
                pos = offset + start + relative
                if flags & 0x100:
                    def length8(p):
                        n = data[p]
                        return (((n & 127) << 8) | data[p + 1], p + 2) if n & 128 else (n, p + 1)
                    _, pos = length8(pos)
                    length, pos = length8(pos)
                    result.append(data[pos:pos + length].decode("utf-8"))
                else:
                    length = struct.unpack_from("<H", data, pos)[0]
                    pos += 2
                    if length & 0x8000:
                        length = ((length & 0x7fff) << 16) | struct.unpack_from("<H", data, pos)[0]
                        pos += 2
                    result.append(data[pos:pos + 2 * length].decode("utf-16le"))
            return result
        if size < header or not size:
            raise ValueError("corrupt XML chunk")
        offset += size
    raise ValueError("missing XML string pool")


def remove_micloud_uses_library(original):
    if struct.unpack_from("<HHI", original) != (3, 8, len(original)):
        raise ValueError("unexpected binary XML header")
    names = _strings(original)
    target_start = None
    target_end = None
    depth = 0
    offset = 8
    while offset < len(original):
        kind, header, size = struct.unpack_from("<HHI", original, offset)
        if not size or size < header or offset + size > len(original):
            raise ValueError("invalid binary XML chunk")
        if kind == 0x102:
            tag = names[struct.unpack_from("<I", original, offset + 20)[0]]
            if target_start is not None and target_end is None:
                raise ValueError("nested element in micloud-sdk uses-library")
            if tag == "uses-library":
                first = offset + 16 + struct.unpack_from("<H", original, offset + 24)[0]
                count = struct.unpack_from("<H", original, offset + 28)[0]
                for index in range(count):
                    pos = first + 20 * index
                    attr_name = names[struct.unpack_from("<I", original, pos + 4)[0]]
                    raw = struct.unpack_from("<I", original, pos + 8)[0]
                    dtype = original[pos + 15]
                    value = struct.unpack_from("<I", original, pos + 16)[0]
                    attr_value = names[raw] if raw != 0xffffffff else names[value] if dtype == 3 else ""
                    if attr_name == "name" and attr_value == "micloud-sdk":
                        if target_start is not None:
                            raise ValueError("multiple micloud-sdk requirements")
                        target_start = offset
                        depth = 1
                        break
        elif kind == 0x103 and target_start is not None and target_end is None:
            depth -= 1
            if depth == 0:
                target_end = offset + size
        offset += size
    if target_start is None or target_end is None:
        raise ValueError("required micloud-sdk element not found")
    patched = bytearray(original[:target_start] + original[target_end:])
    struct.pack_into("<I", patched, 4, len(patched))
    before = decode_manifest(original)
    after = decode_manifest(patched)
    original_libs = [e.get(ANDROID + "name") for e in before.find("application").findall("uses-library")]
    patched_libs = [e.get(ANDROID + "name") for e in after.find("application").findall("uses-library")]
    if original_libs.count("micloud-sdk") != 1 or patched_libs != [x for x in original_libs if x != "micloud-sdk"]:
        raise ValueError("unintended Notes library manifest change")
    if after.get("package") != before.get("package"):
        raise ValueError("Notes identity changed")
    return bytes(patched)
