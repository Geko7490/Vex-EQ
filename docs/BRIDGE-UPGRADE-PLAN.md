# Bridge Upgrade Plan / Bridge Güncelleme Planı

Bu doküman, çalışan **bridge**'i bozmadan daha iyi hale getirmek için yapılan
denetimi ve uygulanacak yolu kaydeder. Hiçbir kod değişikliği, kaynağı
görülmeden yapılmaz.

---

## 1. Denetim sonucu (audit)

Bu ajan oturumunda bağlı olan tek depo: `Geko7490/Vex-EQ`.
Çalışma dalı (working branch): `vex-agent-1793776530292`.

Dal üzerinde görülebilen **tüm** dosyalar:

| Yol | Durum |
| --- | --- |
| `README.md` | mevcut |

Denenen ve **bulunamayan** yollar (hepsi boş / 404 döndü):

- `app/`, `src/`, `android/`, `bridge/`
- `.github/workflows/`
- `bridge.py`, `package.json`, `build.gradle.kts`

### Erişilemeyen kaynaklar

Aşağıdakilere bu araç setiyle erişilemiyor:

1. **`main` dalı ve diğer dallar** — araçlar yalnızca seçili çalışma dalını okur;
   dal listeleme / dal değiştirme yeteneği yok.
2. **GitHub Actions çalıştırmaları ve artifact'ları** — "son sürüm" olarak
   kastedilen build çıktısı (APK / zip / log) indirilemiyor.
3. **Commit geçmişi** — hangi sürümün "son çalışan sürüm" olduğu doğrulanamıyor.

Bu nedenle bridge kaynak kodu **görülmedi**. Görülmeyen bir dosyayı "iyileştirmek"
adına yeniden yazmak, çalışan bir bileşeni bozmanın en olası yoludur. Bu yüzden
bu dalda bridge'e dokunulmadı.

---

## 2. Devam etmek için gerekenler

Aşağıdakilerden biri sağlandığında bridge güncellemesi tek seferde yapılabilir:

- [ ] Bridge dosyalarının çalışma dalına alınması (ör. `main` → bu dal merge),
      veya
- [ ] Bridge kaynak dosyalarının doğrudan mesaj olarak verilmesi, veya
- [ ] Bridge'in bulunduğu dalın çalışma dalı olarak seçilmesi.

Ek olarak faydalı olanlar:

- Son çalışan Actions çalıştırmasının **run ID**'si veya log çıktısı.
- Bridge'in dili/çalışma biçimi (Python servis, Node, Kotlin servis, ADB/HTTP
  köprüsü vb.) ve hangi tarafla konuştuğu (VEXPlayer ↔ VEX EQ).

---

## 3. Bozmadan iyileştirme stratejisi

Bridge görüldüğünde uygulanacak kural seti. Amaç: **davranışsal geriye dönük
uyumluluk**, yani eski istemci yeni bridge ile, yeni istemci eski bridge ile
çalışmaya devam eder.

### 3.1 Sürüm anlaşması (handshake)

- Bridge, açılışta kendi protokol sürümünü ve yeteneklerini (capabilities)
  bildirir: `protocolVersion`, `capabilities[]`.
- İstemci desteklemediği bir sürümle karşılaşırsa **sessizce düşmez**, en düşük
  ortak sürüme iner (graceful downgrade).
- Sürüm alanı yoksa (eski bridge) istemci `protocolVersion = 1` varsayar.

### 3.2 Yalnızca ekleme (additive-only) değişiklikler

- Yeni alanlar **opsiyonel** eklenir; mevcut alanların adı, tipi ve anlamı
  değiştirilmez.
- Mevcut komut/mesaj adları korunur; yeni davranış yeni komut adıyla gelir.
- Kaldırma (deprecation) en az bir sürüm boyunca uyarı olarak yaşar, sonra
  kaldırılır.

### 3.3 Hata toleransı

- Bilinmeyen komut → bağlantıyı koparmak yerine yapılandırılmış hata yanıtı.
- Bozuk/eksik parametre → varsayılana düş, logla, devam et.
- Yeniden bağlanma: üstel geri çekilme (exponential backoff) + üst sınır.
- Tüm I/O çağrılarına timeout; süresiz bloklama yok.

### 3.4 Gözlemlenebilirlik

- Yapılandırılabilir log seviyesi; varsayılan `INFO`, hata ayıklamada `DEBUG`.
- Her oturum için tek satır özet: sürüm, yetenekler, seçilen profil, hata sayısı.
- Hassas veri (cihaz kimliği, ses içeriği) loglanmaz.

### 3.5 Performans profilleri

`Low / Mid / High` profilleri bridge tarafında **tek bir yapılandırma
nesnesinden** okunur; profiller arası fark yalnızca parametrelerdir, kod yolu
aynıdır. Böylece bir profildeki düzeltme diğerlerini etkilemez.

### 3.6 Regresyon güvenliği

- Değişiklik öncesi mevcut davranışı yakalayan testler/örnek mesajlar eklenir.
- Bridge'e giden her değişiklik ayrı ve küçük commit'lerde yapılır; böylece
  sorun çıkarsa tek commit geri alınır.
- `main` dalına doğrudan yazılmaz; değişiklikler çalışma dalında toplanır.

---

## 4. Uygulama sırası (bridge erişimi sağlandığında)

1. Bridge kaynağını oku, mevcut mesaj/komut sözleşmesini çıkar.
2. Sözleşmeyi `docs/BRIDGE-CONTRACT.md` olarak yaz (mevcut hâli, olduğu gibi).
3. Handshake + capability alanlarını **opsiyonel** olarak ekle.
4. Hata toleransı ve timeout'ları ekle.
5. Loglama ve oturum özetini ekle.
6. Profil yapılandırmasını tek nesneye topla.
7. Regresyon testlerini/örnek mesajlarını ekle.
8. CI'da bridge için ayrı bir doğrulama işi (job) tanımla.

Her adım ayrı commit; her adımdan sonra eski istemciyle uyumluluk kontrolü.

---

## 5. Bu dalda yapılanlar

- `docs/BRIDGE-UPGRADE-PLAN.md` (bu dosya) eklendi.
- `README.md`'ye çalışma dalı durumu notu eklendi.
- **Bridge kodu değiştirilmedi** — kaynağı görülmediği için.
