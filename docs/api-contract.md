# Veri modeli ve API sözleşmesi

Bu belge ML-03 için ortak sözleşmedir. İlk hesaplı Trello planı ve K1'in onayladığı kurallar esas alınır; backend Spring Boot'tur. Auth dahil aşağıdaki endpointler geliştirme sözleşmesidir. Yereldeki ML-27 yanıt biçimi esas alınmıştır; main dalında bu endpointlerin uygulanmış veya gerçek veritabanıyla doğrulanmış olduğu iddia edilmez.

## Genel kurallar

- Taban yol `/api`. JSON gövdeleri UTF-8 ve `Content-Type: application/json` kullanır. Fotoğraf yükleme multipart'tır.
- Register ve login dışındaki aşağıdaki işlemler `Authorization: Bearer <accessToken>` ister. Sağlık yolları ayrıdır; bu belgenin iş API'sine dahil değildir.
- User, Memory ve Photo kimlikleri sunucunun ürettiği UUID metinleridir: `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`. Örnekler küçük harfle yazılır. Session anahtarı UUID değildir.
- `createdAt`, `updatedAt` ve `expiresAt` ISO 8601 UTC metnidir ve `Z` ile biter; saniye/kesir kısmı ISO 8601'e göre değişebilir. `memoryDate` saat içermeyen `YYYY-MM-DD` tarihidir. Bugün kontrolü `Europe/Istanbul` takvim gününe göre yapılır.
- Boş liste 200 ve boş içerik döner; bağlantı hatası boş listeye çevrilmez. 204 yanıtta gövde bulunmaz.
- `ownerId`, kullanıcı kimliği, parola hash'i, token hash'i ve fiziksel dosya yolu anı/fotoğraf DTO'larında bulunmaz. İstemci anı/fotoğraf yazma gövdesine salt okunur alan eklerse 400 döner.

## Model ve ilişkiler

| Model | Alanlar ve ilişki |
| --- | --- |
| User | UUID `id`, normalize ve unique `email`, yalnız sunucuda `passwordHash`, UTC `createdAt`. Bir kullanıcının birden çok oturumu ve anısı olabilir. |
| Session | Bir User'a bağlıdır. `tokenHash` SHA-256 hex olarak saklanır; ham token saklanmaz. UTC `createdAt` ve `expiresAt`; ömür 24 saat. |
| Memory | UUID `id`, zorunlu `owner_id → User.id`, başlık, açıklama, `memoryDate`, kategori, isteğe bağlı duygu/konum, UTC oluşturma/güncelleme zamanı. Bir Memory'nin 0–5 Photo kaydı olabilir. |
| Photo | UUID `id`, zorunlu `memory_id → Memory.id`, MIME türü, bayt boyutu, seçim sırası, UTC oluşturma zamanı. Depolama anahtarı ve tekrar kontrol bilgileri yalnız sunucudadır. |

Session–User ve Memory–User ilişkileri çoktan-bire, Photo–Memory ilişkisi çoktan-biredir. FK ve tekilliklerin migration ile kurulması K4'ün altyapı uygulamasında ele alınır; bu belge tablo oluşturmaz.

`owner_id` kayıt sırasında oturumdan belirlenir; istemciden alınmaz. Liste, detay, güncelleme, silme, fotoğraf yükleme/indirme, arama ve harita aynı sahiplik denetimini uygular. Geçerli oturumla başka hesaba ait bir kaynak istenirse, kaynağın varlığını açıklamamak için bulunamayan kaynakla aynı 404 döner. Fotoğraf sahipliği bağlı olduğu Memory üzerinden denetlenir; medya herkese açık dosya klasöründen sunulmaz.

## Anı alanları

| JSON alanı | Kural |
| --- | --- |
| `title` | Zorunlu metin; baş/son boşluklar temizlendikten sonra 1–100 Unicode karakter. |
| `description` | İsteğe bağlı; null/eksik değer boş metin olarak döner. En fazla 3000 Unicode karakter; içeriği sessizce kesilmez. |
| `memoryDate` | Zorunlu, gerçek takvim tarihi; bugün veya geçmiş. Gelecek tarih reddedilir. |
| `category` | Sabit kod: `TRAVEL`, `FAMILY`, `FRIENDS`, `SCHOOL`, `DAILY_LIFE`, `OTHER`. Eksik/null/boşsa `OTHER` (mobil etiketi Diğer). Bilinmeyen kod 400 döner. |
| `emotion` | İsteğe bağlı sabit kod: `HAPPY`, `PEACEFUL`, `EXCITED`, `NOSTALGIC`, `SAD`; eksik/null/boşsa null. Bilinmeyen kod 400 döner. |
| `location` | İsteğe bağlı; null veya `{latitude, longitude}`. İki sayı birlikte verilir. Enlem −90…90, boylam −180…180; NaN/sonsuz kabul edilmez. |

Kategori/duygu kodları API’de sabittir; Türkçe etiketleri mobil gösterir (örneğin TRAVEL → Gezi, HAPPY → Mutlu, OTHER → Diğer). JSON `emotion` alanı SQL şemasındaki `mood`, `order` alanı `position`, `contentType` alanı `media_type` ile eşlenir; iç SQL adları API alan adlarını değiştirmez.

Anı oluşturma ve PUT aynı yazma gövdesini kullanır. PUT'ta zorunlu alanlar yeniden verilir; eksik isteğe bağlı alanlar yukarıdaki varsayılanlara döner. PUT fotoğraf listesini değiştirmez. `id`, `createdAt`, `updatedAt`, `photoCount` ve `coverPhotoId` sunucunun yanıt alanlarıdır; istek alanı değildir.

## Hesap ve oturum

| Yöntem | Yol | Başarılı yanıt |
| --- | --- | --- |
| POST | `/auth/register` | 201 UserResponse; otomatik giriş yapmaz |
| POST | `/auth/login` | 200 LoginResponse |
| GET | `/auth/me` | 200 UserResponse |
| POST | `/auth/logout` | 204; aynı doğru biçimli tokenla tekrar çıkış da 204 |

Register/login isteği:

```json
{"email":"uye@example.test","password":"yerel-ornek-parola-123"}
```

E-posta kırpılır ve Locale.ROOT ile küçük harfe çevrilir; en fazla 254 karakter ve unique'tir. Kayıt parolası 15–128 Unicode karakterdir; boşluk/Türkçe karakter korunur, parola kırpılmaz. Hazır kütüphaneyle Argon2id kullanılır. Yanlış parola ve bilinmeyen hesap aynı `401 INVALID_CREDENTIALS` yanıtını verir. Auth istek gövdesi 8 KiB, giriş/kayıt denemesi IP başına 20/dakika ile sınırlıdır.

UserResponse örneği (register ve me):

```json
{"id":"11111111-1111-4111-8111-111111111111","email":"uye@example.test"}
```

LoginResponse örneği:

```json
{
  "accessToken":"oIuQe1M4GJvWmXfGpE_oXqt0vppb8Fl2HFL7B7rsfHk",
  "tokenType":"Bearer",
  "expiresAt":"2026-10-24T09:00:00Z",
  "user":{"id":"11111111-1111-4111-8111-111111111111","email":"uye@example.test"}
}
```

Token 32 güvenli rastgele bayttan URL-safe Base64 olarak üretilir; 43 karakterdir. Mobil yalnız süreç belleğinde tutar; parola/token kalıcı diske yazılmaz. Süre dolunca veya iptal edilmiş tokenla 401 döner; refresh token ve beni hatırla yoktur. Çıkış yalnız ilgili oturumu iptal eder. Mobil 401/çıkışta token ve önceki hesaba ait ekran/görsel durumunu temizler (ML-30).

## Anı endpointleri

| Yöntem | Yol | Davranış |
| --- | --- | --- |
| POST | `/memories` | 201 MemoryDetail; `Location: /api/memories/{id}`; `Idempotency-Key` zorunlu |
| GET | `/memories` | 200 sayfalı MemorySummary listesi; arama ve filtre aynı endpointte |
| GET | `/memories/{id}` | 200 MemoryDetail |
| PUT | `/memories/{id}` | 200 güncel MemoryDetail; `createdAt` ve fotoğraflar korunur |
| DELETE | `/memories/{id}` | 204; bulunamayan/yabancı anı 404 |
| GET | `/memories/map` | 200, konumlu anıların sayfalamadan bağımsız noktaları |

Oluşturma/düzenleme isteği:

```json
{
  "title":"Sahil yürüyüşü",
  "description":"Akşam arkadaşlarla yürüdük.",
  "memoryDate":"2026-10-22",
  "category":"TRAVEL",
  "emotion":"HAPPY",
  "location":{"latitude":41.0082,"longitude":28.9784}
}
```

MemoryDetail örneği:

```json
{
  "id":"22222222-2222-4222-8222-222222222222",
  "title":"Sahil yürüyüşü",
  "description":"Akşam arkadaşlarla yürüdük.",
  "memoryDate":"2026-10-22",
  "category":"TRAVEL",
  "emotion":"HAPPY",
  "location":{"latitude":41.0082,"longitude":28.9784},
  "createdAt":"2026-10-23T09:00:00Z",
  "updatedAt":"2026-10-23T09:00:00Z",
  "photoCount":0,
  "coverPhotoId":null,
  "photos":[]
}
```

MemorySummary aynı alanları taşır; yalnız `photos` dizisi bulunmaz. `photoCount` ve `coverPhotoId` ile listede gereksiz dosya indirme önlenir. Kapak fotoğrafı, varsa en küçük `order` değerindeki fotoğraftır.

### Sayfalama ve arama

`GET /memories?page=0&size=20&q=sahil&category=TRAVEL` örneğinde `page` sıfırdan başlar; varsayılan 0'dır. `size` varsayılan 20, aralık 1–100'dür. Negatif/geçersiz değer 400; son sayfadan sonrası 200 ve boş `content` döner. `q` kırpılır, en fazla 100 karakterdir; başlık/açıklama içinde büyük-küçük harf duyarsız düz metin aramasıdır. `%` ve `_` kullanıcı için özel joker değildir; aksan/harf sadeleştirmesi yapılmaz. Boş `q` veya `category` filtreyi kaldırır. Kategori sabit kodu büyük harfle tam eşleşir; Türkçe etiket query parametresi olarak gönderilmez. İki filtre birlikte AND olarak uygulanır.

Sıra sabittir: `memoryDate DESC`, ardından `id DESC`. Bu sıralama aynı tarihteki kayıtları belirgin tutar; kayıtlar değişirken offset sayfalama sabit bir snapshot garantisi vermez. Mobil filtre değişince page=0'a döner ve önceki isteğin güncel sonucu ezmesini önler.

```json
{
  "content":[{
    "id":"22222222-2222-4222-8222-222222222222",
    "title":"Sahil yürüyüşü",
    "description":"Akşam arkadaşlarla yürüdük.",
    "memoryDate":"2026-10-22",
    "category":"TRAVEL",
    "emotion":"HAPPY",
    "location":{"latitude":41.0082,"longitude":28.9784},
    "createdAt":"2026-10-23T09:00:00Z",
    "updatedAt":"2026-10-23T09:00:00Z",
    "photoCount":0,
    "coverPhotoId":null
  }],
  "page":0,"size":20,"totalElements":1,"totalPages":1,"hasNext":false
}
```

### Harita

`GET /memories/map?q=sahil&category=TRAVEL` aynı arama/kategori kurallarını uygular. `page`/`size` kabul etmez; verilirse 400 döner. Yalnız oturum sahibinin konumlu kayıtları döner; liste sayfasındaki 20 kayıtla sınırlanmaz. Sonuç sessizce kesilmez; konumsuz anılar atlanır. Noktalar `memoryDate DESC`, `id DESC` sırasındadır.

```json
{"points":[{"id":"22222222-2222-4222-8222-222222222222","title":"Sahil yürüyüşü","memoryDate":"2026-10-22","latitude":41.0082,"longitude":28.9784,"coverPhotoId":null}]}
```

## Fotoğraf endpointleri ve yanıtı

| Yöntem | Yol | Davranış |
| --- | --- | --- |
| POST | `/memories/{id}/photos` | Bir fotoğraf yükle; 201 PhotoResponse, tekrarda 200; `Location` değeri `contentUrl`; `Idempotency-Key` zorunlu |
| GET | `/memories/{id}/photos` | 200, `order ASC` PhotoResponse dizisi |
| DELETE | `/memories/{id}/photos/{photoId}` | 204; bulunamayan/yabancı/eşleşmeyen kaynak 404 |
| GET | `/memories/{id}/photos/{photoId}/content` | Yetkili dosya indirme; 200 `image/jpeg` veya `image/png` |

Yükleme `multipart/form-data` kullanır; tek alan `file` tek dosyadır. `order` sunucunun atadığı yanıt alanıdır, istemci yazmaz. İlk fotoğraf 0, sonraki yeni fotoğraf mevcut `max(position) + 1` değerini alır. Silinen sıra tekrar kullanılmaz ve fotoğraflar yeniden numaralandırılmaz; tüm fotoğraflar silinmişse yeni liste 0'dan başlar. En fazla 5 adet kuralı sıra değerinden değil mevcut kayıt adedinden uygulanır; `order` 5 veya daha büyük olabilir. Liste ve detay galerisi `order ASC` kullanır; kapak en küçük mevcut sıradır.

Mobil seçim sırasını korumak için fotoğrafları seçildiği sırayla, aynı anı için tek tek gönderir. Önceki isteğin sonucu belirsizse aynı anahtarla sonucunu kesinleştirmeden sonraki dosyaya geçmez. Başarısız dosya yeniden denenir veya kullanıcı tarafından seçimden çıkarılır; seçimden çıkarılan dosya daha sonra tekrar eklenirse yeni seçimin sonunda yer alır. İstemci multipart boundary başlığını HTTP kütüphanesine bırakır.

JPEG/PNG içerik ve türü birlikte doğrulanır; yalnız uzantıya güvenilmez. Dosya başına üst sınır 5 MB = 5.000.000 bayt (multipart zarfı hariç); anı başına 5 fotoğraf sunucuda korunur. Dosya adı ve yolu istemciden alınarak depolama yolu yapılmaz. Orijinal galeri dosyası silinmez.

PhotoResponse; liste ve MemoryDetail.photos içinde de aynı biçimdedir:

```json
{
  "id":"33333333-3333-4333-8333-333333333333",
  "memoryId":"22222222-2222-4222-8222-222222222222",
  "order":0,
  "contentType":"image/jpeg",
  "sizeBytes":245678,
  "createdAt":"2026-10-23T09:05:00Z",
  "contentUrl":"/api/memories/22222222-2222-4222-8222-222222222222/photos/33333333-3333-4333-8333-333333333333/content"
}
```

`contentUrl` göreli ve korumalıdır; mobil görsel isteğine de Bearer başlığı ekler. Token URL/query parametresine yazılmaz. Görsel yanıtları ortak JSON zarfına alınmaz; hata halinde dosya yerine aşağıdaki JSON hata gövdesi döner.

## Kısmi başarı ve tekrar deneme

1. Mobil form kaydında bir UUID `Idempotency-Key` üretip POST `/memories` isteğiyle gönderir. 201 yanıtındaki anı kimliği saklanır. Sonra seçili fotoğraflar bu anıya ayrı ayrı yüklenir; istek başına tek dosya olduğundan her dosyanın sonucu bellidir.
2. Her fotoğraf için ayrı UUID anahtar tutulur; yanıtın `order` değeri sunucudan alınır. Anı kaydolup bir fotoğraf hata verirse anı geri alınmaz ve yeniden oluşturulmaz; yalnız başarısız/belirsiz fotoğraf aynı anahtar ve dosya ile yeniden gönderilir. Başarılı fotoğraflar tekrar yüklenmez.
3. Yanıt kaybolmuşsa aynı isteği aynı anahtarla tekrar etmek ilk kaydın kimliğini döndürür; ikinci anı/fotoğraf oluşturmaz. İlk başarılı yazma 201, tekrar 200 ve `Idempotency-Replayed: true` döner. Oluşturulan kaynağın `Location` bilgisi aynı kalır. Aynı anahtarla farklı içerik 409 `IDEMPOTENCY_CONFLICT` döner.
4. Anahtarın kapsamı kullanıcı + yöntem + hedef yoldur. Sunucu başarılı sonucu ve içerik eşleşmesini kalıcı olarak kaydeder; eşzamanlı tekrarda tek kayıt üretilir. Kaynak silinmişse aynı anahtar yeniden kaynak yaratmaz, 409 `IDEMPOTENCY_KEY_RETIRED` döner. Uygulama başarısız olmuş ve hiçbir kayıt oluşmamışsa anahtar yeniden denenebilir. Kullanıcı aynı dosyayı yeni anahtarla bilinçli olarak seçerse bu yeni bir yüklemedir; kopya önleme bu belirsiz ağ tekrarları içindir.
5. Tekrar kontrolü 5 adet sınırı kontrolünden önce yapılır; daha önce başarılı bir isteğin tekrarı limit doldu diye altıncı dosya sayılmaz. Dosya içeriği ve MIME türü eşleşmesi sunucuda denetlenir; ilk başarılı yanıtın sıra değeri tekrar yanıtta korunur. PUT veya DELETE sonrasında durumu doğrulamak için aynı kaynağa GET yapılabilir; silinmiş kaynak için 404 beklenir.
6. Mobil kısmi başarıyı örneğin “Anı kaydedildi, 2 fotoğraf yüklenemedi” olarak gösterir. Başarısız dosyaların seçimi ön plandaki tekrar deneme için korunur; bunun kapsamı süreçler arası çevrimdışı senkronizasyon değildir. 401 durumunda tekrar deneme durur ve oturum akışına dönülür.

Geçersiz dosya/tarih gibi 4xx hataları veri düzeltilmeden tekrar gönderilmez. 429'da en az bir dakika beklenir. Bağlantı/5xx hatasında kullanıcı yeniden deneyebilir; yeni anahtar üretilmez. Anı silme DB kayıtları için transaction, fiziksel dosyalar için commit sonrası temizleme kullanır; dosya hatası nedeniyle kullanıcıya silinmemiş anı gibi yanıt verilmez. Başarı/rollback ve dosya temizliği uygulaması ML-13/ML-14'ün işidir; bu belge o işleri gerçekleştirmez.

## Hata gövdesi ve durumlar

Auth için belirlenen biçim korunur; mobil metinden değil `code` alanından karar verir. Alan hatası `message` içinde anlaşılır anlatılır. Dosya yolu, SQL, stack trace, parola ve token yanıta yazılmaz.

```json
{"code":"INVALID_INPUT","message":"Başlık 1–100 karakter olmalıdır."}
```

| HTTP | Kod | Anlam |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | Alan, UUID, tarih, koordinat, sayfa geçersiz; zorunlu anahtar eksik |
| 400 | `INVALID_JSON` | Okunamayan JSON |
| 401 | `INVALID_CREDENTIALS` | Hesap/parola doğrulanamadı; yalnız login |
| 401 | `INVALID_SESSION` | Eksik, geçersiz, iptal edilmiş veya süresi dolmuş oturum; `WWW-Authenticate: Bearer` |
| 404 | `MEMORY_NOT_FOUND` / `PHOTO_NOT_FOUND` | Kaynak bulunamadı veya kullanıcıya ait değil; yabancı kaynak için ayrı kod yok |
| 409 | `EMAIL_IN_USE` | Normalize e-posta zaten kayıtlı |
| 409 | `PHOTO_LIMIT_REACHED` | Anıda 5 kayıt var |
| 409 | `IDEMPOTENCY_CONFLICT` / `IDEMPOTENCY_KEY_RETIRED` | Anahtar farklı içerikle kullanıldı veya kaynak silinmiş |
| 413 | `FILE_TOO_LARGE` / `REQUEST_TOO_LARGE` | Dosya limiti / auth gövde limiti aşıldı |
| 415 | `UNSUPPORTED_PHOTO_TYPE` | Fotoğraf JPEG/PNG değil veya içerik türü uyuşmuyor |
| 405 / 415 | `HTTP_ERROR` | Yöntem veya JSON isteğinin Content-Type değeri desteklenmiyor |
| 429 | `RATE_LIMITED` | Giriş/kayıt deneme sınırı |
| 500 | `INTERNAL_ERROR` | İstemciye iç ayrıntısı verilmeyen beklenmeyen hata |
| 503 | `DATABASE_UNAVAILABLE` | Bağlantı hazır değil veya veritabanı kullanılamıyor |

## ML-04 şema eşleştirmesi

Güncel main'deki V1 ve V2 migration'ları birlikte esas alınır; uygulanmış V1 değiştirilmez. Bu sözleşme migration veya altyapı kodunu değiştirmez.

| API / kural | ML-04 karşılığı |
| --- | --- |
| User, Memory, Photo UUID | V2 `users.id`, `memories.id`, `photos.id` ve ilgili UUID foreign key'ler |
| Session | Dışarı açılmayan BIGINT `sessions.id`; UUID `user_id`, hash ve UTC zaman alanları |
| `emotion`, `order`, `contentType`, `contentUrl` | SQL `mood`, `position`, `media_type` eşlemesi; URL controller tarafından üretilir, storage_key veya dosya yolu açılmaz |
| Anı `Idempotency-Key` | `memories.client_request_id`, `(owner_id, client_request_id)` tekilliği ve `client_request_sha256` |
| Fotoğraf `Idempotency-Key` | `photos.client_photo_id`, `(memory_id, client_photo_id)` tekilliği ve `content_sha256` |
| JPEG/PNG, 5.000.000 bayt | `media_type` ve `size_bytes` kısıtları |
| Kapak ve sıra | En küçük mevcut position; yeni kayıt max(position)+1. Silinen sıra doldurulmaz |
| En fazla 5 fotoğraf | Servis aynı anı satırını kilitleyip adet kontrolünü yapacak; schema pozisyonu 0–4 ile sınırlamaz |

Anı gövdesi özeti, doğrulanmış/varsayılanları uygulanmış yazma alanlarından hesaplanır: anahtar sırası `title`, `description`, `memoryDate`, `category`, `emotion`, `location`; location içinde `latitude`, `longitude`. JSON UTF-8, gereksiz boşluksuz ve anahtar sırası sabit üretilir; sayılarda anlamsız son sıfırlar kaldırılır, −0 değeri 0 olur. Kaynak metindeki JSON boşlukları ve alan sırası özete etki etmez. Sunucunun bu standart gösteriminin SHA-256 hex değeri `client_request_sha256` olur. Fotoğraf `content_sha256` yalnız ham dosya baytlarından hesaplanır; multipart sınır/metin bilgisi dahil edilmez. Aynı anahtarla gelen MIME türü ayrıca karşılaştırılır. İstemci özeti veya iç alanları belirleyemez.

V2 şeması anahtar ve içerik özetlerini sağlar; başarılı tekrarın 200 yanıtı, anahtar çakışmasının 409 yanıtı ve anı satırı kilidi servis uygulamasında yapılacaktır. Silinmiş anahtarın tekrar kullanılmaması için ayrıca kalıcı emekli-anahtar kaydı gerekir; mevcut V2'nin bunu tek başına sağladığı iddia edilmez. Bu depolama, ilgili API özelliği geliştirilirken sürümlü migration ile eklenecek, burada veritabanı değiştirilmez. Böylece beklenen HTTP sözleşmesi ile henüz yazılmamış uygulama birbirine karıştırılmaz.

## ML-03 teslim durumu

ML-03'ün veri ilişkileri, biçimler, endpointler, DTO/hata/sayfalama/yükleme örnekleri ve kısmi başarı/tekrar kuralları tamamlanmıştır. K2 fotoğraf akışı incelemesini ekipte ayrıca yapacaktır; onay alınmış gibi kaydedilmez. Belgenin hazırlanması, API uygulaması ve ekip incelemesi ayrı işlerdir; doğrulama sonucu ilgili PR/CI üzerinden takip edilir. Bu belge endpoint uygulaması, migration veya mobil ekran geliştirmesi değildir.
