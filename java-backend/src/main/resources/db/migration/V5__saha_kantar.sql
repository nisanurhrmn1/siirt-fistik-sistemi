-- Saha kullanımı: ürün türü (yaş / kuru-kavlak), çuval darası ve ağırlık kaynağı.

-- Kalite sınıfları ürün türüne göre ayrılır; fiyat, silo ve stok sınıfa bağlı olduğu için onlar da ayrılmış olur.
-- Yaş fıstık sınıfları başlangıç verisinde (BaslangicVerisi) eklenir.
ALTER TABLE quality_grades ADD COLUMN IF NOT EXISTS product_type VARCHAR(8) NOT NULL DEFAULT 'DRY';
ALTER TABLE quality_grades ADD CONSTRAINT ck_quality_grades_product_type CHECK (product_type IN ('DRY', 'FRESH'));

-- tare_kg toplam darayı tutmaya devam eder (araç darası + çuval sayısı × çuval darası);
-- net_kg = gross_kg - tare_kg hesaplanan sütunu ve ona bağlı view'lar değişmez.
ALTER TABLE weigh_tickets ADD COLUMN IF NOT EXISTS product_type VARCHAR(8) NOT NULL DEFAULT 'DRY';
ALTER TABLE weigh_tickets ADD COLUMN IF NOT EXISTS sack_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE weigh_tickets ADD COLUMN IF NOT EXISTS sack_tare_kg NUMERIC(6, 2) NOT NULL DEFAULT 0;
ALTER TABLE weigh_tickets ADD COLUMN IF NOT EXISTS gross_source VARCHAR(8) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE weigh_tickets ADD COLUMN IF NOT EXISTS tare_source VARCHAR(8) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE weigh_tickets ADD CONSTRAINT ck_weigh_tickets_product_type CHECK (product_type IN ('DRY', 'FRESH'));
ALTER TABLE weigh_tickets ADD CONSTRAINT ck_weigh_tickets_sacks CHECK (sack_count >= 0 AND sack_tare_kg >= 0);
ALTER TABLE weigh_tickets ADD CONSTRAINT ck_weigh_tickets_sources
    CHECK (gross_source IN ('SCALE', 'MANUAL') AND tare_source IN ('SCALE', 'MANUAL'));
