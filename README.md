# share2s3

Share files directly to S3 (or S3-compatible services like [Garage](https://garagehq.deuxfleurs.fr/)) from Android.

Optionally, copy the public URL once the upload is complete.

## Features

- Appears as **Upload to S3** in the Android share sheet for any file type. Handles single files,
  multiple files, and plain text (uploaded as a `.txt` file).
- Settings screen for the endpoint, region, bucket, access key and secret key, plus a **Test connection** button.
- Configurable object key template, e.g. `{random}/{filename}` or `photos/{date}/{uuid}.{ext}`.
- Optionally copies a link to the uploaded file to the clipboard. The link comes from a configurable template, so it
  can point at a CDN, Garage's web endpoint, or a reverse proxy instead of the S3 API.
- No AWS SDK. The app has a small SigV4 implementation (tested against AWS's published examples and a real Garage
  server) and uses OkHttp for requests.

## Installing

Download the APK from the [latest release](../../releases/latest) and open it on your phone. You will need to allow
installing apps from unknown sources.

## Setting up with Garage

Create a bucket and a key for the app:

```sh
garage bucket create share
garage key create share2s3-phone
garage bucket allow --read --write share --key share2s3-phone
```

Then open Share2S3 and fill in:

| Setting            | Example                                        |
|--------------------|------------------------------------------------|
| Endpoint           | `https://s3.example.com` (your `s3_api` address) |
| Region             | `garage` (the `s3_region` in `garage.toml`)    |
| Bucket             | `share`                                        |
| Access key ID      | `GK…` from `garage key create`                 |
| Secret key         | from `garage key create`                       |
| Path-style URLs    | on (turn off only if you configured `root_domain` for the S3 API) |

Tap **Test connection** to check everything works.

### Public links

With **Public URL template** left empty, the copied link is the S3 URL, e.g.
`https://s3.example.com/share/abc12345/photo.jpg`. That only works if the object is publicly readable.

To serve uploads publicly with Garage, enable website access for the bucket:

```sh
garage bucket website --allow share
```

Then set the template to match how the bucket is exposed, for example:

- `https://share.web.example.com/{key}` (Garage's `s3_web` endpoint with `root_domain = ".web.example.com"`)
- `https://files.example.com/{key}` (a domain or reverse proxy pointing at the bucket)

Template placeholders:

| Placeholder  | Meaning                                                |
|--------------|--------------------------------------------------------|
| `{key}`      | URL-encoded object key, e.g. `abc12345/my%20photo.jpg` |
| `{bucket}`   | Bucket name                                            |
| `{filename}` | URL-encoded last part of the key                       |

### Object key template

Controls where uploads are stored in the bucket. The default is `{random}/{filename}`.

| Placeholder                        | Example                        |
|------------------------------------|--------------------------------|
| `{filename}`                       | `holiday_photo.jpg` (spaces become `_`) |
| `{name}` / `{ext}`                 | `holiday_photo` / `jpg`        |
| `{random}`                         | `k3v9x0qa` (8 characters)      |
| `{uuid}`                           | `3f2a…`                        |
| `{date}`                           | `2024-03-09` (UTC)             |
| `{year}` / `{month}` / `{day}`     | `2024` / `03` / `09`           |
| `{timestamp}`                      | Unix seconds                   |

## Security notes

- Credentials are stored in the app's private preferences. Backups and device-to-device transfer are disabled for
  the app, so the secret key stays on the device.
- Plain `http://` endpoints are allowed, since Garage is often run on a LAN. Prefer `https://` if the endpoint is
  reachable from the internet.
- Give the app a key that can only write to one bucket.

## Development

Requirements: JDK 17+ and the Android SDK (set `ANDROID_HOME` or create `local.properties` with `sdk.dir=…`).

```sh
./gradlew testDebugUnitTest   # unit + Robolectric tests
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug
```

The Garage integration tests are skipped unless a server is available. To run them, start a throwaway
single-node Garage (the script downloads the binary):

```sh
eval "$(scripts/garage-test-server.sh)"
./gradlew testDebugUnitTest
kill "$GARAGE_PID"
```

### Layout

- `app/src/main/java/.../s3/` holds the SigV4 signer, bucket config and a minimal S3 client. This code has no Android dependencies.
- `app/src/main/java/.../upload/` holds settings, the key and URL templates, and the uploader.
- `ShareActivity` / `ShareViewModel` receive share intents and show upload progress.
- `SettingsActivity` is the launcher activity and settings screen.

## CI and releases

`.github/workflows/build.yml` runs on every push and pull request. It runs the tests (including against a real
Garage server) and lint, builds a debug APK, and uploads the APK as a workflow artifact.

To publish a release, push a tag:

```sh
git tag v1.0.0
git push origin v1.0.0
```

This builds a minified release APK and attaches it to a GitHub release for the tag, with generated release notes.

### Release signing

Android only installs an update if it is signed with the same key as the installed version, so set up a permanent
signing key before your first release:

```sh
keytool -genkeypair -v -keystore release.jks -alias share2s3 -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks   # copy the output
```

Add these repository secrets (Settings → Secrets and variables → Actions):

| Secret                    | Value                           |
|---------------------------|---------------------------------|
| `SIGNING_KEYSTORE_BASE64` | base64-encoded `release.jks`    |
| `SIGNING_STORE_PASSWORD`  | keystore password               |
| `SIGNING_KEY_ALIAS`       | `share2s3`                      |
| `SIGNING_KEY_PASSWORD`    | key password                    |

Without these secrets the release APK is signed with the CI runner's throwaway debug key. It still installs, but each
release has a different key, so you have to uninstall the old version before installing a new one.
