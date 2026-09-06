[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

# PaperReader source extension sample

A small out-of-process source extension that demonstrates exact DOI metadata lookup through the
PaperReader AIDL contract.

Status: educational sample. This APK is not one of PaperReader's official provider packages.

## Table of contents

- [About the project](#about-the-project)
- [Built with](#built-with)
- [Getting started](#getting-started)
- [Usage](#usage)
- [Security boundary](#security-boundary)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)
- [Contact](#contact)
- [Acknowledgments](#acknowledgments)

## About the project

The sample calls the [Crossref REST API](https://www.crossref.org/documentation/retrieve-metadata/rest-api/)
for an exact DOI and returns a bounded neutral record through the versioned PaperReader extension
API. It runs in its own Android package and Linux UID.

The service never receives the host database, private-storage paths, credentials, or global tokens.
The host keeps ownership of persistence, trust decisions, and UI rendering.

## Built with

| Area | Technology |
| --- | --- |
| Android | Kotlin, Android application module, min SDK 28, target SDK 36 |
| Contract | `dev.paperreader:extension-api:0.1.0` over versioned AIDL |
| Upstream | Crossref REST API |
| Build | Gradle wrapper, Java and Kotlin target 17 |

## Getting started

### Prerequisites

- A PaperReader checkout with the `:extension-api` module
- JDK 17 or newer
- Android SDK Platform 37

Place the PaperReader checkout in a directory named `PaperReader` under this repository, or set
`PAPERREADER_SDK_PATH` to its absolute path. The default build uses that directory:

```powershell
.\gradlew.bat :app:assembleDebug
```

For a custom checkout, set `PAPERREADER_SDK_PATH` and pass it to Gradle:

```powershell
.\gradlew.bat :app:assembleDebug `
  -PpaperReaderSdkPath=$env:PAPERREADER_SDK_PATH
```

### Configure a local host

The host accepts a service only when the sample is built with the SHA-256 certificate fingerprint of
that host. Set `PAPERREADER_HOST_SIGNER_SHA256` to the 64-character hexadecimal digest, then build:

```powershell
.\gradlew.bat :app:assembleDebug `
  -PpaperReaderSdkPath=$env:PAPERREADER_SDK_PATH `
  -PpaperReaderHostSignerSha256=$env:PAPERREADER_HOST_SIGNER_SHA256
```

When the fingerprint is not configured, the sample deliberately fails closed. The debug APK is
written to `app/build/outputs/apk/debug/app-debug.apk`.

## Usage

Install the debug APK beside a compatible PaperReader debug build that uses the same host signer.
Request an exact DOI from the host and inspect the returned metadata and provider provenance.

The service uses bounded requests and the neutral extension records. It does not become a PaperReader
provider until the host trusts its package, version, API range, exported service, and certificate.

## Security boundary

This sample demonstrates the extension boundary, not a production release process. A release
extension must be reviewed, published through a signed store entry, and verified against its package,
version, API compatibility, service component, APK digest, byte size, and signing certificate.

Do not commit API keys, downloaded papers, signing keys, or host certificate material.

## Roadmap

- Keep the sample aligned with the versioned extension API.
- Keep the exact-identifier example small enough to use as a reference implementation.

## Contributing

Keep changes focused on the sample contract or its deterministic local behavior. Verify the APK
locally before opening a pull request and update the [PaperReader extension guide][extension-guide]
when the host boundary changes.

## License

Licensed under the [Apache License 2.0](LICENSE).

## Contact

Use the [sample repository issue tracker](https://github.com/ImAno177/PaperReader-source-sample/issues)
for questions and focused improvements.

## Acknowledgments

The sample uses the [Crossref REST API](https://www.crossref.org/documentation/retrieve-metadata/rest-api/)
and the [PaperReader extension guide][extension-guide].

[extension-guide]: https://github.com/ImAno177/PaperReader/blob/main/docs/EXTENSIONS.md
