-- 這份 migration 只給測試用（放在 src/test/resources，只有 application-test.yml 會啟用 Flyway
-- 去執行它），唯一目的是讓 ai-service 的整合測試能在完全乾淨的資料庫上獨立跑起來，不必依賴
-- main-service 先跑過。正式環境的 Flyway 是關閉的（見 application.yml），schema 完全交給
-- main-service 的 V1__init.sql 管理，這份檔案永遠不會在正式環境被執行。
--
-- 注意：ai_sessions 的 material_id / user_id 故意不加外鍵約束到 main-service 的
-- materials / users 表——那兩張表不屬於這份 migration 管，獨立跑的時候它們根本不存在。
-- 參照完整性交由應用層（Feign 呼叫 main-service 驗證）負責，只在「單獨測試 ai-service」
-- 這個情境下才會少了資料庫層級的外鍵保護，不影響正式環境（正式環境的表本來就有完整外鍵）。
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

CREATE TABLE IF NOT EXISTS public.ai_sessions (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    material_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ai_sessions_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS public.ai_messages (
    id uuid NOT NULL,
    session_id uuid NOT NULL,
    role character varying(10) NOT NULL,
    content text NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ai_messages_pkey PRIMARY KEY (id),
    CONSTRAINT fk_ai_messages_session FOREIGN KEY (session_id)
        REFERENCES public.ai_sessions(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS public.material_chunks (
    id uuid NOT NULL,
    content text NOT NULL,
    metadata jsonb,
    embedding public.vector(768),
    CONSTRAINT material_chunks_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_ai_sessions_user_material
    ON public.ai_sessions USING btree (user_id, material_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ai_sessions_material_id
    ON public.ai_sessions USING btree (material_id);
CREATE INDEX IF NOT EXISTS idx_ai_messages_session_created
    ON public.ai_messages USING btree (session_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_material_chunks_embedding
    ON public.material_chunks USING hnsw (embedding public.vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_material_chunks_metadata
    ON public.material_chunks USING gin (metadata);
