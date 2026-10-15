# Trusted pen update package

The updater reads `/system_ext/etc/piano_pen/manifest.json` and a flat `.img`
filename in the same directory. Only the ROM maintainer can bundle this package;
applications cannot supply a firmware path or digest. No verified package is
currently available and no image is shipped.

Required JSON fields: `file`, `vendorId` (34), `productId` (20609), `profile`
(`p81c-suota-v1`), `targetVersion` (numeric major.minor.patch), and `sha256`
(the independently verified full image digest). The image must be nonempty,
at most 16 MiB, regular and not a symlink; the target must be newer than the pen's
read revision. A digest validates the bundled bytes, not publisher authenticity:
verify the source/model/protocol/revision before adding a PRODUCT_COPY_FILES rule.

Never reuse this image for PID12929 or another pen. The updater only implements
the inspected P81C SUOTA V1 protocol; downloaded image format/signing/recovery and
physical transfer still require validation. Success is reported only after
reconnection and exact target revision/identity verification.
