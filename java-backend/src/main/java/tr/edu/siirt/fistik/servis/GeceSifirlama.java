package tr.edu.siirt.fistik.servis;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tr.edu.siirt.fistik.api.Oturum;
import tr.edu.siirt.fistik.kural.Dogrulama;

/**
 * Herkese açık demo sunucusu için: her gece işlem verisini silip örnek veriyi yeniden yükler.
 * Ziyaretçiler gün içinde her şeyi silse bile ertesi gün sistem temiz başlar.
 * Yalnızca {@code fistik.gece-sifirlama=true} ise etkindir; tanım verileri (demo hesapları, kalite sınıfları,
 * kesinti türleri, silolar) korunur, sonradan eklenen kullanıcılar silinir.
 *
 * <p>Ücretsiz barındırmada (Render) sunucu kullanılmayınca uyur ve uykudayken 03:00 zamanlayıcısı çalışmaz.
 * Bu yüzden açılışta da bakılır: bugüne ait hiç tartım yoksa (demo verisi eskiyse) sıfırlama hemen yapılır.
 * Böylece "bugün" ekranları ve gün sonu raporu ziyaretçiye her zaman dolu görünür.
 */
@Component
@Order(20)   // BaslangicVerisi (1) ve DemoVerisi (10) sonrası
@ConditionalOnProperty(name = "fistik.gece-sifirlama", havingValue = "true")
public class GeceSifirlama implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GeceSifirlama.class);

    /** İşlem tabloları. TRUNCATE satır trigger'larını (append-only defter koruması) tetiklemez. */
    static final String ISLEM_TABLOLARI = "shipments, customers, payment_allocations, payments, advance_offsets, settlement_deductions, "
            + "producer_ledger_entries, stock_movements, lots, settlements, advances, quality_inspections, weigh_tickets, "
            + "daily_prices, producer_bank_accounts, producers, document_sequences";

    private final EntityManager em;
    private final TransactionTemplate tx;
    private final DemoVerisi demo;

    public GeceSifirlama(EntityManager em, TransactionTemplate tx, DemoVerisi demo) {
        this.em = em;
        this.tx = tx;
        this.demo = demo;
    }

    /** Açılışta: bugünün (Türkiye saati) hiç tartımı yoksa demo verisi eskidir → yenile. */
    @Override
    public void run(ApplicationArguments args) {
        try {
            Long bugunku = em.createQuery("select count(t) from WeighTicket t where t.grossWeighedAt >= :b", Long.class)
                    .setParameter("b", Dogrulama.gunAraligi(Dogrulama.bugunTr())[0]).getSingleResult();
            if (bugunku > 0) return;
            log.info("Açılış: bugüne ait tartım yok, demo verisi eski.");
            sifirla();
        } catch (RuntimeException e) {
            // Yenileme başarısız olsa da site açılmalı
            log.error("Açılışta demo verisi yenilenemedi.", e);
        }
    }

    @Scheduled(cron = "${fistik.sifirlama-cron:0 0 3 * * *}", zone = "Europe/Istanbul")
    public void sifirla() {
        log.info("Demo sıfırlaması: işlem verisi siliniyor, örnek veri yeniden yükleniyor…");
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("TRUNCATE " + ISLEM_TABLOLARI + " RESTART IDENTITY CASCADE").executeUpdate();
            // Ziyaretçilerin eklediği kullanıcılar ve hesap talepleri de silinir; demo hesapları kalır.
            em.createQuery("delete from User u where u.username not in :demolar")
                    .setParameter("demolar", Oturum.DEMO_HESAPLARI.stream().map(h -> (String) h[0]).toList()).executeUpdate();
        });
        demo.yukle();
        log.info("Demo sıfırlaması tamamlandı.");
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "fistik.gece-sifirlama", havingValue = "true")
    static class Zamanlayici {
    }
}
