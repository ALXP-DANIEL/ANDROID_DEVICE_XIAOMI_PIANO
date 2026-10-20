# PencilEngine transport checks

These deliberately small Android Binder/PackageManager/Parcel fixtures exercise
transport policy, death-lease isolation, unsupported functions and failed backend
cleanup on the host. They do not prove Android parcel ABI, SELinux, service
resolution, a functioning PencilEngine backend or hardware effects.

Run from the pianopart checkout:

```sh
out_dir=$(mktemp -d)
javac -d "$out_dir" $(find tests/pencilengine-binder -name '*.java') src/org/lineageos/pianoparts/pencilengine/*.java
java -cp "$out_dir" PencilEngineBinderTest
```

The production sources were also compiled against the retained Android 17
android-all.jar. The transport is deliberately unpublished; an integration owner
must supply a working Backend, secure endpoint and Android runtime validation.
Backend.apply receives trusted calling identity plus the death-scoped lease for
asynchronous color-picker completion. It must not invoke client callbacks itself.
Failed cleanup requires backend-specific retry/reconciliation: this adapter does
not claim a failed HAL write was undone merely because local ownership was freed.

Transport close revokes all client leases, rejects subsequent registration/trigger
requests and is idempotent. Trusted picker cancellation preserves registration,
rejects stale leases, and cannot clear another client's picker. Current fixture
suite passes 31 checks, including authoritative legacy package resolution,
named-null rejection and unavailable capability checks.
