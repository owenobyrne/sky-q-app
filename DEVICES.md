# Devices

## Sitting room Sony TV
- **Model:** Sony KDL-43W809C (BRAVIA 2015)
- **Android:** 7.0
- **IP:** 192.168.1.99
- **ADB serial:** `192.168.1.99:5555`

```bash
adb -s 192.168.1.99:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

## Bedroom Android TV
- **Model:** Google TV dongle
- **IP:** 192.168.1.218
- **ADB serial:** `adb-2803105GN198M7-fOFhlX._adb-tls-connect._tcp`

```bash
adb -s adb-2803105GN198M7-fOFhlX._adb-tls-connect._tcp install -r app/build/outputs/apk/debug/app-debug.apk
```

## Sittingroom Android TV
- **Model:** Chromecast with Google TV (sabrina)
- **Android:** 14
- **IP:** 192.168.1.140
- **ADB serial:** `adb-08101HFDD022EH-U8Gduo._adb-tls-connect._tcp`

```bash
adb -s adb-08101HFDD022EH-U8Gduo._adb-tls-connect._tcp install -r app/build/outputs/apk/debug/app-debug.apk
```
