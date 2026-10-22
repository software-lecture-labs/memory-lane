# Git çalışma biçimi

1. Güncel `main` üzerinden `chore/`, `feature/` veya `fix/` dalı açın.
2. Her commit tek bir anlamlı değişiklik içersin. Başlık ne değiştiğini, gövde nedenini anlatsın; örnek: `Backend Gradle wrapper dosyalarını ekle`. Teknik önek zorunlu değildir.
3. Commit öncesi `git diff --check` ve ilgili derleme/testleri çalıştırın; staged diff içinde gizli bilgi veya SDK yolu olmadığını kontrol edin.
4. Dalı gönderip PR açın. İlgili gerçek issue/Trello kartını bağlayın; yapılanları, test komutlarını, gerçek sonucu ve eksikleri yazın.
5. Diğer üye incelemesi ve gerekli kontrollerden sonra birleştirin. Paylaşılan dallara force push yapmayın; anlamlı commit geçmişini koruyun.

GitHub yönetim erişimi olduğunda main için PR, en az bir onay ve CI kontrolleri zorunlu kılınmalıdır. Bu belge sunucu tarafı branch protection ayarı oluşturmaz.

Trello kanıtı yalnızca gerçekten oluşturulmuş issue, PR ve CI/analiz çalıştırması bağlantılarını içermelidir. Yerel commitler yayınlanana kadar GitHub kanıt bağlantısı sayılmaz. Tarih, yazar veya test sonucu uydurulmaz.

Anlık doğrulama sonuçları ayrı repo raporları yerine PR açıklamasında komut, gerçek sonuç ve ilgili commit için CI bağlantısıyla paylaşılır. Kalıcı kurulum adımları README içinde tutulur.
