-- Kimlik doğrulama: kullanıcı şifreleri BCrypt özeti olarak saklanır (düz şifre asla tutulmaz).
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_hash VARCHAR(100);
