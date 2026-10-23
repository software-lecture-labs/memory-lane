# ADR-0001: Payara Micro yerine Spring Boot

- Durum: Kabul edildi
- Tarih: 2026-10-23
- İlgili kart: ML-04

## Bağlam

Genel proje dokümanı backend için Jakarta EE API'leri (REST, CDI, Validation, Persistence, Transactions) ve WAR dağıtımıyla Payara Micro öngörüyordu. Repodaki backend iskeleti ise Spring Boot 4.1.1, gömülü Tomcat ve Gradle ile kurulmuştu. ML-04 (veritabanı bağlantısı ve migration) başlamadan önce hangi çalışma ortamının esas alınacağı netleşmeliydi.

## Karar

Backend, Payara Micro yerine Spring Boot ile geliştirilir ve `bootJar` ile çalıştırılabilir JAR olarak dağıtılır. Jakarta EE karşılıkları:

| Dokümandaki seçim | Spring Boot karşılığı |
|---|---|
| Jakarta REST | Spring Web MVC |
| CDI | Spring bağımlılık enjeksiyonu |
| Jakarta Persistence | Spring Data JPA + Hibernate (Jakarta Persistence API'si) |
| Jakarta Transactions | `@Transactional` |
| Payara JDBC datasource | Spring Boot datasource (HikariCP) |
| Bean Validation | Jakarta Validation (Spring Boot ile aynı API) |

## Gerekçe

- İskelet, CI ve ekip kurulumu zaten Spring Boot üzerinde çalışıyor; geçiş ek iş ve risk getirirdi.
- Ayrı bir uygulama sunucusu kurmadan tek komutla çalışan JAR, dört haftalık takvimde kurulum yükünü azaltır.
- Flyway, Testcontainers ve sağlık kontrolü gibi ihtiyaçlar Spring Boot'ta hazır entegrasyonla gelir.
- Entity, validation ve persistence kodu Jakarta API'lerini kullanmaya devam eder; ders kapsamındaki mimari, transaction ve SOLID gösterimleri değişmez.

## Sonuçlar

- Trello kartlarında ve proje dokümanında Payara geçen ifadeler bu karara göre okunur.
- Maven Wrapper yerine mevcut Gradle Wrapper kullanılır.
- Ders teslimi için tam bir Jakarta EE uygulama sunucusu zorunlu tutulursa bu karar yeni bir ADR ile yeniden değerlendirilir.
