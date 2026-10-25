# Veri modeli ve API sözleşmesi

Bu belge ML-03 için ortak sözleşmedir. İlk hesaplı Trello planı esas alınır; backend Spring Boot'tur. Auth dahil aşağıdaki endpointler geliştirme sözleşmesidir; uygulanmış veya gerçek veritabanıyla doğrulanmış oldukları iddia edilmez. Ekip rol kodları README'de açıklanır.

## Genel kurallar

- İş API'sinin taban yolu `/api/v1`. JSON gövdeleri UTF-8 ve `Content-Type: application/json` kullanır. Fotoğraf yükleme multipart'tır.
- ML-04'teki mevcut `/api/health` altyapı endpointi değişmez. Bu sözleşmedeki iş endpointleri yalnız `/api/v1` altında uygulanacak; aynı iş için paralel `/api/auth` veya `/api/memories` alias'ı tanımlanmaz. Geriye uyumlu alan eklemeleri v1'de kalabilir; alan tipi/adı değişikliği gibi uyumsuz değişiklik yeni sürüm gerektirir.
- Hesap ve anı yanıtları/hataları `Cache-Control: no-store` taşır. ETag burada önbellek zorunluluğu değil, yazma önkoşuludur.
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
| Memory | UUID `id`, zorunlu `owner_id → User.id`, başlık, açıklama, `memoryDate`, kategori, isteğe bağlı duygu/konum, UTC oluşturma/güncelleme zamanı. Sunucu tarafındaki version eşzamanlı güncellemeyi denetler; DTO yazma alanı değildir. Bir Memory'nin 0–5 Photo kaydı olabilir. |
| Photo | UUID `id`, zorunlu `memory_id → Memory.id`, MIME türü, bayt boyutu, seçim sırası, UTC oluşturma zamanı. Depolama anahtarı ve tekrar kontrol bilgileri yalnız sunucudadır. |

Session–User ve Memory–User ilişkileri çoktan-bire, Photo–Memory ilişkisi çoktan-biredir. FK ve tekillikler ML-04 V1/V2 migration'larında kuruludur; aşağıdaki şema eşleştirmesi bu alanları gösterir. Bu belge tablo oluşturmaz.

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

Anı oluşturma ve PUT aynı yazma gövdesini kullanır. PUT'ta zorunlu alanlar yeniden verilir; eksik isteğe bağlı alanlar yukarıdaki varsayılanlara döner. PUT fotoğraf listesini değiştirmez ve aşağıdaki `If-Match` koşulunu zorunlu tutar. `id`, `createdAt`, `updatedAt`, `photoCount` ve `coverPhotoId` sunucunun yanıt alanlarıdır; istek alanı değildir.

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

Register için `409 EMAIL_IN_USE` bilinçli bir ilk teslim kararıdır: e-postanın kayıtlı olduğunu açığa çıkarır, ancak e-posta doğrulaması/mail altyapısı kapsam dışı olduğundan kullanıcıya açık hata verilir. Bu karar herkese açık hizmet için hesap varlığını gizleyen bir çözüm olarak sunulmaz.

IP limiti tek başına yeterli değildir: CGNAT arkasındaki kullanıcılar aynı kotayı paylaşabilir, dağıtılmış denemeler farklı IP kullanabilir. Login için ek kural, normalize e-posta başına IP'lerden bağımsız 60 saniyede en fazla 5 başarısız denemedir; sonraki deneme pencere bitene kadar 429 alır. Var olan ve olmayan hesaplara aynı sayaç kuralı uygulanır; sayaç için hesap varlığı açıklanmaz. Başarılı giriş başarısız deneme sayacını sıfırlar. Geçersiz JSON/e-posta IP kotasına girer, e-posta kotasına yazılmaz. 429 yanıtı kalan beklemeyi saniye olarak `Retry-After` ile bildirir; iki limit birden dolmuşsa uzun bekleme seçilir. Bu limitler kalıcı hesap kilidi değildir; çoklu sunucuda sayaçların ortak tutulması gerekir. Sayaç anahtarları/loglar düz e-posta veya parola taşımamalıdır. Bunlar auth uygulamasının kabul kurallarıdır; bu belge sayaç kodu eklemez.

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
| POST | `/memories` | 201 MemoryDetail; `Location: /api/v1/memories/{id}`; `Idempotency-Key` zorunlu |
| GET | `/memories` | 200 sayfalı MemorySummary listesi; arama ve filtre aynı endpointte |
| GET | `/memories/{id}` | 200 MemoryDetail ve güçlü `ETag` başlığı |
| PUT | `/memories/{id}` | `If-Match` zorunlu; 200 güncel MemoryDetail; eski sürüm 412, eksik başlık 428 |
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

### Eşzamanlı güncelleme: ETag ve If-Match

Düzenleme formu `GET /memories/{id}` yanıtındaki güçlü `ETag` değerini saklar. Etiket sunucunun ürettiği opak bir değerdir; istemci sürüm numarası tahmin etmez veya etiketi parçalamaz. Örnek GET başlığı: `ETag: "memory-22222222-2222-4222-8222-222222222222-r7"`. Liste yanıtı düzenleme için sürüm kaynağı değildir; form açılırken detay GET yapılır. Etiket ve detay gövdesi aynı tutarlı kayıt görüntüsünden üretilir.

İstemci PUT'ta aldığı etiketi tırnaklarıyla birlikte gönderir:

```http
PUT /api/v1/memories/22222222-2222-4222-8222-222222222222
Authorization: Bearer <accessToken>
Content-Type: application/json
If-Match: "memory-22222222-2222-4222-8222-222222222222-r7"
```

- Oturum ve kaynak sahipliği önce doğrulanır. Oturumsuz istek 401, bulunamayan/yabancı anı 404 alır; bu yanıtlar kaynak sürümünü açığa çıkarmaz.
- `If-Match` yoksa 428 `PRECONDITION_REQUIRED` döner ve hiçbir alan değiştirilmez. İstemci önce detay GET yapıp etiketi alır.
- Bu API düzenleme için tek, tırnaklı ETag ister. Bozuk başlık, birden çok etiket veya sürümü kontrol etmeden her kaydı eşleştiren `*` kullanımı 400 `INVALID_PRECONDITION` döner. Zayıf `W/"..."` etiketi güçlü karşılaştırmada eşleşmez ve 412 döner.
- Etiket güncel temsil ile eşleşmiyorsa 412 `MEMORY_MODIFIED` döner; hiçbir alan veya fotoğraf değiştirilmez. Mobil yerel formu korur, güncel detayı yeniden alır ve iki sürümü kullanıcıya gösterir. Güncel ETag'i alıp eski formu otomatik göndermek yasaktır; yeni kayıt kullanıcının açık incelemesinden sonra yapılır.
- Etiket eşleşiyorsa, sürüm kontrolü ve alanların yazılması aynı transaction'da atomik yapılır. Aynı sürümü kullanan iki eşzamanlı PUT'tan yalnız biri yazabilir; diğeri 412 alır. Önce okuyup sonra koşulsuz UPDATE yapmak yeterli değildir.
- Başarılı PUT 200 ve güncel MemoryDetail döner. Yazma DTO'su normalleştirildiği ve detaydan farklı olduğu için başarılı PUT yanıtında ETag gönderilmez; bir sonraki düzenlemenin etiketi detay GET ile alınır. Bu seçim, dönüştürülen PUT gövdelerinde validator gönderilmesine ilişkin RFC 9110 kuralına uyar.

Yanıtı kaybolan PUT aynı eski etiketle tekrarlandığında 412 alabilir; 200 dönmüş gibi varsayılmaz. İstemci GET ile güncel durumu alıp yerel form ile karşılaştırır; başka cihazın değişikliği otomatik ezilmez. POST için tanımlı Idempotency-Key kuralları PUT'un If-Match koşulunu kaldırmaz.

MemoryDetail fotoğraf bilgilerini de içerdiği için başarılı fotoğraf ekleme/silme, `photoCount`, `coverPhotoId`, `photos` veya başka görünür alanı değiştiren her işlem anının sürümünü de aynı transaction'da artırır. Başarılı bir isteğin idempotent tekrarı veri/sıra/sürümü yeniden değiştirmez. GET gövdesi değişirken aynı güçlü etiket tekrar kullanılamaz.

Sunucu uygulaması için karar: Memory entity'sinde `@Version` long alanı ve `memories.version BIGINT NOT NULL DEFAULT 0` gerekir. Bu kolon mevcut V1/V2'de yoktur; ilgili API özelliği uygulanırken yeni bir sürümlü migration ile eklenecek, uygulanmış dosyalar değiştirilmeyecektir. Fotoğraf işlemleri de anının sürümünü artırmalı ve optimistic-lock hatası sahiplik denetiminden sonra 412'ye çevrilmelidir. Bu PR yalnız sözleşmeyi belirler; var olan şemaya kolon eklemez veya korumanın çalıştığını iddia etmez.

412 gövde örneği:

```json
{"code":"MEMORY_MODIFIED","message":"Anı başka bir işlemle değişti. Güncel halini alıp değişikliklerinizi kontrol edin."}
```

Uygulama kabul senaryoları: iki cihaz aynı r7'yi okur; ilk PUT 200, ikinci PUT 412 ve ilk cihazın kaydı korunur. Eksik If-Match 428, zayıf/eski etiket 412, bozuk/* etiket 400 olmalıdır. Kaybolan başarılı PUT'un tekrarı ve arada fotoğraf değişmesi de eski formla sessiz yazma yapmamalıdır. Bunlar henüz çalıştırılmış backend testleri değildir; endpoint yazılırken gerçek kalıcılık testlerine eklenecektir.

### Sayfalama ve arama

`GET /memories?page=0&size=20&q=sahil&category=TRAVEL` örneğinde `page` sıfırdan başlar; varsayılan 0'dır. `size` varsayılan 20, aralık 1–100'dür. Negatif/geçersiz değer 400; son sayfadan sonrası 200 ve boş `content` döner. `q` kırpılır, en fazla 100 karakterdir; başlık/açıklama içinde Türkçe büyük-küçük harf duyarsız düz metin aramasıdır. Hem sorgu hem aranan metin NFC Unicode normalleştirmesinden sonra Türkçe (`tr-TR`) küçük harfe dönüştürülerek karşılaştırılır; veritabanının varsayılan collation/ILIKE davranışına bırakılmaz. `İstanbul`–`istanbul` ve `Işık`–`ışık` eşleşir; `Işık`–`isik` eşleşmez. `%` ve `_` düz karakterdir, joker değildir; aksan/harf sadeleştirmesi yapılmaz. Boş `q` veya `category` filtreyi kaldırır. Kategori sabit kodu büyük harfle tam eşleşir; Türkçe etiket query parametresi olarak gönderilmez. İki filtre birlikte AND olarak uygulanır.

Arama uygulamasında bu üç Türkçe örneği ve `%`/`_` düz karakter davranışı kabul testi olacak. Bu PR arama kodu veya çalıştırılmış DB testi içermez. Kullanıcıya ait kayıt sayısı düşük demo için tarama kabul edilir; ihtiyaç oluşursa pg_trgm/indeks planı ayrı şema değişikliğiyle değerlendirilecek.

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

`GET /memories/map?q=sahil&category=TRAVEL` aynı arama/kategori kurallarını uygular. `page`/`size` kabul etmez; verilirse 400 döner. Yalnız oturum sahibinin konumlu kayıtları döner; liste sayfasındaki 20 kayıtla sınırlanmaz. Konumsuz anılar atlanır. Noktalar `memoryDate DESC`, `id DESC` sırasındadır. İlk sürümde en fazla 1000 nokta döner; sahiplik ve filtreler sınırdan önce uygulanır. `limit=1000`, `returned` ve `truncated` yanıt alanları zorunludur. 1000 veya daha az eşleşmede truncated=false; daha fazla eşleşmede ilk 1000 nokta ve truncated=true döner. Mobil kesilme bilgisini açıkça gösterir ve kullanıcıyı arama/kategori daraltmaya veya sayfalı listeye yönlendirir; bütün anıların haritada göründüğü izlenimi verilmez. İleride görünen alan için minLat/maxLat/minLng/maxLng filtreleri eklenebilir; şu an bu parametreler desteklenmiş sayılmaz.

```json
{"points":[{"id":"22222222-2222-4222-8222-222222222222","title":"Sahil yürüyüşü","memoryDate":"2026-10-22","latitude":41.0082,"longitude":28.9784,"coverPhotoId":null}],"limit":1000,"returned":1,"truncated":false}
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
  "contentUrl":"/api/v1/memories/22222222-2222-4222-8222-222222222222/photos/33333333-3333-4333-8333-333333333333/content"
}
```

`contentUrl` göreli ve korumalıdır; mobil görsel isteğine de Bearer başlığı ekler. Token URL/query parametresine yazılmaz. Görsel yanıtları ortak JSON zarfına alınmaz; hata halinde dosya yerine aşağıdaki JSON hata gövdesi döner.

## Kısmi başarı ve tekrar deneme

1. Mobil form kaydında bir UUID `Idempotency-Key` üretip POST `/memories` isteğiyle gönderir. 201 yanıtındaki anı kimliği saklanır. Sonra seçili fotoğraflar bu anıya ayrı ayrı yüklenir; istek başına tek dosya olduğundan her dosyanın sonucu bellidir.
2. Her fotoğraf için ayrı UUID anahtar tutulur; yanıtın `order` değeri sunucudan alınır. Anı kaydolup bir fotoğraf hata verirse anı geri alınmaz ve yeniden oluşturulmaz; yalnız başarısız/belirsiz fotoğraf aynı anahtar ve dosya ile yeniden gönderilir. Başarılı fotoğraflar tekrar yüklenmez.
3. Yanıt kaybolmuşsa aynı isteği aynı anahtarla tekrar etmek ilk kaydın kimliğini döndürür; ikinci anı/fotoğraf oluşturmaz. İlk başarılı yazma 201, tekrar 200 ve `Idempotency-Replayed: true` döner. Oluşturulan kaynağın `Location` bilgisi aynı kalır. Aynı anahtarla farklı içerik 409 `IDEMPOTENCY_CONFLICT` döner.
4. Anahtarın kapsamı kullanıcı + yöntem + hedef yoldur. Sunucu başarılı sonucu ve içerik eşleşmesini kalıcı olarak kaydeder; eşzamanlı tekrarda tek kayıt üretilir. Kaynak silinmişse aynı anahtar yeniden kaynak yaratmaz, 409 `IDEMPOTENCY_KEY_RETIRED` döner. Uygulama başarısız olmuş ve hiçbir kayıt oluşmamışsa anahtar yeniden denenebilir. Kullanıcı aynı dosyayı yeni anahtarla bilinçli olarak seçerse bu yeni bir yüklemedir; kopya önleme bu belirsiz ağ tekrarları içindir.
5. Tekrar kontrolü 5 adet sınırı kontrolünden önce yapılır; daha önce başarılı bir isteğin tekrarı limit doldu diye altıncı dosya sayılmaz. Dosya içeriği ve MIME türü eşleşmesi sunucuda denetlenir; ilk başarılı yanıtın sıra değeri tekrar yanıtta korunur. PUT yanıtı belirsizse önceki If-Match ile körlemesine yazmak yerine detay GET yapılır; 412 akışı izlenir. DELETE sonrası doğrulamada silinmiş kaynak için 404 beklenir.
6. Mobil kısmi başarıyı örneğin “Anı kaydedildi, 2 fotoğraf yüklenemedi” olarak gösterir. Başarısız dosyaların seçimi ön plandaki tekrar deneme için korunur; bunun kapsamı süreçler arası çevrimdışı senkronizasyon değildir. 401 durumunda tekrar deneme durur ve oturum akışına dönülür.

Geçersiz dosya/tarih gibi 4xx hataları veri düzeltilmeden tekrar gönderilmez. 429'da Retry-After süresi beklenir; başlık beklenmedik biçimde yoksa güvenli varsayılan 60 saniyedir. Bağlantı/5xx hatasında kullanıcı yeniden deneyebilir; yeni anahtar üretilmez. Anı silme DB kayıtları için transaction, fiziksel dosyalar için commit sonrası temizleme kullanır; dosya hatası nedeniyle kullanıcıya silinmemiş anı gibi yanıt verilmez. Başarı/rollback ve dosya temizliği uygulaması ML-13/ML-14'ün işidir; bu belge o işleri gerçekleştirmez.

## Hata gövdesi ve durumlar

Auth için belirlenen biçim korunur; mobil metinden değil `code` alanından karar verir. Alan hatası `message` içinde anlaşılır anlatılır. Dosya yolu, SQL, stack trace, parola ve token yanıta yazılmaz.

```json
{"code":"INVALID_INPUT","message":"Başlık 1–100 karakter olmalıdır."}
```

| HTTP | Kod | Anlam |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | Alan, UUID, tarih, koordinat, sayfa geçersiz; zorunlu anahtar eksik |
| 400 | `INVALID_JSON` | Okunamayan JSON |
| 400 | `INVALID_PRECONDITION` | PUT için If-Match biçimi desteklenmiyor veya * kullanılmış |
| 401 | `INVALID_CREDENTIALS` | Hesap/parola doğrulanamadı; yalnız login |
| 401 | `INVALID_SESSION` | Eksik, geçersiz, iptal edilmiş veya süresi dolmuş oturum; `WWW-Authenticate: Bearer` |
| 404 | `MEMORY_NOT_FOUND` / `PHOTO_NOT_FOUND` | Kaynak bulunamadı veya kullanıcıya ait değil; yabancı kaynak için ayrı kod yok |
| 409 | `EMAIL_IN_USE` | Normalize e-posta zaten kayıtlı |
| 409 | `PHOTO_LIMIT_REACHED` | Anıda 5 kayıt var |
| 409 | `IDEMPOTENCY_CONFLICT` / `IDEMPOTENCY_KEY_RETIRED` | Anahtar farklı içerikle kullanıldı veya kaynak silinmiş |
| 412 | `MEMORY_MODIFIED` | PUT etiketi güncel sürümle eşleşmiyor; veri değiştirilmez |
| 428 | `PRECONDITION_REQUIRED` | PUT için If-Match eksik; önce detay GET yapılmalı |
| 413 | `FILE_TOO_LARGE` / `REQUEST_TOO_LARGE` | Dosya limiti / auth gövde limiti aşıldı |
| 415 | `UNSUPPORTED_PHOTO_TYPE` | Fotoğraf JPEG/PNG değil veya içerik türü uyuşmuyor |
| 405 / 415 | `HTTP_ERROR` | Yöntem veya JSON isteğinin Content-Type değeri desteklenmiyor |
| 429 | `RATE_LIMITED` | IP veya login e-posta deneme sınırı; Retry-After saniye bilgisi |
| 500 | `INTERNAL_ERROR` | İstemciye iç ayrıntısı verilmeyen beklenmeyen hata |
| 503 | `DATABASE_UNAVAILABLE` | Bağlantı hazır değil veya veritabanı kullanılamıyor |

## ML-04 şema eşleştirmesi

Güncel main'deki V1 ve V2 migration'ları birlikte esas alınır; uygulanmış V1 değiştirilmez. Bu sözleşme migration veya altyapı kodunu değiştirmez.

| API / kural | ML-04 karşılığı |
| --- | --- |
| User, Memory, Photo UUID | V2 `users.id`, `memories.id`, `photos.id` ve ilgili UUID foreign key'ler |
| Session | Dışarı açılmayan BIGINT `sessions.id`; UUID `user_id`, hash ve UTC zaman alanları |
| ETag/If-Match sürümü | Yeni `memories.version` kolonu ve entity @Version alanı gerektirir; mevcut V1/V2'de bulunmaz, sonraki sürümlü migration ile eklenir |
| `emotion`, `order`, `contentType`, `contentUrl` | SQL `mood`, `position`, `media_type` eşlemesi; URL controller tarafından üretilir, storage_key veya dosya yolu açılmaz |
| Anı `Idempotency-Key` | `memories.client_request_id`, `(owner_id, client_request_id)` tekilliği ve `client_request_sha256` |
| Fotoğraf `Idempotency-Key` | `photos.client_photo_id`, `(memory_id, client_photo_id)` tekilliği ve `content_sha256` |
| JPEG/PNG, 5.000.000 bayt | `media_type` ve `size_bytes` kısıtları |
| Kapak ve sıra | En küçük mevcut position; yeni kayıt max(position)+1. Silinen sıra doldurulmaz |
| En fazla 5 fotoğraf | Servis aynı anı satırını kilitleyip adet kontrolünü yapacak; schema pozisyonu 0–4 ile sınırlamaz |

Anı gövdesi özeti, doğrulanmış/varsayılanları uygulanmış yazma alanlarından hesaplanır: anahtar sırası `title`, `description`, `memoryDate`, `category`, `emotion`, `location`; location içinde `latitude`, `longitude`. JSON UTF-8, gereksiz boşluksuz ve anahtar sırası sabit üretilir; sayılarda anlamsız son sıfırlar kaldırılır, −0 değeri 0 olur. Kaynak metindeki JSON boşlukları ve alan sırası özete etki etmez. Sunucunun bu standart gösteriminin SHA-256 hex değeri `client_request_sha256` olur. Fotoğraf `content_sha256` yalnız ham dosya baytlarından hesaplanır; multipart sınır/metin bilgisi dahil edilmez. Aynı anahtarla gelen MIME türü ayrıca karşılaştırılır. İstemci özeti veya iç alanları belirleyemez.

V2 şeması anahtar ve içerik özetlerini sağlar; başarılı tekrarın 200 yanıtı, anahtar çakışmasının 409 yanıtı ve anı satırı kilidi servis uygulamasında yapılacaktır. Silinmiş anahtarın tekrar kullanılmaması için ayrıca kalıcı emekli-anahtar kaydı gerekir; mevcut V2'nin bunu tek başına sağladığı iddia edilmez. Bu depolama, ilgili API özelliği geliştirilirken sürümlü migration ile eklenecek, burada veritabanı değiştirilmez. Böylece beklenen HTTP sözleşmesi ile henüz yazılmamış uygulama birbirine karıştırılmaz.

## Sözleşme ve hata biçimi kararı

Bu PR'da tek kaynak bu Markdown sözleşmesidir. OpenAPI 3.1 incelendi; şema araçları, istemci üretimi ve sözleşme testleri için uygun bir sonraki adımdır. Bu PR için zorunlu olmayan ikinci bir OpenAPI dosyası eklenmez. İlk iş endpointi yazılmadan önce taşıma kararı kesinleştirilecek; taşınırsa alan/yanıt şemaları OpenAPI'de tek kaynak olacak ve Markdown yalnız akış kararlarını açıklayacak. Aynı kuralları iki yerde bağımsız güncel tutma yolu seçilmez.

Spring MVC `ProblemDetail` ve RFC 9457'yi destekler; bu, Spring Boot kullanınca bütün özel hata yanıtlarının kendiliğinden o biçime dönüştüğü anlamına gelmez. İlk sürüm için kararımız, bu belgede örneklenen tek `{code,message}` JSON hata gövdesini korumaktır; mobil yalnız code üzerinden eşler. Problem Details'e geçilirse ayrı ve açık bir sözleşme sürümü kararı gerekir; bazı endpointlerin bir biçim, bazılarının diğer biçim dönmesi kabul edilmez. Bu PR ikinci bir hata biçimi veya exception handler eklemez.

Kaynaklar: [OpenAPI 3.1](https://spec.openapis.org/oas/v3.1.1.html), [Spring MVC hata yanıtları](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html).

## ML-03 teslim durumu

ML-03'ün veri ilişkileri, biçimler, endpointler, DTO/hata/sayfalama/yükleme örnekleri ve kısmi başarı/tekrar kuralları tamamlanmıştır. K2 fotoğraf akışı incelemesini ekipte ayrıca yapacaktır; onay alınmış gibi kaydedilmez. Belgenin hazırlanması, API uygulaması ve ekip incelemesi ayrı işlerdir; doğrulama sonucu ilgili PR/CI üzerinden takip edilir. Bu belge endpoint uygulaması, migration veya mobil ekran geliştirmesi değildir.

## HTTP eşzamanlılık kaynağı

- [RFC 9110 — If-Match](https://www.rfc-editor.org/rfc/rfc9110.html#name-if-match)
- [RFC 9110 — PUT](https://www.rfc-editor.org/rfc/rfc9110.html#name-put)
- [RFC 6585 — 428 Precondition Required](https://www.rfc-editor.org/rfc/rfc6585.html#section-3)
