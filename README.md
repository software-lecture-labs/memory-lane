# Memory Lane

Android mobil uygulaması ve Java HTTP backend için ortak repo.

- `mobile/`: Android uygulaması (mevcut `android/` iskeletinden taşındı).
- `backend/`: Java 17, Spring Boot 3.5.16, gömülü Tomcat.
- `docs/`: mimari gereksinimler, Git çalışma biçimi ve doğrulama sonuçları.

## İlk kurulum

1. Repoyu klonlayın, JDK 17 kurun ve `java -version` ile kontrol edin.
2. Android Studio üzerinden Android SDK Platform 35 ve ilgili build tools kurun.
3. `mobile/local.properties.example` dosyasını `mobile/local.properties` olarak kopyalayıp kendi SDK yolunuzu girin; alternatif olarak `ANDROID_HOME` tanımlayın.
4. Backend varsayılan ayarlarla çalışır. Yerel değişiklik gerekiyorsa `backend/src/main/resources/application-local.properties.example` dosyasını aynı dizinde `.example` uzantısı olmadan kopyalayın.

PowerShell, repo kökünde:

```powershell
cd backend
.\gradlew.bat test bootJar
.\gradlew.bat bootRun
```

Sunucu varsayılan olarak 8080 portunda açılır. Henüz API endpoint'i yoktur; `/` için 404 beklenir. Test, rastgele portta gerçek sunucu/context açılışını kontrol eder.

```powershell
cd mobile
.\gradlew.bat assembleDebug lintDebug testDebugUnitTest
```

Bu ikinci örneğe repo kökünden başlayın. macOS/Linux üzerinde `gradlew.bat` yerine `./gradlew` kullanın. Wrapper ilk çalıştırmada internetten Gradle 8.9 indirir ve SHA-256 doğrular; global Gradle kurulumu gerekmez.

Mobil modül henüz Activity veya işlevsel ekran içermez. APK derlemesi kurulum altyapısını doğrular; kullanılabilir uygulama kabul testi sayılmaz.

SDK yolları, parolalar, `.env`, imzalama anahtarları ve yerel ayarlar commitlenmez. Örneklerde gerçek parola kullanmayın. Ayrıntılar: [mimari](docs/architecture.md), [Git workflow](docs/git-workflow.md), [doğrulama](docs/verification.md).
