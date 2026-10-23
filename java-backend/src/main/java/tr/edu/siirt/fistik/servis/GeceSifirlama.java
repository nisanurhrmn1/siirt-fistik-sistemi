package tr.edu.siirt.fistik.servis;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Herkese açık demo sunucusu için: her gece işlem verisini silip örnek veriyi yeniden yükler.
 * Ziyaretçiler gün içinde her şeyi silse bile ertesi gün sistem temiz başlar.
 * Yalnızca {@code fistik.gece-sifirlama=true} ise etkindir; tanım verileri (kullanıcılar, kalite sınıfları,
 * kesinti türleri, silolar) korunur.
 */
@Component
@ConditionalOnProperty(name = "fistik.gece-sifirlama", havingValue = "true")
public class GeceSifirlama {

    private static final Logger log = LoggerFactory.getLogger(GeceSifirlama.class);

    /** İşlem tabloları. TRUNCATE satır trigger'larını (append-only defter koruması) tetiklemez. */
    static final String ISLEM_TABLOLARI = "payment_allocations, payments, advance_offsets, settlement_deductions, "
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

    @Scheduled(cron = "${fistik.sifirlama-cron:0 0 3 * * *}", zone = "Europe/Istanbul")
    public void sifirla() {
        log.info("Gece sıfırlaması: demo verisi yeniden yükleniyor…");
        tx.executeWithoutResult(s -> em.createNativeQuery("TRUNCATE " + ISLEM_TABLOLARI + " RESTART IDENTITY CASCADE").executeUpdate());
        demo.yukle();
        log.info("Gece sıfırlaması tamamlandı.");
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "fistik.gece-sifirlama", havingValue = "true")
    static class Zamanlayici {
    }
}
