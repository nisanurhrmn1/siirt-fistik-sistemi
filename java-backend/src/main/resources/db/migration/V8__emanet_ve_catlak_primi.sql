-- Emanet fıstık ve numuneye dayalı çatlak primi.

-- ─── Emanet: fıstık siloya girer, fiyat/hakediş satışa çevrilene kadar oluşmaz ───
ALTER TABLE weigh_tickets ADD COLUMN sale_mode VARCHAR(8) NOT NULL DEFAULT 'SALE';
ALTER TABLE weigh_tickets ADD CONSTRAINT ck_weigh_tickets_sale_mode CHECK (sale_mode IN ('SALE', 'DEPOSIT'));
ALTER TABLE weigh_tickets ADD COLUMN deposit_converted_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE weigh_tickets DROP CONSTRAINT ck_weigh_tickets_ticket_status;
ALTER TABLE weigh_tickets ADD CONSTRAINT ck_weigh_tickets_ticket_status
    CHECK (status IN ('GROSS_WEIGHED', 'NET_WEIGHED', 'DEPOSITED', 'GRADED', 'SETTLED', 'CANCELLED'));
-- Emanette bekleyen fişin fiyatı yoktur; yalnızca emanet fişi satışa çevrilerek fiyatlanabilir
ALTER TABLE weigh_tickets ADD CONSTRAINT ck_weigh_tickets_deposit_unpriced
    CHECK (status <> 'DEPOSITED' OR (sale_mode = 'DEPOSIT' AND daily_price_id IS NULL));
ALTER TABLE weigh_tickets ADD CONSTRAINT ck_weigh_tickets_converted
    CHECK (deposit_converted_at IS NULL OR (sale_mode = 'DEPOSIT' AND daily_price_id IS NOT NULL));

-- ─── Çatlak primi: birim fiyat = baz fiyat + prim (eksi ise iskonto) ───
ALTER TABLE weigh_tickets ADD COLUMN base_unit_price NUMERIC(12, 2);
ALTER TABLE weigh_tickets ADD COLUMN split_premium NUMERIC(12, 2) NOT NULL DEFAULT 0;
ALTER TABLE weigh_tickets ADD CONSTRAINT ck_weigh_tickets_premium
    CHECK (base_unit_price IS NULL OR unit_price = base_unit_price + split_premium);

-- Emanette bekleyen fişin tartım/üretici bilgisi de değiştirilemez; prim ve baz fiyat da fiyat snapshot'ının parçasıdır
CREATE OR REPLACE FUNCTION fn_weigh_tickets_freeze() RETURNS trigger AS $$
BEGIN
    IF OLD.daily_price_id IS NOT NULL AND (
           NEW.daily_price_id  IS DISTINCT FROM OLD.daily_price_id
        OR NEW.unit_price      IS DISTINCT FROM OLD.unit_price
        OR NEW.base_unit_price IS DISTINCT FROM OLD.base_unit_price
        OR NEW.split_premium   IS DISTINCT FROM OLD.split_premium
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
    RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TABLE split_premium_rules (
    product_type      VARCHAR(8) PRIMARY KEY,
    base_split_ratio  NUMERIC(5, 2) NOT NULL,
    premium_per_point NUMERIC(8, 2) NOT NULL,
    max_adjustment    NUMERIC(8, 2),
    is_active         BOOLEAN NOT NULL DEFAULT false,
    updated_by_id     INTEGER REFERENCES users (id),
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ck_split_premium_rules_type CHECK (product_type IN ('DRY', 'FRESH')),
    CONSTRAINT ck_split_premium_rules_values CHECK (base_split_ratio BETWEEN 0 AND 100 AND premium_per_point >= 0
        AND (max_adjustment IS NULL OR max_adjustment >= 0))
);
-- ÖRNEK kurallar, kapalı başlar: fiyat yöneticisi açar
INSERT INTO split_premium_rules (product_type, base_split_ratio, premium_per_point, max_adjustment)
VALUES ('DRY', 85, 2, 20), ('FRESH', 80, 1, 10);

-- ─── Numune analizinde girilen gramlar (oranlar bunlardan hesaplanır) ───
ALTER TABLE quality_inspections ADD COLUMN split_weight_g NUMERIC(8, 2);
ALTER TABLE quality_inspections ADD COLUMN empty_weight_g NUMERIC(8, 2);
ALTER TABLE quality_inspections ADD CONSTRAINT ck_quality_inspections_sample_grams
    CHECK ((split_weight_g IS NULL OR split_weight_g BETWEEN 0 AND sample_weight_g)
       AND (empty_weight_g IS NULL OR empty_weight_g BETWEEN 0 AND sample_weight_g));

-- ─── Emanetin satışa çevrilmesi de SMS ile bildirilir ───
ALTER TABLE sms_messages DROP CONSTRAINT ck_sms_messages_trigger;
ALTER TABLE sms_messages ADD CONSTRAINT ck_sms_messages_trigger CHECK (trigger_type IN ('DELIVERY', 'ADVANCE', 'CONVERSION'));
