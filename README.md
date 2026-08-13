# PaperReader source extension sample

This repository is a minimal out-of-process PaperReader source extension. It
queries the real [OpenAlex API](https://docs.openalex.org/) and returns bounded
neutral paper records through the versioned PaperReader AIDL contract.

The extension runs in its own Android package and UID. It never receives
PaperReader's database, private paths, credentials, or global tokens.

## Build

Clone `PaperReader` into this repository as `PaperReader`, or provide its path
explicitly:

```bash
./gradlew :app:assembleDebug -PpaperReaderSdkPath=/path/to/PaperReader
```

For a local runtime test, also pass the SHA-256 certificate fingerprint of the
PaperReader build that is allowed to bind the service:

```bash
./gradlew :app:assembleDebug \
  -PpaperReaderSdkPath=/path/to/PaperReader \
  -PpaperReaderHostSignerSha256=<64-hex-sha256>
```

The default fingerprint is deliberately invalid, so an unconfigured sample
fails closed.

The sample is intentionally not trusted by PaperReader releases. A production
extension must be signed, published through a reviewed index entry, and matched
by package, version, API level, service component, and certificate SHA-256
before the host will bind it.

See the [PaperReader extension guide][extension-guide] for the contract limits
and host-side development properties.

Licensed under the [Apache License 2.0](LICENSE).

[extension-guide]: https://github.com/ImAno177/PaperReader/blob/main/docs/EXTENSIONS.md
