package tr.edu.siirt.fistik.api;

import tr.edu.siirt.fistik.alan.PaymentMethod;
import tr.edu.siirt.fistik.alan.WeightSource;
import tr.edu.siirt.fistik.api.Yanitlar.*;
import tr.edu.siirt.fistik.kural.Dogrulama;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Gün sonu raporunun CSV hâli. Türkçe Excel'in doğrudan açabileceği biçimdedir:
 * UTF-8 BOM (Türkçe karakterler bozulmaz), ';' ayraç, ondalık virgül, Türkiye saati.
 */
public final class CsvRaporu {

    private CsvRaporu() {
    }

    private static final DateTimeFormatter TARIH = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter SAAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter TARIH_SAAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    static String sayi(BigDecimal x) {
        return x == null ? "" : x.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    static String saat(OffsetDateTime t) {
        return t == null ? "" : t.atZoneSameInstant(Dogrulama.TR).format(SAAT);
    }

    /** ';', '"' veya satır sonu içeren alanlar tırnaklanır. */
    static String alan(Object o) {
        String s = o == null ? "" : o.toString();
        return s.contains(";") || s.contains("\"") || s.contains("\n") || s.contains("\r")
                ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    static String yontem(PaymentMethod y) {
        if (y == null) return "";
        return switch (y) {
            case CASH -> "Nakit";
            case BANK_TRANSFER -> "Havale/EFT";
            case CHEQUE -> "Çek";
        };
    }

    static String kaynak(WeightSource k) {
        return k == WeightSource.SCALE ? "Kantar" : "Elle";
    }

    private static class Yazici {
        private final StringBuilder sb = new StringBuilder("﻿");

        Yazici satir(Object... alanlar) {
            sb.append(Arrays.stream(alanlar).map(CsvRaporu::alan).collect(Collectors.joining(";"))).append("\r\n");
            return this;
        }

        Yazici bos() {
            sb.append("\r\n");
            return this;
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }

    public static String gunSonu(GunSonuRaporu r) {
        Yazici y = new Yazici();
        GunlukOzet o = r.ozet();
        KasaOzeti k = r.kasa();
        y.satir("SİİRT FISTIĞI ALIM MERKEZİ - GÜN SONU KASA VE KANTAR RAPORU");
        y.satir("Rapor günü", r.tarih().format(TARIH));
        y.satir("Oluşturulma", r.olusturulma().atZoneSameInstant(Dogrulama.TR).format(TARIH_SAAT));
        y.bos();

        y.satir("ÖZET", "Değer");
        y.satir("Teslimat sayısı (satış)", o.teslimatSayisi());
        y.satir("Emanet teslimat / kg", o.emanetTeslimatSayisi(), sayi(o.emanetKg()));
        y.satir("Üretici sayısı", o.ureticiSayisi());
        y.satir("Toplam net kg", sayi(o.toplamNetKg()));
        y.satir("Ortalama birim fiyat (TL/kg)", sayi(o.ortalamaBirimFiyat()));
        y.satir("Brüt alım tutarı (TL)", sayi(o.brutAlimTutari()));
        y.satir("Kesintiler (TL)", sayi(o.toplamKesinti()));
        y.satir("Avans mahsubu (TL)", sayi(o.toplamAvansMahsubu()));
        y.satir("Günün net hakedişi (TL)", sayi(k.gununNetHakedisi()));
        y.satir("Günün hakedişinden ödenen (TL)", sayi(k.gununHakedisindenOdenen()));
        y.satir("Günün kalan hakedişi (TL)", sayi(k.gununKalanHakedisi()));
        y.satir("Tüm açık hakedişler (TL)", sayi(k.tumAcikHakedisler()));
        y.bos();

        y.satir("KASA", "Nakit", "Havale/EFT", "Çek", "Toplam");
        y.satir("Hakediş ödemeleri (TL)", sayi(k.nakitOdeme()), sayi(k.havaleOdeme()), sayi(k.cekOdeme()), sayi(k.toplamOdeme()));
        y.satir("Verilen avanslar (TL)", sayi(k.nakitAvans()), sayi(k.havaleAvans()), sayi(k.cekAvans()), sayi(k.toplamAvans()));
        y.satir("Kasadan çıkan nakit (TL)", sayi(k.kasadanCikanNakit()));
        y.satir("SATIŞ TAHSİLATI", "Nakit", "Havale/EFT", "Vadeli / açık hesap", "Toplam satış");
        y.satir("Sevkiyat satışları (TL)", sayi(k.nakitTahsilat()), sayi(k.havaleTahsilat()), sayi(k.vadeliSatis()), sayi(k.toplamSatis()));
        y.satir("ALICI TAHSİLATLARI", "Nakit", "Havale/EFT", "Çek/Senet", "Toplam");
        y.satir("Vadeli satış tahsilatı (TL)", sayi(k.aliciNakitTahsilat()), sayi(k.aliciHavaleTahsilat()), sayi(k.aliciCekTahsilat()),
                sayi(k.aliciTahsilatToplami()));
        y.satir("Kasa net nakit: giren - çıkan (TL)", sayi(k.kasaNetNakit()));
        y.bos();

        y.satir("ÜRÜN TÜRÜ", "Teslimat", "Net kg", "Net hakediş (TL)");
        r.urunTurleri().forEach(u -> y.satir(u.urunTuruAdi(), u.teslimatSayisi(), sayi(u.netKg()), sayi(u.netHakedis())));
        y.bos();

        y.satir("TESLİMATLAR");
        y.satir("Saat", "Fiş no", "Lot no", "Üretici", "Plaka", "Teslim şekli", "Ürün", "Sınıf", "Net kg", "Birim fiyat", "Brüt tutar",
                "Kesinti", "Avans mahsubu", "Net hakediş", "Ödenen", "Kalan", "Brüt kaynağı", "Dara kaynağı");
        for (GunSonuTeslimat t : r.teslimatlar())
            y.satir(saat(t.zaman()), t.fisNo(), t.lotNo(), t.uretici(), t.plaka(), t.teslimSekli(), t.urunTuruAdi(), t.kaliteKodu(),
                    sayi(t.netKg()), sayi(t.birimFiyat()), sayi(t.brutTutar()), sayi(t.kesinti()), sayi(t.avansMahsubu()),
                    sayi(t.netOdenecek()), sayi(t.odenen()), sayi(t.kalan()), kaynak(t.brutKaynagi()), kaynak(t.daraKaynagi()));
        if (r.teslimatlar().isEmpty()) y.satir("Bu gün teslimat yok.");
        y.bos();

        y.satir("KASA HAREKETLERİ");
        y.satir("Saat", "İşlem", "Belge no", "Üretici", "Yöntem", "Tutar (TL)", "Referans");
        for (GunSonuKasaHareketi h : r.kasaHareketleri())
            y.satir(saat(h.zaman()), h.tur(), h.belgeNo(), h.uretici(), yontem(h.odemeYontemi()), sayi(h.tutar()), h.referansNo());
        if (r.kasaHareketleri().isEmpty()) y.satir("Bu gün ödeme veya avans yok.");
        y.bos();

        y.satir("EMANETTEN SATIŞA ÇEVRİLENLER");
        y.satir("Saat", "Bozdurma fişi", "Emanet fişi", "Üretici", "Teslim tarihi", "Sınıf", "Satılan kg", "Birim fiyat", "Net hakediş");
        for (EmanetDonusumu d : r.emanetDonusumleri())
            y.satir(saat(d.zaman()), d.fisNo(), d.kaynakFisNo(), d.uretici(), d.teslimTarihi().atZoneSameInstant(Dogrulama.TR).format(TARIH),
                    d.kaliteKodu(), sayi(d.netKg()), sayi(d.birimFiyat()), sayi(d.netOdenecek()));
        if (r.emanetDonusumleri().isEmpty()) y.satir("Bu gün emanetten satışa çevrilen yok.");
        y.bos();

        y.satir("SEVKİYATLAR (SATIŞ)");
        y.satir("Saat", "Sevkiyat no", "Alıcı", "Plaka", "Silo", "Ürün", "Sınıf", "Net kg", "Birim fiyat", "Tutar", "Tahsilat");
        for (GunSonuSevkiyat v : r.sevkiyatlar())
            y.satir(saat(v.zaman()), v.sevkiyatNo(), v.musteri(), v.plaka(), v.siloKodu(), v.urunTuruAdi(), v.kaliteKodu(),
                    sayi(v.netKg()), sayi(v.birimFiyat()), sayi(v.toplamTutar()), v.tahsilatAdi());
        if (r.sevkiyatlar().isEmpty()) y.satir("Bu gün sevkiyat yok.");
        y.bos();

        y.satir("SİLO HAREKETLERİ");
        y.satir("Silo", "Ad", "Kalite", "Giren lot", "Giren kg", "Çıkan kg", "Güncel stok kg");
        List<SiloGirisi> s = r.siloGirisleri();
        s.forEach(g -> y.satir(g.siloKodu(), g.siloAdi(), g.kaliteKodu(), g.lotSayisi(), sayi(g.girenKg()), sayi(g.cikanKg()),
                sayi(g.guncelStokKg())));
        if (s.isEmpty()) y.satir("Bu gün silo hareketi yok.");
        return y.toString();
    }
}
