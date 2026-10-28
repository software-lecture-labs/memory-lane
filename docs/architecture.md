# Mimari ve kabul gereksinimleri

## Java backend ve application server

Backend Java 17 üzerinde Spring Boot 4.1.1 ile çalışır; seçilen HTTP çalışma ortamı gömülü Tomcat'tir. `bootJar` çalıştırılabilir JAR üretir. Ayrı Jakarta EE application server/WAR dağıtımı bu kurulumda uygulanmadı.

Ekip, genel proje dokümanındaki Payara Micro + Jakarta EE seçimi yerine Spring Boot kullanmaya karar verdi. Karşılıklar: REST → Spring Web MVC, CDI → Spring bağımlılık enjeksiyonu, Persistence → Spring Data JPA/Hibernate, Transactions → `@Transactional`, Payara datasource → Spring Boot/HikariCP datasource. Trello kartlarında ve proje dokümanında Payara geçen ifadeler bu karara göre okunmalıdır. Gerekçe: [ADR-0001](adr/0001-spring-boot-uygulama-calisma-ortami.md).

## Veritabanı ve migration (ML-04)

- PostgreSQL 17; yerelde `backend/docker-compose.yml` ile yalnızca 127.0.0.1 üzerinde çalışır. Bağlantı bilgileri `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` ortam değişkenlerinden okunur.
- Şemanın sahibi Flyway'dir. Migration dosyaları `backend/src/main/resources/db/migration/V<n>__aciklama.sql` biçimindedir ve uygulama açılışında otomatik çalışır. Uygulanmış bir migration değiştirilmez; her şema değişikliği yeni bir sürüm dosyasıdır.
- Hibernate şemayı yalnızca doğrular (`ddl-auto=validate`); tablo oluşturmaz veya değiştirmez. Zaman damgaları UTC `TIMESTAMPTZ`, anı günü saat diliminden bağımsız `DATE` olarak tutulur.
- `V1` proje dokümanındaki veri modelini (users, sessions, memories, photos, file_cleanup_jobs) kurar. `V2` users, memories ve photos kimliklerini ve bunlara bağlı foreign key'leri `UUID` yapar; sessions ve file_cleanup_jobs kimlikleri dışarı açılmadığı için `BIGINT` kalır. Bu tablolara henüz API yazmadığı için V2 tabloları yeniden oluşturur; içlerinde satır varsa veri silmek yerine açıklayıcı bir hatayla durur. Kategori ve duygu değerleri İngilizce sabitlerle saklanır (`TRAVEL`, `HAPPY` vb.); Türkçe etiketler istemcide gösterilir.
- Fotoğraf sınırı dosya başına 5 MB = 5.000.000 bayttır. Kapak, anının mevcut en küçük `position` değerine sahip fotoğrafıdır. Pozisyonlar yeniden numaralandırılmaz: kapak silinince sıradaki fotoğraf kapak olur, yeni fotoğraf `max(position) + 1` alır. Anı başına 5 fotoğraf sınırını servis, anı satırını kilitleyerek (`SELECT ... FOR UPDATE`) sayar ve uygular.
- Tekrar denemeler (idempotency): Anı oluşturma isteği istemcinin ürettiği `client_request_id` (UUID) ile gelir; aynı hesapta bu anahtar tekildir. Sunucu oluşturma isteğinin SHA-256 özetini `client_request_sha256` olarak saklar. Aynı anahtar ve aynı özetle gelen tekrar yeni anı üretmez, mevcut anıyı döndürür; aynı anahtarla farklı içerik çakışma (409) olarak reddedilir. Fotoğraf yükleme de aynı kuralı izler: `client_photo_id` anı içinde tekildir, dosya içeriğinin SHA-256 özeti `content_sha256` olarak saklanır; aynı anahtar ve aynı içerik mevcut fotoğrafı döndürür, farklı içerik 409 alır. Yanıt kodları ve özetin hangi alanlardan hesaplanacağı ML-03 API sözleşmesinde kesinleştirilir.
- Veritabanına ulaşılamazsa uygulama açılmaz ve nedeni ile yapılacakları kısa bir hata mesajıyla bildirir. Çalışırken `GET /api/health` veritabanı erişilebilirse 200 `{"status":"UP","database":"UP"}`, değilse 503 `DOWN` döner.
- Veritabanı testleri test JVM'i başına tek bir `postgres:17` Testcontainers örneğini paylaşır (singleton container); temiz şema gereken test bu container içinde ayrı bir veritabanı açar. Docker yoksa bu testler yalnızca yerelde atlanır, CI'da atlanmaz. Veritabanı gerektirmeyen testler Docker'a ihtiyaç duymaz.

## SOLID

- Controller HTTP doğrulama/eşlemeye, service iş kurallarına, repository veri erişimine odaklanır (SRP).
- Değişen dış servisler için dar arayüzler ve bağımlılık enjeksiyonu kullanılır (OCP, DIP).
- Arayüz uygulamaları aynı davranış sözleşmesini korur; sözleşme testleri eklenir (LSP).
- Kullanılmayan metotları zorunlu kılan geniş arayüzler oluşturulmaz (ISP).

Mevcut paket iskeleti korunmuştur. Henüz iş kuralları veya repository uygulaması bulunmadığından SOLID uyumu tamamlandı iddiası yoktur.

## Transaction

Kalıcı veri katmanı eklendiğinde bir kullanım senaryosunun tüm yazmaları service katmanındaki tek transaction sınırında yürütülmelidir. Spring transaction yönetimi ve `@Transactional` kullanılacaktır; checked exception rollback kuralları açıkça seçilmelidir. Dış HTTP çağrıları uzun DB transaction'ı içinde tutulmamalıdır.

Kabul testleri: başarılı işlemde tüm kayıtlar commit olur; ikinci yazmada hata üretildiğinde ilk yazma da rollback olur; eşzamanlı güncellemede veri kaybı engellenir (örneğin optimistic locking); yetkisiz erişimde hiçbir kayıt değişmez. Üretimde kullanılacak DB ile entegrasyon testi gerekir. PostgreSQL bağlantısı ve şema kuruldu; transaction uygulaması ve bu testler henüz yoktur.

## Statik analiz: A hedefi

Hedef SonarQube/SonarCloud üzerinde reliability, security ve maintainability derecelerinin A olmasıdır; kullanılan sürüm ve değerlendirme modu rapora yazılmalıdır. Quality gate geçişi ayrıca kaydedilir. Sonuçlar proje anahtarı, commit SHA ve analiz bağlantısıyla ilişkilendirilmelidir. Henüz Sonar sunucusu/proje anahtarı verilmedi ve analiz çalıştırılmadı; A elde edildiği iddia edilmez.

Android lint uyarıları hataya dönüştürülür. Lint geçişi tek başına Sonar A kanıtı değildir. İşlevler eklendikçe birim/entegrasyon testleri ve coverage raporları analiz sürecine bağlanmalıdır.

## Sürüm kaynakları

- https://developer.android.com/build/releases/past-releases/agp-8-7-0-release-notes
- https://docs.spring.io/spring-boot/4.1/system-requirements.html
- https://docs.gradle.org/8.14.3/userguide/gradle_wrapper.html

## Bağımsız Gradle build kararı

Mobil ve backend ayrı dağıtılır; birbirinin derlenmiş çıktısını kullanmaz. Ayrı settings.gradle ve wrapper dosyaları Android SDK gerektirmeden backend'i, backend bağımlılıklarını çözmeden mobil uygulamayı derlemeyi sağlar. Bu seçim gözlemlenmiş bir Android/Spring plugin çakışmasına dayanmaz; araç sürümlerini ve CI işlerini bağımsız tutmak içindir. Mobil Gradle 8.9 üzerinde kalır; yalnızca Spring Boot 4.1 için gereken minimum Gradle 8.14 koşulunu sağlamak üzere backend 8.14.3 kullanır. Ortak JVM kütüphanesi veya kökten çalıştırma ihtiyacı doğarsa includeBuild ile composite build değerlendirilebilir; şimdilik ek kök build gerekmiyor.

## Yerel yapılandırma

Yerel profil ayarları backend/config/application-local.properties içinde, classpath kaynaklarının dışında tutulur. Spring Boot bu dosyayı yalnızca local profil etkinken yükler; koşulsuz classpath import kullanılmaz. Yanlışlıkla src/main/resources altına konan application-local.properties/yml/yaml dosyaları processResources tarafından dışlanır. Yerel dosyalar Git tarafından ignore edilir; örnek gerçek sır içermez.

## Mobil araçlar ve CI

Mobil modülün Android SDK 35, AGP 8.7.3 ve Gradle 8.9 sürümleri korunur. Lint uyarıları hatadır ve bağımlılıklar da kontrol edilir. Tek istisna OldTargetApi güncellik bildirimidir: SDK 35 bilinçli olarak sabitlendiği için bu kural kapalıdır. Diğer lint kuralları, bağımlılık kontrolleri ve warningsAsErrors etkin kalır. Hedef API yükseltmesi ayrı uyumluluk çalışması gerektirir. CI'da SDK/build-tools sürümleri tekrar yazılmaz; setup-android lisansları kabul eder, Gradle eksik bileşenleri build tanımına göre indirir. Push kontrolü yalnızca main üzerinde; diğer dal değişiklikleri PR kontrolüyle doğrulanır ve eski çalışmalar concurrency ile iptal edilir.

## Mobil mimari (ML-28)

Mobil uygulama Java ve XML layout ile yazılır. Tek Activity (`MainActivity`) ve Navigation Component kullanılır; alt sekmeler (Anılar, Harita, Profil) `res/navigation/nav_graph.xml` hedefleridir ve menü kimlikleri hedef kimlikleriyle aynıdır.

Katmanlar MVVM + Repository biçimindedir: Fragment yalnız çizer ve kullanıcı olayını ViewModel'e iletir; ViewModel ekran durumunu `LiveData<UiState>` olarak tutar; Repository arayüzü veri kaynağını gizler; Retrofit arayüzü yalnız HTTP yolunu tanımlar. Bu, backend'deki controller → service → repository ayrımının mobil karşılığıdır.

- SRP: Çizim, ekran durumu, veri erişimi ve HTTP tanımı ayrı sınıflardadır.
- OCP: Paketler özellik bazlıdır. `core/` (config, di, network, ui) bütün ekranların ortak altyapısı, `feature/<özellik>/data` ve `feature/<özellik>/ui` özelliğe ait koddur. Yeni özellik mevcut özelliklere dokunmadan yeni paket olarak eklenir.
- LSP/DIP: ViewModel somut sınıfa değil `HealthRepository` gibi arayüze bağlıdır; uzak uygulama ile testteki sahte uygulama aynı sözleşmeyle değiştirilebilir.
- ISP: Repository arayüzleri özellik başına dardır; tek bir genel "Api" arayüzü kurulmaz.

Bağımlılık enjeksiyonu elle yapılır: `MemoryLaneApp` açılışta tek bir `AppContainer` kurar ve somut sınıfları bilen tek yer orasıdır. ViewModel'ler fabrika ile bağımlılıklarını yapıcıdan alır.

Ağ katmanında tek Retrofit/OkHttp istemcisi ve Gson kullanılır. `ApiCaller` her sonucu `ApiResult` (veri veya `ApiError`) olarak döndürür ve hatayı türüne ayırır: NETWORK (yanıt yok: sunucu kapalı, adres yanlış, zaman aşımı), HTTP (2xx dışı; sözleşmedeki `{code,message}` gövdesi okunur), PARSE (okunamayan 2xx gövde). Ekran kararları mesaj metnine değil tür ve `code` alanına göre verilir ([API sözleşmesi](api-contract.md)). `UiState` içinde LOADING, SUCCESS, EMPTY ve ERROR ayrı durumlardır; liste ekranları `UiState.fromList` kullanır ve yalnız başarılı ama boş yanıt EMPTY olur. Bağlantı hatası boş listeye çevrilmez. Ortak `StateView` hata durumunu ikon ve "Tekrar dene" ile, boş durumu bunlar olmadan gösterir.

Sunucu adresi `mobile/local.properties` içindeki `memorylane.apiBaseUrl` değeridir; yoksa emülatörün ana bilgisayar adresi `http://10.0.2.2:8080/` kullanılır. Değer BuildConfig'e derlenir; şemasız veya boş değer açılışta açık hatayla durur. Düz HTTP yalnız debug manifestinde açıktır; release derlemesi Android'in varsayılan cleartext engelini korur. Release için HTTPS adresi yayın hazırlığında belirlenecektir.

Tasarım değerleri "Warm Nostalgia Scrapbook" tasarım sisteminden `res/values` altına aktarılır (renk, ölçü, yazı ve bileşen stilleri); ana renk tasarım sistemi belgesindeki `#D93678`'dir. Layout'lar hex, dp veya sp değeri yazmaz; bu kaynakları kullanır. Ortak bileşenler `ScreenHeaderView` (ekran başlığı), `StateView` (yükleniyor/hata/boş), birincil/ikincil/ghost buton, kart, form alanı ve alt menü stilleridir. Henüz bir ekranda kullanılmayan stiller `tools:ignore="UnusedResources"` ile işaretlidir; kullanıldıklarında işaret kaldırılır. Plus Jakarta Sans fontu çevrimdışı ve Google Play servisleri olmayan cihazlarda da aynı görünmesi için OFL 1.1 lisansıyla uygulamaya gömülüdür (`mobile/third_party/plus-jakarta-sans/OFL.txt`).

Değerlendirilen alternatifler:

| Konu | Seçim | Alternatif ve seçilmeme nedeni |
| --- | --- | --- |
| Desen | MVVM + Repository | MVC'de iş mantığı Activity/Fragment içinde birikir ve test cihaz ister. MVP'de Presenter yaşam döngüsünü elle izler; AndroidX ViewModel ekran döndürmede durumu hazır korur. |
| DI | Elle `AppContainer` | Hilt, Java'da annotation processor ve ek Gradle eklentisi getirir; mevcut bağımlılık sayısında kazancı azdır. Bağımlılıklar yapıcıdan alındığı için ileride geçiş yalnız container'ı değiştirir. |
| Navigasyon | Navigation Component | Elle FragmentTransaction yönetiminde sekme geri yığını ve durum saklama tekrar yazılır. |
| HTTP/JSON | Retrofit + OkHttp + Gson | HttpURLConnection veya yalın OkHttp ile JSON dönüşümü ve hata eşlemesi her istekte tekrarlanır. |
| Paketler | Özellik bazlı | Katman bazlı (ui/data/model) yapıda özellik sayısı arttıkça ilgili dosyalar dağılır; ilk iskeletteki boş klasörler bu yapıyla değiştirildi. |

Bilinen sınırlar: Anılar sekmesi şimdilik sunucu sağlık kontrolünü gösterir; anı listesi, harita ve profil içeriği ilgili kartlarda eklenecektir. Giriş/kayıt ekranları auth API'sine (ML-27) bağlanacağı için ayrı PR'da yapılacaktır. Testler JVM birim testleridir (ağ katmanı MockWebServer ile gerçek HTTP üzerinden); cihaz üzerinde otomatik UI testi henüz yoktur.

## Veri saklama ve yedekleme kararı

Anıların kalıcı ve asıl kaydı backend'de tutulacak. Mobil cihazda sunucudan tekrar alınabilen veriler önbellek olarak ele alınacak; yeni cihazda yeniden giriş yapılıp sunucudaki kayıtlar getirilecek. Henüz sunucuya gönderilmemiş anılar ise önbellek sayılmayacak: başarılı sunucu onayı alınana kadar korunmalı ve kullanıcıya senkronizasyon durumları gösterilmelidir.

Parola, erişim/yenileme anahtarı ve cihaza özel kimlik bilgileri bulut yedeğine veya device-transfer'a dahil edilmeyecek. Mobil oturum saklama geliştirilirken Android Keystore ve yedek dışı depolama kullanılmalı; yeni cihaza geçişte kullanıcı yeniden giriş yapmalıdır. Anıların backend'de tutulması tek başına yedek değildir: sunucu veritabanı ve varsa fotoğraf depolaması için yedekleme, saklama süresi ve geri yükleme testleri ayrıca hazırlanmalıdır.

Bu iskelet aşamasında allowBackup=false ve mevcut yedekleme dosyaları korunur; device-transfer bu PR'da açılmaz. Mevcut XML'deki root dışlamasının bütün veri alanlarını veya her üreticinin cihaz transferi davranışını kapsadığı varsayılmaz. Yedek dışı hassas depolama, çevrimdışı kuyruk ve senkronizasyon uygulandığında izin verilen veri alanları tek tek belirlenip yedekleme/geri yükleme testiyle doğrulanmalıdır.

Bu bölüm hedef mimariyi belirler. Bu PR'da anı API'si, anı veritabanı, mobil veri saklama veya geri yükleme akışı henüz uygulanmadı; mevcut veriler için kayıpsız kurtarma garantisi verilmez.

## ML-27 veritabanı planı

ML-27'de planlanan veritabanı temeli ML-04 ile uygulandı: geçici DataSource dışlaması ve `database` profili kaldırıldı; uygulama her profilde PostgreSQL'e bağlanır. Şema Flyway ile `backend/src/main/resources/db/migration/` altında sürümlenir (V1 ilk şema, V2 UUID kimlikler ve tekrar deneme alanları), `ddl-auto=validate` entity eşlemelerini bu şemayla karşılaştırır. Entegrasyon testleri Testcontainers/PostgreSQL kullanır; yerel geliştirme için `backend/docker-compose.yml` vardır. Spring Boot'un otomatik Docker Compose desteği eklenmedi: uygulama açılışı Docker'a bağlı kalmasın ve aynı compose dosyası elle yönetilebilsin diye compose ayrı komutla başlatılır. Paylaşılan ekip veritabanı entegrasyon veya demo ortamı olarak kullanılabilir. Entity/repository taraması ve kalıcılık katmanının kendisi ML-27 kapsamında kalır.
