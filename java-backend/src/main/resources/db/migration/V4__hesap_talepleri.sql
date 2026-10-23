-- Kullanıcı yönetimi:
--   pending_approval      giriş ekranından gelen hesap talebi; yönetici onaylayana kadar giriş yapılamaz
--   must_change_password  yöneticinin verdiği geçici şifre; ilk girişte değiştirilmesi istenir
--   request_note          talep sahibinin yöneticiye notu (ör. "Kurtalan kantarında çalışıyorum")
ALTER TABLE users ADD COLUMN IF NOT EXISTS pending_approval BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE users ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE users ADD COLUMN IF NOT EXISTS request_note VARCHAR(300);
