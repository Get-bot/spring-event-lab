-- Instant(Java) → TIMESTAMPTZ(PostgreSQL) 타입 정합성 보정
-- Hibernate 6+는 java.time.Instant를 TIMESTAMP WITH TIME ZONE으로 매핑하므로
-- 기존 TIMESTAMP 컬럼을 TIMESTAMPTZ로 변경한다.
ALTER TABLE event
    ALTER COLUMN started_at  TYPE TIMESTAMPTZ USING started_at  AT TIME ZONE 'UTC',
    ALTER COLUMN ended_at    TYPE TIMESTAMPTZ USING ended_at    AT TIME ZONE 'UTC',
    ALTER COLUMN created_at  TYPE TIMESTAMPTZ USING created_at  AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at  TYPE TIMESTAMPTZ USING updated_at  AT TIME ZONE 'UTC';

ALTER TABLE coupon_issue
    ALTER COLUMN created_at  TYPE TIMESTAMPTZ USING created_at  AT TIME ZONE 'UTC';
