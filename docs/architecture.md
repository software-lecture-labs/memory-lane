# Mimari ve kabul gereksinimleri

## Java backend ve application server

Backend Java 17 üzerinde Spring Boot ile çalışır; seçilen HTTP çalışma ortamı gömülü Tomcat'tir. `bootJar` çalıştırılabilir JAR üretir. Ayrı Jakarta EE application server/WAR dağıtımı bu kurulumda uygulanmadı. Ders şartı özellikle WildFly/Payara gibi tam Jakarta EE sunucusu gerektiriyorsa bu tercih öğretim görevlisiyle doğrulanmalı ve ayrı bir işte uyarlanmalıdır.

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
- https://docs.spring.io/spring-boot/3.5/system-requirements.html
- https://docs.gradle.org/8.9/userguide/gradle_wrapper.html
