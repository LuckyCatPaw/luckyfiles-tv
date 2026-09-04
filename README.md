<p align="center">
  <img src="app/src/main/res/mipmap-xhdpi/tv_banner.png" alt="LuckyFiles TV" width="320">
</p>

<p align="center">
  <strong>A remote friendly file manager for Android TV</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android_TV-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android TV">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square" alt="License: MIT"></a>
</p>

Built for the television rather than adapted from a touch interface. Navigation, focus handling and dialogs are designed around a directional pad, and every action is reachable with a standard remote. English and German.

## Features

- Internal storage, USB media and SMB network shares in one browser
- Copy, move, rename and delete, with multiple selection and conflict handling
- Thumbnails for images, videos, PDFs and audio, including files on a share
- Search, recent documents, sorting, folder artwork from `folder.jpg`
- Opens files in other apps and installs APKs through the system installer

## Network shares

Added through the menu at the top right. A share needs the server address and the share name; credentials are optional and guest access is a switch. The dialog tests the connection before saving. Passwords are encrypted with a key held in the Android keystore.

Files on a share behave like local ones, including copying and moving in both directions. A move inside one share is performed by the server without transferring data, and playback in another app streams from the share instead of copying first.

Not supported: **SMB1**, server discovery, and search across shares. Replacing an existing file on a share cannot be rolled back if the transfer is interrupted.

## Permissions

| Permission | Used for |
| :--- | :--- |
| `MANAGE_EXTERNAL_STORAGE` | Browsing arbitrary folders and changing files outside the app directory. The narrower media permissions cannot do this. Must be granted by the user in the system settings |
| `INTERNET` | Reaching the configured network shares, nothing else |
| `ACCESS_LOCAL_NETWORK` | Required from Android 17 on to reach a device on the local network |
| `REQUEST_INSTALL_PACKAGES` | Handing an APK to the system installer. The installation is confirmed by the user, and Android asks separately whether this app may act as a source |

## Privacy

Files are processed on the device. There is no analytics, advertising, tracking or crash reporting, and nothing is transmitted anywhere except to the servers the user configured. Settings, share configuration and generated thumbnails are stored locally and can be removed through the Android system settings.

Opening a file in another app hands that content to it, and its own behaviour then applies.

## Building

```bash
./gradlew assemblePlayDebug
```

`play` is the regular flavor. `system` adds the resource overlay for the system document picker and only makes sense for builds with system privileges, which require `MANAGE_DOCUMENTS`.

## Contributing

Bug reports and focused pull requests are welcome. A useful report names the Android version, the device, the storage source and the steps to reproduce.

## License

[MIT](LICENSE)
