# Gerçek doğrulama sonuçları

20 Ekim 2026, Windows, Temurin JDK 17.0.19, yerel Android SDK 35.

- Başlangıç: `da2604d`, `android-backend-klasorleri` dalı; kaynak çalışma ağacı temizdi. Kaynak repo değiştirilmedi; çalışma kopyası `chore/workspace-setup` dalında hazırlandı.
- Gradle 8.9 wrapper dağıtımı checksum doğrulamasıyla indirildi ve çalıştırıldı. Wrapper JAR resmi SHA-256 değeriyle eşleşti.
- Backend: `gradlew.bat test bootJar --no-daemon` başarılı. 1 test, 0 başarısızlık, 0 hata, 0 atlama. Test rastgele portta gömülü Tomcat açılışını doğruladı. JAR üretildi.
- Android ilk çalıştırması: APK üretildi, lint 3 bulguyla başarısız oldu. İkon, data extraction ve eski Android yedekleme kuralları eklendi; sabit SDK 35 için eski hedef API bildirimi uyarı olarak bırakıldı.
- Android tekrar: `gradlew.bat assembleDebug lintDebug testDebugUnitTest --no-daemon` başarılı, son tekrar 13 saniye. `testDebugUnitTest NO-SOURCE`: mobil birim testi bulunmadığı için test koşulmadı. Cihaz/emülatör testi yapılmadı. Lint raporu: 0 hata, 2 uyarı (OldTargetApi ve DataExtractionRules). Eski Android için fullBackupContent ayarı eklenmesine rağmen son raporda DataExtractionRules uyarısı kalmıştır; çözülmüş sayılmadı.
- `git diff --check`: başarılı. Yerel SDK yolu, backend yerel ayarları, `.env`, keystore ve build çıktıları için ignore kuralları kontrol edildi. İki wrapper JAR takip ediliyor.
- SonarQube/Cloud analizi, A derecesi, DB transaction testleri ve uzak CI çalışması henüz yoktur.

## Kanıt ve teslim

`outputs/evidence/` içinde gerçek JUnit XML ve Android lint raporları bulunur. Yerel çıktılar GitHub CI kanıtı değildir. Issue, PR veya uzak test bağlantısı üretilmedi; dal GitHub'a gönderilmedi.

Teslim klasöründeki repo Git geçmişini içerir. Kaynak repoya aktarmak için mevcut değişiklikleri kontrol ettikten sonra bu yerel repodan `chore/workspace-setup` dalını fetch ederek inceleyin. Uzak repoya yayınlanınca gerçek PR ve CI bağlantıları Trello kartına eklenebilir.
