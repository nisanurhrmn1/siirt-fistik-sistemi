-- Alıcı (müşteri) tahsilatları: vadeli sevkiyatların sonradan tahsil edilmesi.
-- Açık hesap = vadeli sevkiyatlar − tahsilatlar (peşin tahsil edilen sevkiyatlar zaten kapalıdır).
CREATE TABLE customer_payments (
    id            SERIAL PRIMARY KEY,
    receipt_no    VARCHAR(20) NOT NULL UNIQUE,
    customer_id   INTEGER NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    amount        NUMERIC(14, 2) NOT NULL,
    method        VARCHAR(16) NOT NULL,
    received_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    reference_no  VARCHAR(60),
    note          TEXT,
    created_by_id INTEGER NOT NULL REFERENCES users (id),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ck_customer_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_customer_payments_method CHECK (method IN ('CASH', 'BANK_TRANSFER', 'CHEQUE'))
);
CREATE INDEX ix_customer_payments_customer ON customer_payments (customer_id, received_at);
CREATE INDEX ix_customer_payments_received ON customer_payments (received_at);

-- Tahsilat kaydı değiştirilemez / silinemez (yanlışsa ters kayıt girilir) — cari defter ilkesi
CREATE OR REPLACE FUNCTION fn_customer_payments_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Tahsilat kaydı değiştirilemez veya silinemez (makbuz %)', OLD.receipt_no;
END $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_customer_payments_immutable ON customer_payments;
CREATE TRIGGER trg_customer_payments_immutable
    BEFORE UPDATE OR DELETE ON customer_payments
    FOR EACH ROW EXECUTE FUNCTION fn_customer_payments_immutable();
