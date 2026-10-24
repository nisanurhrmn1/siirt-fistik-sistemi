-- Satış ve sevkiyat (depodan çıkış): alıcı firmalar, sevkiyat kantar kayıtları ve stok düşümü.

CREATE TABLE customers (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    tax_number  VARCHAR(11),
    phone       VARCHAR(20),
    is_active   BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
-- Aynı firma iki kez açılmasın (büyük/küçük harf ve baş/son boşluk farkı yok sayılır)
CREATE UNIQUE INDEX uq_customers_name ON customers (lower(btrim(name)));

CREATE TABLE shipments (
    id               SERIAL PRIMARY KEY,
    shipment_no      VARCHAR(20) NOT NULL UNIQUE,
    customer_id      INTEGER NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    silo_id          INTEGER NOT NULL REFERENCES silos (id) ON DELETE RESTRICT,
    quality_grade_id INTEGER NOT NULL REFERENCES quality_grades (id),
    product_type     VARCHAR(8) NOT NULL,
    vehicle_plate    VARCHAR(12) NOT NULL,
    driver_name      VARCHAR(120),
    gross_kg         NUMERIC(12, 2) NOT NULL,
    tare_kg          NUMERIC(12, 2) NOT NULL,
    net_kg           NUMERIC(12, 2) GENERATED ALWAYS AS (gross_kg - tare_kg) STORED,
    gross_source     VARCHAR(8) NOT NULL DEFAULT 'MANUAL',
    tare_source      VARCHAR(8) NOT NULL DEFAULT 'MANUAL',
    unit_price       NUMERIC(12, 2) NOT NULL,
    total_amount     NUMERIC(14, 2) NOT NULL,
    collection       VARCHAR(16) NOT NULL,
    shipped_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    note             TEXT,
    created_by_id    INTEGER NOT NULL REFERENCES users (id),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ck_shipments_weights CHECK (gross_kg > 0 AND tare_kg >= 0 AND tare_kg < gross_kg),
    CONSTRAINT ck_shipments_price CHECK (unit_price > 0),
    CONSTRAINT ck_shipments_total CHECK (total_amount = round((gross_kg - tare_kg) * unit_price, 2)),
    CONSTRAINT ck_shipments_collection CHECK (collection IN ('CASH', 'BANK_TRANSFER', 'ON_ACCOUNT')),
    CONSTRAINT ck_shipments_product_type CHECK (product_type IN ('DRY', 'FRESH')),
    CONSTRAINT ck_shipments_sources CHECK (gross_source IN ('SCALE', 'MANUAL') AND tare_source IN ('SCALE', 'MANUAL'))
);
CREATE INDEX ix_shipments_shipped_at ON shipments (shipped_at);
CREATE INDEX ix_shipments_customer ON shipments (customer_id);

-- Hangi lottan ne kadar çıktığı: üreticiden müşteriye izlenebilirlik
ALTER TABLE stock_movements ADD COLUMN shipment_id INTEGER REFERENCES shipments (id) ON DELETE RESTRICT;
ALTER TABLE stock_movements ADD CONSTRAINT ck_stock_movements_dispatch_shipment
    CHECK (movement_type <> 'DISPATCH' OR shipment_id IS NOT NULL);
CREATE INDEX ix_stock_movements_shipment ON stock_movements (shipment_id);

-- Uygulamadaki kontrolden bağımsız son koruma: bir lot bir siloda eksi stoğa düşemez.
CREATE OR REPLACE FUNCTION fn_stock_non_negative() RETURNS trigger AS $$
DECLARE
    v_kalan numeric;
BEGIN
    IF NEW.quantity_kg < 0 THEN
        SELECT COALESCE(SUM(quantity_kg), 0) INTO v_kalan
        FROM stock_movements WHERE lot_id = NEW.lot_id AND silo_id = NEW.silo_id;
        IF v_kalan < 0 THEN
            RAISE EXCEPTION 'Stok eksiye düşemez: lot % silo % kalan % kg', NEW.lot_id, NEW.silo_id, v_kalan
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_stock_non_negative ON stock_movements;
CREATE TRIGGER trg_stock_non_negative
    AFTER INSERT ON stock_movements
    FOR EACH ROW EXECUTE FUNCTION fn_stock_non_negative();
