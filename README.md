# Memory Lane

Android mobil uygulaması ve Java HTTP backend için ortak repo.

- `mobile/`: Java 17, Android SDK 35 ve AGP 8.7.3 uygulama iskeleti.
- `backend/`: Java 17, Spring Boot 4.1.1 ve gömülü Tomcat.
- `docs/`: kalıcı mimari kararlar ve Git çalışma biçimi.

## Veri modeli ve API

Hesap, oturum, kullanıcıya özel anı ve fotoğraf işlemlerinin ortak alanları, endpointleri, hata yanıtları ve tekrar deneme kuralları [ML-03 API sözleşmesinde](docs/api-contract.md) tanımlıdır. Bu belge geliştirme sözleşmesidir; endpointlerin uygulanmış olduğunu göstermez.

## Ekip rol kodları

Kartlar ve belgelerde K1 hesap/oturum backend'i ve anı oluşturmayı; K2 fotoğraf akışı ve kayıt/giriş mobil ekranlarını; K3 mobil oturum, liste, detay, düzenleme ve aramayı; K4 veritabanı altyapısı, sahiplik, harita ve silme/transaction işlerini ifade eder. Bunlar ekip içi rol kodlarıdır. İş API'si `/api/v1` sözleşmesini izler; mevcut `/api/health` altyapı yolu ayrıdır.

## İlk kurulum

1. Repoyu klonlayın, JDK 17 kurun ve `java -version` ile kontrol edin.
2. Android Studio ile Android SDK Platform 35 kurun. Lisanslar kabul edilmişse Gradle eksik SDK bileşenlerini de indirebilir.
3. `mobile/local.properties.example` dosyasını `mobile/local.properties` olarak kopyalayıp Windows/macOS/Linux için kendi SDK yolunuzu girin veya `ANDROID_HOME` tanımlayın. Uygulama emülatörde varsayılan olarak `http://10.0.2.2:8080/` adresine bağlanır; gerçek telefonda aynı dosyaya `memorylane.apiBaseUrl=http://<bilgisayarın yerel IP adresi>:8080/` yazın.
4. Backend PostgreSQL gerektirir. Docker Desktop kurun, ardından `backend/.env.example` dosyasını `backend/.env` olarak kopyalayıp `DB_PASSWORD` değerini değiştirin ve `backend` dizininde `docker compose up -d` çalıştırın. `docker compose ps` çıktısında `(healthy)` görünmelidir. Uygulama `backend` dizininden çalıştırıldığında aynı `.env` dosyasını kendisi okur; şifre tek yerde tutulur. Gerçek ortam değişkenleri `.env` değerlerinin önüne geçer. Şifrenin varsayılanı yoktur.
5. Backend diğer ayarlarda varsayılanlarla çalışır. Yerel değişiklik gerekiyorsa `backend/config/application-local.properties.example` dosyasını aynı dizinde `application-local.properties` olarak kopyalayın. Bu dosya kaynak klasörlerinin dışındadır.

PowerShell, repo kökünden:

```powershell
cd backend
docker compose up -d
.\gradlew.bat test bootJar
# Yalnızca harici yerel ayarları kullanmak istediğinizde:
$env:SPRING_PROFILES_ACTIVE = 'local'
.\gradlew.bat bootRun
```

Komutları backend dizininden çalıştırın. Farklı çalışma dizininde harici config konumunu `SPRING_CONFIG_ADDITIONAL_LOCATION` ile belirtin. Local profil etkin değilse `application-local.properties` okunmaz. Gerçek parolaları örneğe yazmayın; ortam değişkenleri kullanın.

Sunucu varsayılan olarak 8080 portunda açılır. Açılışta Flyway bekleyen migration'ları uygular; boş veritabanı bu şekilde kurulur. `GET http://localhost:8080/api/health` veritabanı erişilebilirse 200 döner. Veritabanı kapalıysa uygulama açılmaz ve ne yapılacağını yazar. Veritabanı testleri Docker ile tek bir geçici PostgreSQL container'ı açar. Docker yoksa bu testler yalnızca yerelde atlanır; CI'da (`CI` ortam değişkeni varken) hiçbir zaman atlanmaz, Docker yoksa build başarısız olur. Gradle 8.14.3, JDK 25 ile çalışmaz; `JAVA_HOME` JDK 17'yi göstermelidir.

Mobil için tekrar repo kökünden başlayın:

```powershell
cd mobile
.\gradlew.bat assembleDebug lintDebug testDebugUnitTest
```

macOS/Linux üzerinde `gradlew.bat` yerine `./gradlew` kullanın. Backend wrapper Gradle 8.14.3, mobil wrapper Gradle 8.9 indirir ve SHA-256 doğrular; global Gradle kurulumu gerekmez. Build'ler bağımsızdır; yalnızca çalıştırdığınız modülün araçları gerekir.

Hedef mimaride anıların asıl kaydı backend'de tutulacak; mobil oturum anahtarları yedeklere dahil edilmeyecek. Bu veri saklama ve senkronizasyon akışı henüz uygulanmadı; ayrıntılar mimari belgesindedir.

Mobil uygulama alt sekmeli ana ekranla açılır; Anılar sekmesi `GET /api/health` sonucunu gösterir. Sunucu kapalıysa veya adres yanlışsa bağlantı hatası, veritabanı kapalıysa veritabanı hatası "Tekrar dene" ile gösterilir. Mobil mimari ve ortak bileşenler [mimari belgesinde](docs/architecture.md#mobil-mimari-ml-28) anlatılır.

SDK yolları, parolalar, `.env`, imzalama anahtarları ve yerel ayarlar commitlenmez. Yanlışlıkla classpath altına konan application-local.properties/yml/yaml dosyaları da paketlemeden dışlanır.

Ayrıntılar: [mimari](docs/architecture.md), [Git çalışma biçimi](docs/git-workflow.md). Anlık doğrulama sonuçlarının kaynağı ilgili PR açıklaması ve o commit için GitHub Actions kontrolleridir; repodaki belgeler kalıcı kurulum bilgilerini içerir.

## Veritabanı ve profiller

Backend her profilde PostgreSQL'e bağlanır ve açılışta Flyway migration'larını uygular; ayrı bir `database` profili yoktur. Bağlantı bilgileri yukarıdaki kurulum adımında anlatılan `backend/.env` dosyasından veya ortam değişkenlerinden gelir. Harici yerel ayarlarla çalıştırmak için backend dizininde: `.\gradlew.bat bootRun --args="--spring.profiles.active=local --spring.config.additional-location=optional:file:./config/"`.

## CI raporları

GitHub Actions içindeki Verify çalıştırmasının Artifacts bölümünden `backend-reports` ve `mobile-reports` raporlarına ulaşabilirsiniz. Test ve lint raporları, ilgili adım başarısız olsa da 14 gün saklanır. Derleme rapor üretemeden durursa artifact oluşmayabilir; rapor bulunmaması testlerin başarılı olduğu anlamına gelmez. Sonuç için ilgili işin durumunu ve loglarını kontrol edin.

Her iki build Gradle önbelleğini kullanır; yalnızca main çalıştırmaları önbelleğe yazar.
