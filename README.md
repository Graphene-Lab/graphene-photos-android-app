# Graphene Photos

Native Android photo synchronization app (`com.graphenelab.photosync`).

On Windows, run `.\build --help` for build shortcuts and `.\release --help` for release preparation and publishing. For example, `.\build apk` builds locally; `.\release promote 21` starts the existing workflow to promote internal versionCode 21 to production. Release dispatch needs authenticated GitHub CLI (`gh`); these shortcuts need no local Ruby or npm.

- [Android builds and releases](docs/android-builds-and-releases.md): local APK/AAB builds, version preparation, Fastlane and manual GitHub Actions publishing.
- [Login screen tests](docs/login-screen-tests.md)
- [Synchronization algorithm](SYNC_ALGORITHM.md)
