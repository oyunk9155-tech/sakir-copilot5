# Şakir — Motosikletçi için sesli yapay zeka yardımcısı (Android)

Jarvis tarzı sesli asistan. Beyni seçtiğin yapay zeka servisi (Gemini, ChatGPT/OpenAI veya Claude), ellerin ise telefonun: uygulama açar, müzik/YouTube yönetir, Google Haritalar'da motosiklet rotası başlatır, arar, WhatsApp mesajı gönderir, alarm kurar, hava durumunu söyler, hızını ve viraj yatış açını bildirir. "Şakir" demeden hiçbir şey yapmaz.

> Bu proje yazıldı ancak ilk derleme ve gerçek telefon testi henüz yapılmadı. İlk derlemede küçük bir hata çıkarsa hata mesajını geliştiriciye (Claude'a) gönder, düzeltilir.

## 1. APK'yı derle (bilgisayar gerekmez, GitHub yeter)

1. github.com'da ücretsiz hesap aç, yeni bir **private** depo oluştur (ör. `sakir-copilot`).
2. Bu klasörün tüm içeriğini depoya yükle. `.github` klasörü gizli olduğu için web'den sürükle-bırak bazen atlar; en güvenlisi git ile:

```
cd sakir-copilot
git init -b main
git add .
git commit -m "Sakir"
git remote add origin https://github.com/KULLANICI_ADIN/sakir-copilot.git
git push -u origin main
```

3. Depoda **Actions** sekmesine gir → **Build APK** → çalışmasını bekle (yaklaşık 5-8 dakika).
4. Bittiğinde çalışmanın altındaki **Artifacts** bölümünden **Sakir-APK** dosyasını indir. İçindeki `app-debug.apk` senin uygulaman.

Alternatif: Android Studio ile klasörü açıp **Build → Build APK(s)** de yapabilirsin.

## 2. Telefona kur

APK dosyasını telefona gönder, aç, "bilinmeyen kaynaklardan yükleme" iznini ver ve kur. Bu bir debug sürümüdür; kişisel kullanım içindir, Play Store için değildir.

## 3. İlk kurulum (bir kez)

1. Uygulamayı aç → **AYARLAR** → yapay zeka servisini seç ve API anahtarını gir:
   - **Google Gemini** (ücretsiz katman var): [aistudio.google.com](https://aistudio.google.com) üzerinden "Get API key" ile alınır. Model adı boşsa `gemini-3.8-flash` kullanılır; çalışmazsa AI Studio'daki güncel bir Flash model adını yaz.
   - **OpenAI / ChatGPT API:** ChatGPT aboneliği API'yi içermez; API ayrıca platform.openai.com'dan kredi yükleyerek kullanılır. Model adı boşsa `gpt-4o-mini` denenir; çalışmazsa hesabındaki güncel bir model adını yaz.
   - **Claude:** [console.anthropic.com](https://console.anthropic.com) üzerinden alınır; kullanım başına ücretlendirilir.
2. **İZİNLER** → hepsine izin ver (mikrofon, konum, telefon, rehber, bildirim).
3. **ÖZEL İZİN** menüsünden üçünü de aç:
   - Diğer uygulamaların üzerinde göster (arka plandayken uygulama açabilmesi için şart)
   - Erişilebilirlik → "Şakir erişim" (WhatsApp'ta gönder'e basma, YouTube'da ilk videoyu açma, ekrana dokunma)
   - Pil optimizasyonunu kapat (arka planda kapanmasın)
4. **BAŞLAT**'a bas. Bildirimde "Şakir dinliyor" görünür.
5. İsteğe bağlı gerçekçi ses: ElevenLabs hesabından API anahtarı ve bir ses kimliği (voice id) girersen Şakir o sesle konuşur; girmezsen telefonun Türkçe sesini kalın perdeyle kullanır.

## 4. Kullanım örnekleri

- "Şakir, YouTube'u aç." → "Şakir, şunu ara: Ducati Panigale egzoz sesi." → "Şakir, sıradaki videoyu aç."
- "Şakir, müzik aç." (son çalınan) · "Şakir, Yol Şarkıları çalma listesini aç." · "Şakir, şarkıyı durdur / başlat."
- "Şakir, yoruldum." → mola/akaryakıt için rota önerir veya başlatır.
- "Şakir, evin konumunu aklında tut." (evdeyken) → sonra "Şakir, eve dönüş rotası oluştur."
- "Şakir, hızımız kaç?" · "Şakir, yolculuk bilgilerini söyle." · "Şakir, motoru kalibre et." (motor dikken) · "Şakir, son virajda yatış açım nasıldı?"
- "Şakir, Ahmet'e WhatsApp'tan 'yoldayım, 20 dakikaya oradayım' yaz." · "Şakir, Ayşe'yi ara."
- Biri aradığında: "Efendim, Ahmet arıyor. Açayım mı?" → "evet" / "hayır".
- "Şakir, yarın sabah 7'ye alarm kur." · "Şakir, 10 dakikalık sayaç başlat." · "Şakir, kronometreyi başlat."
- "Şakir, sesi yüzde 60 yap." · "Şakir, saat kaç, pil durumu ne?" · "Şakir, Ankara'ya giderken yolda hava nasıl?"
- Birden fazla komut: "Şakir, Spotify'ı aç, sesi kıs ve eve rota oluştur."

## 5. Bilinmesi gereken sınırlar (dürüst liste)

- **Uyandırma sözü:** Telefonun konuşma tanıma motoruyla sürekli dinleyerek yapılır; internet ister, pil tüketir, bazı telefonlarda hafif tıkırtı/bip olabilir ve Android mikrofon kullanımını gösterir. Daha verimli çevrimdışı uyandırma sözü sonraki sürümde eklenebilir.
- **"Tüm uygulamalar":** Android, bir uygulamanın başka uygulamayı tam kontrol etmesine izin vermez. Şakir uygulamaları açar, medya tuşlarını kullanır, erişilebilirlikle metne dokunur ve ekranı okur. WhatsApp ve YouTube otomasyonları uygulama güncellemeleriyle bozulabilir.
- **Spotify:** Son çalınanı başlatma medya tuşuyla yapılır; ada göre çalma listesi/şarkı çalma Spotify'ın Android'deki "aramadan çal" desteğine bağlıdır, bazı cihazlarda yalnızca arama açılır.
- **Web araması:** Şimdilik yalnızca Claude seçiliyken çalışır; Gemini ve OpenAI seçiliyken internetten güncel bilgi aratma yok (hava durumu ayrı çalışır).
- **Yatış açısı:** Yaklaşık değerdir (birkaç derece sapma olabilir). Telefon motora sabit takılı olmalı, motor dikken kalibre edilmeli (40 km/sa'yı geçince ilk seferde otomatik kalibre olur). Bir spor ölçüm cihazı değildir.
- **Yol koşulları:** Güzergâh hava durumu Open-Meteo'dan (ücretsiz, anahtarsız) gelir; trafik ve yol durumu Google Haritalar'da kalır.
- **Arama açma/reddetme:** Bazı üreticilerin Android sürümlerinde kısıtlı olabilir.
- **Gizlilik:** Söylediklerin Google konuşma tanıma ve seçtiğin yapay zeka servisine gider (Gemini'nin ücretsiz katmanında Google bu verileri model geliştirmede kullanabilir); API anahtarı telefonda şifrelenmeden saklanır. Hafıza telefonundadır.
- **Güvenlik:** Sürerken ekrana dokunma, her şeyi sesle yap; yerel trafik kurallarına uy. Kask interkomu/Bluetooth kulaklık önerilir.
