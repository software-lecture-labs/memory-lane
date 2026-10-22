# Mimari ve kabul gereksinimleri

## Java backend ve application server

Backend Java 17 üzerinde Spring Boot 4.1.1 ile çalışır; seçilen HTTP çalışma ortamı gömülü Tomcat'tir. `bootJar` çalıştırılabilir JAR üretir. Ayrı Jakarta EE application server/WAR dağıtımı bu kurulumda uygulanmadı. Ders şartı özellikle WildFly/Payara gibi tam Jakarta EE sunucusu gerektiriyorsa bu tercih öğretim görevlisiyle doğrulanmalı ve ayrı bir işte uyarlanmalıdır.

## SOLID

- Controller HTTP doğrulama/eşlemeye, service iş kurallarına, repository veri erişimine odaklanır (SRP).
- Değişen dış servisler için dar arayüzler ve bağımlılık enjeksiyonu kullanılır (OCP, DIP).
- Arayüz uygulamaları aynı davranış sözleşmesini korur; sözleşme testleri eklenir (LSP).
- Kullanılmayan metotları zorunlu kılan geniş arayüzler oluşturulmaz (ISP).

Mevcut paket iskeleti korunmuştur. Henüz iş kuralları veya repository uygulaması bulunmadığından SOLID uyumu tamamlandı iddiası yoktur.

## Transaction

Kalıcı veri katmanı eklendiğinde bir kullanım senaryosunun tüm yazmaları service katmanındaki tek transaction sınırında yürütülmelidir. Spring transaction yönetimi ve `@Transactional` kullanılacaktır; checked exception rollback kuralları açıkça seçilmelidir. Dış HTTP çağrıları uzun DB transaction'ı içinde tutulmamalıdır.

Kabul testleri: başarılı işlemde tüm kayıtlar commit olur; ikinci yazmada hata üretildiğinde ilk yazma da rollback olur; eşzamanlı güncellemede veri kaybı engellenir (örneğin optimistic locking); yetkisiz erişimde hiçbir kayıt değişmez. Üretimde kullanılacak DB ile entegrasyon testi gerekir. Bu kurulumda veritabanı, transaction uygulaması ve bu testler henüz yoktur.

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

## Veri saklama ve yedekleme kararı

Anıların kalıcı ve asıl kaydı backend'de tutulacak. Mobil cihazda sunucudan tekrar alınabilen veriler önbellek olarak ele alınacak; yeni cihazda yeniden giriş yapılıp sunucudaki kayıtlar getirilecek. Henüz sunucuya gönderilmemiş anılar ise önbellek sayılmayacak: başarılı sunucu onayı alınana kadar korunmalı ve kullanıcıya senkronizasyon durumları gösterilmelidir.

Parola, erişim/yenileme anahtarı ve cihaza özel kimlik bilgileri bulut yedeğine veya device-transfer'a dahil edilmeyecek. Mobil oturum saklama geliştirilirken Android Keystore ve yedek dışı depolama kullanılmalı; yeni cihaza geçişte kullanıcı yeniden giriş yapmalıdır. Anıların backend'de tutulması tek başına yedek değildir: sunucu veritabanı ve varsa fotoğraf depolaması için yedekleme, saklama süresi ve geri yükleme testleri ayrıca hazırlanmalıdır.

Bu iskelet aşamasında allowBackup=false ve mevcut yedekleme dosyaları korunur; device-transfer bu PR'da açılmaz. Mevcut XML'deki root dışlamasının bütün veri alanlarını veya her üreticinin cihaz transferi davranışını kapsadığı varsayılmaz. Yedek dışı hassas depolama, çevrimdışı kuyruk ve senkronizasyon uygulandığında izin verilen veri alanları tek tek belirlenip yedekleme/geri yükleme testiyle doğrulanmalıdır.

Bu bölüm hedef mimariyi belirler. Bu PR'da anı API'si, anı veritabanı, mobil veri saklama veya geri yükleme akışı henüz uygulanmadı; mevcut veriler için kayıpsız kurtarma garantisi verilmez.

## ML-27 veritabanı planı

Veritabanısız varsayılan profil geçici bir başlangıç çözümüdür. ML-27 kalıcılık katmanı eklenirken entity/repository taraması, datasource ve açılış testleri birlikte düzenlenecek; mevcut DataSource dışlaması ile `database` profilindeki boş exclude kalıcı çözüm olarak kullanılmayacak. Şema, ekip veritabanında elle yönetilmek yerine Flyway ile `backend/src/main/resources/db/migration/V1__...sql` altında repoda sürümlenecek. Migration uygulandıktan sonra `ddl-auto=validate`, entity eşlemelerini bu şemayla karşılaştıracak. Entegrasyon testlerinin paylaşılan veritabanından bağımsız çalışması için Testcontainers/PostgreSQL, yerel geliştirme için Spring Boot Docker Compose desteği planlanıyor; bunlar Docker erişimi gerektirir. Paylaşılan ekip veritabanı entegrasyon veya demo ortamı olarak kullanılabilir. Bu PR yalnız planı belgeler; Flyway, Testcontainers ve Compose kurulumu yapmaz, veritabanı/container başlatmaz ve mevcut veritabanı testini değiştirmez. Uygulama ve gerçek kalıcılık doğrulaması ML-27 kapsamında ayrıca yapılacak.
