-- Parçalı emanet bozdurma ve kör/boş tane iskontosu.

-- ─── Parçalı emanet: emanet fişi kalır, her bozdurma ayrı bir satış fişi (EB-…) olur ───
-- Emanet fişinde satılan toplam kg; kalan emanet = net − satılan.
ALTER TABLE weigh_tickets ADD COLUMN deposit_sold_kg NUMERIC(12, 2) NOT NULL DEFAULT 0;
ALTER TABLE weigh_tickets ADD CONSTRAINT ck_weigh_tickets_deposit_sold
    CHECK (deposit_sold_kg >= 0 AND (deposit_sold_kg = 0 OR (sale_mode = 'DEPOSIT' AND tare_kg IS NOT NULL
                                                              AND deposit_sold_kg <= gross_kg - tare_kg)));
-- Bozdurma fişinin kaynağı olan emanet fişi
ALTER TABLE weigh_tickets ADD COLUMN parent_ticket_id INTEGER REFERENCES weigh_tickets (id) ON DELETE RESTRICT;
ALTER TABLE weigh_tickets ADD CONSTRAINT ck_weigh_tickets_parent
    CHECK (parent_ticket_id IS NULL OR (sale_mode = 'DEPOSIT' AND deposit_converted_at IS NOT NULL));
CREATE INDEX ix_weigh_tickets_parent ON weigh_tickets (parent_ticket_id);

-- ─── Kör/boş tane iskontosu: eşik üzeri her puan için ₺/kg ───
ALTER TABLE split_premium_rules ADD COLUMN empty_threshold NUMERIC(5, 2) NOT NULL DEFAULT 3;
ALTER TABLE split_premium_rules ADD COLUMN empty_penalty_per_point NUMERIC(8, 2) NOT NULL DEFAULT 5;
ALTER TABLE split_premium_rules ADD COLUMN empty_active BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE split_premium_rules ADD CONSTRAINT ck_split_premium_rules_empty
    CHECK (empty_threshold BETWEEN 0 AND 100 AND empty_penalty_per_point >= 0);

ALTER TABLE weigh_tickets ADD COLUMN empty_penalty NUMERIC(12, 2) NOT NULL DEFAULT 0;
ALTER TABLE weigh_tickets ADD CONSTRAINT ck_weigh_tickets_empty_penalty CHECK (empty_penalty >= 0);
ALTER TABLE weigh_tickets DROP CONSTRAINT ck_weigh_tickets_premium;
ALTER TABLE weigh_tickets ADD CONSTRAINT ck_weigh_tickets_premium
    CHECK (base_unit_price IS NULL OR unit_price = base_unit_price + split_premium - empty_penalty);

-- Boş tane iskontosu da fiyat snapshot'ının parçası; emanet fişinde yalnızca satılan kg artabilir
CREATE OR REPLACE FUNCTION fn_weigh_tickets_freeze() RETURNS trigger AS $$
BEGIN
    IF OLD.daily_price_id IS NOT NULL AND (
           NEW.daily_price_id  IS DISTINCT FROM OLD.daily_price_id
        OR NEW.unit_price      IS DISTINCT FROM OLD.unit_price
        OR NEW.base_unit_price IS DISTINCT FROM OLD.base_unit_price
        OR NEW.split_premium   IS DISTINCT FROM OLD.split_premium
        OR NEW.empty_penalty   IS DISTINCT FROM OLD.empty_penalty
        OR NEW.priced_at       IS DISTINCT FROM OLD.priced_at) THEN
        RAISE EXCEPTION 'Fiyat snapshot''ı dondurulmuş, değiştirilemez (fiş %)', OLD.ticket_no;
    END IF;
    IF OLD.status IN ('DEPOSITED', 'GRADED', 'SETTLED') AND (
           NEW.gross_kg   IS DISTINCT FROM OLD.gross_kg
        OR NEW.tare_kg    IS DISTINCT FROM OLD.tare_kg
        OR NEW.producer_id IS DISTINCT FROM OLD.producer_id
        OR NEW.gross_weighed_at IS DISTINCT FROM OLD.gross_weighed_at) THEN
        RAISE EXCEPTION 'Sınıflanmış teslimatın tartım/üretici bilgisi değiştirilemez (fiş %)', OLD.ticket_no;
    END IF;
    IF NEW.deposit_sold_kg < OLD.deposit_sold_kg THEN
        RAISE EXCEPTION 'Emanetten satılan miktar azaltılamaz (fiş %)', OLD.ticket_no;
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
