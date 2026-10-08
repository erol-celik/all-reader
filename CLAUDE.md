# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Proje

Kişisel kullanım için ücretsiz, tamamen çevrimdışı Android belge okuyucu (Kotlin + Jetpack Compose, tek modül `app`, paket `com.erol.allreader`). Mağazada yayınlanmaz; APK doğrudan telefona kurulur.
## Komutlar

Windows'ta `./gradlew` (Git Bash) veya `.\gradlew.bat` (PowerShell). JDK 21 ve Android SDK (`C:\Android\Sdk`, `local.properties` içinde) makinede kurulu; `local.properties` git'e girmez.

- Derle: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Telefona kur: `./gradlew installDebug` (veya `adb install -r <apk>`)
- Birim testleri: `./gradlew testDebugUnitTest`; tek test: `./gradlew testDebugUnitTest --tests "*DocxConverterTest*"`
- Lint: `./gradlew lintDebug`

Ağ yavaş (~20–30 KB/s); ilk derleme bağımlılık indirmesi nedeniyle uzun sürer, `~/.gradle` önbelleğini silme.

## Mimari (hedef)

Her dosya formatı iki motordan birine gider (`engine/DocumentFormat.kt` eşler):

- **Sayfa motoru** (MuPDF `com.artifex.mupdf:fitz`): PDF, CBZ, görseller. Sayfalar Bitmap olarak çizilir; arama ve zoom bu motorda.
- **Akış motoru** (HTML'e çevir + WebView): TXT, MD, DOCX, XLSX, PPTX. Her format `HtmlConverter` arayüzüyle HTML'e çevrilir. Office dosyaları ek kütüphane olmadan `ZipInputStream` + `XmlPullParser` ile ayrıştırılır (Apache POI kullanılmaz). Eski ikili `.doc/.xls/.ppt` desteklenmez.

### Sayfa motoru ayrıntıları (`engine/page/`, `ui/reader/PageReaderScreen.kt`)

- MuPDF thread-safe değildir: tüm belge işlemleri `mupdfDispatcher` (tek iş parçacığı) üzerinde yapılır. Yeni MuPDF çağrısı yazarken `withContext(mupdfDispatcher)` kullan.
- Zoom düzen tabanlıdır: sayfa listesinin genişliği `viewport * zoom` olur ve bitmap'ler o genişlikte yeniden çizilir; pinch sırasında yalnızca `graphicsLayer` ile görsel ölçek uygulanır, bırakınca `commitZoom` kalıcı yapar. Geometri `PageLayoutMath` içindedir (saf Kotlin, birim testli).
- Kapak küçük resimleri `CoverRepository` ile `cacheDir/covers` altında; üretilemeyenler `.none` işaretçisiyle tekrar denenmez.
- Parola alanında `KeyboardType.Password` kullanma (parola yöneticisi tetiklenir).

### Akış motoru ve düzenleme ayrıntıları (`engine/reflow/`, `ui/reader/Reflow*`)

- `TextCodec` TXT/MD kodlamasını (BOM, UTF-8, UTF-16, yoksa Windows-1254) ve satır sonunu algılar; editör metni `\n` ile normalize tutar, kaydederken özgün kodlama/satır sonu/BOM geri uygulanır. Kodlanamayan karakter varsa `NotEncodable` döner (UTF-8'e geçiş kullanıcıya sorulur).
- `TextFileStore.save`: önce `lastModified` çakışma kontrolü, yedek (`cacheDir/backups`), `"wt"` ile yazma, boyut doğrulaması, hata olursa yedekten geri yükleme. Taslaklar `cacheDir/drafts` altında (düzenleme sırasında 1 sn debounce ile).
- Düzenleme yalnızca TXT/MD ve ≤ 2 MB için; yazma izni `LibraryRepository.persistFolder` ile `READ|WRITE` alınır, eski (yalnız okuma) klasörlerde klasörü yeniden seçtirme akışı vardır (`ReflowDialog.NeedWritePermission`).
- WebView: JS kapalı, `blockNetworkLoads`, bağlantılara tıklanınca gezinme yok. Konum geri yükleme içerik yüksekliği oturana kadar yeniden dener (`restoreWhenStable`). Markdown `escapeHtml(true)` + `sanitizeUrls(true)` ile işlenir.
- Office dönüştürücüleri `engine/reflow/office/`: `OfficePackage` ZIP'i bellekte açar (girdi/toplam boyut sınırlı), `XmlNode` hafif DOM kurar (eleman öneki atılır, öznitelikler ham ad `w:val`). Hatalar `OfficeFormatException` (Türkçe mesaj, doğrudan kullanıcıya gösterilir). JVM birim testleri `kxml2` test bağımlılığına dayanır.
- Arama/yer imi: sayfa motorunda durum `PageReaderViewModel` içinde (`search`, `jumps` kanalı, `currentPage`); okuyucu `jumps`'ı toplayıp `LazyListState`'i kaydırır. Akış motorunda `WebController` WebView'ü ekran düzeyinden yönetir. Yer imi `page >= 0` ise sayfa, değilse `ratio` (akış). Room şema değişikliğinde `app/schemas` güncellenir ve `AutoMigration` kullanılır.
- Okuyucu ayarları: gece modu/okuma modu `SettingsRepository` (DataStore). Tam ekran `FullscreenEffect` (ReaderChrome.kt). "Birlikte aç" kayıtları `LibraryRepository.EXTERNAL_ROOT` köküyle tutulur; tarama bu kayıtları listeleme dışı bırakır, yalnızca erişilemeyenleri siler.
- `BasicTextField(state)` ve `undoState` deneysel Compose API'leridir (`@OptIn(ExperimentalFoundationApi)`).

## Test dosyaları

Telefonda `/sdcard/AllReaderTest` uç durum dosyalarını içerir (bitince silinecek). adb ile ekran otomasyonunda: Git Bash'te `/sdcard/...` yolları için `MSYS_NO_PATHCONV=1` gerekir; `input text` içinde `.` sonrası klavye boşluk ekleyebilir; kütüphanedeyken Geri tuşu uygulamadan çıkarır.

## Değişmez kısıtlar

- `INTERNET` izni olmamalı. Manifest'te `tools:node="remove"` ile zorla silinir; bir kütüphane geri eklerse derlenmiş APK'da `aapt2 dump permissions` ile doğrula.
- WebView: JavaScript kapalı, `blockNetworkLoads = true`; içerik `loadDataWithBaseURL` ile yüklenir.
- Dosya erişimi yalnızca Storage Access Framework (`OPEN_DOCUMENT_TREE` + kalıcı izin); `MANAGE_EXTERNAL_STORAGE` isteme.
- Sadece `arm64-v8a` ABI'si paketlenir (`app/build.gradle.kts`).
- MuPDF AGPL lisanslıdır; uygulama paylaşılırsa kaynak kodu da açılmalı.
- Bağımlılık sürümleri yalnızca `gradle/libs.versions.toml` içinde tutulur. Hilt yok; bağımlılıklar elle `AppContainer` ile verilir.
- AGP 9: Kotlin yerleşik olduğundan `kotlin-android` eklentisi kullanılmaz.

## Git

Commit mesajlarına ve PR açıklamalarına Claude/Anthropic atıf satırı ekleme (global kural).
