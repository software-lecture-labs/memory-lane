# Memory Lane

Android mobil uygulaması ve Java HTTP backend için ortak repo.

- `mobile/`: Java 17, Android SDK 35 ve AGP 8.7.3 uygulama iskeleti.
- `backend/`: Java 17, Spring Boot 4.1.1 ve gömülü Tomcat.
- `docs/`: kalıcı mimari kararlar ve Git çalışma biçimi.

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

## İlk teslim kapsamı

İlk hesaplı Trello planı esas alınır; yalnız backend teknolojisi Spring Boot olarak güncellenmiştir. Kayıt, giriş, çıkış ve 24 saatlik iptal edilebilir oturum korunur. Anılar ve fotoğraflar oturum sahibine bağlıdır; farklı hesapların birbirinin verisine erişmemesi hedefi ML-29/ML-31 kapsamında doğrulanacaktır. Bu sahiplik denetimi henüz tamamlanmış sayılmaz.

Anı oluşturma/düzenleme, fotoğraf yükleme/görüntüleme, liste/detay, arama, harita ve silme temel kapsamdadır. Şifre sıfırlama, profil düzenleme, e-posta doğrulama, sosyal giriş, sesli anı, EXIF, yakındakiler, senkronizasyon ve dışa aktarma kapsam dışıdır. Tarihte Bugün ve gelişmiş filtreler temel teslimden sonra değerlendirilir. Mağaza yayını ve herkese açık hizmet planlanmaz.

K1 hesap API'si ve anı oluşturma; K2 kayıt/giriş mobil ekranları ve fotoğraflar; K3 mobil oturum, liste, detay, anı düzenleme ve arama; K4 veritabanı altyapısı, sahiplik, harita, silme ve transaction işlerinden sorumludur. İlk plandaki görev dağılımı değişmez. Mimari alternatifler ve bekleyen ders kriterleri [mimari belgesindedir](docs/architecture.md#ml-01-ders-kriterleri-ve-alternatifler).

## İlk sprint anı demosu (ML-07)

1. Backend'i boş bir yerel PostgreSQL ile başlatın. Kayıt ekranı henüz hazır değilse `POST /api/v1/auth/register` ile iki test hesabı açın. İstek alanları [API sözleşmesinde](docs/api-contract.md#hesap-ve-oturum) yer alır.
2. Android uygulamasında **Anılar → Anıların için giriş yap** ile A hesabına girin. Başlık ve tarih seçip kaydedin. Yeni anı listede görünür. Başlıksız kayıt hata verir.
3. Uygulamayı kapatıp yeniden açın. Token süreç belleğinde olduğu için A ile tekrar girince anı sunucudan yeniden yüklenir.
4. Çıkış yapıp B hesabına girin. B'nin listesinde A'nın anısı görünmez.

`POST /api/v1/memories` oluşturur, `GET /api/v1/memories` hesaba ait sayfalı listeyi döndürür. Türkçe arama ve kategori filtresi liste API'sindedir. Detay/düzenleme, fotoğraf ve harita akışları kendi kartlarında uygulanır.

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

Mobil uygulama Anılar sekmesiyle açılır. Sekme giriş ve anı akışının yanında `GET /api/health` sonucunu gösterir. Sunucu kapalıysa veya adres yanlışsa bağlantı hatası, veritabanı kapalıysa veritabanı hatası "Tekrar dene" ile gösterilir. Mobil mimari ve ortak bileşenler [mimari belgesinde](docs/architecture.md#mobil-mimari-ml-28) anlatılır.

SDK yolları, parolalar, `.env`, imzalama anahtarları ve yerel ayarlar commitlenmez. Yanlışlıkla classpath altına konan application-local.properties/yml/yaml dosyaları da paketlemeden dışlanır.

Ayrıntılar: [mimari](docs/architecture.md), [Git çalışma biçimi](docs/git-workflow.md). Anlık doğrulama sonuçlarının kaynağı ilgili PR açıklaması ve o commit için GitHub Actions kontrolleridir; repodaki belgeler kalıcı kurulum bilgilerini içerir.

## Veritabanı ve profiller

Backend her profilde PostgreSQL'e bağlanır ve açılışta Flyway migration'larını uygular; ayrı bir `database` profili yoktur. Bağlantı bilgileri yukarıdaki kurulum adımında anlatılan `backend/.env` dosyasından veya ortam değişkenlerinden gelir. Harici yerel ayarlarla çalıştırmak için backend dizininde: `.\gradlew.bat bootRun --args="--spring.profiles.active=local --spring.config.additional-location=optional:file:./config/"`.

## CI raporları

GitHub Actions içindeki Verify çalıştırmasının Artifacts bölümünden `backend-reports` ve `mobile-reports` raporlarına ulaşabilirsiniz. Test ve lint raporları, ilgili adım başarısız olsa da 14 gün saklanır. Derleme rapor üretemeden durursa artifact oluşmayabilir; rapor bulunmaması testlerin başarılı olduğu anlamına gelmez. Sonuç için ilgili işin durumunu ve loglarını kontrol edin.

Her iki build Gradle önbelleğini kullanır; yalnızca main çalıştırmaları önbelleğe yazar.
