# Anime Indo untuk CloudStream

Ekstensi CloudStream untuk nonton anime sub indo dari:
- **Samehadaku**: https://v2.samehadaku.how
- **Otakudesu**: https://otakudesu.blog

## Cara pasang
1. Buka CloudStream → Settings → Extensions → Add repository
2. Tempel URL ini:
   `https://raw.githubusercontent.com/mbelosai1/anime-indo-cloudstream/main/repo.json`
3. Install **Samehadaku** dan/atau **Otakudesu**.
4. Kalau sumbernya tidak muncul di halaman utama: Settings → Providers → Extension languages → centang **Indonesian**.

## Build lokal
Butuh JDK 17 dan Android SDK:

    ./gradlew make makePluginsJson

GitHub Actions membangun plugin otomatis dan menaruh `.cs3` + `plugins.json` di branch `builds`.
