# TV Hub: halki, tez Android TV media app

Android 9 aur us se naye TV ke liye. Isay remote se poora chalaya ja sakta hai, root ki zaroorat nahi, aur original launcher hamesha wapas aa sakta hai.

> **Imaandaari ki baat:** yeh project jis computer par likha gaya, wahan Android SDK download karna mumkin nahi tha. Is liye **APK abhi tak build ya test nahi hui.** Code ko dhyan se check kiya gaya hai, lekin pehli build aap ke Android Studio mein hogi. Agar koi error aaye to uska text bhej dein, main theek kar dunga.

---

## 1. App mein kya hai

| Hissa | Kaam |
|---|---|
| **Home** | Bari tiles: YouTube, Watcho, Dodear, USB, Videos, Music, Pictures, Apps, Settings. USB ka status, Continue Watching aur Recently Played |
| **YouTube / Watcho / Dodear** | TV par install hui **official app** kholti hai. Login, DRM ya subscription ko bypass nahi karti. App na mile to Play Store mein dhundne ka option, ya "installed apps mein se chunein" (aap ki choice yaad rehti hai) |
| **USB Storage** | Drives ki list (free/total space), folder ke andar folder, Back dabane se parent folder. USB nikalne par app crash nahi hoti |
| **Videos** | Saari videos (subfolders samet), thumbnail, duration, sort (Name/Newest/Size), drive filter. Jahan chhori thi wahin se dobara shuru hoti hai |
| **Music** | Title, artist, album, cover art. Shuffle, repeat aur background playback |
| **Pictures** | Grid, full-screen viewer, zoom 1×/2×/3×, slideshow |
| **Settings** | Animations on/off, video size, extra audio decoder, permissions, home-screen mode. TV ki RAM, display aur decoders ki maloomat bhi yahin dikhti hai |

### Video quality kyun achi rahegi
- Video TV ke **hardware decoder** se chalti hai: sab se saaf picture aur sab se kam CPU use.
- Video ka asal size aur shape barqarar rehta hai ("Fit"), koi stretching nahi. Menu button se Fill ya Zoom bhi chun sakte hain.
- Android 11+ par player video ka frame-rate TV ko batata hai, taa ke jerky motion kam ho (agar TV support kare).
- Jo awaaz formats TV chip nahi chala sakti (AC3, E-AC3, DTS), unke liye **FFmpeg** sirf backup ke taur par aata hai.
- Agar koi file chal hi nahi sakti, to app saaf message deti hai. Kaala screen ya hang nahi hota.

### App halki kyun hai
- Android Views use hote hain (Compose nahi), aur koi faltu background service nahi chalti.
- Videos, Music aur Pictures ki list Android ke apne media index (MediaStore) se aati hai. Hazaron files bhi foran khulti hain.
- Thumbnails Android ke system se, sirf tile ke size mein aati hain. Scroll karte hi purani loading ruk jaati hai.
- USB check karne ke liye koi polling nahi hoti. System khud batata hai jab USB lagti ya nikalti hai.
- Is ke bawajood koi app TV ki RAM ya CPU barha nahi sakti. Bahut sasti TV par system khud bhi slow ho sakta hai.

---

## 2. APK kaise banayein (Windows / Mac / Linux)

1. **Android Studio** install karein (developer.android.com/studio). Pehli dafa khulne par yeh SDK khud download karega.
2. `TvHub` folder ko unzip karein, phir Android Studio mein **File → Open** se woh folder chunein.
3. Neeche **"Gradle sync"** khatam hone dein. Pehli dafa internet chahiye aur 5–15 minute lag sakte hain.
4. Menu se **Build → Build App Bundle(s) / APK(s) → Build APK(s)** chunein.
5. Kaam hone par **"locate"** par click karein. APK yahan milegi:
   `app/build/outputs/apk/debug/app-debug.apk`

**Release (chhoti aur tez) APK:** Build → Generate Signed App Bundle / APK → APK. Wahan apni keystore banayein aur `release` chunein.
Command line se bhi bana sakte hain: `./gradlew assembleRelease`. Yeh abhi debug key se sign hoti hai, jo sideload ke liye theek hai. Hamesha ke liye apni keystore istemal karein, aur keystore file sambhal kar rakhein.

---

## 3. TV par install kaise karein

### Tareeqa A: USB pendrive se (sab se aasaan)
1. APK pendrive mein copy karein aur pendrive TV mein lagayein.
2. TV par koi file manager kholein (bahut si TVs mein "File Manager" ya "Media Center" hota hai), aur APK par OK dabayein.
3. Agar "Unknown sources" ka message aaye: **Settings → Device Preferences → Security & restrictions → Unknown sources** mein file manager ko allow karein.

### Tareeqa B: ADB (developers ke liye, network par)
1. TV par: **Settings → Device Preferences → About → Build** par 7 dafa OK dabayein. Is se Developer options on ho jayenge.
2. **Developer options → USB debugging / Network debugging** on karein.
3. Computer par:
   ```
   adb connect <TV-ka-IP>:5555
   adb install -r app-debug.apk
   ```

---

## 4. Pehli dafa chalane par
- Videos, Music ya Pictures kholne par app **storage permission** maangegi. **Allow** karein.
- USB Storage mein drive kholte waqt agar Android ka **folder picker** khule, to drive ka root chun kar **"Use this folder" / Allow** dabayein.
- Agar TV mein folder picker hai hi nahi: **Settings → All files access** mein TV Hub ko allow karein. Yeh optional hai.

### Remote ke buttons

| Jagah | Button | Kaam |
|---|---|---|
| Har jagah | D-pad / OK | Chunna |
| Har jagah | Back | Popup band → player band → parent folder → pichhli screen |
| Home | Back 2 dafa | App band |
| Home (YouTube / Watcho / Dodear tile par) | Menu | Batayein ke kaun si installed app kholni hai |
| Video | ◀ ▶ (controls chhupe hon) | 10 second peeche/aage. Daba kar rakhein to 30 aur phir 60 second |
| Video | OK / ▲ ▼ | Controls dikhayein |
| Video | Menu | Awaaz ki zubaan, subtitles, video size |
| Video | CH+ / CH− | Agli / pichhli video |
| Music | Menu | Gaana band karke bahar niklein |
| Music | Back | Screen band, lekin gaana chalta rahega |
| Pictures | OK | Zoom |
| Pictures | ▶ / Play | Slideshow |
| Pictures | Menu | Slideshow ki speed |
| Lists | Menu | Sort badlein |

Volume ke buttons TV khud handle karta hai. App unhein nahi chhedti.

---

## 5. TV Hub ko home screen banana (optional)
1. TV Hub → **Settings → Use TV Hub as home screen → On**.
2. Agar TV "Home app" chunne ki screen dikhaye, to TV Hub chunein. Warna remote ka **Home** dabayein. Agar TV poochay, TV Hub chunein.
3. **Kuch TVs is ko allow nahi karti** (firmware lock hota hai). Aisi TV par kuch nahi badlega, aur yeh normal hai.

### Purana launcher wapas laana
- TV Hub → Settings → **Use TV Hub as home screen → Off**. Is ke baad Home dabane par purana launcher aa jayega.
- Ya TV ki **Settings → Apps → Default apps → Home app** mein purana launcher chunein.
- Ya **Settings → Apps → TV Hub → Uninstall** karein.
- ADB se: `adb shell pm disable-user --user 0 com.tvhub.media/.HomeAlias`, ya `adb uninstall com.tvhub.media`.

Yeh app system files ko nahi chhedti, is liye original launcher hamesha mehfooz rehta hai.

---

## 6. Masle aur hal (troubleshooting)

**USB nazar nahi aa rahi**
- USB nikal kar dobara lagayein, phir Refresh dabayein.
- TV ka apna file manager USB dekh pa raha hai? Agar nahi, to masla TV/USB ka hai. NTFS ya exFAT format kuch TVs support nahi karti, **FAT32** try karein.
- Videos mein file nahi aa rahi, lekin USB Storage mein folder kholne par aa rahi hai? Kuch TVs USB ko index nahi karti. USB Storage se kholein, wahan bhi chalti hai.

**Video mein awaaz nahi**
- Settings → **Extra audio decoder → On** rakhein. Yeh AC3, DTS waghaira ke liye hai.
- TV se soundbar/HDMI ARC laga hai to TV ki audio settings mein "Auto/PCM" check karein.

**Video nahi chal rahi / "TV cannot decode"**
- Settings → **Hardware video decoders** dekhein. Agar H.265 ✗ hai, to HEVC/4K videos is TV par nahi chalengi. Video ko H.264 (MP4) mein convert karein.
- 10-bit HEVC aur AV1 bahut si sasti TVs par nahi chalti. Yeh hardware ki limit hai.
- AVI ke andar purana Xvid/DivX bhi kuch TVs par nahi chalta.

**Video atak atak kar chal rahi hai**
- Pehle Settings → **Animations → Off** karein.
- Settings → **Tunneled video** On kar ke dekhein. Agar masla barh jaye to wapas Off kar dein.
- 4K ya 60fps file sasti TV par bhaari hoti hai. 1080p version try karein.
- USB 2.0 pendrive par bari bitrate wali file ruk sakti hai. File ko USB 3.0 drive ya HDD se chalayein.

**Picture ka rang ya shape ajeeb hai**
- Video par Menu → **Video size → Fit** chunein (asli shape).
- TV ki apni Picture settings (Aspect / Overscan / "Just Scan") check karein.

**App slow lag rahi hai**
- Animations Off karein.
- TV ki Settings → Apps mein faltu background apps band karein.
- TV Hub ki Settings → **Clear history and thumbnails** chalayein.

**YouTube / Watcho / Dodear "Not installed" dikha raha hai, lekin app installed hai**
- Us tile par jayein aur **Menu** dabayein, phir list mein sahi app chunein.

---

## 7. Test checklist (apni TV par)
- [ ] App 1–2 second mein khulti hai
- [ ] Har tile par focus ka safed border saaf dikhta hai
- [ ] 500+ videos wala USB: list jaldi aati hai aur scroll smooth hai
- [ ] Video chalate hue USB nikaal dein: message aaye, crash na ho
- [ ] USB dobara lagayein: Home par "USB connected" aaye, aur list Refresh ho
- [ ] Back 10 dafa jaldi jaldi dabayein: app crash na ho, Home par "press again" aaye
- [ ] Folder ke andar folder mein jayein, Back dabayein: wahi folder select mile
- [ ] Video aadhi chhor dein, dobara kholein: wahin se shuru ho, aur Home par Continue Watching mein dikhe
- [ ] MKV jis mein AC3 awaaz ho: awaaz aaye
- [ ] Kharab ya adhoori file: saaf message aaye
- [ ] Gaana chala kar Back dabayein: gaana chalta rahe, aur Music → Now Playing se control ho
- [ ] 50 bari photos ka slideshow: memory ki wajah se app band na ho

---

## 8. Project ka dhancha
```
app/src/main/java/com/tvhub/media/
  App.kt                 thumbnail loader + startup
  MainActivity.kt        screens aur Back ka nizam
  core/                  settings, focus, permissions, USB monitor
  data/                  MediaStore, drives, folders, thumbnails, resume, app launcher
  ui/home|media|browser|apps|settings   screens
  player/                video player, music player + background service
  viewer/                picture viewer
```
Libraries: AndroidX Media3 ExoPlayer 1.9.0, Jellyfin FFmpeg audio decoder, Coil 2.7.0.
