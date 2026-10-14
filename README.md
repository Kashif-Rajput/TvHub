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

## 1B. Naya (version 1.3.0): "Movies & TV" aur "Web TV" ab Watcho ki website se

- **Movies & TV** aur **Web TV** tiles ab YouTube se nahi, **Watcho ki apni website (watcho.pk)** TV Hub ke andar kholti hain. Movies, shows aur Live TV channels wahi aate hain jo Watcho par hain, aur Watcho ka apna player hi chalata hai.
- **Account ki zaroorat nahi:** watcho.pk free chalti hai, seedha movie ya channel par OK dabayein.
- **Remote se chalana (screen par teer/pointer):** ▲▼◀▶ = pointer chalayein (dabaye rakhein to tez) · screen ke kinare par pahunch kar aur dabayein to page scroll hota hai · **OK = click** · OK dabaye rakhein = long-press / slider kheenchna · CH+ / CH− = poora page scroll · ⏯ = play/pause · ⏪ ⏩ = 10 second · **Back** = full screen se bahar → pichhla page → band.
- **Menu button:** Home, **Type text** (agar keyboard na khule: pehle box par OK, phir Menu → Type text), Zoom in/out, **"Open this page every time"** (jaise Live TV wala page khol kar yeh dabayein, phir **Web TV** tile seedha wahi kholegi), Desktop/Mobile website, Watcho app kholein, Sign out.
- **Tip:** Web TV tile mein ek dafa Watcho ka **Live TV** page kholein aur Menu → "Open this page every time" dabayein. Movies & TV tile mein Movies page ke saath yahi karein.
- **Watcho** tile pehle ki tarah official Watcho app kholti hai. **YouTube** tile mein koi tabdeeli nahi.
- Website ka address badalna ho to Menu → Website address…

## 1A. Naya (version 1.1.0 / 1.2.0): YouTube aur "Movies & TV" app ke andar

- **YouTube tile:** upar search box hai. OK dabayein, keyboard khulega, likh kar Search dabayein (remote mein mic ho to 🎤 Voice bhi). Results neeche aate hain. Kisi video par OK dabayein, woh **TV Hub ke andar** chalegi. Neeche scroll karne par aur videos khud aati hain. Search ke neeche Trending, Top Music, Live News, Cricket waghera ke button bhi hain.
- **Movies & TV tile (version 1.2.0, ab 1.3.0 mein Watcho website se badal di gayi):** upar 3 tabs hain: **Movies**, **Videos**, **TV Channels**. Har tab mein apna search box hai.
  - **Movies:** Hollywood, Hollywood (Hindi), Bollywood, South (Hindi Dubbed), Pakistani, Punjabi, Action, Comedy, Horror, Romantic, Cartoon Movies, Islamic History. Saal ke buttons: **Latest** (pichhle 6 mahine mein aayi), 2026, 2025 ... 2000. Search mein film ka naam likhein, saal bhi chun sakte hain.
  - **Videos:** Reality Shows, Songs, Pakistani Dramas, Indian Serials, Web Series, Comedy Shows, Talk Shows, Kids, Cooking, Naat / Bayan, Cricket, Documentaries. Har section mein pichhle 30 din ki latest videos.
  - **TV Channels:** All Live, Pakistan News, India News, World News, Sports, Religious, Entertainment, Music, Kids. Sirf jo is waqt live chal rahe hain. Search mein channel ka naam likhein (jaise Geo, ARY, PTV).
- **Player ke buttons:** OK = Play/Pause · ◀ ▶ = 10 second peeche/aage (dabaye rakhein to 30) · ▲ ▼ = info · Menu = Agli / Pichhli video / YouTube app mein kholein · CH+ / CH− = agli / pichhli · Back = band.
- Video khatam hone par list ki agli video khud chal jati hai.
- Har section aur search ki list TV mein kuch ghante ke liye yaad rehti hai, taa ke YouTube ka muft limit bache.
- Watcho aur Dodear tiles pehle ki tarah un ki **official app** kholti hain (neeche wajah).

### YouTube API key (sirf 1 dafa, muft)
Search aur sections ke liye Google ki muft "YouTube Data API key" chahiye. Is ke baghair YouTube screen par message aayega.

1. Computer par **console.cloud.google.com** kholein aur apne Gmail se login karein.
2. Upar **Select a project → New Project** → naam `TvHub` → **Create**.
3. Upar search mein **YouTube Data API v3** likhein → kholein → **Enable**.
4. Left menu: **APIs & Services → Credentials → + Create credentials → API key**. Key copy karein (yeh `AIza` se shuru hoti hai).
5. GitHub par apni **TvHub** repository kholein → **Settings → Secrets and variables → Actions → New repository secret**.
   - Name: `YT_API_KEY`
   - Secret: copy ki hui key paste karein → **Add secret**.
6. Nayi APK banayein (Actions). Key APK ke andar chali jayegi, lekin aap ki public repository mein nazar nahi aayegi.

Doosra tareeqa: TV par **Settings → YouTube API key** mein key type kar dein.

**Limit:** muft key se rozana taqreeban **100 searches** (har section ka pehla khulna bhi ek search hai). "Trending" aur "Top Music" bohat sasti hain. Limit khatam ho to agle din (Pakistan waqt taqreeban 12–1 baje dopahar) theek ho jata hai.

*Optional hifazat:* Google Cloud mein key par click → **Application restrictions → Android apps** → Package `com.tvhub.media`, SHA-1 `9D:34:E2:03:CF:8A:F2:C9:38:9D:9B:EF:E9:4A:5A:12:FD:C2:4F:D5` → Save. Phir yeh key sirf TV Hub mein chalegi.

### Kuch videos kyun nahi chalti
- Kuch channel apni video doosri apps mein chalne nahi dete. Aisi video par message aayega. OK = agli video, Menu → "YouTube app mein kholein".
- Player TV ki **Android System WebView** use karta hai. Agar "player could not start" aaye to Play Store se *Android System WebView* update karein.
- Picture quality YouTube khud internet ki speed ke hisaab se chunta hai.

### Watcho / Dodear ka content app ke andar kyun nahi
Watcho (Dish TV) ka koi public API nahi, aur us ki movies/TV channels login, subscription aur DRM ke peeche hain. Unhein kisi doosri app mein khenchna un ki hifazat todna hoga, jo hum nahi karte. Dodear ki bhi koi official API nahi hai. Is liye content ko "khencha" nahi jata. Version 1.3.0 se **Movies & TV** aur **Web TV** tiles Watcho ki apni website app ke andar kholti hain, jahan aap apne account se login karte hain aur Watcho ka apna player chalta hai (oopar 1B dekhein).

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
