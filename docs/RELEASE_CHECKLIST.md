# Release Checklist

- [ ] Source diff check
- [ ] Build succeeds
- [ ] SHA-256 recorded
- [ ] Preflash audit PASS=7 FAIL=0
- [ ] No probe or secret-debug markers
- [ ] SELinux Enforcing verified
- [ ] No Weaver write or vendor getConfig call
- [ ] KeyMint fail-closed guards present
- [ ] Clean image boot test
- [ ] Owner PIN decrypt test
- [ ] Internal Storage test
- [ ] Stock Android reboot/unlock test
- [ ] Supported firmware list by region/build
- [ ] Known limitations reviewed
- [ ] Release SHA recorded
