package tr.edu.siirt.fistik.kural;

import java.util.List;

/** Uygulama hataları → HTTP durum kodları (eşleme: api/HataYoneticisi). */
public final class Hatalar {

    private Hatalar() {
    }

    /** 400 — iş kuralı ihlali. */
    public static class IsKuraliHatasi extends RuntimeException {
        public IsKuraliHatasi(String mesaj) {
            super(mesaj);
        }
    }

    /** 404 — istenen kayıt yok. */
    public static class BulunamadiHatasi extends RuntimeException {
        public BulunamadiHatasi(String mesaj) {
            super(mesaj);
        }
    }

    /** 409 — tekrarlı kayıt. */
    public static class CakismaHatasi extends RuntimeException {
        public CakismaHatasi(String mesaj) {
            super(mesaj);
        }
    }

    /** 401 — işlemi yapan kullanıcı belirlenemedi. */
    public static class YetkiHatasi extends RuntimeException {
        public YetkiHatasi(String mesaj) {
            super(mesaj);
        }
    }

    /** 429 — çok fazla deneme (halka açık sorgu). */
    public static class CokFazlaDenemeHatasi extends RuntimeException {
        public CokFazlaDenemeHatasi(String mesaj) {
            super(mesaj);
        }
    }

    /** 422 — doğrulama hatası (alan bazlı). */
    public static class DogrulamaHatasi extends RuntimeException {
        private final List<AlanHatasi> detaylar;

        public DogrulamaHatasi(List<AlanHatasi> detaylar) {
            super("Gönderilen veriler geçersiz.");
            this.detaylar = detaylar;
        }

        public DogrulamaHatasi(String alan, String mesaj) {
            this(List.of(new AlanHatasi(alan, mesaj)));
        }

        public List<AlanHatasi> getDetaylar() {
            return detaylar;
        }
    }

    public record AlanHatasi(String alan, String mesaj) {
    }
}
