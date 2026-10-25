package tr.edu.siirt.fistik.sms;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tr.edu.siirt.fistik.alan.Producer;
import tr.edu.siirt.fistik.alan.SmsMessage;
import tr.edu.siirt.fistik.alan.TicketStatus;
import tr.edu.siirt.fistik.api.Yanitlar.AvansYaniti;
import tr.edu.siirt.fistik.api.Yanitlar.TartimYaniti;
import tr.edu.siirt.fistik.kural.Dogrulama;
import tr.edu.siirt.fistik.sms.Sms.SmsSaglayici;
import tr.edu.siirt.fistik.sms.Sms.SmsSonucu;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Müstahsil bilgilendirme SMS'leri: tartım tamamlanınca ve avans verilince.
 * SMS hiçbir zaman asıl işlemi bozmaz: gönderim, işlem kaydedildikten sonra yapılır ve her hata yakalanıp kaydedilir.
 */
@Service
public class SmsServisi {

    private static final Logger log = LoggerFactory.getLogger(SmsServisi.class);

    /** Panelde ve API'de gösterilen gönderim özeti. */
    public record SmsBildirimi(Long id, SmsMessage.Status durum, String durumAdi, String telefon, String mesaj,
                               String saglayici, boolean simulasyon, String hata, OffsetDateTime zaman) {
        public static SmsBildirimi from(SmsMessage m) {
            return new SmsBildirimi(m.getId(), m.getStatus(), m.getStatus().getAd(), m.getPhone(), m.getMessage(),
                    m.getProvider(), m.getStatus() == SmsMessage.Status.SIMULATED, m.getError(), m.getCreatedAt());
        }
    }

    private final EntityManager em;
    private final TransactionTemplate tx;
    private final SmsSaglayici saglayici;
    private final boolean etkin;

    public SmsServisi(EntityManager em, TransactionTemplate tx, SmsSaglayici saglayici,
                      @Value("${fistik.sms.etkin:true}") boolean etkin) {
        this.em = em;
        this.tx = tx;
        this.saglayici = saglayici;
        this.etkin = etkin;
    }

    static String bakiyeMetni(BigDecimal bakiye) {
        return bakiye.signum() < 0 ? "-" + Dogrulama.tl(bakiye.negate()) + " (avans borcu)" : Dogrulama.tl(bakiye);
    }

    static String teslimatMesaji(String ad, TartimYaniti t, BigDecimal emanetToplam) {
        String sinif = t.urunTuruAdi() + (t.kalite() != null ? " " + t.kalite().sinifKodu() + " sınıfı" : "");
        if (t.emanet() && t.hakedis() == null)
            return "Sn. %s, %s ile %s %s fıstığınız emanet olarak teslim alınmıştır. Toplam emanet stoğunuz: %s. Fiyat, satışa çevirdiğiniz gün belirlenir."
                    .formatted(ad, t.fisNo(), Dogrulama.kg(t.netKg()), sinif, Dogrulama.kg(emanetToplam));
        return "Sn. %s, %s ile %s %s fıstığınız teslim alınmıştır. Avans mahsubu sonrası net hakediş: %s. Güncel bakiyeniz: %s."
                .formatted(ad, t.fisNo(), Dogrulama.kg(t.netKg()), sinif,
                        Dogrulama.tl(t.hakedis() != null ? t.hakedis().netOdenecek() : BigDecimal.ZERO),
                        bakiyeMetni(t.ureticiGuncelBakiye()));
    }

    static String satisaCevirmeMesaji(String ad, TartimYaniti t) {
        String kaynak = t.kaynakFisNo() != null ? t.kaynakFisNo() : t.fisNo();
        String kalan = t.emanetKalanKg() != null && t.emanetKalanKg().signum() > 0
                ? " Kalan emanetiniz: %s.".formatted(Dogrulama.kg(t.emanetKalanKg())) : "";
        return "Sn. %s, %s nolu emanetinizden %s bugünkü %s/kg fiyatla satışa çevrilmiştir (%s).%s Avans mahsubu sonrası net hakediş: %s. Güncel bakiyeniz: %s."
                .formatted(ad, kaynak, Dogrulama.kg(t.netKg()), Dogrulama.tl(t.fiyat().birimFiyat()), t.fisNo(), kalan,
                        Dogrulama.tl(t.hakedis().netOdenecek()), bakiyeMetni(t.ureticiGuncelBakiye()));
    }

    static String avansMesaji(String ad, AvansYaniti a) {
        return "Sn. %s, hesabınıza %s avans kaydedilmiştir. Güncel bakiyeniz: %s."
                .formatted(ad, Dogrulama.tl(a.tutar()), bakiyeMetni(a.ureticiGuncelBakiye()));
    }

    public SmsBildirimi teslimatBildir(TartimYaniti t) {
        return bildir(t.ureticiId(), SmsMessage.Trigger.DELIVERY, t.id(), u -> teslimatMesaji(u.getFullName(), t,
                tx.execute(x -> em.createQuery("select coalesce(sum(w.netKg - w.depositSoldKg), 0) from WeighTicket w where w.producerId = :u and w.status = :e",
                        BigDecimal.class).setParameter("u", t.ureticiId()).setParameter("e", TicketStatus.DEPOSITED).getSingleResult())));
    }

    public SmsBildirimi satisaCevirmeBildir(TartimYaniti t) {
        return bildir(t.ureticiId(), SmsMessage.Trigger.CONVERSION, t.id(), u -> satisaCevirmeMesaji(u.getFullName(), t));
    }

    public SmsBildirimi avansBildir(AvansYaniti a) {
        return bildir(a.ureticiId(), SmsMessage.Trigger.ADVANCE, a.id(), u -> avansMesaji(u.getFullName(), a));
    }

    private SmsBildirimi bildir(Long ureticiId, SmsMessage.Trigger tetik, Long ilgiliId,
                                java.util.function.Function<Producer, String> metin) {
        if (!etkin) return null;
        try {
            Producer u = tx.execute(s -> em.find(Producer.class, ureticiId));
            String mesaj = metin.apply(u);
            // Sağlayıcı çağrısı transaction dışında: yavaş bir SMS servisi veritabanı bağlantısı tutmaz
            SmsSonucu sonuc;
            try {
                sonuc = saglayici.gonder(u.getPhone(), mesaj);
            } catch (RuntimeException e) {
                sonuc = new SmsSonucu(false, null, e.getMessage());
            }
            SmsMessage m = new SmsMessage();
            m.setProducerId(ureticiId);
            m.setPhone(u.getPhone());
            m.setMessage(mesaj);
            m.setTriggerType(tetik);
            m.setRelatedId(ilgiliId);
            m.setProvider(saglayici.ad());
            m.setStatus(!sonuc.basarili() ? SmsMessage.Status.FAILED
                    : saglayici.gercek() ? SmsMessage.Status.SENT : SmsMessage.Status.SIMULATED);
            m.setProviderRef(sonuc.referans());
            m.setError(sonuc.hata());
            tx.executeWithoutResult(s -> em.persist(m));
            if (!sonuc.basarili()) log.warn("SMS gönderilemedi ({} {}): {}", tetik, ilgiliId, sonuc.hata());
            return SmsBildirimi.from(m);
        } catch (RuntimeException e) {
            // SMS asıl işlemi hiçbir koşulda bozmaz
            log.error("SMS bildirimi oluşturulamadı ({} {})", tetik, ilgiliId, e);
            return null;
        }
    }

    /** Bir tartım veya avans için gönderilen son SMS (fişte "SMS gönderildi" bilgisi için). */
    @Transactional(readOnly = true)
    public SmsBildirimi son(SmsMessage.Trigger tetik, Long ilgiliId) {
        return em.createQuery("select m from SmsMessage m where m.triggerType = :t and m.relatedId = :i order by m.id desc",
                        SmsMessage.class).setParameter("t", tetik).setParameter("i", ilgiliId).setMaxResults(1)
                .getResultStream().findFirst().map(SmsBildirimi::from).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<SmsBildirimi> listele(Long ureticiId) {
        var q = em.createQuery("select m from SmsMessage m" + (ureticiId != null ? " where m.producerId = :u" : "")
                + " order by m.id desc", SmsMessage.class).setMaxResults(100);
        if (ureticiId != null) q.setParameter("u", ureticiId);
        return q.getResultStream().map(SmsBildirimi::from).toList();
    }
}
