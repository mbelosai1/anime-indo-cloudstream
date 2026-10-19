# Samehadaku untuk CloudStream

Ekstensi CloudStream untuk menonton anime dari https://v2.samehadaku.how

## Cara pasang
1. Buka CloudStream → Settings → Extensions → Add repository
2. Tempel URL ini:
   `https://raw.githubusercontent.com/mbelosai1/samehadaku-cloudstream/main/repo.json`
3. Install **Samehadaku**.

## Build lokal
Butuh JDK 17 dan Android SDK:

    ./gradlew make makePluginsJson

GitHub Actions membangun plugin otomatis dan menaruh `.cs3` + `plugins.json` di branch `builds`.
