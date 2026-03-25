# Inkeepx Android App

A clean WebView app for inkeepx.com — no ads, no third-party SDKs.

## How to get your APK (no installs needed)

1. Create a free account at [github.com](https://github.com)
2. Create a new repository called `inkeepx-app`
3. Upload all these files (drag & drop works)
4. GitHub Actions will automatically build your APK
5. Go to **Actions** tab → click the latest build → download `inkeepx-debug-apk`

## Replacing the icon

Replace all `ic_launcher.png` and `ic_launcher_round.png` files in the `mipmap-*` folders with your own icon at the correct sizes:
- mipmap-mdpi: 48×48
- mipmap-hdpi: 72×72
- mipmap-xhdpi: 96×96
- mipmap-xxhdpi: 144×144
- mipmap-xxxhdpi: 192×192

## Installing on your phone

1. Download the APK from GitHub Actions
2. On your Android phone: Settings → Security → Enable "Install unknown apps"
3. Open the APK file and install
