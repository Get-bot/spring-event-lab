CREATE TABLE coupon_issue
(
    id         UUID PRIMARY KEY,
    event_id   UUID      NOT NULL REFERENCES event (id),
    user_id    UUID      NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_coupon_issue UNIQUE (event_id, user_id)
);

CREATE INDEX idx_coupon_issue_event_id ON coupon_issue (event_id);
CREATE INDEX idx_coupon_issue_user_id ON coupon_issue (user_id);

COMMENT ON TABLE coupon_issue IS '쿠폰 발급 이력';
COMMENT ON COLUMN coupon_issue.event_id IS '이벤트 ID';
COMMENT ON COLUMN coupon_issue.user_id IS '사용자 ID';