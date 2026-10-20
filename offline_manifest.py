"""Remove pinned cloud/AI lifecycle components and direct Internet access."""
import struct
import xml.etree.ElementTree as ET
from manifest import decode_manifest
from strip_micloud import _strings

ANDROID = "{http://schemas.android.com/apk/res/android}"
COMPONENTS = {
    "com.miui.notes.ui.AccountReceiver",
    "com.miui.notes.backup.NoteSettingsBackupService",
    "com.miui.notes.cloudservice.NotesSyncService",
    "com.miui.todo.cloudservice.PushReceiver",
    "com.miui.notes.ai.NoteAiTextWidgetService",
    "com.miui.notes.ai.AiTextWidgetService",
    "com.miui.notes.ai.GlobalAiTextWidgetService",
    "com.miui.notes.ai.provider.AiTextWidgetBlockProvider",
    "com.miui.notes.agent.tool.provider.NoteToolService",
}


def apply(original):
    if struct.unpack_from("<HHI", original) != (3, 8, len(original)):
        raise ValueError("Unexpected binary manifest header")
    names = _strings(original)
    offset, depth, removing = 8, 0, None
    ranges, found = [], set()
    while offset < len(original):
        kind, header, size = struct.unpack_from("<HHI", original, offset)
        if size < header or size == 0 or offset + size > len(original):
            raise ValueError("Invalid manifest chunk")
        if kind == 0x102:
            depth += 1
            tag = names[struct.unpack_from("<I", original, offset + 20)[0]]
            first = offset + 16 + struct.unpack_from("<H", original, offset + 24)[0]
            count = struct.unpack_from("<H", original, offset + 28)[0]
            name = None
            for index in range(count):
                pos = first + index * 20
                if names[struct.unpack_from("<I", original, pos + 4)[0]] != "name":
                    continue
                raw = struct.unpack_from("<I", original, pos + 8)[0]
                dtype, value = original[pos + 15], struct.unpack_from("<I", original, pos + 16)[0]
                name = names[raw] if raw != 0xffffffff else names[value] if dtype == 3 else None
            target = ((tag == "uses-permission" and name == "android.permission.INTERNET")
                      or (tag in {"service", "receiver", "provider"} and name in COMPONENTS))
            if target:
                if removing is not None or name in found:
                    raise ValueError("Nested or duplicate offline component")
                found.add(name)
                removing = (offset, depth)
        elif kind == 0x103:
            if removing is not None and depth == removing[1]:
                ranges.append((removing[0], offset + size))
                removing = None
            depth -= 1
            if depth < 0:
                raise ValueError("Unbalanced manifest")
        offset += size
    if depth or removing is not None or found != COMPONENTS | {"android.permission.INTERNET"}:
        raise ValueError("Pinned offline component inventory changed")
    patched = bytearray(original)
    for start, end in reversed(ranges):
        del patched[start:end]
    struct.pack_into("<I", patched, 4, len(patched))
    expected = decode_manifest(original)
    for parent in expected.iter():
        for child in list(parent):
            name = child.get(ANDROID + "name")
            if ((child.tag == "uses-permission" and name == "android.permission.INTERNET")
                    or (child.tag in {"service", "receiver", "provider"} and name in COMPONENTS)):
                parent.remove(child)
    if ET.tostring(expected) != ET.tostring(decode_manifest(patched)):
        raise ValueError("Unintended manifest change")
    return bytes(patched)
