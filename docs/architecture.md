# Mimari ve kabul gereksinimleri

## Java backend ve application server

Backend Java 17 üzerinde Spring Boot 4.1.1 ile çalışır; seçilen HTTP çalışma ortamı gömülü Tomcat'tir. `bootJar` çalıştırılabilir JAR üretir. Ayrı Jakarta EE application server/WAR dağıtımı bu kurulumda uygulanmadı.

Ekip, genel proje dokümanındaki Payara Micro + Jakarta EE seçimi yerine Spring Boot kullanmaya karar verdi. Karşılıklar: REST → Spring Web MVC, CDI → Spring bağımlılık enjeksiyonu, Persistence → Spring Data JPA/Hibernate, Transactions → `@Transactional`, Payara datasource → Spring Boot/HikariCP datasource. Trello kartlarında ve proje dokümanında Payara geçen ifadeler bu karara göre okunmalıdır. Gerekçe: [ADR-0001](adr/0001-spring-boot-uygulama-calisma-ortami.md).

## Veritabanı ve migration (ML-04)

- PostgreSQL 17; yerelde `backend/docker-compose.yml` ile yalnızca 127.0.0.1 üzerinde çalışır. Bağlantı bilgileri `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` ortam değişkenlerinden okunur.
- Şemanın sahibi Flyway'dir. Migration dosyaları `backend/src/main/resources/db/migration/V<n>__aciklama.sql` biçimindedir ve uygulama açılışında otomatik çalışır. Uygulanmış bir migration değiştirilmez; her şema değişikliği yeni bir sürüm dosyasıdır.
- Hibernate şemayı yalnızca doğrular (`ddl-auto=validate`); tablo oluşturmaz veya değiştirmez. Zaman damgaları UTC `TIMESTAMPTZ`, anı günü saat diliminden bağımsız `DATE` olarak tutulur.
- `V1` proje dokümanındaki veri modelini (users, sessions, memories, photos, file_cleanup_jobs) kurar. `V2` users, memories ve photos kimliklerini ve bunlara bağlı foreign key'leri `UUID` yapar; sessions ve file_cleanup_jobs kimlikleri dışarı açılmadığı için `BIGINT` kalır. V2 hazırlandığı sırada bu tablolara API yazmadığı için migration tabloları yeniden oluşturur; içlerinde satır varsa veri silmek yerine açıklayıcı bir hatayla durur. `V3` yabancı anahtarların silme davranışını açıkça `RESTRICT` yapar ve fotoğraf pozisyonu tekilliğini ertelenebilir (`DEFERRABLE`) hale getirir. Kategori ve duygu değerleri İngilizce sabitlerle saklanır (`TRAVEL`, `HAPPY` vb.); Türkçe etiketler istemcide gösterilir.
- Fotoğraf sınırı dosya başına 5 MB = 5.000.000 bayttır. Kapak, anının mevcut en küçük `position` değerine sahip fotoğrafıdır. Pozisyonlar yeniden numaralandırılmaz: kapak silinince sıradaki fotoğraf kapak olur, yeni fotoğraf `max(position) + 1` alır. Anı başına 5 fotoğraf sınırını servis, anı satırını kilitleyerek (`SELECT ... FOR UPDATE`) sayar ve uygular. Ürün kararı: ilk sürümde sıra eklenme sırasıdır; kullanıcı kapağı veya fotoğraf sırasını elle değiştiremez. İleride sıralama eklenirse `uq_photos_memory_position` ertelenebilir olduğu için bir transaction `SET CONSTRAINTS uq_photos_memory_position DEFERRED` ile pozisyonları yer değiştirebilir.
- Silme politikası ([ADR-0002](adr/0002-silme-politikasi.md)): Kalıcı silme uygulanır, yumuşak silme ve veritabanı cascade'i kullanılmaz. Yabancı anahtarlar `ON DELETE RESTRICT` olduğu için servis bağımlı kayıtları tek transaction'da sırayla siler. Anı silmede önce `file_cleanup_jobs` kayıtları eklenir, sonra fotoğraflar ve anı silinir. Hesap silmede (KVKK talebi) tüm anılar bu şekilde, ardından oturumlar ve en son kullanıcı silinir. Fiziksel dosyalar commit sonrasında temizlenir.
- Tekrar denemeler (idempotency): Anı oluşturma isteği istemcinin ürettiği `client_request_id` (UUID) ile gelir; aynı hesapta bu anahtar tekildir. Sunucu oluşturma isteğinin SHA-256 özetini `client_request_sha256` olarak saklar. Aynı anahtar ve aynı özetle gelen tekrar yeni anı üretmez, mevcut anıyı döndürür; aynı anahtarla farklı içerik çakışma (409) olarak reddedilir. Fotoğraf yükleme de aynı kuralı izler: `client_photo_id` anı içinde tekildir, dosya içeriğinin SHA-256 özeti `content_sha256` olarak saklanır; aynı anahtar ve aynı içerik mevcut fotoğrafı döndürür, farklı içerik 409 alır. Yanıt kodları ve özetin hangi alanlardan hesaplanacağı ML-03 API sözleşmesinde kesinleştirilir.
- Veritabanına ulaşılamazsa uygulama açılmaz ve nedeni ile yapılacakları kısa bir hata mesajıyla bildirir. Çalışırken `GET /api/health` veritabanı erişilebilirse 200 `{"status":"UP","database":"UP"}`, değilse 503 `DOWN` döner.
- Veritabanı testleri test JVM'i başına tek bir `postgres:17` Testcontainers örneğini paylaşır (singleton container); temiz şema gereken test bu container içinde ayrı bir veritabanı açar. Docker yoksa bu testler yalnızca yerelde atlanır, CI'da atlanmaz. Veritabanı gerektirmeyen testler Docker'a ihtiyaç duymaz.

## SOLID

- Controller HTTP doğrulama/eşlemeye, service iş kurallarına, repository veri erişimine odaklanır (SRP).
- Değişen dış servisler için dar arayüzler ve bağımlılık enjeksiyonu kullanılır (OCP, DIP).
- Arayüz uygulamaları aynı davranış sözleşmesini korur; sözleşme testleri eklenir (LSP).
- Kullanılmayan metotları zorunlu kılan geniş arayüzler oluşturulmaz (ISP).

ML-27 hesap/oturum akışı controller, service ve repository olarak ayrılmıştır; JPA entity'leri dış API yanıtı olarak kullanılmaz. Giriş limiti doğrulama işlemini bir Supplier üzerinden çalıştırır; parola veya AuthService nesnesini bilmez. Anı/fotoğraf özellikleri kendi görevlerinde geliştirilecektir; bu teslim tüm uygulamanın SOLID uyumunun tamamlandığı iddiası değildir.

## Transaction

Kalıcı veri katmanı eklendiğinde bir kullanım senaryosunun tüm yazmaları service katmanındaki tek transaction sınırında yürütülmelidir. Spring transaction yönetimi ve `@Transactional` kullanılacaktır; checked exception rollback kuralları açıkça seçilmelidir. Dış HTTP çağrıları uzun DB transaction'ı içinde tutulmamalıdır. Auth akışı için ağır parola hesabı bu sınırın dışındadır: AuthService transaction başlatmaz; tek hesap/oturum yazmaları AuthRepository'de kısa transaction'larla yapılır. Birden çok anı/fotoğraf yazmasını atomik yapan service transaction'ı kuralı ilgili özellikler için korunur.

Kabul testleri: başarılı işlemde tüm kayıtlar commit olur; ikinci yazmada hata üretildiğinde ilk yazma da rollback olur; eşzamanlı güncellemede veri kaybı engellenir (örneğin optimistic locking); yetkisiz erişimde hiçbir kayıt değişmez. Üretimde kullanılacak DB ile entegrasyon testi gerekir. PostgreSQL bağlantısı ve şema kuruldu; ML-27 hesap/oturum repository yazmaları kısa transaction içindedir; Argon2 encode/verify işlemi transaction dışında ve ortak Semaphore sınırı altındadır. Anı silme, çoklu yazma rollback ve optimistic locking uygulaması ilgili ML-13/ML-21 görevlerinde doğrulanacaktır.

## Fotoğraf yükleme ve saklama (ML-09)

Fotoğraf işleri `backend/photo` paketindedir ve ADR-0003'teki kurallara uyar: kullanıcı `@CurrentUser` ile alınır, her yükleme tek transaction içinde ilk iş `OwnershipGuard.lockMemory` çağırır. Kilit altında sırasıyla tekrar anahtarı (aynı anahtar ve aynı içerik 200, farklı içerik 409), anı başına 5 adet sınırı ve yeni sıra numarası belirlenir; tekrar kontrolü sınırdan önce yapıldığı için başarılı bir yüklemenin tekrarı altıncı fotoğraf sayılmaz.

Dosya türü uzantıya veya istemcinin beyan ettiği türe değil dosyanın ilk baytlarına bakılarak belirlenir; beyan edilen tür bununla eşleşmelidir. Sınır sözleşmedeki gibi 5.000.000 bayttır ve kodda denetlenir; servlet multipart sınırı yalnız çok daha büyük istekleri erken durdurur ve aynı 413 gövdesini döner. Bu durumda sunucu gövdenin tamamını okumadan bağlantıyı kapatabildiği için istemci yanıtı alamayıp bağlantı hatası da görebilir; mobil 5 MB kuralını yüklemeden önce kendisi denetlediğinden bu yalnız kurala uymayan istemcileri etkiler. İstemcinin dosya adı hiçbir yerde kullanılmaz, dosya rastgele bir UUID adıyla saklanır. Fotoğraf kurallarının hataları `PhotoErrors` ile sözleşmedeki `{code,message}` gövdesine çevrilir; veritabanı kesintisi (503) ve beklenmeyen hata (500) için, ADR-0003'teki tek yer kuralı gereği, anı ve hesap uç noktalarının kullandığı ortak `AuthErrors` işleyicileri kullanılır. İstemci yalnız genel bir gövde aldığı için bu işleyiciler sunucu logunu tek iz olarak bırakır: beklenmeyen 500 hatası istek yöntemi, yolu ve hata iziyle `ERROR`, veritabanı kesintisi (oturum filtresinde de) `WARN` olarak yazılır. 405/415 gibi istemci hataları ve bağlantıyı kendisi kapatan istemci hata olarak loglanmaz. Loga başlık, sorgu metni veya istek gövdesi yazılmaz; bunlar token ve parola taşıyabilir.

Saklama `PhotoStorage` arayüzünün arkasındadır; servis yalnız bu arayüzü bilir. İlk uygulama `LocalPhotoStorage` dosyaları backend'in diskinde tutar; dosya önce geçici adla yazılıp tek adımda gerçek adına taşınır. Dosya diske veritabanı satırından önce yazıldığı için transaction rollback olursa bir `TransactionSynchronization` dosyayı siler; böylece başarısız yükleme diskte iz bırakmaz. İki adım arasında süreç çökerse sahipsiz dosya kalabilir; bu dosyalar [dosya temizliği](#dosya-temizliği-ml-14) taramasıyla silinir.

| Seçenek | Karar |
| --- | --- |
| Backend diski | Seçildi. ADR-0002'deki dosya temizleme işleri ve şemadaki `storage_key` alanı bu modele göre tasarlandı; ek servis veya hesap gerektirmez. Sınırı: ortak veritabanında her backend yalnız kendi yüklediği dosyaları sunar (README). |
| Veritabanında `bytea` | Ortak veritabanında herkes aynı dosyayı görürdü, ancak ücretsiz plandaki veritabanı boyut sınırı fotoğraflarla hızla dolar ve her liste/yedek işlemi büyür. |
| Supabase Storage / nesne deposu | Ortak dosya sağlar, ancak ek kimlik bilgisi ve Supabase'e özel istemci getirir; ekip Supabase'i şu an yalnız PostgreSQL olarak kullanıyor (Data API kapalı, README). Gerekirse `PhotoStorage` için yeni bir uygulama yazılarak servis değiştirilmeden geçilebilir. |

Güvenlik: yükleme OWASP dosya yükleme önerilerine göre ele alındı. İstemcinin dosya adı ve yolu kullanılmaz, tür içerikten belirlenir, sunucu görüntüyü açıp işlemediği için görüntü bombası riski yoktur. Disk yolu ve `storage_key` hiçbir yanıtta yer almaz. İçerik yanıtı `nosniff` ve `Content-Security-Policy: default-src 'none'; sandbox` ile gönderilir; özel hazırlanmış bir dosya tarayıcıda açılsa bile betik çalıştıramaz. Bilinen sınırlar: kullanıcı başına disk kotası ve yükleme hız sınırı yoktur (anı başına 5 fotoğraf sınırı dışında); fotoğraflar gelen EXIF bilgisiyle saklanır ve yalnız sahibine sunulur. Mobil uygulama GPS konumunu yüklemeden önce siler (ML-10); başka bir istemci konumlu dosya gönderirse sunucu onu değiştirmez.

Henüz yapılmayanlar: fotoğraf silme uç noktası ve silinen kaynağın anahtarının emekliye ayrılması (409 `IDEMPOTENCY_KEY_RETIRED`) ayrı bir iş olarak eklenecektir. Emekli anahtarlar için yeni bir sürümlü migration ve tablo gerekir. Silme işlemi dosyasını ML-14'teki temizlik mekanizmasıyla kaldırır. Başarılı yükleme anının `memories.version` değerini (V5) aynı transaction'da artırır; böylece anı detayının ETag'i değişir, tekrar ve reddedilen yükleme sürümü değiştirmez.

## Dosya temizliği (ML-14)

Veritabanı transaction'ı bir dosya silmeyi geri alamaz. Bu yüzden ADR-0002'deki gibi kayıtlar transaction içinde, dosyalar commit'ten sonra silinir. Mekanizma `photo/PhotoFileCleanup` sınıfındadır. Fotoğraf satırı silen her işlem (anı silme ML-13, ileride fotoğraf ve hesap silme) kendi transaction'ı içinde `deleteAfterCommit(storageKeys)` çağırır:

1. Aynı transaction içinde her dosya için bir `file_cleanup_jobs` satırı yazılır ve bir olay yayınlanır. Transaction rollback olursa iş satırları da geri alınır, olay işlenmez. Hâlâ kullanılan bir dosya bu yüzden hiçbir zaman silinmez.
2. Commit başarılı olunca `@TransactionalEventListener(phase = AFTER_COMMIT)` dosyaları siler ve iş satırlarını kaldırır. Bu adım ayrı bir transaction'da ama asenkron değil, aynı thread'de çalışır: Spring onu commit'ten sonra `afterCompletion` içinde çağırır. Kullanıcının silme yanıtı birkaç yerel dosya silme süresi kadar bekler. Bu adımda çıkan hata Spring tarafından yakalanıp loglanır, çağırana iletilmez; commit edilmiş silme başarılı yanıt verir ve kalan işi `retryDue` tamamlar. `@Async` bekleyişi kaldırırdı ama ayrı bir thread havuzu ve hata yönetimi gerektirirdi; birkaç dosya silme için buna gerek görülmedi.
3. Silinemeyen dosya için hata loglanır ve iş satırı kalır. Satıra deneme sayısı, son hata ve sonraki deneme zamanı yazılır; bekleme 1, 2, 4… dakika diye artar, en fazla bir gündür. Zamanlanmış `retryDue` görevi vakti gelen işleri tekrar dener. Commit'ten hemen sonra süreç kapanırsa da işler kayıtlı olduğu için yeniden başlatmada kaybolmaz. `FOR UPDATE SKIP LOCKED` aynı işin iki kez işlenmesini önler.

Metot `Propagation.MANDATORY` ile işaretlidir: transaction dışında çağrılırsa beklenecek bir commit olmadığından hata verir ve dosyaya dokunmaz. Çağıranlar yalnız sildikleri fotoğraf satırlarından okudukları anahtarları verir, istekten gelen değeri vermez. Buna rağmen dosya silinmeden hemen önce veritabanına bir kez daha sorulur: bir fotoğraf satırı o dosyayı hâlâ kullanıyorsa dosya silinmez, iş kaldırılır ve uyarı loglanır. Böylece yanlış sırayla ya da yanlış listeyle yapılan bir çağrı bile kullanıcının fotoğrafını silemez. Depolamanın üretmediği bir anahtar (ör. `../dosya`) dosya sistemine hiç ulaşmaz; böyle bir iş tekrar denenmez, kaldırılır. Değerin kendisi log'a yazılmaz.

İş satırı yazılmayan durumlar için `sweepOrphans` görevi vardır. Yarım kalmış `upload-*.tmp` dosyaları bir saatten eskiyse silinir. Hiçbir fotoğraf satırının kullanmadığı fotoğraf dosyası ise silinmez, fotoğraf klasöründeki `quarantine` klasörüne taşınır. Backend boş, hatalı ya da eski bir yedekten geri yüklenmiş bir veritabanına bağlanırsa bütün fotoğraflar sahipsiz görünür; silmek gerçek fotoğrafları yok ederdi. Karantinadaki dosya, karantina süresi (`memorylane.photos.quarantine-lifetime`, varsayılan 7 gün) dolunca silinir; bu süre içinde gerekirse elle fotoğraf klasörüne geri taşınabilir. Süre dolduğunda dosyayı yeniden bir fotoğraf satırı kullanıyorsa (doğru veritabanına dönülmüşse) dosya silinmez ve uyarı loglanır. Bir saatten yeni dosyalara dokunulmaz, çünkü yükleme dosyasını satırından bir an önce yazar ve o satır commit olana kadar görünmez.

Tarama tek bir dosyada hata alırsa (kilitli ya da okunamayan dosya) o dosyayı loglayıp atlar ve diğerleriyle devam eder; atlanan dosya bir sonraki taramada yeniden denenir. Tarama yalnız klasör listelenemezse ya da veritabanı yanıt vermezse durur; o durumda o çalışmada hiçbir dosya taşınmaz ya da silinmez. Görev aralıkları `memorylane.photos.cleanup-retry-delay` (varsayılan 5 dakika) ve `memorylane.photos.orphan-sweep-delay` (varsayılan 6 saat) ayarlarıyla değiştirilir.

Testler (`PhotoFileCleanupIntegrationTest`, gerçek PostgreSQL): commit edilen silmede dosya ve iş satırı kalkar; kasıtlı hatayla geri alınan silmede dosya ve satırı yerinde kalır; silinemeyen dosya loglanır, sonraki denemeye kadar beklenir ve sonra silinir; transaction dışındaki çağrı reddedilir; hâlâ kullanılan dosya ve geçersiz anahtar silinmez; tarama eski sahipsiz dosyaları karantinaya alır, yarım dosyaları siler ve kullanılan fotoğrafa dokunmaz; karantina süresi dolan dosya silinir, dolmayan kalır. Mobil taraf galerideki asıl dosyalara dokunmaz, yalnız uygulamanın kendi önbelleğindeki kopyaları siler (ML-10).

Bilinen sınır: Fotoğraflar her geliştiricinin kendi diskinde, veritabanı ise ortaktır. Bir geliştiricinin sildiği fotoğrafın iş satırını başka bir geliştiricinin backend'i işlerse dosyayı kendi diskinde bulamaz ve işi tamamlanmış sayar. Dosya ilk backend'de kalır, o backend'in taraması onu karantinaya alır ve süre dolunca siler. Backend farklı bir veritabanına bağlanırsa o veritabanında satırı olmayan fotoğraflar silinmez, karantinaya alınır ve 7 gün boyunca elle geri taşınabilir. Yerelde migration denemeleri için ortak veritabanı yerine yerel PostgreSQL kullanılması önerilir. Tek sunuculu gerçek kullanımda bu durum oluşmaz.

## Statik analiz: A hedefi

Analiz aracı ve A ölçütü öğretim elemanından doğrulanacaktır. SonarQube/SonarCloud adaydır; seçilmiş araç veya alınmış not olarak gösterilmez. Araç seçilirse kullanılan sürüm ve değerlendirme modu rapora yazılmalıdır. Quality gate geçişi ayrıca kaydedilir. Sonuçlar proje anahtarı, commit SHA ve analiz bağlantısıyla ilişkilendirilmelidir. Henüz Sonar sunucusu/proje anahtarı verilmedi ve analiz çalıştırılmadı; A elde edildiği iddia edilmez.

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
- OCP: Bir sınıfın davranışı, sınıfın kendisi değiştirilmeden genişletilebilir. `HealthViewModel` yalnız `HealthRepository` arayüzünü bilir; bellekte çalışan veya önbellekli yeni bir repository eklemek ViewModel'de değişiklik gerektirmez. Ağ katmanında `ParseErrorConverterFactory`, Gson dönüştürücüsünü sarmalayarak dönüştürme hatalarını ayırt etme davranışı ekler; Retrofit ve Gson koduna dokunulmaz.
- LSP/DIP: ViewModel somut sınıfa değil `HealthRepository` gibi arayüze bağlıdır; uzak uygulama ile testteki sahte uygulama aynı sözleşmeyle değiştirilebilir.
- ISP: Repository arayüzleri özellik başına dardır; tek bir genel "Api" arayüzü kurulmaz.

Paketleme SOLID'den ayrı bir karardır: kod özellik bazlı (package by feature) düzenlenir ve yüksek bağlılık (cohesion) hedeflenir. `core/` (config, di, network, ui) bütün ekranların ortak altyapısı, `feature/<özellik>/data` ve `feature/<özellik>/ui` özelliğe ait koddur; bir özelliğin dosyaları bir arada durur ve yeni özellik kendi paketiyle eklenir.

Bağımlılık enjeksiyonu elle yapılır: `MemoryLaneApp` açılışta tek bir `AppContainer` kurar ve somut sınıfları bilen tek yer orasıdır. ViewModel'ler fabrika ile bağımlılıklarını yapıcıdan alır.

Ağ katmanında tek Retrofit/OkHttp istemcisi ve Gson kullanılır. `ApiCaller` her sonucu `ApiResult` (veri veya `ApiError`) olarak döndürür ve hatayı türüne ayırır: NETWORK (yanıt yok: sunucu kapalı, adres yanlış, zaman aşımı), HTTP (2xx dışı; sözleşmedeki `{code,message}` gövdesi okunur), PARSE (eksiksiz alınmış ama dönüştürülemeyen 2xx gövde). PARSE ayrımı hata türünden tahmin edilmez: OkHttp de yanıt ortasında kopan bağlantıda `EOFException` fırlatabildiği için `ParseErrorConverterFactory` gövdeyi önce tamamen okur, yalnız dönüştürme aşamasındaki hatayı `ResponseParseException` ile işaretler; diğer her `IOException` bağlantı hatasıdır. Ekran kararları mesaj metnine değil tür ve `code` alanına göre verilir ([API sözleşmesi](api-contract.md)). `UiState` içinde LOADING, SUCCESS, EMPTY ve ERROR ayrı durumlardır; liste ekranları `UiState.fromList` kullanır ve yalnız başarılı ama boş yanıt EMPTY olur. Bağlantı hatası boş listeye çevrilmez. Ortak `StateView` hata durumunu ikon ve "Tekrar dene" ile, boş durumu bunlar olmadan gösterir.

Sunucu adresi `mobile/local.properties` içindeki `memorylane.apiBaseUrl` değeridir; yoksa emülatörün ana bilgisayar adresi `http://10.0.2.2:8080/` kullanılır. Değer BuildConfig'e derlenir; şemasız veya boş değer açılışta açık hatayla durur. Düz HTTP yalnız debug manifestinde açıktır; release derlemesi Android'in varsayılan cleartext engelini korur. Bu yüzden adres `https://` değilse release derlemesi (`preReleaseBuild`) açık bir hatayla durur; aksi halde APK sorunsuz derlenir ama her istek nedeni ekranda görünmeyen bir bağlantı hatası verirdi. Debug derlemesi, lint ve CI bu kontrolden etkilenmez. Release için HTTPS adresi yayın hazırlığında belirlenecektir.

Tasarım değerleri "Warm Nostalgia Scrapbook" tasarım sisteminden `res/values` altına aktarılır (renk, ölçü, yazı ve bileşen stilleri); ana renk tasarım sistemi belgesindeki `#D93678`'dir. Layout'lar hex, dp veya sp değeri yazmaz; bu kaynakları kullanır. Ortak bileşenler `ScreenHeaderView` (ekran başlığı), `StateView` (yükleniyor/hata/boş), birincil/ikincil/ghost buton, kart, form alanı ve alt menü stilleridir. Henüz bir ekranda kullanılmayan stiller `tools:ignore="UnusedResources"` ile işaretlidir; kullanıldıklarında işaret kaldırılır. Plus Jakarta Sans fontu çevrimdışı ve Google Play servisleri olmayan cihazlarda da aynı görünmesi için OFL 1.1 lisansıyla uygulamaya gömülüdür (`mobile/third_party/plus-jakarta-sans/OFL.txt`).

Giriş ve kayıt ekranları (`feature/auth/ui`) aynı deseni izler: alan doğrulaması ViewModel'dedir ve JVM testleriyle sınanır, Fragment yalnız durumu çizer. Ortak e-posta biçimi ve sınırlar (e-posta en fazla 254, şifre 15–128 Unicode karakter) `AuthInputRules` sınıfındadır; bunlar yalnız erken geri bildirim içindir, asıl doğrulama sunucudadır ve sözleşmedeki sınırlar değişirse bu sınıf da güncellenir. Şifre kırpılmaz, değerler sunucuya olduğu gibi gönderilir. Kayıt oturum açmadığı için token işlenmez; başarılı kayıtta bilgi mesajıyla giriş ekranına dönülür. `EMAIL_IN_USE` e-posta alanında, alana bağlı olmayan sunucu hataları (bağlantı, 429, 503) formdaki genel hata satırında gösterilir. İstek sürerken form kilitlenir ve tekrar gönderim engellenir. `MainActivity`, giriş, kayıt ve anı oluşturma hedeflerinde alt menüyü gizler ve klavye için alt boşluk bırakır.

### Anı ekranları (ML-07)

Anılar sekmesi (`MemoriesFragment`) ve oluşturma ekranı (`MemoryCreateFragment`) bu katmanları izler. Liste ekranı yalnız `MemoryListRepository`, oluşturma ekranı yalnız `MemoryCreateRepository`, detay ekranı yalnız `MemoryDetailRepository`, düzenleme ekranı `MemoryDetailRepository` ve `MemoryEditRepository` arayüzlerini bilir (ISP/DIP); `AppContainer` hepsine aynı uzak `MemoryRepository` örneğini verir. Testler arayüzlerin sahte uygulamalarıyla çalışır.

- Liste durumu tek bir değişmez `MemoryListUiState` nesnesidir: SIGNED_OUT, LOADING, CONTENT, EMPTY, ERROR ve CONTENT içinde `loadingMore`, `hasMore` ve satırlar korunarak taşınan sayfa/yenileme hatası. Fragment tek bir `render(state)` ile çizer.
- `MemoryListMapper` yüklenen bütün sayfaları birlikte satırlara çevirir: en yeni anı üstte, her yılın önünde bir yıl başlığı, sayfa sınırında tekrar eden kayıt bir kez. Sözleşmeye uymayan kayıt (kimlik, başlık veya `YYYY-MM-DD` tarihi yok) gizlenmez; ekran PARSE hatası gösterir.
- `MemoriesViewModel` aynı anda tek liste isteği çalıştırır. Yenileme ve oturum değişikliği süren isteği iptal eder; her isteğin nesil numarası olduğu için geç gelen yanıt yok sayılır. Yenileme sırasında satırlar ekranda kalır. Herhangi bir istekte 401 oturumu kapatır, satırları siler ve ekrana token'ı temizletir.
- Sunucudaki `MemoryInput` kuralları istemcide erken geri bildirim için ortak `MemoryFormRules` sınıfında toplanır: başlık 1–100, açıklama en fazla 3000 Unicode karakter, tarih zorunlu ve gelecekte olamaz; konum isteğe bağlıdır, ama girilirse enlem (±90) ve boylam (±180) birlikte ve aralık içinde olmalıdır. Oluşturma (`MemoryCreateViewModel`) ve düzenleme (`MemoryEditViewModel`) akışları aynı sınıfı kullanır. Düzenleme formunda konum alanı olmadığı için konum değiştirilmeden geri gönderilir; konum girişi eklendiğinde aynı kural kullanılacaktır. `MemoryCreateViewModel` içinde aynı taslağın yeniden denenmesi aynı `Idempotency-Key`'i kullanır; taslak değişince yeni anahtar alınır. Kayıt başarılı olunca anının kimliği ViewModel'de tutulur ve yeni kayıt isteği gönderilmez; seçilen fotoğraflar bu anıya yüklenir (aşağıda "Mobil fotoğraf seçimi ve yükleme"). Ekran işi bitince Navigation'ın `SavedStateHandle`'ı ile liste girişine sonuç bırakır; liste bir kez yenilenir.
- Fotoğraf seçimi, hazırlama ve yükleme yalnız oluşturma ekranındadır; liste ekranı yalnız listeyi gösterir ve oluşturma ekranına geçirir. Yükleme kuyruğu ekranın `PhotoSelectionViewModel`'inde yaşadığı için ekran fotoğraflar bitene kadar açık kalır: form kilitlenir, ilerleme çubuğu görünür, kapat düğmesi kapanır ve geri tuşu yalnız bilgi mesajı gösterir. Kayıt sonrası adım `MemoryCreateViewModel.afterSave` ile belirlenir ve birim testiyle sınanır: yükleme sürüyor ya da "Tekrar dene"/"Vazgeç" bekleniyorsa ekran açık kalır; sunucunun reddettiği fotoğraf varsa neden okunabilsin diye kapat düğmesi beklenir; aksi halde (fotoğrafsız kayıt, hepsi yüklendi, "Vazgeç") listeye dönülür. Fotoğraf yüklemesindeki 401, anı kaydındaki gibi oturumu kapatıp listeye döner.
- Alan metinleri ViewModel'de tutulur ve `saveEnabled="false"` alanlara görünüm oluşturulurken izleyiciler bağlanmadan önce yazılır; ekran döndürme hataları silen bir kullanıcı düzenlemesi gibi görünmez. Gezinme düğmeleri hızlı çift dokunmaya karşı `getCurrentDestination()` ile korunur.
- Çıkış Profil sekmesindedir. Token `AppContainer`'dan silinir ve sunucuda iptal edilir; Anılar sekmesi görünümü geri geldiğinde `setSession(null)` ile listeyi sıfırlar.

Anı ekranları ortak kart stilinden türeyen `Widget.MemoryLane.Card.Scrapbook` stilini kullanır: saf beyaz zemin, 32dp köşe ve `#F0D7E8` kenarlık. Material 3'ün yüksekliğe göre birincil renk tonlaması yalnız bu kartlarda kapatılır; ortak `Widget.MemoryLane.Card` değişmez. Kategori, duygu ve konum alanları ML-08 ile oluşturma ekranına eklendi; kuralları aşağıdaki [Anı formu kuralları (ML-08)](#anı-formu-kuralları-ml-08) bölümündedir. Düzenleme formu kategoriyi seçtirir, duygu ve konumu değiştirmeden geri gönderir (aşağıda). Kategori/duygu kodları ve Türkçe etiketleri tek kaynaktadır (`MemoryLabels`, metinler `memory_labels_strings.xml`); oluşturma ve düzenleme formlarının seçenekleri, liste ve detay rozetleri buradan gelir.

### Zaman çizelgesi, anı detayı, fotoğraf görüntüleyici ve düzenleme (ML-11, ML-12)

Ekranlar Stitch "Anılar", "Anı Detayı", "Fotoğraf Görüntüleyici" ve "Anıyı Düzenle" tasarımlarını izler. Hedefler `nav_graph.xml` içinde `memoriesFragment → memoryDetailFragment → memoryPhotoViewerFragment / memoryEditFragment` zinciridir; hepsi `memoryId` argümanı alır, `SessionRoutes` gereği korumalıdır ve alt menüyü gizler.

- **Zaman çizelgesi:** `MemoryTimelineDecoration` bütün liste için tek, kesintisiz kılavuz çizgiyi çizer; yıl satırında çift yıllar pembe, tek yıllar mor düğümle gösterilir. `coverPhotoId` olan kart kapağı ve "X fotoğraf" rozetini, olmayan kart sade metin varyasyonunu gösterir. Kategori, duygu ve konum rozetleri yalnız değer varsa görünür; Türkçe etiketler `MemoryLabels` içindedir, konum adres çözümleme yapılmadan koordinat olarak yazılır. Anının saati yoktur (`memoryDate` gün bilgisidir), bu yüzden kartta saat gösterilmez.
- **Detay:** `MemoryDetailViewModel` tek istek ve nesil numarasıyla LOADING, CONTENT, NOT_FOUND (404; yabancı ve olmayan anı aynıdır), ERROR ("Tekrar dene") ve SIGNED_OUT (401) durumlarını yönetir; sözleşmeye uymayan detay PARSE hatasıdır. Galeri `RecyclerView` + `PagerSnapHelper` ile yapılır (ViewPager2 eklenmedi); fotoğraf yoksa galeri, konum yoksa konum kartı tamamen gizlenir. Silme kontrolü yoktur (ML-13).
- **Fotoğraf görüntüleyici:** Detaydan dokunulan fotoğrafla açılır (`photoIds`, `initialPosition`, başlık ve tarih argümanları). Sayfalar ekran boyutuna göre örneklenmiş fotoğrafı, yüklenirken bir göstergeyi, hata halinde "Tekrar dene"yi gösterir; görüntüleyici açıkken sistem çubukları gizlenir. Yakınlaştırma, paylaşma ve EXIF/konum meta verisi kapsam dışıdır.
- **Düzenleme:** Form açılırken detay GET ile alanlar ve güçlü ETag okunur; liste etiket kaynağı değildir. Başlangıç değerleri `hasUnsavedChanges()` için anlık görüntüdür; geri oku, sistem geri tuşu ve "İptal" değişiklik varsa onay ister. Kayıt `If-Match` ile PUT yapar ve duygu/konumu değiştirmeden geri gönderir, çünkü PUT eksik isteğe bağlı alanları temizler. 412'de taslak korunur ve kaydet kilitlenir; "Son Halini Yükle" son sürümü ve etiketini okuyup taslağın yanında gösterir, kullanıcı "Son Hali Kullan" ya da "Benimkini Koru" der. Otomatik yeniden gönderim yoktur; yeni kayıt kullanıcının açık eylemiyle, yeni etiketle yapılır. 400/428 sunucu mesajıyla gösterilir, 404 NOT_FOUND ve listeye dönüş, 401 oturum kapanışıdır. Alan kuralları oluşturma formuyla ortak `MemoryFormRules` içindedir.
- **Senkronizasyon:** Başarılı kayıttan sonra düzenleme ekranı `RESULT_UPDATED` değerini detay ve zaman çizelgesi girişlerinin `SavedStateHandle`'ına bırakır. Detay `refresh()` ile yeni sürümü okur (eski içerik okuma bitene kadar ekranda kalır), zaman çizelgesi göründüğünde ilk sayfayı yeniden yükler. Giriş yığında yoksa (ör. oturum düşmesiyle yığın değiştiyse) sonuç bırakılmaz.

**Oturumlu fotoğraf yükleme.** `core/image/PhotoImageLoader` korumalı içerik ucunu sabit yollu `PhotoApi.downloadPhotoContent` ile çağırır; Bearer başlığını ve 401'de oturum kapatmayı `AuthInterceptor` yapar, token URL'e yazılmaz. Sunucu `Cache-Control: no-store` döndüğü için çözülmüş görüntüler yalnız bellekte, heap'in sekizde biri kadar bayt bütçeli bir LRU'da tutulur; diske hiçbir şey yazılmaz. Önbellek anahtarı `(memoryId, photoId, genişlik, yükseklik)`'tir. Her sahip (`ImageView`) yalnız son isteğinin yanıtını alır; geri dönüştürülen liste hücresi eski isteği iptal eder, böylece yanlış kapak görünmez. Oturum bitince (çıkış veya 401) `SessionStore.Listener` önbelleği boşaltır, süren indirmeleri iptal eder ve geç yanıtları atar. `BitmapSampling` önce yalnız boyutları okur, hedefin altına düşmeyen ikinin kuvveti `inSampleSize` ile çözer (en fazla 16 MP) ve olduğu gibi yüklenen JPEG'lerin EXIF yönünü uygular; başka EXIF verisi okunmaz. Görüntü türü JVM testleri için parametredir (uygulamada `Bitmap`).

Testler: `MemoryDetailViewModelTest`, `MemoryEditViewModelTest` (yükleme, doğrulama, kaydedilmemiş değişiklik, duygu/konum korunarak PUT, 412 inceleme akışı, 404, 401, ağ hatası), `MemoriesViewModelTest` (düzenleme sonrası yenileme), `MemoryLabelsTest` (tek kod/etiket kaynağı), `MemoryFormRulesTest` (ortak konum kuralı), `MemoryListItemTest`, `MemoryListMapperTest`, `MemoryPhotoViewerFragmentTest`, `PhotoImageLoaderTest` (MockWebServer ve gerçek `AuthInterceptor`), `BitmapSamplingTest`, `MemoryRepositoryTest` (detay ETag'i, `If-Match` iletimi, 412/428/400/404/401 ayrımı), `ApiCallerTest` ve `SessionLogoutTest` (oturum sonunda fotoğraf yanıtının atılması). Backend tarafı `MemoryIntegrationTest`, `OwnershipIntegrationTest`, `PhotoIntegrationTest` ve `MemoryPreconditionTest` ile gerçek PostgreSQL üzerinde doğrulanır. Ekran akışları emülatörde yerel bir sahte API ile elle denendi; `BitmapFactory` ile çözme ve EXIF döndürme JVM'de değil emülatörde doğrulanır.

Bilinen sınırlar: Düzenleme kaydedilmeden kapatılırsa detay kendi yüklü sürümünü gösterir; başka cihazdaki değişiklik ancak yeniden açılınca görünür. Düzenleme ekranında fotoğraf ekleme/silme olmadığı için API sözleşmesindeki "fotoğraf işleminden sonra etiket yenileme" kuralı bu sürümde mobilde tetiklenmez. Görüntüleyicide yakınlaştırma yoktur.

Değerlendirilen alternatifler:

| Konu | Seçim | Alternatif ve seçilmeme nedeni |
| --- | --- | --- |
| Desen | MVVM + Repository | MVC'de iş mantığı Activity/Fragment içinde birikir ve test cihaz ister. MVP'de Presenter yaşam döngüsünü elle izler; AndroidX ViewModel ekran döndürmede durumu hazır korur. |
| DI | Elle `AppContainer` | Hilt, Java'da annotation processor ve ek Gradle eklentisi getirir; mevcut bağımlılık sayısında kazancı azdır. Bağımlılıklar yapıcıdan alındığı için ileride geçiş yalnız container'ı değiştirir. |
| Navigasyon | Navigation Component | Elle FragmentTransaction yönetiminde sekme geri yığını ve durum saklama tekrar yazılır. |
| HTTP/JSON | Retrofit + OkHttp + Gson | HttpURLConnection veya yalın OkHttp ile JSON dönüşümü ve hata eşlemesi her istekte tekrarlanır. |
| Paketler | Özellik bazlı | Katman bazlı (ui/data/model) yapıda özellik sayısı arttıkça ilgili dosyalar dağılır; ilk iskeletteki boş klasörler bu yapıyla değiştirildi. |

Bilinen sınırlar: Giriş ekranı PR #9, anı kaydı ve listesi PR #12, fotoğraf seçimi ve yüklemesi ML-10, kayıt ekranı ve giriş–kayıt geçişi ML-28 kayıt PR'ı ile bu temelin üzerine eklendi; ML-07 liste ve oluşturma ekranlarını yeniden yazdı, fotoğraf seçimini oluşturma ekranına taşıdı, oturum ve sunucu durumunu Profil sekmesine taşıdı. Anı detayı, fotoğraf gösterimi ve düzenleme ML-11/ML-12 ile eklendi (yukarıda); harita ve mobil arama ilgili kartlarda eklenecektir. Testler JVM birim testleridir (ağ katmanı MockWebServer ile gerçek HTTP üzerinden); cihaz üzerinde otomatik UI testi henüz yoktur, ekran akışları emülatörde elle doğrulanır. Kayıttan sonra e-posta giriş formuna otomatik aktarılmaz.

## Anı formu kuralları (ML-08)

Anı oluşturma formu backend'deki `MemoryInput` kurallarını erken geri bildirim olarak uygular; son karar her zaman backend'dedir.

- **Metin sınırları:** Başlık zorunludur ve baştaki/sondaki boşluklar atıldıktan sonra en fazla 100 karakter olabilir; açıklama en fazla 3000 karakterdir. Sayım backend gibi Unicode karakterle yapılır. Metin sessizce kesilmez, kaydet hata gösterir.
- **Tarih:** Zorunludur, bugün veya geçmiş bir gün olmalıdır. Seçici gelecek günleri kapatır; "Bugün" kısayolu cihazın saat dilimindeki bugünü seçer.
- **Kategori:** Tek seçimdir ve varsayılan `OTHER` (Diğer) seçilidir. Kodlar İngilizce sabitlerle gönderilir, Türkçe etiketler istemcide gösterilir (`MemoryLabels`).
- **Duygu ve konum:** İkisi de isteğe bağlıdır; boş bırakılırsa istek gövdesine yazılmaz. Konumda enlem (±90) ve boylam (±180) birlikte girilir, ondalık virgül de kabul edilir; bu kural oluşturma ve düzenleme formlarıyla ortak `MemoryFormRules` içindedir. Harita ile konum seçimi harita kartına bırakıldı.
- **Çift kayıt ve form kaybı:** Kaydederken bütün alanlar kilitlenir ve ikinci dokunuş yeni istek açmaz. Aynı içerik aynı `Idempotency-Key` ile yeniden denenir, içerik değişirse yeni anahtar üretilir. Alanlar ve seçimler ViewModel'de tutulduğu için ekran döndürmede ve başarısız istekte korunur. Kayıt başarılı olunca form kilitli kalır; seçilen fotoğraflar yüklendikten sonra ekran kapanır (ML-10).

## Mobil fotoğraf seçimi ve yükleme (ML-10)

Fotoğraf kodu `feature/photos` paketindedir ve anı oluşturma ekranının (`MemoryCreateFragment`) "Fotoğraflar" kartında kullanılır. Seçim sistemin fotoğraf seçicisiyle (`PickMultipleVisualMedia`) yapılır; uygulama depolama izni istemez ve yalnız kullanıcının seçtiği dosyaları görür. Seçilen her fotoğraf arka planda uygulamanın önbelleğine kopyalanır (`DevicePhotoPreparer`). Kopyalama bitmeden "Kaydet" kapalı kalır; böylece seçilip henüz hazırlanmamış bir fotoğraf anının dışında kalmaz. Kayıt isteği başladıktan sonra yeni fotoğraf seçilemez.

Hazırlama sözleşmedeki sınırları istemcide uygular: 5.000.000 baytı aşmayan JPEG ve PNG olduğu gibi gönderilir. Daha büyük dosyalar ve diğer biçimler (HEIC, WebP vb.) uzun kenarı en fazla 2560 piksel olacak şekilde küçültülüp EXIF yönüne göre döndürülür ve JPEG'e çevrilir. Kalite sınırın altına inene kadar adım adım düşürülür. Dönüştürme yalnız bir kez yapılır; tekrar denemede aynı baytlar ve aynı `Idempotency-Key` gönderilir. Dosya türü uzantıya göre değil ilk baytlara göre belirlenir. Kopya diskte en fazla 60 MB okunur, büyük görüntüler belleğe örnekleme (`inSampleSize`) ile alınır. Küçük bir dosya çok büyük boyut bildirebileceği için (görüntü bombası) bir seferde en fazla 16 megapiksel çözülür. Bellek yetmezse ya da dosya bozuksa yalnız o fotoğraf reddedilir, uygulama kapanmaz ve yarım kalan kopya silinir.

Yükleme sırası ve tekrar kuralları Android'den bağımsız `PhotoUploadQueue` sınıfındadır ve birim testleriyle doğrulanır. Fotoğraflar anı kaydedildikten sonra seçildikleri sırayla tek tek gönderilir. Kesin bir 4xx yanıtı (413, 415, 409 sınır) o fotoğrafı işaretler ve sıradakine geçilir. Bağlantı hatası, 5xx ya da okunamayan yanıtta sunucunun fotoğrafı saklayıp saklamadığı bilinemez. Bu durumda kuyruk durur ve "Tekrar dene" aynı fotoğrafı aynı anahtarla yeniden gönderir. 429'da `Retry-After` kadar (başlık yoksa 60 saniye, en fazla bir saat) beklenip aynı fotoğraf otomatik yeniden gönderilir. 401 oturumu kapatır; anı isteklerindeki oturum düşmesiyle aynı yol izlenir. Kuyruk tek bir anıya aittir: aynı anı için yeniden başlatılması hiçbir şeyi iki kez göndermez, başka bir anıya bağlanması ise reddedilir. Her oluşturma ekranı kendi kuyruğunu açar ve kaydettiği tek anıdan sonra yeni kayıt kabul etmez; böylece bir anının fotoğrafları başka bir anıya gidemez. `ApiError`, 429 yanıtındaki `Retry-After` değerini saniye olarak taşır.

Önbellekteki kopyalar sunucu fotoğrafı aldığında, kullanıcı fotoğrafı kaldırdığında, vazgeçtiğinde, oturum düştüğünde ve ekran kapandığında silinir; galerideki asıl dosyalara dokunulmaz. Tamamlanan yüklemede reddedilen fotoğraf varsa özeti ("Anı ve 3 fotoğraf kaydedildi; 1 fotoğraf kabul edilmedi: …") ekranda kalır, kullanıcı kapatınca listeye dönülür.

Bilinen sınırlar: Seçim ekranda tutulur, süreç sonlanırsa yarım kalan yüklemeler kaybolur (WorkManager ile arka plan yüklemesi değerlendirilmedi; oturum anahtarı yalnız bellekte olduğu için süreç dışında zaten kullanılamaz).

Gizlilik: Fotoğraftaki GPS konumu yüklemeden önce silinir. Anının konumu kullanıcının seçtiği ayrı bir alandır (ML-03) ve harita onu kullanır. Fotoğrafın EXIF konumuna hiçbir özellik ihtiyaç duymaz (README'de EXIF kapsam dışıdır); göndermek yalnız kullanıcının evini ya da bulunduğu yeri sunucuya taşırdı. Olduğu gibi gönderilen JPEG/PNG'de yalnız GPS etiketleri silinir; yön bilgisi kaldığı için fotoğraf doğru yönde görünür. Etiketler güvenle yeniden yazılamazsa fotoğraf JPEG'e çevrilir; dönüştürülen dosyada EXIF hiç kalmaz. Bu işlem ilk denemeden önce bir kez yapılır, tekrarlar aynı baytları gönderir.

## Veri saklama ve yedekleme kararı

Anıların kalıcı ve asıl kaydı backend'de tutulacak. Mobil cihazda sunucudan tekrar alınabilen veriler önbellek olarak ele alınacak; yeni cihazda yeniden giriş yapılıp sunucudaki kayıtlar getirilecek. Henüz sunucuya gönderilmemiş anılar ise önbellek sayılmayacak: başarılı sunucu onayı alınana kadar korunmalı ve kullanıcıya senkronizasyon durumları gösterilmelidir.

Parola ve oturum anahtarı kalıcı diske, bulut yedeğine veya device-transfer'a yazılmaz. İlk sürümde token yalnız süreç belleğindedir; Keystore ile kalıcı oturum kullanılmaz. Gerekçe [mobil oturum kararında](#mobil-oturum-saklama-kararı) kayıtlıdır. Anıların backend'de tutulması tek başına yedek değildir: sunucu veritabanı ve varsa fotoğraf depolaması için yedekleme, saklama süresi ve geri yükleme testleri ayrıca hazırlanmalıdır.

Bu iskelet aşamasında allowBackup=false ve mevcut yedekleme dosyaları korunur; device-transfer bu PR'da açılmaz. Mevcut XML'deki root dışlamasının bütün veri alanlarını veya her üreticinin cihaz transferi davranışını kapsadığı varsayılmaz. Yedek dışı hassas depolama, çevrimdışı kuyruk ve senkronizasyon uygulandığında izin verilen veri alanları tek tek belirlenip yedekleme/geri yükleme testiyle doğrulanmalıdır.

Bu bölüm veri saklama hedefini belirler. Anı/fotoğraf tabloları ML-04 migration'larında, hesap/oturum API'si ML-27'de bulunur. Anı iş API'si, mobil veri saklama ve geri yükleme akışı bu teslimde tamamlanmış sayılmaz; mevcut veriler için kayıpsız kurtarma garantisi verilmez.

## ML-27 veritabanı planı

ML-27'de planlanan veritabanı temeli ML-04 ile uygulandı: geçici DataSource dışlaması ve `database` profili kaldırıldı; uygulama her profilde PostgreSQL'e bağlanır. Şema Flyway ile `backend/src/main/resources/db/migration/` altında sürümlenir (V1 ilk şema, V2 UUID kimlikler ve tekrar deneme alanları), `ddl-auto=validate` entity eşlemelerini bu şemayla karşılaştırır. Entegrasyon testleri Testcontainers/PostgreSQL kullanır; yerel geliştirme için `backend/docker-compose.yml` vardır. Spring Boot'un otomatik Docker Compose desteği eklenmedi: uygulama açılışı Docker'a bağlı kalmasın ve aynı compose dosyası elle yönetilebilsin diye compose ayrı komutla başlatılır. Paylaşılan ekip veritabanı entegrasyon veya demo ortamı olarak kullanılabilir. ML-27, UserAccount/UserSession JPA eşlemelerini ve AuthRepository'yi bu şemaya bağlar. Parola encode/verify işlemi transaction dışında çalışır; hesap/oturum okuma ve yazmaları repository'de ayrı kısa transaction'larla yürütülür; eşzamanlı e-posta kaydı PostgreSQL INSERT ON CONFLICT ile tek hesapla sonuçlanır. Migration'lar değiştirilmez.

## Mobil oturum saklama kararı

İlk hesaplı Trello planıyla uyumlu olarak ilk sürümde Bearer token yalnız uygulama süreci belleğinde tutulacak. Şifre saklanmaz; refresh token, beni hatırla veya Keystore ile şifreli kalıcı oturum yoktur. Karar kontrollü ders demosunda kalıcı kimlik bilgisi, yedek dışlama ve anahtar yaşam döngüsü kapsamını büyütmemek içindir; tekrar giriş gerektirmesinin kullanıcı deneyimi maliyeti kabul edilir.

Android arka plandaki süreci sonlandırırsa token kaybolur ve kullanıcı yeniden giriş yapar. Yalnız arka plana geçmek veya Activity'nin dönme gibi nedenlerle yeniden oluşması çıkış değildir; token Activity yerine uygulama süreci düzeyinde yönetilmelidir. Sürecin kapanması sunucuya logout gönderilmiş sayılmaz; sunucunun 24 saatlik oturum süresi ve açık logout iptali kuralı değişmez. Kullanıcıya uygulamayı her durumda hatırlayan kalıcı giriş sözü verilmez.

Keystore ile şifreli kalıcı token, daha az tekrar giriş sağlayan alternatif olarak değerlendirildi; bu sürüme eklenmedi. Gerçek kullanımda bu tercih değişirse oturum saklama, yedek/cihaz aktarımı, iptal ve anahtar yönetimi birlikte ele alınacak ve mobil oturum geliştirilmeden önce yeni karar kaydedilecek. Bellek ve kalıcı saklama aynı sürümün çelişen iki yolu olarak uygulanmayacak.

### Oturum katmanı (ML-30)

Bu karar `core/session`, `core/network` ve `core/navigation` paketlerindeki üç parçayla uygulanır; ekranlar token'ı kendileri taşımaz.

- `SessionStore` oturumu (token ve e-posta) yalnız süreç belleğinde tutar ve `AppContainer` içinde tek örnektir. OkHttp iş parçacıkları ile ana iş parçacığı aynı anda eriştiği için senkronizedir. Oturum yalnız iki yolla biter: çıkış (`logout`, LOGGED_OUT) veya 401 (`expire`, EXPIRED). `expire` yalnız reddedilen token hâlâ geçerli token ise oturumu kapatır; eski hesabın geç gelen 401'i sonradan giriş yapan hesabı düşürmez. Dinleyiciler yalnız oturum gerçekten bittiğinde ve ana iş parçacığında çağrılır.
- `AuthInterceptor` korumalı her isteğe `Authorization: Bearer` başlığını ekler. Korumalı istek, yapılandırılan sunucudaki `api/v1/**` yoludur; `auth/login` ve `auth/register` bunun dışındadır, `api/health` zaten `api/v1` altında değildir. Token başka şema, sunucu veya porta hiçbir zaman gönderilmez. İstekte zaten bir `Authorization` başlığı varsa ona dokunulmaz. Korumalı istekte 401 gelirse gönderilen token ile `SessionStore.expire` çağrılır; giriş isteğindeki 401 yanlış şifre demektir ve oturuma dokunmaz.
- `SessionRoutes` oturumsuz açılabilecek hedefleri listeler: yalnız giriş ve kayıt. Listede olmayan her hedef, sonradan eklenenler de dahil, korumalıdır; unutulan bir hedef korumalı ekranı oturumsuz göstermez, giriş ekranına düşer. `SessionRoutesTest` bu listeyi `nav_graph.xml` ile karşılaştırır.
- `SessionNavigator`, `MainActivity` içinde NavController'a bağlanır. Oturum bittiğinde bütün geri yığını giriş ekranıyla değiştirir ve alt menünün sakladığı sekme yığınlarını da temizler; böylece korumalı ekranların ViewModel'leri silinir ve sonraki hesap öncekinden hiçbir şey görmez. Oturumsuz bir korumalı hedefe gidildiğinde de aynı yönlendirmeyi yapar: ilk açılış, süreç sonrası geri yükleme ya da sonraki her gezinme. Activity durumunu kaydettikten sonra fragment işlemi yapılamadığı için bekleyen yönlendirme `onStart`'ta uygulanır.

Ekranlardaki oturum düşmesi aynı yolu izler: anı listesi, detayı, düzenlemesi, anı kaydı, fotoğraf yüklemesi ve fotoğraf indirmesindeki 401 `SessionStore` üzerinden oturumu kapatır, giriş ekranına dönüşü `SessionNavigator` yapar. Testleri `SessionStoreTest`, `AuthInterceptorTest` (MockWebServer), `SessionRoutesTest` ve `SessionLogoutTest`'tir.

Arama SQL'de ve sayfalamadan önce yapılır; deterministic Türkçe ICU collation tasarımı ve COUNT eşleşmesi [API sözleşmesindedir](api-contract.md#türkçe-karşılaştırma-sqlde-yapılır). Fotoğraf işlemlerinin form taslağını kaybetmeden güncel ETag'i alması ve başka cihazın metnini ezmemesi de [aynı sözleşmede](api-contract.md#fotoğraf-işleminden-sonra-düzenleme-etiketini-yenileme) tanımlıdır. ML-07 liste aramasında bu SQL filtresi ve V4 collation kullanılır. Mobil düzenleme ekranı (ML-12) If-Match ve 412 kurallarını uygular; fotoğraf işleminden sonraki etiket yenileme, düzenleme ekranına fotoğraf işlemi eklendiğinde uygulanacaktır.

## ML-01 ders kriterleri ve alternatifler

Sabit kapsam README'deki [kapsam](../README.md#kapsam) bölümündedir. Java/XML Android, Java 17 / Spring Boot 4.1.1 / gömülü Tomcat ve PostgreSQL kullanılır. İlk hesaplı plan korunur; hesapsız demo kararı alınmamıştır. Anı ve fotoğraf erişiminde owner_id istemciden alınmayacak, doğrulanan oturumdan belirlenecektir. User, Session, Memory ve Photo ilişkileri [ML-03 sözleşmesinde](api-contract.md) tanımlıdır.

| Konu | Soru ve kayıtlı durum |
| --- | --- |
| Sunucu kabulü | Öğretim elemanı PR #2 incelemesinde gömülü Tomcat ile Spring Boot kullanımını bu dönem için kabul etti ([20 Ekim 2026 tarihli yorum](https://github.com/software-lecture-labs/memory-lane/pull/2#discussion_r4325681153)). Ekip bu seçeneği ADR-0001 ile seçti. Ayrı Jakarta EE sunucusu kullanılmaz. Bu kabul, yerel/uzak demo, TLS ve cihaz erişimi gibi bütün teslim ayrıntılarının ayrıca onaylandığı anlamına gelmez; bu ayrıntılar bekleyen varsayımlardır. |
| Analiz A ölçütü | Hangi araç/sürüm, mobil/backend kapsamı ve kalite kapısı esas alınacak? Araç ve ölçüt henüz doğrulanmadı; alınmış A notu yoktur. |

Controller HTTP sözleşmesini, service iş kurallarını/transaction'ı, repository kalıcılığı yürütür. Entity dış API sözleşmesinin yerine geçmez; request/response DTO'ları kullanılır. Örnek akış: mobil form → HTTP API → controller → service → repository → PostgreSQL. Sorumlulukların ayrılması ve constructor injection SOLID için başlangıçtır; iskelet tek başına tam uyum kanıtı sayılmaz.

| Alternatif | Karar ve gerekçe |
| --- | --- |
| Controller içinde doğrudan veri erişimi | Seçilmedi; HTTP, iş kuralları ve transaction aynı yerde toplanır. |
| Controller/service/repository katmanlı tek uygulama | Seçildi; dört kişilik ekipte sorumlulukları ayırır, tek backend dağıtımını korur. |
| Mikroservisler | Seçilmedi; kapsam için gerekli görülmeyen ayrı servis iletişimi ve dağıtımı ekler. |
| Jakarta EE/Payara | Öğretim elemanının PR #2 kabulü ve ADR-0001 doğrultusunda Spring Boot/gömülü Tomcat seçildi; ayrı Jakarta EE sunucusu kullanılmaz. |

| Ders kriteri | Karşılık ve doğrulama |
| --- | --- |
| Java ve sunucu | Java 17, Spring Boot ve çalıştırılabilir JAR; Spring Boot/gömülü Tomcat kabulü PR #2 yorumunda kayıtlı. Açılış/HTTP kanıtları ilgili özellik PR/CI kayıtlarından izlenir; demo ortamı ayrıntıları ayrıca netleştirilir |
| SOLID | Katman sorumlulukları ve bağımlılık enjeksiyonu; özellik PR'larında kod incelemesi ve davranış testleri |
| Kalıcılık | K4/ML-04 PostgreSQL/JPA/Flyway teslimi; repoda sürümlü migration, yeniden başlatma ve bağlantı hatası senaryoları |
| Transaction | Service üzerinde @Transactional; ML-13/ML-21 başarı ve rollback testleri. ML-14 dosya temizliği commit sonrası (`AFTER_COMMIT`) çalışır ve tekrar denenir; rollback testi `PhotoFileCleanupIntegrationTest` |
| Hesap ve sahiplik | ML-27/ML-29/ML-31 ile oturum ve iki hesap arasında CRUD/medya/arama/harita erişim ayrımı testleri |
| Statik analiz A | ML-06/ML-22 kapsamında doğrulanan araç, kapsam, ölçüt ve gerçek rapor; lint başarısı A sonucu yerine geçmez |
| Teslim kanıtı | Gerçek issue/PR/CI bağlantıları, başka üye incelemesi ve main üzerinde çalışan demo |

Şema ve veritabanı/test araçları güncel ML-04 bölümü ve ADR-0001'de anlatıldığı biçimde kullanılır. Geçici database profili ve DataSource dışlaması kaldırılmıştır; burada ikinci bir kurulum yolu tanımlanmaz. API alanları ve tekrar davranışları [ML-03 sözleşmesindedir](api-contract.md). Bu belge çalışması veritabanı veya container başlatmaz.

### ML-01 kontrol listesi

| Kart maddesi | Durum |
| --- | --- |
| Sunucu çalıştırma ortamı kabulünü sor | Spring Boot/gömülü Tomcat kabulü PR #2 yorumuyla doğrulandı ve ADR-0001 ile bağlandı; demo ortamı ayrıntıları bekleyen varsayım olarak kayıtlı. |
| Analiz aracını ve A ölçütünü sor | Araç, sürüm, mobil/backend kapsamı ve A ölçütü için öğretim elemanının yanıtı bekleniyor. |
| Katmanlı mimariyi ve alternatiflerini yaz | Bu belgede tamamlandı. |
| Sabit kapsamı README'ye bağla | Hesaplı ilk planın kapsamı README'ye işlendi ve bu belgeden bağlandı. |

ML-01 için kapsam, mimari gerekçe, alternatifler, kriter karşılıkları ve bekleyen varsayımlar kayıtlıdır. Sunucu teknolojisi kabulü kaynaklıdır; analiz ölçütü ve demo ayrıntıları için yanıt beklenir. Kartın belge kabulü yanıtların veya bekleyen varsayımların kaydını ister; bütün uygulama, analiz ve test işlerinin burada tamamlanmasını istemez. Başka üye incelemesi ve main'e alınması gerçekleşmeden ortak Bitti ölçütü sağlanmış sayılmaz.
