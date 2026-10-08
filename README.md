# All Reader

Ücretsiz, tamamen çevrimdışı Android belge okuyucu (Kotlin + Jetpack Compose). Uygulamada **INTERNET izni yoktur**; hiçbir veri cihazdan çıkmaz.

## Özellikler

- **Formatlar:** PDF, CBZ, görseller (JPG/PNG/WebP/GIF/BMP), TXT, Markdown, DOCX, XLSX, PPTX
- Kütüphane (klasör seçerek), son okunanlar, kaldığın yerden devam, kapak küçük resimleri
- Metin içi arama, yer imleri, şifreli PDF desteği
- PDF için yakınlaştırma, gece modu, kaydırma / sayfa sayfa okuma
- TXT ve Markdown dosyalarını uygulama içinde düzenleme (kodlama ve satır sonu korunur)
- Açık / koyu / sepya temalar, yazı boyutu ayarı, tam ekran okuma
- Dosya yöneticisinden "Birlikte aç" desteği

Eski ikili Office biçimleri (`.doc`, `.xls`, `.ppt`) desteklenmez. Office dosyaları "okunabilir" düzeyde gösterilir; Word/PowerPoint'in birebir görünümü hedeflenmez.

## Derleme

Gereksinimler: JDK 21, Android SDK (compileSdk 37). `local.properties` içine `sdk.dir` yaz.

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # birim testleri
./gradlew lintDebug
```

İmzalı release için kök dizine `keystore.properties` koy (git'e girmez):

```
storeFile=C:/yol/anahtar.jks
storePassword=...
keyAlias=...
keyPassword=...
```

ve `./gradlew assembleRelease` çalıştır. Yalnızca `arm64-v8a` ABI'si paketlenir (çoğu modern telefon).

## Mimari

Her dosya iki motordan birine gider:

- **Sayfa motoru** ([MuPDF](https://mupdf.com/)): PDF, CBZ, görseller. Sayfalar bitmap olarak çizilir.
- **Akış motoru** (HTML + WebView): TXT, MD, DOCX, XLSX, PPTX. Office dosyaları ek kütüphane olmadan `ZipInputStream` + `XmlPullParser` ile HTML'e çevrilir. WebView'de JavaScript ve ağ erişimi kapalıdır.

Dosya erişimi yalnızca Storage Access Framework ile yapılır; geniş depolama izni istenmez.

## Lisans

[GNU AGPL-3.0](LICENSE). Uygulama, AGPL lisanslı MuPDF kullandığı için kaynak kodu da aynı lisansla paylaşılır.
