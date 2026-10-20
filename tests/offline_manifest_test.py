#!/usr/bin/env python3
"""Check the offline manifest policy against the staged pinned APK."""
from pathlib import Path
import sys
import zipfile
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from offline_manifest import apply, COMPONENTS, ANDROID
from manifest import decode_manifest
with zipfile.ZipFile(Path(__file__).resolve().parents[1]/"prebuilt/MIUINotes.apk") as archive:
    original=archive.read("AndroidManifest.xml")
patched=apply(original)
root=decode_manifest(patched)
assert not any(p.get(ANDROID+"name")=="android.permission.INTERNET" for p in root.findall("uses-permission"))
app=root.find("application")
assert not ({c.get(ANDROID+"name") for c in app}&COMPONENTS)
assert any(c.get(ANDROID+"name")=="com.miui.notes.provider.NotesProvider" for c in app)
assert any(c.get(ANDROID+"name")=="com.miui.notes.notesui.feature.note.presentation.list.AINoteListActivity" for c in app)
for bad in [patched,original[:-1]]:
    try:apply(bad)
    except (ValueError,IndexError):pass
    else:raise AssertionError("corrupt/already-patched input accepted")
print("Offline manifest checks passed; local provider/list retained")
