# Demo signing key

`agusvr-release.p12` is a **self-signed demo key** (PKCS#12, RSA 2048, valid
until 2056) created specifically for this open project so that GitHub Actions
can produce an **installable signed release APK** without any secrets.

- Alias: `agusvr`
- Store/key password: `agusvr2026` (see `gradle.properties`)

⚠️ **Do NOT use this key to publish to Google Play or anywhere else.**
Anyone can read it. For production, generate your own key:

```bash
keytool -genkeypair -v -keystore my-release.p12 -storetype PKCS12 \
  -alias myalias -keyalg RSA -keysize 2048 -validity 10000
```

then point the `AGUS_KEYSTORE_*` properties (or GitHub Actions secrets) at it.
