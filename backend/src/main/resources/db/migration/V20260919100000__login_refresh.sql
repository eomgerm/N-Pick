CREATE TABLE npick.login_refresh (
    token_hash CHAR(64) PRIMARY KEY,
    login_id VARCHAR(100) NOT NULL REFERENCES npick.member(login_id) ON DELETE CASCADE,
    access_session_id CHAR(36) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX login_refresh_expiry_idx ON npick.login_refresh (expires_at);
