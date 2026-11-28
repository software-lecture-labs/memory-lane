# ADR-0004: Harita için MapLibre ve OpenStreetMap (OpenFreeMap)

- Durum: Önerildi
- Tarih: 2026-11-28
- İlgili kartlar: ML-15, ML-16

## Bağlam

ML-16 konumlu anıları haritada pin olarak, ML-15 anı formunda haritadan konum seçmeyi ister. Mobil uygulama Java + XML ile yazılır. Kısıtlar:

- Ekipteki dört geliştirici repoyu klonlayıp ek hesap açmadan çalıştırabilmeli; README temiz kurulumu bunu varsayar (ML-24).
- Repoda gizli bilgi tutulmaz; kişiye özel ayarlar `local.properties` dosyasındadır.
- ML-15: Konum seçimi cihaz konum iznine bağlı olmamalı, harita yüklenemezse form yine çalışmalı.
- Kontrollü demo; mağaza yayını ve herkese açık hizmet yoktur.

## Değerlendirilen seçenekler

1. **Google Maps SDK for Android:** Tanıdık görünüm. Faturalandırması açık bir Google Cloud hesabı (kart bilgisi) ve API anahtarı gerektirir. Anahtar paket adı ve her geliştiricinin imza (SHA-1) değeriyle kısıtlanmalıdır; emülatörde Google Play hizmetleri olan bir sistem imajı gerekir. Anahtarın ekip içinde güvenli paylaşılması ayrıca iş çıkarır.
2. **osmdroid:** OpenStreetMap için yaygın, anahtarsız bir kütüphane; ancak proje Kasım 2024'te arşivlendi, son sürümü 6.1.20 (Ağustos 2024). Teslim süresince hata düzeltmesi beklenemez.
3. **MapLibre Native Android + OpenFreeMap (seçilen):** MapLibre aktif geliştirilen, açık kaynak bir harita kütüphanesidir; Java ile kullanılabilir. OpenFreeMap, OpenStreetMap verisinden hazırlanmış karoları anahtar ve hesap olmadan sunar.
4. **MapLibre + doğrudan `tile.openstreetmap.org`:** Anahtarsızdır, ancak OSM karo sunucusunun kullanım politikası uygulama içi yoğun kullanımı uygun bulmaz ve görsel stil sağlamaz.

## Karar

Seçenek 3 uygulanır.

- Bağımlılık: `org.maplibre.gl:android-sdk:13.7.1`. 13.8.0 seçim günü üç günlük olduğu için bir önceki kararlı sürüm alındı.
- Harita stili varsayılan olarak `https://tiles.openfreemap.org/styles/positron`; pastel arayüzle uyumlu açık bir tema. Adres `BuildConfig.MAP_STYLE_URL` ile okunur ve `local.properties` içindeki `memorylane.mapStyleUrl` ile değiştirilebilir. Bu, ML-15'teki "harita anahtarını yerel yapılandır" maddesinin anahtarsız karşılığıdır: başka bir sağlayıcıya geçmek kod değişikliği gerektirmez.
- Pinler bit eşlem yerine tek bir GeoJSON kaynağından çizilen daire katmanıdır; seçili pin ayrı ve büyük bir katmandır. Pin verisi cihazda kalır.
- Konum izni istenmez; "konumuma git" özelliği yoktur.

## Gizlilik

- Harita karoları üçüncü taraf bir sunucudan (OpenFreeMap) indirilir. Bu istekler cihazın IP adresini ve kullanıcının o an görüntülediği bölgeyi (karo koordinatları) sunucuya iletir. Anı içeriği, başlığı, kimliği veya kaydedilmiş konumu bu isteklere eklenmez; pinler istemcide çizilir.
- Kullanıcı bir anının konumuna yakınlaştığında, o bölgenin karoları istenir; bu, dolaylı olarak ilgilenilen yeri belli eder. Kontrollü demo kapsamında kabul edilmiştir. Gerçek bir yayında kendi karo sunucusu veya sözleşmeli bir sağlayıcı değerlendirilmelidir.
- MapLibre telemetri göndermez.

## Atıf ve lisans

- OpenStreetMap verisi ODbL lisanslıdır ve "© OpenStreetMap contributors" atfı görünür olmalıdır. Stilin atfı MapLibre'nin atıf düğmesiyle gösterilir. Harita sekmesinde alttaki önizleme kartı kenarı kapattığı için atıf ve logo sol üste, anı sayısı çipinin altına alınmıştır; gizlenmez.
- MapLibre Native BSD-2-Clause lisanslıdır.

## APK boyutu

MapLibre yerel bir C++ kütüphanesi (`libmaplibre.so`) içerir. 13.7.1 paketinde ölçülen boyutlar (sıkıştırılmış / açık):

| ABI | Sıkıştırılmış | Açık |
|---|---|---|
| arm64-v8a | 4,3 MB | 12,4 MB |
| armeabi-v7a | 3,8 MB | 9,2 MB |
| x86 | 4,4 MB | 12,8 MB |
| x86_64 | 4,4 MB | 13,0 MB |

Dört ABI'yi birlikte içeren tek APK yaklaşık 17 MB büyür; Java sınıfları yaklaşık 0,8 MB ekler. Tek ABI'li dağıtımda (App Bundle veya ABI ayrımı) cihaz başına indirme artışı yaklaşık 4–4,5 MB'tır. Demo APK'sı için ABI ayrımı yapılmadı; gerekirse `splits.abi` ile yalnız `arm64-v8a` ve emülatör için `x86_64` üretilebilir.

## Sonuçlar

- Yeni geliştirici için ek kurulum adımı yoktur; harita internet bağlantısıyla çalışır.
- Karolar yüklenemezse harita sekmesi ayrı bir uyarı gösterir; pinler, önizleme kartı ve anı formu çalışmaya devam eder.
- OpenFreeMap'in hizmet sürekliliği garanti değildir. Stil adresi yapılandırılabilir olduğu için başka bir MapLibre uyumlu sağlayıcıya geçiş yapılandırma değişikliğidir.
