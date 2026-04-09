CREATE TABLE event
(
    id              UUID PRIMARY KEY,
    title           VARCHAR(200) NOT NULL,
    total_quantity  INT          NOT NULL,
    issued_quantity INT          NOT NULL DEFAULT 0,
    event_status    VARCHAR(20)  NOT NULL DEFAULT 'READY',
    started_at      TIMESTAMP    NOT NULL,
    ended_at        TIMESTAMP    NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE event IS '선착순 이벤트';
COMMENT ON COLUMN event.title IS '이벤트 제목';
COMMENT ON COLUMN event.total_quantity IS '총 수량';
COMMENT ON COLUMN event.issued_quantity IS '발급된 수량';
COMMENT ON COLUMN event.event_status IS '이벤트 상태 (READY/OPEN/CLOSED)';
COMMENT ON COLUMN event.started_at IS '시작 시각';
COMMENT ON COLUMN event.ended_at IS '종료 시각';