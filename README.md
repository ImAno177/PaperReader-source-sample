# PaperReader source extension sample

Status: educational API 2 sample, separate from official provider packages.
Exact DOI lookup through the Crossref REST API, returned over bounded AIDL.
The readable-document method returns `UNAVAILABLE`; this sample advertises
metadata lookup only.

## Build

Requires JDK 21, Android Platform 37.0, Build-Tools 36.1.0, and a PaperReader
checkout containing `:extension-api`. The SDK dependency is
`dev.paperreader:extension-api:0.2.0`; API 1 hosts are incompatible.

Set `PAPERREADER_SDK_PATH` to the host checkout, then run:

```powershell
.\gradlew.bat `
  "-PpaperReaderSdkPath=$env:PAPERREADER_SDK_PATH" `
  testDebugUnitTest lintDebug assembleDebug
```

CI pins the SDK to `f5ab91e089798c0c81b1c53a6e1579d45008241f`.
The APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Test with a local host

Set `PAPERREADER_HOST_SIGNER_SHA256` to the host APK certificate's SHA-256
fingerprint, then rebuild with:

```powershell
.\gradlew.bat `
  "-PpaperReaderSdkPath=$env:PAPERREADER_SDK_PATH" `
  "-PpaperReaderHostSignerSha256=$env:PAPERREADER_HOST_SIGNER_SHA256" `
  assembleDebug
```

An unset fingerprint deliberately rejects host calls. The host must also trust
the sample's package, service, API version, version code, and certificate.
Install beside a trusted PaperReader debug build and request an exact DOI.

## Boundary

The extension runs in its own Android package and UID. It never receives the
host database, private-storage paths, or credentials. Production installation
requires a verified signed-store entry and Android's user confirmation.
See the [host extension guide][extension-guide] for the full contract.

Uses the [Crossref REST API](https://www.crossref.org/documentation/retrieve-metadata/rest-api/).

Code is licensed under [Apache License 2.0](LICENSE).

[extension-guide]: https://github.com/ImAno177/PaperReader/blob/main/docs/EXTENSIONS.md
