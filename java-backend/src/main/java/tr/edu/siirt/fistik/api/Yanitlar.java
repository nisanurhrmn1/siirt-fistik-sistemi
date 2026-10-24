package tr.edu.siirt.fistik.api;

import tr.edu.siirt.fistik.alan.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Yanıt gövdeleri (JSON alan adları snake_case; para/ağırlık değerleri metin olarak döner). */
public final class Yanitlar {

    private Yanitlar() {
    }

    public record HataYaniti(String hata) {
    }

    public record DogrulamaHatasiYaniti(String hata, List<tr.edu.siirt.fistik.kural.Hatalar.AlanHatasi> detaylar) {
    }

    public record Sayfa<T>(long toplam, int sayfa, int sayfaBoyutu, List<T> kayitlar) {
    }

    // ─── Üretici ───
    public record BankaHesabiYaniti(Long id, String bankaAdi, String iban, String hesapSahibi, boolean varsayilan,
                                    boolean aktif) {
    }

    public record UreticiYaniti(Long id, ProducerType tip, String tcKimlikNo, String vergiNo, String vergiDairesi,
                                String adSoyad, String telefon, String eposta, String adres, String ilce, String il,
                                boolean aktif, OffsetDateTime kayitZamani, List<BankaHesabiYaniti> bankaHesaplari) {
    }

    // ─── Hakediş / finans ───
    public record KesintiYaniti(String kod, String ad, DeductionCalcMethod hesaplama, BigDecimal oran, BigDecimal matrah,
                                BigDecimal tutar) {
    }

    public record AvansMahsubuYaniti(Long avansId, String avansNo, BigDecimal tutar) {
    }

    public record HakedisYaniti(Long id, String hakedisNo, Long tartimId, String fisNo, Long ureticiId, BigDecimal netKg,
                                BigDecimal birimFiyat, BigDecimal brutTutar, List<KesintiYaniti> kesintiler,
                                BigDecimal kesintiToplami, List<AvansMahsubuYaniti> avansMahsuplari,
                                BigDecimal avansMahsubuToplami, BigDecimal netOdenecek, BigDecimal odenen,
                                BigDecimal kalan, SettlementStatus durum, OffsetDateTime olusturmaZamani) {
    }

    public record AvansYaniti(Long id, String avansNo, Long ureticiId, BigDecimal tutar, BigDecimal mahsupEdilen,
                              BigDecimal kalan, AdvanceStatus durum, PaymentMethod odemeYontemi,
                              OffsetDateTime verilisZamani, String aciklama, BigDecimal ureticiGuncelBakiye) {
    }

    public record OdemeYaniti(Long id, String odemeNo, BigDecimal tutar, PaymentMethod odemeYontemi, Long bankaHesapId,
                              String iban, OffsetDateTime odemeZamani, String referansNo) {
    }

    public record OdemeSonucu(String mesaj, BigDecimal buIslemdeMahsupEdilenAvans, OdemeYaniti odeme,
                              HakedisYaniti hakedis, BigDecimal ureticiGuncelBakiye) {
    }

    public record EkstreOzeti(BigDecimal bakiye, String bakiyeDurumu, BigDecimal toplamTeslimatKg,
                              BigDecimal toplamTeslimatTutari, BigDecimal toplamKesinti, BigDecimal toplamVerilenAvans,
                              BigDecimal toplamOdeme, BigDecimal acikAvansBakiyesi, BigDecimal odenmemisHakedisToplami,
                              BigDecimal odenebilirTutar) {
    }

    public record EkstreTeslimat(Long tartimId, String fisNo, String lotNo, OffsetDateTime tarih, BigDecimal netKg,
                                 String kaliteKodu, BigDecimal randiman, BigDecimal birimFiyat, Long hakedisId,
                                 BigDecimal brutTutar, BigDecimal kesinti, BigDecimal avansMahsubu,
                                 BigDecimal netOdenecek, BigDecimal odenen, BigDecimal kalan,
                                 SettlementStatus hakedisDurumu, ProductType urunTuru, String urunTuruAdi) {
    }

    public record EkstreAvans(Long id, String avansNo, OffsetDateTime tarih, BigDecimal tutar, BigDecimal mahsupEdilen,
                              BigDecimal kalan, AdvanceStatus durum) {
    }

    public record EkstreHareket(Long id, OffsetDateTime tarih, String tur, String aciklama, BigDecimal alacak,
                                BigDecimal borc, BigDecimal bakiye) {
    }

    public record UreticiKisa(Long id, String adSoyad, String tcKimlikNo, String vergiNo, String telefon) {
    }

    public record UreticiEkstre(UreticiKisa uretici, OffsetDateTime donemBaslangic, OffsetDateTime donemBitis,
                                EkstreOzeti ozet, BigDecimal devredenBakiye, List<EkstreTeslimat> teslimatlar,
                                List<EkstreAvans> acikAvanslar, List<EkstreHareket> hareketler,
                                List<EkstreAvans> avanslar, List<EkstreOdeme> odemeler) {
    }

    /** Dönemde yapılan ödeme. */
    public record EkstreOdeme(Long id, String odemeNo, OffsetDateTime tarih, BigDecimal tutar, PaymentMethod odemeYontemi,
                              String referansNo) {
    }

    // ─── Kantar ───
    public record KaliteSonucu(String sinifKodu, String sinifAdi, BigDecimal catlakOrani, BigDecimal randiman,
                               BigDecimal bosOrani, BigDecimal curukOrani, BigDecimal nemOrani, boolean manuelAtama) {
    }

    public record FiyatKilidi(Long fiyatId, BigDecimal birimFiyat, OffsetDateTime fiyatGecerlilikBaslangici,
                              OffsetDateTime fiyatOnayZamani, OffsetDateTime kilitlenmeZamani) {
    }

    public record StokKaydi(Long siloId, String siloKodu, BigDecimal miktarKg) {
    }

    public record TartimYaniti(Long id, String fisNo, String lotNo, TicketStatus durum, Long ureticiId,
                               String ureticiAdi, String plaka, String soforAdi, BigDecimal brutKg, BigDecimal daraKg,
                               BigDecimal netKg, OffsetDateTime tartimZamani, KaliteSonucu kalite, FiyatKilidi fiyat,
                               HakedisYaniti hakedis, List<StokKaydi> stok, BigDecimal ureticiGuncelBakiye,
                               ProductType urunTuru, String urunTuruAdi, BigDecimal aracDarasiKg, int cuvalSayisi,
                               BigDecimal cuvalDarasiKg, WeightSource brutKaynagi, WeightSource daraKaynagi) {
    }

    public record KesintiOnizleme(String kod, String ad, BigDecimal tutar) {
    }

    public record TartimOnizleme(BigDecimal netKg, String kaliteKodu, String kaliteAdi, Long fiyatId,
                                 BigDecimal birimFiyat, BigDecimal brutTutar, List<KesintiOnizleme> kesintiler,
                                 BigDecimal kesintiToplami, BigDecimal acikAvans, BigDecimal avansMahsubu,
                                 BigDecimal tahminiNetOdenecek, List<String> uyarilar) {
    }

    public record TartimOzeti(Long id, String fisNo, String lotNo, OffsetDateTime tartimZamani, Long ureticiId,
                              String ureticiAdi, String plaka, BigDecimal netKg, String kaliteKodu,
                              BigDecimal birimFiyat, Long hakedisId, BigDecimal netOdenecek, BigDecimal kalan,
                              String hakedisDurumu, ProductType urunTuru) {
    }

    // ─── Fiyat ───
    public record FiyatYaniti(Long id, String kaliteKodu, String kaliteAdi, BigDecimal birimFiyat, String paraBirimi,
                              OffsetDateTime gecerlilikBaslangici, OffsetDateTime gecerlilikBitisi, PriceStatus durum,
                              OffsetDateTime onayZamani) {
    }

    public record GuncelFiyatKalemi(String kaliteKodu, String kaliteAdi, FiyatYaniti fiyat, ProductType urunTuru,
                                    String urunTuruAdi) {
    }

    public record GuncelFiyatlar(OffsetDateTime sorguZamani, List<GuncelFiyatKalemi> kalemler) {
    }

    // ─── Depo / rapor ───
    public record KaliteStokPayi(String kaliteKodu, BigDecimal stokKg, int lotSayisi) {
    }

    public record SiloStok(Long siloId, String siloKodu, String siloAdi, String adanmisKalite, BigDecimal kapasiteKg,
                           BigDecimal stokKg, BigDecimal bosKapasiteKg, BigDecimal dolulukOrani,
                           List<KaliteStokPayi> kaliteler) {
    }

    public record KaliteStok(String kaliteKodu, String kaliteAdi, BigDecimal stokKg, int lotSayisi,
                             List<String> silolar) {
    }

    public record StokDurumu(String gruplama, OffsetDateTime sorguZamani, BigDecimal toplamStokKg,
                             List<SiloStok> silolar, List<KaliteStok> kaliteler) {
    }

    public record UreticiBakiye(Long ureticiId, String adSoyad, BigDecimal bakiye, BigDecimal acikAvans,
                                BigDecimal odenmemisHakedis, BigDecimal odenebilir, BigDecimal toplamTeslimatKg) {
    }

    public record KaliteKirilimi(String kaliteKodu, int teslimatSayisi, BigDecimal netKg, BigDecimal brutTutar,
                                 BigDecimal ortalamaBirimFiyat, BigDecimal ortalamaRandiman) {
    }

    // ─── Gün sonu kasa & kantar raporu ───

    /** Kasadan çıkan para ve hakediş durumu. "Nakit" kasadan fiilen çıkan paradır (havale/çek bankadan). */
    public record KasaOzeti(BigDecimal nakitOdeme, BigDecimal havaleOdeme, BigDecimal cekOdeme, BigDecimal toplamOdeme,
                            BigDecimal nakitAvans, BigDecimal havaleAvans, BigDecimal cekAvans, BigDecimal toplamAvans,
                            BigDecimal kasadanCikanNakit, BigDecimal gununNetHakedisi, BigDecimal gununHakedisindenOdenen,
                            BigDecimal gununKalanHakedisi, BigDecimal tumAcikHakedisler) {
    }

    public record UrunTuruOzeti(ProductType urunTuru, String urunTuruAdi, int teslimatSayisi, BigDecimal netKg,
                                BigDecimal netHakedis) {
    }

    public record GunSonuTeslimat(OffsetDateTime zaman, String fisNo, String lotNo, String uretici, String plaka,
                                  String urunTuruAdi, String kaliteKodu, BigDecimal netKg, BigDecimal birimFiyat,
                                  BigDecimal brutTutar, BigDecimal kesinti, BigDecimal avansMahsubu, BigDecimal netOdenecek,
                                  BigDecimal odenen, BigDecimal kalan, WeightSource brutKaynagi, WeightSource daraKaynagi) {
    }

    /** O gün kasadan / bankadan çıkan para: hakediş ödemesi veya avans. */
    public record GunSonuKasaHareketi(OffsetDateTime zaman, String tur, String belgeNo, String uretici,
                                      PaymentMethod odemeYontemi, BigDecimal tutar, String referansNo) {
    }

    public record SiloGirisi(String siloKodu, String siloAdi, String kaliteKodu, int lotSayisi, BigDecimal girenKg,
                             BigDecimal guncelStokKg) {
    }

    public record GunSonuRaporu(LocalDate tarih, OffsetDateTime olusturulma, GunlukOzet ozet, KasaOzeti kasa,
                                List<UrunTuruOzeti> urunTurleri, List<GunSonuTeslimat> teslimatlar,
                                List<GunSonuKasaHareketi> kasaHareketleri, List<SiloGirisi> siloGirisleri) {
    }

    public record GunlukOzet(LocalDate tarih, int teslimatSayisi, int ureticiSayisi, BigDecimal toplamNetKg,
                             BigDecimal ortalamaRandiman, BigDecimal ortalamaBirimFiyat, BigDecimal brutAlimTutari,
                             BigDecimal toplamKesinti, BigDecimal toplamAvansMahsubu, BigDecimal netHakedisToplami,
                             BigDecimal odenenToplam, int odemeSayisi, BigDecimal verilenAvansToplami,
                             List<KaliteKirilimi> kaliteKirilimi) {
    }
}
