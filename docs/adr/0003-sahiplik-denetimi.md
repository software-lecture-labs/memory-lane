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
   - `lockMemory(user, memoryId)`: güncelleme, silme ve fotoğraf yükleme. Çağıranın transaction'ı içinde anı satırını kilitler; 5 fotoğraf sınırı ve eşzamanlı yazmalar bu kilitle sıralanır.
   - `requirePhoto(user, memoryId, photoId)`: fotoğraf görüntüleme ve silme. Önce anının sahibini, sonra fotoğrafın o anıya ait olduğunu denetler.
4. **Tek tip 404:** Yabancı ve var olmayan kaynak aynı gövdeyi alır (`ResourceNotFoundException`, `OwnershipErrors`).
5. **Statik dosya sunumu kapalı:** `spring.web.resources.add-mappings=false`. Fotoğraflar yalnızca sahiplik denetimi yapan endpointlerden döner.

## Yeni bir anı veya fotoğraf endpointi yazarken

- Endpoint `/api/v1` altında ve `/api/v1/auth/` dışında olmalıdır; filtre bunu otomatik korur.
- Kullanıcıyı `@CurrentUser` ile alın; `Authorization` başlığını kendiniz çözmeyin.
- Kimliği yoldan gelen her anı/fotoğraf için önce `OwnershipGuard` çağırın; yazmalarda `lockMemory` kullanın.
- Liste, arama ve harita sorgularında `WHERE m.owner_id = :user` koşulu sayfalama ve sınırdan önce uygulanmalıdır.
- Yabancı kaynak için 403 veya farklı bir mesaj döndürmeyin.
- Her endpoint için iki hesaplı test ekleyin: B hesabı A'nın kimliğini bilse de 404 almalı, A'nın verisi değişmemeli.

## Sonuçlar

- Her `/api/v1` isteği oturumu bir kez veritabanından doğrular. Demo ölçeği için kabul edilebilir; gerekirse sonradan önbellek eklenebilir.
- ML-07'deki `MemoryController` henüz kullanıcıyı `auth.me(authorization)` ile kendisi çözüyor. Filtreyle birlikte doğru çalışır, yalnızca oturum iki kez okunur. ML-07 merge edildikten sonra `@CurrentUser` kullanacak şekilde sadeleştirilecek.
- `OwnershipGuard` henüz main'deki gerçek endpointlerde değil, test endpointleriyle doğrulandı. Detay, güncelleme, silme, fotoğraf ve harita kartları bu kuralları uygulayarak tamamlanır.
