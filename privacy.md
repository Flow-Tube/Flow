# Privacy and Permissions

Flow has no account system, no analytics or telemetry SDK, no crash reporting service, and no
advertising identifier. Nothing is uploaded to a server operated by the project. Watch history,
subscriptions, playlists, downloads, and recommendation data are stored in a local Room database
and in local DataStore preferences on the device. They leave the device only if the user explicitly
starts a backup export or a device-to-device sync over their own local network.

This document lists every permission that appears in the built APK, why it is declared, when it is
requested, and whether the app still works without it.

## Summary

| Permission | Type | Feature | Required? |
| --- | --- | --- | --- |
| `INTERNET` | install-time | All network access | Yes |
| `ACCESS_NETWORK_STATE` | install-time | Offline detection, retry policy, download constraints | Yes |
| `ACCESS_WIFI_STATE` | install-time | Local IP for DLNA casting and device sync | No |
| `CHANGE_WIFI_MULTICAST_STATE` | install-time | SSDP discovery for DLNA casting | No |
| `WAKE_LOCK` | install-time | Background playback and downloads | Yes |
| `FOREGROUND_SERVICE` | install-time | Playback and download services | Yes |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | install-time | Media3 playback service (API 34+) | Yes |
| `FOREGROUND_SERVICE_DATA_SYNC` | install-time | Download and device-sync services (API 34+) | No |
| `POST_NOTIFICATIONS` | runtime | Media notification, download progress, new-video alerts | No |
| `CAMERA` | runtime | QR code scan for device-to-device sync | No |
| `RECORD_AUDIO` | runtime | Microphone song recognition | No |
| `READ_MEDIA_VIDEO` | runtime, API 33+ | Local video browser, recovering existing downloads | No |
| `READ_MEDIA_AUDIO` | runtime, API 33+ | Local music browser, recovering existing downloads | No |
| `READ_EXTERNAL_STORAGE` | runtime, API 32 and below | Same as the two above on older Android | No |
| `WRITE_EXTERNAL_STORAGE` | runtime, API 28 and below | Writing downloads on older Android | No |
| `MANAGE_EXTERNAL_STORAGE` | special access | Saving downloads to a user-chosen public folder | No |
| `SYSTEM_ALERT_WINDOW` | special access | Fallback popup player where the ROM has no working PiP | No |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | special access | Keeping background alerts and playback alive on aggressive OEMs | No |
| `DOWNLOAD_WITHOUT_NOTIFICATION` | install-time | Legacy, no longer used, scheduled for removal | No |
| `RECEIVE_BOOT_COMPLETED` | install-time, from library | Added by `androidx.work`, reschedules background jobs after reboot | Library |
| `REQUEST_INSTALL_PACKAGES` | install-time, from library | `github` flavor only, in-app updater | Library |
| `<package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | signature | Added by `androidx.core`, the app's own signature-level permission | Library |

Everything marked "No" is optional. The app degrades to the feature being unavailable rather than
refusing to start, and none of these are requested at launch.

## The four sensitive permissions

### `CAMERA`

Used by one screen: Settings > Device Sync. Flow can copy a library between two of the user's own
devices over the local network. The session key is passed out of band by showing a QR code on one
device and scanning it with the other, so the key never travels over the wire. The camera preview
is bound only while that screen is open, frames are decoded locally by ZXing, and no image is
stored or transmitted.

Requested at: `app/src/main/java/io/github/aedev/flow/ui/screens/sync/SyncSetupContent.kt`

Users who never open Device Sync are never asked for it.

### `RECORD_AUDIO`

Used by one feature: song recognition, reachable from the recognition screen and its home-screen
widget. While the user holds the button, the app records a short microphone sample, computes an
audio fingerprint on device, and sends only that fingerprint to Shazam's public endpoint
(`amp.shazam.com`) to get the track title back. The raw recording is not written to disk and is not
uploaded. Recording stops as soon as the query is made or the screen is left. There is no
background or passive listening.

Requested at: `app/src/main/java/io/github/aedev/flow/ui/screens/recognition/RecognitionScreen.kt`

Gated at: `app/src/main/java/io/github/aedev/flow/data/recognition/MusicRecognitionRepository.kt`

### `READ_MEDIA_AUDIO`

Two uses, both local:

1. The local media browser (Library > Local media) plays music files already on the device, so the
   app works as an offline player with no network.
2. Recovering downloads. Flow can save downloads to a public folder that survives an uninstall. On
   reinstall, the download library is rebuilt by reading those files back. Audio-only downloads are
   audio files, so recovering them needs `READ_MEDIA_AUDIO` alongside `READ_MEDIA_VIDEO`.

Nothing is scanned in the background. The `MediaStore` query runs when the user opens the local
browser or triggers a download rescan, and the results stay on the device.

Requested at: `LocalMediaScreen.kt`, `DownloadsScreen.kt`, `DownloadSettingsScreen.kt`

### `SYSTEM_ALERT_WINDOW`

Used for the fallback popup player. Android's native picture-in-picture is the default path and
needs no permission. On ROMs where PiP is missing, disabled by the vendor, or broken, Flow can draw
the small floating video window itself with a `TYPE_APPLICATION_OVERLAY` window instead. The
permission is checked before that path is taken, and if it has not been granted the app stays with
native PiP or with no popup at all. It is never used to draw over other apps for any other purpose,
and no overlay exists outside an active playback session.

Checked at: `app/src/main/java/io/github/aedev/flow/player/PictureInPictureHelper.kt`,
`app/src/main/java/io/github/aedev/flow/service/VideoPlayerService.kt`

Window created at: `app/src/main/java/io/github/aedev/flow/player/PopupPlayerWindow.kt`

## The remaining permissions

### Network

`INTERNET` is needed to fetch video and music streams, metadata, thumbnails, and search results.

`ACCESS_NETWORK_STATE` backs the offline banner, the retry and backoff logic in the extractor, and
the "download on Wi-Fi only" constraint.

`ACCESS_WIFI_STATE` reads the device's own address on the local network. Two features need it:
DLNA casting, where the app runs a small local HTTP proxy and has to tell the TV which address to
pull the stream from, and Device Sync, which puts the host's LAN address into the QR code. It does
not scan for or list nearby networks, which on modern Android would require the location permission
that Flow does not declare.

`CHANGE_WIFI_MULTICAST_STATE` holds a multicast lock while searching for DLNA and UPnP renderers.
SSDP discovery is multicast, and Android drops multicast packets without this lock. The lock is
acquired when a cast search starts and released when it ends.

### Playback and services

`WAKE_LOCK` keeps the CPU alive while audio plays with the screen off and while a download runs.
Media3 also uses it internally through `setWakeMode`. The app switches between a local and a
network wake mode depending on whether playback is in the foreground, and releases the lock when
playback stops.

`FOREGROUND_SERVICE` plus `FOREGROUND_SERVICE_MEDIA_PLAYBACK` are what Android requires from API 34
onward to run the Media3 playback service that owns the media session and the media notification.

`FOREGROUND_SERVICE_DATA_SYNC` covers the two non-playback services: the download service, so long
downloads are not killed when the app is backgrounded, and the Device Sync transfer service, so a
LAN transfer survives the screen going off. Both run only while that work is actually in progress.

`POST_NOTIFICATIONS` covers the media notification with playback controls, download progress and
completion, and optional new-video alerts for subscribed channels. Declining it leaves playback and
downloads working, without their notifications.

### Storage

`READ_MEDIA_VIDEO` mirrors `READ_MEDIA_AUDIO` above for video files: the local video browser, and
recovering video downloads after a reinstall.

`READ_EXTERNAL_STORAGE` (capped at API 32) and `WRITE_EXTERNAL_STORAGE` (capped at API 28) are the
pre-Android-13 equivalents. The `maxSdkVersion` caps in the manifest mean they are not requested on
newer releases.

`MANAGE_EXTERNAL_STORAGE` is optional and off by default. Downloads go to app-private storage
unless the user opts into a custom location in Download settings, at which point Flow can write to
the public `Movies` and `Music` folders so the files survive an uninstall and are visible to other
apps. The app checks `Environment.isExternalStorageManager()` and sends the user to the system
settings page rather than assuming the grant. Users who keep the default never see the prompt.

### Battery

`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` only lets the app show the system dialog asking the user to
exempt it from Doze. It is presented in context, when the user enables new-video notifications,
because several OEM battery managers put the app in a restricted App Standby bucket and cut its
background network access, which silently starves the periodic subscription check. Declining leaves
everything working while the app is open.

Used at: `app/src/main/java/io/github/aedev/flow/notification/BackgroundWorkPolicy.kt`

### Legacy

`DOWNLOAD_WITHOUT_NOTIFICATION` is a leftover. Flow downloads through its own service and does not
enqueue anything into Android's system `DownloadManager`, so this permission has no effect. It will
be removed from the manifest.

## Permissions added by libraries

These are not declared in Flow's own manifest. They are merged in from dependencies.

`RECEIVE_BOOT_COMPLETED` comes from `androidx.work:work-runtime`. WorkManager uses it to restore
scheduled jobs after a reboot. Flow's jobs are the subscription check, the upcoming-video reminder,
the optional auto-backup, and, on the `github` flavor, the update check. Flow registers no boot
receiver of its own.

`<package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` comes from `androidx.core`. It is a
signature-level permission that the library defines under the app's own package name so
`ContextCompat.registerReceiver` can keep dynamically registered receivers non-exported. It grants
nothing to other apps and takes no capability from the system.

`REQUEST_INSTALL_PACKAGES` comes from `com.github.supersu-man:apkupdater-library` and is present in
the `github` flavor only, for the in-app updater that offers to install a new release APK. The
`foss` flavor does not include the updater library and does not carry this permission.

## Build flavors

Flow ships two flavors. The permission difference between them is the one above.

- `foss`: no in-app updater, no `REQUEST_INSTALL_PACKAGES`. This is the build intended for F-Droid
  style distribution.
- `github`: adds the in-app updater and the optional Discord presence integration.

## Network endpoints

For completeness, these are the hosts the app can contact. All are contacted directly, with no
project-operated proxy or relay in between.

- YouTube and Google: `www.youtube.com`, `m.youtube.com`, `music.youtube.com`, `i.ytimg.com`,
  `img.youtube.com`, `*.googlevideo.com`, `s.youtube.com`, `suggestqueries.google.com`,
  `suggestqueries-clients6.youtube.com`. Content, metadata, thumbnails, search suggestions.
- `api.pipepipe.dev`: remote signature helper, used only as a fallback when both local decoders
  fail on a given video.
- `music.163.com` and `api.amll.dev`: the last lyrics providers, asked only when the others have no
  lyrics for a song, and only when the user opens lyrics.
- Lyrics providers, tried in order until one answers, and only when the user opens lyrics:
  `lrclib.net`, `lyrics.kugou.com`, `mobileservice.kugou.com`, `api-lyrics.simpmusic.org`,
  `lyrics-api.boidu.dev`, `lyrics.paxsenix.org`, the `lyricsplus` mirrors,
  `lyrics-api.binimum.org`, `amp-api.music.apple.com`, `beta.music.apple.com`, `lrc.red`,
  `unison.boidu.dev`.
- `artwork.boidu.dev` and `itunes.apple.com`: animated album covers, only if the user turns them on.
  They receive the song's title, artist, album and length, and the chosen region.
- `amp.shazam.com`: song recognition, only on an explicit user request, and it receives an audio
  fingerprint rather than the recording.
- `sponsor.ajay.app` and `dearrow-thumb.ajay.app`: SponsorBlock and DeArrow, only if the user turns
  them on.
- `returnyoutubedislikeapi.com`: Return YouTube Dislike, only if the user turns it on.
- `api.github.com` and `github.com`: release check and changelog, `github` flavor only.
- `discord.com`: rich presence, `github` flavor only, and only after the user links an account.
- Local network addresses: DLNA renderers on the LAN, and the peer device during Device Sync.

## What Flow does not do

- No account, login, or user identifier of any kind.
- No analytics, telemetry, crash reporting, or advertising SDK.
- No background microphone, camera, or location access. Flow declares no location permission.
- No reading or uploading of contacts, call logs, SMS, or the installed app list.
- No sending of watch history, search history, or recommendation data anywhere. The recommendation
  engine runs entirely on device.
- No sharing of the device's media library. `MediaStore` results are read for display and playback
  and are not transmitted.
