# Memory Lane

Memory Lane, kişisel bir anı defteridir. Kullanıcı hesap açar; anılarını başlık, tarih, kategori, duygu, isteğe bağlı konum ve en fazla beş fotoğrafla kaydeder; zaman çizelgesinde, haritada ve aramayla geri bulur. Her hesabın verisi yalnızca kendisine görünür.

Repo iki uygulamadan oluşur: Java + XML ile yazılmış bir **Android uygulaması** ve Java **Spring Boot REST API**. Veriler **PostgreSQL**'de tutulur. Proje bir ders kapsamında kontrollü demo olarak geliştirilir; mağaza yayını ve herkese açık hizmet planlanmaz.

## Proje yapısı

| Klasör | İçerik |
|---|---|
| `backend/` | Java 17, Spring Boot 4.1.1 (Spring Web MVC, Spring Data JPA, JdbcClient), gömülü Tomcat, Flyway migration'ları, entegrasyon testleri |
| `mobile/` | Java 17, Android SDK 35, AGP 8.7.3; özellik bazlı paketler (`feature/<özellik>/data`, `feature/<özellik>/ui`) ve ortak altyapı (`core/`) |
| `docs/` | [Mimari](docs/architecture.md), [API sözleşmesi](docs/api-contract.md), [Git çalışma biçimi](docs/git-workflow.md) ve mimari karar kayıtları (`docs/adr/`) |
| `.github/` | CI iş akışı (Verify: backend ve mobil derleme, lint, test), PR şablonu, CODEOWNERS |

Mimari karar kayıtları (ADR):

- [ADR-0001](docs/adr/0001-spring-boot-uygulama-calisma-ortami.md): Payara Micro yerine Spring Boot ve gömülü Tomcat
- [ADR-0002](docs/adr/0002-silme-politikasi.md): Silme politikası ve fotoğraf sırası
- [ADR-0003](docs/adr/0003-sahiplik-denetimi.md): Oturum ve sahiplik denetimi
- [ADR-0004](docs/adr/0004-harita-kutuphanesi.md): Harita için MapLibre ve OpenStreetMap

## Kapsam

Temel kapsam: kayıt, giriş, çıkış ve 24 saatlik iptal edilebilir oturum; anı oluşturma, listeleme (yıl gruplu zaman çizelgesi), detay, düzenleme ve silme; fotoğraf yükleme ve görüntüleme; Türkçe arama ve kategori filtresi; harita. Anılar ve fotoğraflar oturum sahibine bağlıdır; bir hesabın başka bir hesabın verisini okuyamaması, değiştirememesi ve silememesi ML-29 ile uygulanmıştır ve ML-31'in iki hesaplı testleriyle doğrulanır.

Kapsam dışı: şifre sıfırlama, profil düzenleme, e-posta doğrulama, sosyal giriş, sesli anı, EXIF, yakındakiler, senkronizasyon ve dışa aktarma. Tarihte Bugün ve gelişmiş filtreler yalnız temel teslim tamamlanırsa ele alınır.

Görev dağılımı: K1 hesap API'si ve anı oluşturma; K2 kayıt/giriş mobil ekranları ve fotoğraflar; K3 mobil oturum, liste, detay, düzenleme ve arama; K4 veritabanı altyapısı, sahiplik, harita, silme ve transaction.

## Önkoşullar

| Araç | Sürüm | Ne için |
|---|---|---|
| JDK | **17** (ör. Eclipse Temurin 17) | Backend ve mobil derleme. Gradle 8.14.3 JDK 25 ile çalışmaz; `JAVA_HOME` JDK 17'yi göstermelidir. |
| Android Studio + Android SDK Platform 35 | Güncel Android Studio | Yalnız mobil uygulama için; emülatör de buradan kurulur. |
| Docker Desktop | Güncel | Yerel PostgreSQL 17 ve backend entegrasyon testleri. Docker yoksa ortak Supabase veritabanı kullanılabilir; entegrasyon testleri yerelde atlanır, CI'da çalışır. |
| Git | Güncel | Repo. |

Gradle ayrıca kurulmaz: backend wrapper Gradle 8.14.3, mobil wrapper Gradle 8.9 indirir ve SHA-256 ile doğrular. Backend ve mobil build'leri bağımsızdır; yalnız çalıştırdığınız tarafın araçları gerekir.

## Yapılandırma

Kişiye özel ayarlar ve parolalar repoya girmez. `backend/.env`, `mobile/local.properties`, `backend/config/application-local.properties`, imzalama anahtarları ve SDK yolları `.gitignore` ile dışarıdadır; repoda yalnız parolasız örnekleri (`*.example`) vardır.

**Backend** (`backend/.env` dosyası veya gerçek ortam değişkenleri; ortam değişkenleri `.env` değerlerinin önüne geçer):

| Değişken | Varsayılan | Açıklama |
|---|---|---|
| `DB_PASSWORD` | *(yok, zorunlu)* | PostgreSQL parolası. Varsayılanı yoktur. |
| `DB_HOST`, `DB_PORT` | `127.0.0.1`, `5432` | Veritabanı adresi. |
| `DB_NAME`, `DB_USER` | `memory_lane`, `memory_lane` | Veritabanı ve kullanıcı adı. |
| `SPRING_DATASOURCE_URL`, `..._USERNAME`, `..._PASSWORD` | *(yukarıdakilerden oluşur)* | Tam JDBC adresi gerektiğinde (ör. Supabase, TLS parametreleriyle). |
| `SERVER_PORT` | `8080` | API portu. |
| `SPRING_FLYWAY_ENABLED` | `true` | Açılışta migration uygulanması. Ortak Supabase bağlantısında `false`'tur (aşağıda). |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | `10` | Bağlantı havuzu boyutu. |
| `MEMORYLANE_PHOTOS_DIRECTORY` | `storage/photos` | Fotoğraf dosyalarının klasörü (backend dizinine göre). |
| `SPRING_PROFILES_ACTIVE` | *(boş)* | `local` verilirse `backend/config/application-local.properties` okunur. |

Fotoğraf temizliğinin zamanlamaları (`MEMORYLANE_PHOTOS_CLEANUP_RETRY_DELAY`, `..._ORPHAN_SWEEP_DELAY`, `..._QUARANTINE_LIFETIME`) ve auth ayarları da değiştirilebilir; varsayılanlar `backend/src/main/resources/application.properties` içindedir.

**Mobil** (`mobile/local.properties`, `mobile/local.properties.example` dosyasından kopyalanır):

| Anahtar | Varsayılan | Açıklama |
|---|---|---|
| `sdk.dir` | — | Android SDK klasörü (`ANDROID_HOME` tanımlıysa gerekmez). |
| `memorylane.apiBaseUrl` | `http://10.0.2.2:8080/` | Backend adresi; `/` ile bitmelidir. Emülatör ve telefon için aşağıdaki [adres tablosuna](#mobil-uygulama-ve-sunucu-adresi) bakın. |

## Veri modeli ve API

Hesap, oturum, kullanıcıya özel anı ve fotoğraf işlemlerinin ortak alanları, endpointleri, hata yanıtları ve tekrar deneme kuralları [ML-03 API sözleşmesinde](docs/api-contract.md) tanımlıdır. Bu belge geliştirme sözleşmesidir; endpointlerin uygulanmış olduğunu göstermez.

## Hesap API'si (ML-27)

`POST /api/v1/auth/register` e-posta ve parolayla hesap açar (201); otomatik oturum açmaz. `POST /api/v1/auth/login` aynı JSON alanlarıyla Bearer token ve `expiresAt` döndürür. `GET /api/v1/auth/me` token sahibini döndürür; `POST /api/v1/auth/logout` yalnız o tokenı iptal eder (204). Son iki istekte `Authorization: Bearer <accessToken>` gerekir. Çıkış aynı doğru biçimli tokenla tekrarlanabilir; geçersiz/süresi dolmuş oturumla `me` 401 döner.

Kayıtta e-posta kırpılıp küçük harfe çevrilir; parola 15–128 Unicode karakterdir, kırpılmaz. Argon2id (19 MiB, 2 geçiş, 1 paralellik) parola özeti; 32 rastgele bayttan üretilen 24 saatlik tokenın yalnız SHA-256 özeti PostgreSQL'e yazılır. Ham parola/token loglanmaz. Yanıtlar `Cache-Control: no-store` taşır.

Auth gövdesi 8 KiB ile, kayıt/giriş denemeleri gerçek bağlantı IP'si başına 20/dakika ile sınırlıdır. Login için normalize e-posta başına beş başarısız denemeden sonra pencere bitene kadar 429/Retry-After döner. Başarılı giriş hesabın başarısızlık sayacını sıfırlar. Sayaçlar bu kontrollü, tek sunuculu demo için bellektedir; yeniden başlatmada sıfırlanır ve birden çok sunucu arasında paylaşılmaz. Proxy başlıklarına güvenilmez; proxy kullanımı ayrıca yapılandırılmalıdır. HTTPS dağıtımı ML-32, iş kaynaklarının sahiplik denetimi ML-29 kapsamındadır.

Mevcut Flyway `users`/`sessions` şeması, UserAccount/UserSession JPA eşlemeleri ve dar `AuthRepository` ile kullanılır. `INSERT ... ON CONFLICT` eşzamanlı kayıtta tek hesap üretir; parola hesaplaması transaction dışındadır; hesap ekleme, oturum oluşturma ve iptal yalnız repository'deki kısa transaction'lar içinde çalışır. Session–User ilişkisi çoktan-biredir; JPA şemayı değiştirmez, Flyway şemasını doğrular. Anı/fotoğraf geliştirmesi bu teslimden ayrıdır. API biçimleri [sözleşmede](docs/api-contract.md), çalıştırma/test komutları aşağıdadır. Auth entegrasyon testleri HTTP uçlarını gerçek PostgreSQL üzerinde kontrol eder; Docker yoksa yalnız yerelde atlanır, CI'da zorunludur.

### Hash kapasitesi ve oturum bakımı

Argon2id çalışma belleği işlem başına 19 MiB olarak yapılandırılmıştır. Kayıt, giriş ve bilinmeyen kullanıcı doğrulaması aynı Semaphore sınırını paylaşır: varsayılan en fazla iki hash işlemi, yani Argon2 çalışma alanları için yaklaşık 38 MiB. Bu sayı toplam JVM belleği veya ölçülmüş heap tepe değeri değildir; kütüphane nesneleri ve uygulamanın diğer kullanımları ayrıca yer tutar. `memorylane.auth.max-concurrent-hashes` 1–8 aralığında ayarlanabilir; yükseltmeden önce sunucunun belleği ölçülmelidir. Kapasite doluysa kuyruk oluşturulmaz; `503 AUTH_BUSY` ve `Retry-After: 1` döner. Parola hash/verify işlemi DB transaction'ı ve bağlantısı tutmaz; repository okuma/yazmaları ayrı kısa transaction'lardır.

Süresi dolan token, temizlik beklenmeden hemen 401 ile reddedilir. Başlangıçtan bir saat sonra ve ardından saatte bir çalışan temizlik işi `expires_at <= şimdi` kayıtlarını, çağrı başına en fazla 1.000 satırlık kısa transaction ile siler. İptal edilmiş kayıtlar da doğal sona erme zamanına kadar tutulur; ardından aynı iş tarafından silinir. Aktif oturumlar korunur. Büyük birikimde kalan kayıtlar sonraki turlarda temizlenir; daha yüksek trafik için ayrı indeks/bakım planı ve çalışma sıklığı değerlendirilmelidir. `memorylane.auth.session-cleanup-delay` ISO-8601 süre olarak ayarlanabilir (varsayılan `PT1H`). Temizlik hatası tokenın geçerlilik süresini uzatmaz.

### Proxy varsayımı

Mevcut geliştirme/ders demosu backend'e doğrudan bağlantı varsayar. `getRemoteAddr()` doğrudan bağlantının adresidir; reverse proxy/load balancer arkasında bütün kullanıcılar için proxy IP'si görünebilir ve IP başına 20/dakika sınırı ortak bir kotaya dönüşebilir. Bu kurulum proxy arkasında kullanıcı bazlı doğru IP sınırı sağladığı iddiasını taşımaz. Hesap başına başarısız giriş sınırı bundan bağımsız kalır. `Forwarded`/`X-Forwarded-For` başlıkları kodda doğrudan okunmaz; istemcinin yazdığı başlığa güvenilmez. Dağıtımda güvenilir proxy adresleri, backend'e doğrudan erişim ve başlıkların proxy tarafından temizlenip yeniden yazılması birlikte ele alınmalı; ancak bundan sonra uygun Spring/Tomcat forwarded-header stratejisi seçilip iki farklı istemciyle test edilmelidir. Load balancer yapılandırması bu K1 backend değişikliğine dahil değildir.

## İlk sprint anı demosu (ML-07)

1. Backend'i boş bir yerel PostgreSQL ile başlatın. Android uygulamasında **Anılar → Giriş yap → Kayıt ol** ile iki test hesabı (A ve B) açın; şifre 15–128 karakter olmalıdır. Kayıt oturum açmaz, başarılı kayıttan sonra giriş ekranına dönülür. Hesaplar istenirse `POST /api/v1/auth/register` ile de açılabilir; istek alanları [API sözleşmesinde](docs/api-contract.md#hesap-ve-oturum) yer alır.
2. **Anılar → Giriş yap** ile A hesabına girin. Boş hesapta "Henüz anın yok" görünür.
3. **Anı Ekle** ile oluşturma ekranını açın. Başlıksız veya tarihsiz kayıt alan hatası verir; tarih seçicide gelecek günler seçilemez. Başlık ve tarih girin, isterseniz **Fotoğraf ekle** ile en fazla 5 fotoğraf seçip **Kaydet**'e dokunun. Fotoğraf seçildiyse ekran fotoğraflar yüklenene kadar açık kalır ve ilerlemeyi gösterir; sonra listeye döner, liste yenilenir ve anı kendi yıl başlığının altında en üstte görünür.
4. Uygulamayı kapatıp yeniden açın. Token süreç belleğinde olduğu için tekrar giriş gerekir; A ile girince anı sunucudan yeniden yüklenir.
5. **Profil → Çıkış yap** ile çıkıp B hesabına girin. Anılar listesi sıfırlanır; B'nin listesinde A'nın anısı görünmez.

`POST /api/v1/memories` oluşturur, `GET /api/v1/memories` hesaba ait sayfalı listeyi döndürür. Liste 20'şer kayıtla sayfalanır; "Daha fazla anı göster" sonraki sayfayı ekler. Yükleme hatası boş liste gibi gösterilmez, "Tekrar dene" ile yinelenir; 401 yanıtı oturumu kapatır. Kayıt tekrar denendiğinde aynı `Idempotency-Key` kullanılır, böylece bağlantı kopsa da anı iki kez oluşmaz. Türkçe arama ve kategori filtresi liste API'sindedir; mobil arama arayüzü ve harita kendi kartlarında uygulanır. Karta dokununca anı detayı açılır (fotoğraf galerisi ve tam ekran görüntüleyiciyle); "Anıyı Düzenle" `GET /api/v1/memories/{id}` ile alınan ETag'i `PUT` isteğinin `If-Match` başlığında gönderir; anı başka yerde değiştiyse (412) taslak korunur ve son hal gösterilir.

## Fotoğraf yükleme (ML-09)

`POST /api/v1/memories/{id}/photos` tek bir JPEG/PNG dosyasını `file` adlı multipart alanında ve `Idempotency-Key` başlığıyla alır. `GET /api/v1/memories/{id}/photos` anının fotoğraflarını sırasıyla listeler, her fotoğrafın `contentUrl` adresi dosyayı yalnız anının sahibine döndürür. Kurallar ve hata kodları [API sözleşmesindedir](docs/api-contract.md#fotoğraf-endpointleri-ve-yanıtı).

Dosyalar backend'in diskinde, varsayılan olarak `backend/storage/photos` klasöründe tutulur; klasör Git dışındadır ve `MEMORYLANE_PHOTOS_DIRECTORY` ile değiştirilebilir. Veritabanında yalnız fotoğraf kaydı durur. Ortak Supabase veritabanını kullanırken her geliştiricinin backend'i kendi klasörünü kullanır: başka bir backend üzerinden yüklenmiş fotoğrafın kaydı görünür ama dosyası sizde olmadığı için içerik isteği 404 döner. Demo ve fotoğraf denemeleri tek bir backend üzerinden yapılmalıdır.

## Ortak PostgreSQL — Supabase

1. Şema sorumlusundan ekip bağlantı dosyasını güvenli kanaldan alın ve `backend/.env` olarak kaydedin. Repodaki `backend/.env.supabase.example` aynı ayarların parolasız örneğidir.
2. [Supabase PostgreSQL CA sertifikasını](https://supabase-downloads.s3-ap-southeast-1.amazonaws.com/prod/ssl/prod-ca-2021.crt) indirip `backend/config/supabase-ca.crt` adıyla kaydedin. Backend, Session pooler üzerinden TLS ile bağlanır. Supabase Data API kapalıdır.
3. JDK 17 ile `backend` klasöründen Windows'ta `.\gradlew.bat bootRun`, macOS/Linux'ta `./gradlew bootRun` çalıştırın. `GET http://localhost:8080/api/health` yanıtında veritabanı `UP` olmalıdır. Android uygulaması Spring Boot API adresini kullanır.

Uygulama veritabanına sınırlı `memorylane_app` kullanıcısıyla bağlanır: bu kullanıcı satır okuyup yazabilir, tablo oluşturamaz, silemez, boşaltamaz, değiştiremez ve migration geçmişini okuyamaz. Yetkiler ve güvenlik sınırları (en fazla 20 bağlantı, 30 sn sorgu süresi) [`backend/db/supabase/memorylane_app_role.sql`](backend/db/supabase/memorylane_app_role.sql) dosyasındadır; sonraki migration'ların oluşturduğu tablolara satır yetkisi otomatik verilir. Tam yetkili şema sahibi (`postgres`) bilgileri yalnız şema sorumlusundadır, Git dışındaki `backend/.env.owner` dosyasında durur ve yalnız migration için kullanılır; uygulama bu hesapla çalıştırılmaz.

Ekip bağlantısında Flyway kapalıdır. Şema sorumlusu, yalnızca güncel `main` üzerinden ve şema sahibi bağlantısıyla `SPRING_FLYWAY_ENABLED=true` kullanarak migration uygular. Türkçe arama migration’ı V4 uygulanmadan önce, silme ve fotoğraf sırası migration’ı V3 ana dala birleşmiş ve şemaya uygulanmış olmalıdır. Ortak ortamda yalnız deneme verisi kullanın.

Uygulamayı ortak veritabanında başlatmadan yalnız migration çalıştırmak için backend'in kullandığı Flyway sürümünün CLI imajı kullanılabilir (uygulamanın kendisi başlatılmaz, zamanlanmış işler çalışmaz). Önce prova yapın: şemayı veri olmadan (`pg_dump -n memorylane` ve kullanıcı tablolarında `--exclude-table-data`) geçici bir PostgreSQL 17 konteynerine aktarıp aynı komutu orada çalıştırın. `validateOnMigrate` uygulanmış migration dosyalarının değişmediğini de denetler.

```powershell
docker run --rm -e FLYWAY_USER -e FLYWAY_PASSWORD -v "${PWD}\src\main\resources\db\migration:/flyway/sql:ro" -v "${PWD}\config:/ca:ro" flyway/flyway:12.4.0 "-url=jdbc:postgresql://<host>:5432/postgres?sslmode=verify-full&sslrootcert=/ca/supabase-ca.crt" -schemas=memorylane -defaultSchema=memorylane -locations=filesystem:/flyway/sql -validateOnMigrate=true migrate
```

Ortak Supabase şeması 27.11.2026 itibarıyla V5'tedir (V3, V4 ve V5 bu yöntemle, önce provada denenerek uygulandı; mevcut kayıtlar korundu, V5 mevcut anılara 1 sürümünü verdi). Otomatik entegrasyon testleri CI'da ayrı PostgreSQL kullanır.

## İlk kurulum (yerel Docker seçeneği)

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

Mobil uygulama Anılar sekmesiyle açılır; sekme oturum açıksa anıları yıllara göre gruplanmış kartlarla listeler, yeni anı oluşturma ekranına geçirir. Fotoğraflar oluşturma ekranında seçilir ve anı kaydedildikten sonra yüklenir. Profil sekmesi oturumu (giriş/çıkış) ve `GET /api/health` sonucunu gösterir. Sunucu kapalıysa veya adres yanlışsa bağlantı hatası, veritabanı kapalıysa veritabanı hatası "Tekrar dene" ile gösterilir. Giriş ekranındaki **Kayıt ol** bağlantısı kayıt ekranını açar; giriş, kayıt ve anı oluşturma ekranlarında alt menü gizlenir. Mobil mimari ve ortak bileşenler [mimari belgesinde](docs/architecture.md#mobil-mimari-ml-28) anlatılır.

SDK yolları, parolalar, `.env`, imzalama anahtarları ve yerel ayarlar commitlenmez. Yanlışlıkla classpath altına konan application-local.properties/yml/yaml dosyaları da paketlemeden dışlanır.

Ayrıntılar: [mimari](docs/architecture.md), [Git çalışma biçimi](docs/git-workflow.md). Anlık doğrulama sonuçlarının kaynağı ilgili PR açıklaması ve o commit için GitHub Actions kontrolleridir; repodaki belgeler kalıcı kurulum bilgilerini içerir.

## Veritabanı ve profiller

Backend her profilde PostgreSQL'e bağlanır ve açılışta Flyway migration'larını uygular; ayrı bir `database` profili yoktur. Bağlantı bilgileri yukarıdaki kurulum adımında anlatılan `backend/.env` dosyasından veya ortam değişkenlerinden gelir. Harici yerel ayarlarla çalıştırmak için backend dizininde: `.\gradlew.bat bootRun --args="--spring.profiles.active=local --spring.config.additional-location=optional:file:./config/"`.

## CI raporları

GitHub Actions içindeki Verify çalıştırmasının Artifacts bölümünden `backend-reports` ve `mobile-reports` raporlarına ulaşabilirsiniz. Test ve lint raporları, ilgili adım başarısız olsa da 14 gün saklanır. Derleme rapor üretemeden durursa artifact oluşmayabilir; rapor bulunmaması testlerin başarılı olduğu anlamına gelmez. Sonuç için ilgili işin durumunu ve loglarını kontrol edin.

Her iki build Gradle önbelleğini kullanır; yalnızca main çalıştırmaları önbelleğe yazar.
