# Memory Lane

Android mobil uygulaması ve Java HTTP backend için ortak repo.

- `mobile/`: Java 17, Android SDK 35 ve AGP 8.7.3 uygulama iskeleti.
- `backend/`: Java 17, Spring Boot 4.1.1 ve gömülü Tomcat.
- `docs/`: kalıcı mimari kararlar ve Git çalışma biçimi.

## İlk kurulum

1. Repoyu klonlayın, JDK 17 kurun ve `java -version` ile kontrol edin.
2. Android Studio ile Android SDK Platform 35 kurun. Lisanslar kabul edilmişse Gradle eksik SDK bileşenlerini de indirebilir.
3. `mobile/local.properties.example` dosyasını `mobile/local.properties` olarak kopyalayıp Windows/macOS/Linux için kendi SDK yolunuzu girin veya `ANDROID_HOME` tanımlayın.
4. Backend varsayılan ayarlarla çalışır. Yerel değişiklik gerekiyorsa `backend/config/application-local.properties.example` dosyasını aynı dizinde `application-local.properties` olarak kopyalayın. Bu dosya kaynak klasörlerinin dışındadır.

PowerShell, repo kökünden:

```powershell
cd backend
.\gradlew.bat test bootJar
# Yalnızca harici yerel ayarları kullanmak istediğinizde:
$env:SPRING_PROFILES_ACTIVE = 'local'
.\gradlew.bat bootRun
```

Komutları backend dizininden çalıştırın. Farklı çalışma dizininde harici config konumunu `SPRING_CONFIG_ADDITIONAL_LOCATION` ile belirtin. Local profil etkin değilse `application-local.properties` okunmaz. Gerçek parolaları örneğe yazmayın; ortam değişkenleri kullanın.

Sunucu varsayılan olarak 8080 portunda açılır. Henüz API endpoint'i yoktur; `/` için 404 beklenir. Test, rastgele portta gerçek sunucu açılışını doğrular; ayrı test harici local ayarların yalnızca doğru profilde yüklendiğini kontrol eder.

Mobil için tekrar repo kökünden başlayın:

```powershell
cd mobile
.\gradlew.bat assembleDebug lintDebug testDebugUnitTest
```

macOS/Linux üzerinde `gradlew.bat` yerine `./gradlew` kullanın. Backend wrapper Gradle 8.14.3, mobil wrapper Gradle 8.9 indirir ve SHA-256 doğrular; global Gradle kurulumu gerekmez. Build'ler bağımsızdır; yalnızca çalıştırdığınız modülün araçları gerekir.

Hedef mimaride anıların asıl kaydı backend'de tutulacak; mobil oturum anahtarları yedeklere dahil edilmeyecek. Bu veri saklama ve senkronizasyon akışı henüz uygulanmadı; ayrıntılar mimari belgesindedir.

Mobil modül henüz Activity veya işlevsel ekran içermez. APK derlemesi kullanılabilir uygulama kabul testi değildir. Mobil test kaynağı henüz yoksa `testDebugUnitTest` adımı NO-SOURCE döner.

SDK yolları, parolalar, `.env`, imzalama anahtarları ve yerel ayarlar commitlenmez. Yanlışlıkla classpath altına konan application-local.properties/yml/yaml dosyaları da paketlemeden dışlanır.

Ayrıntılar: [mimari](docs/architecture.md), [Git çalışma biçimi](docs/git-workflow.md). Anlık doğrulama sonuçlarının kaynağı ilgili PR açıklaması ve o commit için GitHub Actions kontrolleridir; repodaki belgeler kalıcı kurulum bilgilerini içerir.

## İsteğe bağlı veritabanı profili

Varsayılan açılış datasource oluşturmaz ve migration çalıştırmaz; yerel veritabanı kurulması gerekmez. Ekip veritabanı hazır olduğunda DB_HOST, DB_PORT, DB_NAME, DB_USER ve DB_PASSWORD ortam değişkenlerini verip `database` profilini etkinleştirin. Profil yalnız mevcut PostgreSQL şemasını doğrular; tablo veya veritabanı oluşturmaz. Şema/migration teslimi ve bu profille bağlantı doğrulaması ayrıca tamamlanmalıdır.

Harici yerel dosya ile birlikte kullanım: backend dizininde `.\gradlew.bat bootRun --args="--spring.profiles.active=local,database"`. `backend/.env.example` değişken örneğidir; otomatik yüklenmez. Bağlantı bilgisi gelene kadar yalnız `local` profili veya varsayılan profil kullanılabilir.
