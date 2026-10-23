-- ════════════════════════════════════════════════════════════════════════
-- Siirt Fıstığı — PostgreSQL'e özgü güvenlik tetikleyicileri ve raporlama view'ları
-- Çalıştırma sırası: 1) schema.sql (tablolar)  2) bu dosya
-- (Alembic'te: ilk migration'da op.execute(...) ile.)
-- Uygulama katmanı aynı kuralları zaten uygular; bunlar, uygulamayı atlayan
-- doğrudan SQL erişimlerine karşı son savunma hattıdır.
-- ════════════════════════════════════════════════════════════════════════

-- ─── 1. Onaylı fiyat değiştirilemez ───────────────────────────────────────
CREATE OR REPLACE FUNCTION fn_daily_prices_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'Onaylı/iptal edilmiş fiyat silinemez (id=%)', OLD.id;
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.status = 'REVOKED' THEN
        RAISE EXCEPTION 'İptal edilmiş fiyat değiştirilemez (id=%)', OLD.id;
    END IF;

    IF OLD.status = 'APPROVED' THEN
        IF NEW.price_per_kg   IS DISTINCT FROM OLD.price_per_kg
        OR NEW.quality_grade_id IS DISTINCT FROM OLD.quality_grade_id
        OR NEW.valid_from     IS DISTINCT FROM OLD.valid_from
        OR NEW.currency       IS DISTINCT FROM OLD.currency
        OR NEW.approved_by_id IS DISTINCT FROM OLD.approved_by_id
        OR NEW.approved_at    IS DISTINCT FROM OLD.approved_at THEN
            RAISE EXCEPTION 'Onaylı fiyatın tutar/sınıf/başlangıç alanları değiştirilemez (id=%)', OLD.id;
        END IF;
        -- Bitiş yalnızca ileriye dönük kısaltılabilir: geçmişte tartılmış teslimatların fiyatı değişmez.
        IF NEW.valid_to IS DISTINCT FROM OLD.valid_to
           AND (NEW.valid_to IS NULL OR NEW.valid_to < now() - interval '5 minutes') THEN
            RAISE EXCEPTION 'Onaylı fiyatın bitişi geçmişe çekilemez (id=%)', OLD.id;
        END IF;
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_daily_prices_guard ON daily_prices;
CREATE TRIGGER trg_daily_prices_guard
    BEFORE UPDATE OR DELETE ON daily_prices
    FOR EACH ROW EXECUTE FUNCTION fn_daily_prices_guard();


-- ─── 2. Teslimatın fiyat snapshot'ı ve tartım değerleri dondurulur ──────────
CREATE OR REPLACE FUNCTION fn_weigh_tickets_freeze() RETURNS trigger AS $$
BEGIN
    IF OLD.daily_price_id IS NOT NULL AND (
           NEW.daily_price_id IS DISTINCT FROM OLD.daily_price_id
        OR NEW.unit_price     IS DISTINCT FROM OLD.unit_price
        OR NEW.priced_at      IS DISTINCT FROM OLD.priced_at) THEN
        RAISE EXCEPTION 'Fiyat snapshot''ı dondurulmuş, değiştirilemez (fiş %)', OLD.ticket_no;
    END IF;
    IF OLD.status IN ('GRADED', 'SETTLED') AND (
           NEW.gross_kg   IS DISTINCT FROM OLD.gross_kg
        OR NEW.tare_kg    IS DISTINCT FROM OLD.tare_kg
        OR NEW.producer_id IS DISTINCT FROM OLD.producer_id
        OR NEW.gross_weighed_at IS DISTINCT FROM OLD.gross_weighed_at) THEN
        RAISE EXCEPTION 'Fiyatlanmış teslimatın tartım/üretici bilgisi değiştirilemez (fiş %)', OLD.ticket_no;
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_weigh_tickets_freeze ON weigh_tickets;
CREATE TRIGGER trg_weigh_tickets_freeze
    BEFORE UPDATE ON weigh_tickets
    FOR EACH ROW EXECUTE FUNCTION fn_weigh_tickets_freeze();


-- ─── 3. Hakedişin parasal alanları değişmez ────────────────────────────────
-- İzin verilenler: paid_amount/status güncellemesi ve sonradan verilen avansın mahsubu
-- (advance_offset_total artar, net_payable aynı miktarda azalır; toplamları sabit kalır).
CREATE OR REPLACE FUNCTION fn_settlements_freeze() RETURNS trigger AS $$
BEGIN
    IF NEW.net_kg IS DISTINCT FROM OLD.net_kg
    OR NEW.unit_price IS DISTINCT FROM OLD.unit_price
    OR NEW.gross_amount IS DISTINCT FROM OLD.gross_amount
    OR NEW.deductions_total IS DISTINCT FROM OLD.deductions_total
    OR NEW.advance_offset_total < OLD.advance_offset_total
    OR NEW.advance_offset_total + NEW.net_payable <> OLD.advance_offset_total + OLD.net_payable
    OR NEW.ticket_id IS DISTINCT FROM OLD.ticket_id
    OR NEW.producer_id IS DISTINCT FROM OLD.producer_id THEN
        RAISE EXCEPTION 'Hakediş tutarları değiştirilemez (%); ters kayıt kullanın', OLD.settlement_no;
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_settlements_freeze ON settlements;
CREATE TRIGGER trg_settlements_freeze
    BEFORE UPDATE ON settlements
    FOR EACH ROW EXECUTE FUNCTION fn_settlements_freeze();


-- ─── 4. Cari hesap defteri yalnızca ekleme (append-only) ────────────────────
CREATE OR REPLACE FUNCTION fn_ledger_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'producer_ledger_entries yalnızca eklemeye açıktır; düzeltme için REVERSAL kaydı girin';
END $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_ledger_append_only ON producer_ledger_entries;
CREATE TRIGGER trg_ledger_append_only
    BEFORE UPDATE OR DELETE ON producer_ledger_entries
    FOR EACH ROW EXECUTE FUNCTION fn_ledger_append_only();


-- ─── 5. Silo kalite ayrıştırması ───────────────────────────────────────────
CREATE OR REPLACE FUNCTION fn_stock_grade_check() RETURNS trigger AS $$
DECLARE
    v_lot_grade  integer;
    v_silo_grade integer;
BEGIN
    IF NEW.quantity_kg <= 0 THEN RETURN NEW; END IF;
    SELECT quality_grade_id INTO v_lot_grade FROM lots WHERE id = NEW.lot_id;
    SELECT dedicated_grade_id INTO v_silo_grade FROM silos WHERE id = NEW.silo_id;
    IF v_silo_grade IS NOT NULL AND v_silo_grade <> v_lot_grade THEN
        RAISE EXCEPTION 'Silo % yalnızca kalite sınıfı % kabul eder', NEW.silo_id, v_silo_grade;
    END IF;
    IF EXISTS (
        SELECT 1 FROM stock_movements m JOIN lots l ON l.id = m.lot_id
        WHERE m.silo_id = NEW.silo_id AND l.quality_grade_id <> v_lot_grade
        GROUP BY l.quality_grade_id HAVING SUM(m.quantity_kg) > 0
    ) THEN
        RAISE EXCEPTION 'Silo % içinde farklı kalite sınıfı stok var', NEW.silo_id;
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_stock_grade_check ON stock_movements;
CREATE TRIGGER trg_stock_grade_check
    BEFORE INSERT ON stock_movements
    FOR EACH ROW EXECUTE FUNCTION fn_stock_grade_check();


-- ════════════════════════════════════════════════════════════════════════
-- RAPORLAMA VIEW'LARI  (gün sınırı: Europe/Istanbul)
-- ════════════════════════════════════════════════════════════════════════

-- Üretici bazlı bakiye (negatif = kapanmamış avans borcu)
CREATE OR REPLACE VIEW v_producer_balance AS
SELECT p.id AS producer_id,
       p.full_name,
       COALESCE(SUM(l.amount), 0)                                               AS balance,
       COALESCE(SUM(l.amount) FILTER (WHERE l.entry_type = 'DELIVERY'), 0)      AS total_delivery,
       COALESCE(-SUM(l.amount) FILTER (WHERE l.entry_type = 'DEDUCTION'), 0)    AS total_deductions,
       COALESCE(-SUM(l.amount) FILTER (WHERE l.entry_type = 'ADVANCE'), 0)      AS total_advances,
       COALESCE(-SUM(l.amount) FILTER (WHERE l.entry_type = 'PAYMENT'), 0)      AS total_paid,
       (SELECT COALESCE(SUM(a.amount - a.offset_amount), 0)
          FROM advances a WHERE a.producer_id = p.id)                           AS open_advance,
       (SELECT COALESCE(SUM(s.net_payable - s.paid_amount), 0)
          FROM settlements s WHERE s.producer_id = p.id)                        AS unpaid_settlements
FROM producers p
LEFT JOIN producer_ledger_entries l ON l.producer_id = p.id
GROUP BY p.id, p.full_name;

-- Günlük alım (kalite sınıfı kırılımında)
CREATE OR REPLACE VIEW v_daily_purchases AS
SELECT (t.gross_weighed_at AT TIME ZONE 'Europe/Istanbul')::date AS purchase_date,
       g.code                                    AS grade_code,
       COUNT(*)                                  AS delivery_count,
       COUNT(DISTINCT t.producer_id)             AS producer_count,
       SUM(t.net_kg)                             AS total_net_kg,
       SUM(s.gross_amount)                       AS total_gross_amount,
       ROUND(SUM(t.net_kg * t.unit_price) / NULLIF(SUM(t.net_kg), 0), 2) AS weighted_avg_price,
       ROUND(SUM(q.yield_ratio * t.net_kg) / NULLIF(SUM(t.net_kg), 0), 2) AS weighted_avg_yield
FROM weigh_tickets t
JOIN quality_inspections q ON q.ticket_id = t.id
JOIN quality_grades g      ON g.id = q.quality_grade_id
LEFT JOIN settlements s    ON s.ticket_id = t.id
WHERE t.status IN ('GRADED', 'SETTLED')
GROUP BY 1, 2;

-- Üretici bazlı ortalama randıman / kalite profili (kg ağırlıklı)
CREATE OR REPLACE VIEW v_producer_quality AS
SELECT t.producer_id,
       COUNT(*)                                                          AS delivery_count,
       SUM(t.net_kg)                                                     AS total_net_kg,
       ROUND(SUM(q.yield_ratio    * t.net_kg) / NULLIF(SUM(t.net_kg), 0), 2) AS avg_yield,
       ROUND(SUM(q.split_ratio    * t.net_kg) / NULLIF(SUM(t.net_kg), 0), 2) AS avg_split,
       ROUND(SUM(q.moisture_ratio * t.net_kg) / NULLIF(SUM(t.net_kg), 0), 2) AS avg_moisture,
       ROUND(SUM((q.empty_ratio + q.rotten_ratio) * t.net_kg) / NULLIF(SUM(t.net_kg), 0), 2) AS avg_defect
FROM weigh_tickets t
JOIN quality_inspections q ON q.ticket_id = t.id
WHERE t.status IN ('GRADED', 'SETTLED')
GROUP BY t.producer_id;

-- Silo × kalite stok durumu
CREATE OR REPLACE VIEW v_silo_stock AS
SELECT s.id AS silo_id, s.code AS silo_code, s.capacity_kg,
       g.code AS grade_code,
       SUM(m.quantity_kg)                                       AS stock_kg,
       ROUND(100 * SUM(m.quantity_kg) / s.capacity_kg, 2)       AS fill_pct
FROM stock_movements m
JOIN silos s  ON s.id = m.silo_id
JOIN lots l   ON l.id = m.lot_id
JOIN quality_grades g ON g.id = l.quality_grade_id
GROUP BY s.id, s.code, s.capacity_kg, g.code
HAVING SUM(m.quantity_kg) <> 0;

-- Lot izlenebilirliği: lot hangi siloda ne kadar
CREATE OR REPLACE VIEW v_lot_stock AS
SELECT l.lot_no, t.ticket_no, t.producer_id, l.quality_grade_id, m.silo_id,
       SUM(m.quantity_kg) AS stock_kg
FROM lots l
JOIN weigh_tickets t     ON t.id = l.ticket_id
JOIN stock_movements m   ON m.lot_id = l.id
GROUP BY l.lot_no, t.ticket_no, t.producer_id, l.quality_grade_id, m.silo_id
HAVING SUM(m.quantity_kg) <> 0;

-- Günlük finansal özet (cari defterden)
CREATE OR REPLACE VIEW v_daily_financial_summary AS
SELECT (entry_at AT TIME ZONE 'Europe/Istanbul')::date                     AS day,
       COALESCE( SUM(amount) FILTER (WHERE entry_type = 'DELIVERY'), 0)    AS gross_purchases,
       COALESCE(-SUM(amount) FILTER (WHERE entry_type = 'DEDUCTION'), 0)   AS deductions,
       COALESCE(-SUM(amount) FILTER (WHERE entry_type = 'ADVANCE'), 0)     AS advances_given,
       COALESCE(-SUM(amount) FILTER (WHERE entry_type = 'PAYMENT'), 0)     AS payments_made,
       SUM(amount)                                                         AS net_change_in_liability
FROM producer_ledger_entries
GROUP BY 1;

-- Kesinti türü bazında dönemsel toplam (stopaj beyannamesi vb. için)
CREATE OR REPLACE VIEW v_deduction_summary AS
SELECT date_trunc('month', s.created_at AT TIME ZONE 'Europe/Istanbul')::date AS month,
       dt.code, dt.name,
       SUM(sd.base_amount) AS base_total,
       SUM(sd.amount)      AS deduction_total
FROM settlement_deductions sd
JOIN settlements s      ON s.id = sd.settlement_id
JOIN deduction_types dt ON dt.id = sd.deduction_type_id
GROUP BY 1, 2, 3;
