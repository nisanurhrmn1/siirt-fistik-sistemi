-- Müstahsil bilgilendirme SMS'leri: her gönderim denemesi kayıt altında (gönderildi / simülasyon / hata).
CREATE TABLE sms_messages (
    id           BIGSERIAL PRIMARY KEY,
    producer_id  INTEGER NOT NULL REFERENCES producers (id) ON DELETE CASCADE,
    phone        VARCHAR(20) NOT NULL,
    message      TEXT NOT NULL,
    trigger_type VARCHAR(16) NOT NULL,
    related_id   INTEGER NOT NULL,
    provider     VARCHAR(20) NOT NULL,
    status       VARCHAR(12) NOT NULL,
    provider_ref VARCHAR(64),
    error        TEXT,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ck_sms_messages_trigger CHECK (trigger_type IN ('DELIVERY', 'ADVANCE')),
    CONSTRAINT ck_sms_messages_status CHECK (status IN ('SENT', 'SIMULATED', 'FAILED'))
);
CREATE INDEX ix_sms_messages_related ON sms_messages (trigger_type, related_id);
CREATE INDEX ix_sms_messages_producer ON sms_messages (producer_id);
