# ADR-0003: Oturum ve sahiplik denetimi

- Durum: Kabul edildi
- Tarih: 2026-11-13
- İlgili kart: ML-29

## Bağlam

Hesapların eklenmesiyle her anı ve fotoğraf işlemi yalnızca oturum sahibinin verisiyle çalışmalıdır. API sözleşmesi (ML-03) kuralları belirler: register ve login dışındaki `/api/v1` istekleri Bearer token ister, oturumsuz istek `401 INVALID_SESSION` alır; başka hesaba ait kaynak, var olmayan kaynakla aynı `404 MEMORY_NOT_FOUND` / `PHOTO_NOT_FOUND` yanıtını alır; fotoğraf sahipliği bağlı anı üzerinden denetlenir; `ownerId` istemciden alınmaz; medya herkese açık klasörden sunulmaz.

Her controller oturumu kendisi denetlerse, unutulan tek bir endpoint verileri açığa çıkarır. Sahiplik sorgusu her serviste ayrı yazılırsa 404 yanıtları ve fotoğraf denetimi tutarsızlaşabilir.

## Karar

1. **Ortak oturum filtresi:** `SessionAuthenticationFilter`, `/api/v1/auth/` dışındaki tüm `/api/v1` isteklerinde controller çalışmadan önce oturumu doğrular. Token doğrulaması `AuthService.me` ile yapılır. Oturumsuz istek bilinmeyen bir yola gitse de 401 alır, böylece rota bilgisi sızmaz.
2. **Kullanıcı kimliği yalnızca oturumdan gelir:** Controller'lar kullanıcıyı `@CurrentUser UUID user` parametresiyle alır. İstek gövdesi, sorgu parametresi veya başlıktaki kullanıcı/sahip bilgisi kullanılmaz.
3. **Ortak sahiplik denetimi:** `OwnershipGuard` üç işlem sunar:
   - `requireMemory(user, memoryId)`: okuma işlemleri.
   - `lockMemory(user, memoryId)`: tüm yazma işlemleri (anı güncelleme ve silme, fotoğraf yükleme ve silme). Çağıranın transaction'ı içinde anı satırını `SELECT ... FOR UPDATE` ile kilitler; transaction yoksa hata verir.
   - `requirePhoto(user, memoryId, photoId)`: önce anının sahibini, sonra fotoğrafın o anıya ait olduğunu denetler. Kilit almaz; fotoğraf görüntülemede tek başına kullanılır.
4. **Eşzamanlı yazma kuralı: her yazma `lockMemory` altında çalışır.** Fotoğraf silme de bir yazmadır: aynı transaction'da önce `lockMemory`, ardından `requirePhoto` çağrılır, sonra satır silinir. Böylece aynı anıya yapılan tüm yazmalar (5 fotoğraf sınırı, kapak sırası, anı silme) tek bir kilitle sıraya girer. İkinci yazma, ilk transaction commit veya rollback olana kadar bekler. Okumalar kilit almaz ve beklemez. Kilit her zaman anı satırında alınır; fotoğraf satırında ayrı kilit alınmaz, böylece iki işlemin kilitleri ters sırada alıp birbirini beklemesi (deadlock) önlenir. `OwnershipIntegrationTest.secondWriterWaitsUntilFirstWriterReleasesTheMemoryLock`, ikinci yazmanın PostgreSQL'de kilit beklediğini ve ancak ilk transaction bittikten sonra devam ettiğini doğrular.
5. **Tek tip 404:** Yabancı ve var olmayan kaynak aynı gövdeyi alır (`ResourceNotFoundException`, `OwnershipErrors`).
6. **Veritabanı kullanılamazsa 503:** Filtre oturumu veritabanı erişilemediği için doğrulayamazsa `503 DATABASE_UNAVAILABLE` döner. Gövde auth endpointleriyle aynıdır ve tek yerde (`AuthFailure.databaseUnavailable()`) üretilir.
7. **Statik dosya sunumu kapalı:** `spring.web.resources.add-mappings=false`. Fotoğraflar yalnızca sahiplik denetimi yapan endpointlerden döner.

## Yeni bir anı veya fotoğraf endpointi yazarken

- Endpoint `/api/v1` altında ve `/api/v1/auth/` dışında olmalıdır; filtre bunu otomatik korur.
- Kullanıcıyı `@CurrentUser` ile alın; `Authorization` başlığını kendiniz çözmeyin.
- Kimliği yoldan gelen her anı/fotoğraf için önce `OwnershipGuard` çağırın. Her yazmada, transaction içinde ilk iş `lockMemory` çağırın; fotoğraf yazmalarında ardından `requirePhoto` çağırın.
- Liste, arama ve harita sorgularında `WHERE m.owner_id = :user` koşulu sayfalama ve sınırdan önce uygulanmalıdır.
- Yabancı kaynak için 403 veya farklı bir mesaj döndürmeyin.
- Her endpoint için iki hesaplı test ekleyin: B hesabı A'nın kimliğini bilse de 404 almalı, A'nın verisi değişmemeli.

## Sonuçlar

- Her `/api/v1` isteği oturumu bir kez veritabanından doğrular. Demo ölçeği için kabul edilebilir; gerekirse sonradan önbellek eklenebilir.
- ML-07'deki `MemoryController` henüz kullanıcıyı `auth.me(authorization)` ile kendisi çözüyor. Filtreyle birlikte doğru çalışır, yalnızca oturum iki kez okunur. ML-07 merge edildikten sonra `@CurrentUser` kullanacak şekilde sadeleştirilecek.
- `OwnershipGuard` henüz main'deki gerçek endpointlerde değil, test endpointleriyle doğrulandı. Detay, güncelleme, silme, fotoğraf ve harita kartları bu kuralları uygulayarak tamamlanır.
