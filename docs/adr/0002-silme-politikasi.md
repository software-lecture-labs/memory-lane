# ADR-0002: Silme politikası

- Durum: Kabul edildi
- Tarih: 2026-10-24
- İlgili kart: ML-04 (inceleme notu), entity ve silme servisleri yazılmadan önce

## Bağlam

Şemadaki yabancı anahtarlar silme davranışı tanımlamıyordu (PostgreSQL varsayılanı `NO ACTION`). Anı silme ilk sürümün parçasıdır; proje dokümanı bunu ders için transaction örneği olarak tarif eder: sahiplik doğrulanır, anıya bağlı Photo kayıtları, Memory kaydı ve dosya temizleme işleri aynı veritabanı transaction'ında işlenir, fiziksel dosyalar commit sonrasında silinir. Hesap silme ilk sürüm ekranlarında yoktur, ancak KVKK kapsamında bir silme talebinin nasıl karşılanacağı entity'ler yazılmadan önce belirlenmelidir.

Değerlendirilen seçenekler:

1. **Veritabanında cascade (`ON DELETE CASCADE`):** Kod kısalır, ancak fotoğraf satırları sessizce silinir ve dosya temizleme işleri oluşturulmadan diskte sahipsiz dosyalar kalabilir. Silme adımları servis kodunda görünmez, transaction örneği de anlatılamaz hale gelir.
2. **Yumuşak silme (`deleted_at`):** Geri alma kolaylaşır, ancak kişisel veri ve fotoğraflar saklanmaya devam eder. KVKK silme talebi için ayrıca kalıcı silme gerekir. Ayrıca her sorguya filtre eklemeyi gerektirir; unutulan bir filtre başka bir silinmiş kaydı gösterebilir.
3. **Uygulama içinde açık kalıcı silme + veritabanında `RESTRICT`:** Servis bağımlı kayıtları sırayla ve aynı transaction'da siler; veritabanı, bağımlısı kalan bir satırın silinmesini reddeder.

## Karar

Seçenek 3 uygulanır.

- Tüm yabancı anahtarlar açıkça `ON DELETE RESTRICT` tanımlanır (V3). Unutulmuş bir bağımlılık sessiz veri kaybına değil, hataya ve rollback'e yol açar.
- **Anı silme:** Servis sahipliği doğrular; anının fotoğrafları için `file_cleanup_jobs` kayıtları ekler, `photos` satırlarını ve ardından `memories` satırını siler. Hepsi tek transaction'dadır. Fiziksel dosyalar commit sonrasında temizleme işleyicisi tarafından silinir.
- **Hesap silme (ileride, KVKK talebi):** Aynı yol kullanıcı düzeyinde uygulanır: tüm anılar yukarıdaki gibi silinir, `sessions` satırları silinir, en son `users` satırı silinir; hepsi tek transaction'dadır. Kişisel veri saklanmaz, yumuşak silme kullanılmaz.
- Yedeklerdeki kopyaların saklama süresi ayrı bir operasyon kararıdır ve bu ADR'nin kapsamında değildir.

## Sonuçlar

- Silme servisleri bağımlı kayıtları doğru sırayla silmek zorundadır; yanlış sıra `RESTRICT` hatasıyla yakalanır ve testte görünür.
- Rollback testi (proje dokümanındaki kasıtlı hata senaryosu) tüm silme adımlarını kapsayabilir.
- Geri alma (çöp kutusu) özelliği istenirse bu karar yeni bir ADR ile yeniden değerlendirilir.
