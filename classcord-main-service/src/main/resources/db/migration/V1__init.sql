--
-- PostgreSQL database dump
--

-- Dumped from database version 16.14 (Debian 16.14-1.pgdg12+1)
-- Dumped by pg_dump version 16.14 (Debian 16.14-1.pgdg12+1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: vector; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;


--
-- Name: EXTENSION vector; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION vector IS 'vector data type and ivfflat and hnsw access methods';


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: ai_messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ai_messages (
                                    id uuid NOT NULL,
                                    session_id uuid NOT NULL,
                                    role character varying(10) NOT NULL,
                                    content text NOT NULL,
                                    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ai_sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ai_sessions (
                                    id uuid NOT NULL,
                                    user_id uuid NOT NULL,
                                    material_id uuid NOT NULL,
                                    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: channels; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.channels (
                                 id uuid NOT NULL,
                                 server_id uuid NOT NULL,
                                 name character varying(100) NOT NULL,
                                 type character varying(20) DEFAULT 'general'::character varying NOT NULL,
                                 "position" integer DEFAULT 0 NOT NULL
);


--
-- Name: material_chunks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_chunks (
                                        id uuid NOT NULL,
                                        content text NOT NULL,
                                        metadata jsonb,
                                        embedding public.vector(768)
);


--
-- Name: material_questions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_questions (
                                           id uuid NOT NULL,
                                           material_id uuid NOT NULL,
                                           type character varying(50) DEFAULT 'SINGLE_CHOICE'::character varying NOT NULL,
                                           question text NOT NULL,
                                           options jsonb NOT NULL,
                                           correct_answer jsonb NOT NULL,
                                           explanation jsonb,
                                           created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                           is_deleted boolean DEFAULT false NOT NULL
);


--
-- Name: materials; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.materials (
                                  id uuid NOT NULL,
                                  message_id uuid NOT NULL,
                                  file_url character varying(500) NOT NULL,
                                  file_type character varying(20) NOT NULL,
                                  original_name character varying(255) NOT NULL,
                                  file_size bigint DEFAULT 0 NOT NULL,
                                  status character varying(20) DEFAULT 'DISABLED'::character varying NOT NULL,
                                  error_message text
);


--
-- Name: messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.messages (
                                 id uuid NOT NULL,
                                 channel_id uuid NOT NULL,
                                 user_id uuid NOT NULL,
                                 content text,
                                 created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                 deleted boolean DEFAULT false NOT NULL
);


--
-- Name: quiz_generation_jobs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quiz_generation_jobs (
                                             id uuid NOT NULL,
                                             material_id uuid NOT NULL,
                                             status character varying(20) NOT NULL,
                                             error_message text,
                                             created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                             updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: quiz_questions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quiz_questions (
                                       id uuid NOT NULL,
                                       quiz_id uuid NOT NULL,
                                       question_id uuid NOT NULL,
                                       user_answer jsonb,
                                       is_correct boolean
);


--
-- Name: quizzes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quizzes (
                                id uuid NOT NULL,
                                user_id uuid NOT NULL,
                                material_id uuid NOT NULL,
                                score integer,
                                created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: server_members; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.server_members (
                                       id uuid NOT NULL,
                                       server_id uuid NOT NULL,
                                       user_id uuid NOT NULL,
                                       role character varying(20) DEFAULT 'student'::character varying NOT NULL,
                                       joined_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: servers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.servers (
                                id uuid NOT NULL,
                                name character varying(100) NOT NULL,
                                owner_id uuid NOT NULL,
                                created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                used_storage bigint DEFAULT 0 NOT NULL
);


--
-- Name: user_identities; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_identities (
                                        id uuid NOT NULL,
                                        user_id uuid NOT NULL,
                                        provider character varying(20) NOT NULL,
                                        provider_uid character varying(255),
                                        password_hash character varying(255),
                                        created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
                              id uuid NOT NULL,
                              username character varying(50) NOT NULL,
                              email character varying(255) NOT NULL,
                              avatar_url character varying(500),
                              created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ai_messages ai_messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_messages
    ADD CONSTRAINT ai_messages_pkey PRIMARY KEY (id);


--
-- Name: ai_sessions ai_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_sessions
    ADD CONSTRAINT ai_sessions_pkey PRIMARY KEY (id);


--
-- Name: channels channels_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.channels
    ADD CONSTRAINT channels_pkey PRIMARY KEY (id);


--
-- Name: material_chunks material_chunks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_chunks
    ADD CONSTRAINT material_chunks_pkey PRIMARY KEY (id);


--
-- Name: material_questions material_questions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_questions
    ADD CONSTRAINT material_questions_pkey PRIMARY KEY (id);


--
-- Name: materials materials_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.materials
    ADD CONSTRAINT materials_pkey PRIMARY KEY (id);


--
-- Name: messages messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_pkey PRIMARY KEY (id);


--
-- Name: quiz_generation_jobs quiz_generation_jobs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quiz_generation_jobs
    ADD CONSTRAINT quiz_generation_jobs_pkey PRIMARY KEY (id);


--
-- Name: quiz_questions quiz_questions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quiz_questions
    ADD CONSTRAINT quiz_questions_pkey PRIMARY KEY (id);


--
-- Name: quizzes quizzes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quizzes
    ADD CONSTRAINT quizzes_pkey PRIMARY KEY (id);


--
-- Name: server_members server_members_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.server_members
    ADD CONSTRAINT server_members_pkey PRIMARY KEY (id);


--
-- Name: servers servers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.servers
    ADD CONSTRAINT servers_pkey PRIMARY KEY (id);


--
-- Name: materials uk_materials_file_url; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.materials
    ADD CONSTRAINT uk_materials_file_url UNIQUE (file_url);


--
-- Name: user_identities uq_provider_uid; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_identities
    ADD CONSTRAINT uq_provider_uid UNIQUE (provider, provider_uid);


--
-- Name: server_members uq_server_user; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.server_members
    ADD CONSTRAINT uq_server_user UNIQUE (server_id, user_id);


--
-- Name: users uq_users_email; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT uq_users_email UNIQUE (email);


--
-- Name: user_identities user_identities_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_identities
    ADD CONSTRAINT user_identities_pkey PRIMARY KEY (id);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: idx_ai_messages_session_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ai_messages_session_created ON public.ai_messages USING btree (session_id, created_at DESC);


--
-- Name: idx_ai_sessions_user_material; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ai_sessions_user_material ON public.ai_sessions USING btree (user_id, material_id, created_at DESC);


--
-- Name: idx_material_chunks_embedding; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_chunks_embedding ON public.material_chunks USING hnsw (embedding public.vector_cosine_ops);


--
-- Name: idx_material_chunks_metadata; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_chunks_metadata ON public.material_chunks USING gin (metadata);


--
-- Name: idx_messages_channel_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_messages_channel_created_at ON public.messages USING btree (channel_id, created_at DESC);


--
-- Name: idx_quizzes_user_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quizzes_user_created ON public.quizzes USING btree (user_id, created_at DESC);


--
-- Name: ai_messages fk_ai_messages_session; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_messages
    ADD CONSTRAINT fk_ai_messages_session FOREIGN KEY (session_id) REFERENCES public.ai_sessions(id) ON DELETE CASCADE;


--
-- Name: channels fk_channels_server; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.channels
    ADD CONSTRAINT fk_channels_server FOREIGN KEY (server_id) REFERENCES public.servers(id) ON DELETE CASCADE;


--
-- Name: user_identities fk_identities_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_identities
    ADD CONSTRAINT fk_identities_user FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: material_questions fk_material_questions_material; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_questions
    ADD CONSTRAINT fk_material_questions_material FOREIGN KEY (material_id) REFERENCES public.materials(id) ON DELETE CASCADE;


--
-- Name: materials fk_materials_message; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.materials
    ADD CONSTRAINT fk_materials_message FOREIGN KEY (message_id) REFERENCES public.messages(id) ON DELETE CASCADE;


--
-- Name: server_members fk_members_server; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.server_members
    ADD CONSTRAINT fk_members_server FOREIGN KEY (server_id) REFERENCES public.servers(id) ON DELETE CASCADE;


--
-- Name: server_members fk_members_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.server_members
    ADD CONSTRAINT fk_members_user FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: messages fk_messages_channel; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT fk_messages_channel FOREIGN KEY (channel_id) REFERENCES public.channels(id) ON DELETE CASCADE;


--
-- Name: messages fk_messages_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT fk_messages_user FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: quiz_questions fk_quiz_questions_question; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quiz_questions
    ADD CONSTRAINT fk_quiz_questions_question FOREIGN KEY (question_id) REFERENCES public.material_questions(id);


--
-- Name: quiz_questions fk_quiz_questions_quiz; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quiz_questions
    ADD CONSTRAINT fk_quiz_questions_quiz FOREIGN KEY (quiz_id) REFERENCES public.quizzes(id) ON DELETE CASCADE;


--
-- Name: quizzes fk_quizzes_material; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quizzes
    ADD CONSTRAINT fk_quizzes_material FOREIGN KEY (material_id) REFERENCES public.materials(id) ON DELETE CASCADE;


--
-- Name: quizzes fk_quizzes_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quizzes
    ADD CONSTRAINT fk_quizzes_user FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: servers fk_servers_owner; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.servers
    ADD CONSTRAINT fk_servers_owner FOREIGN KEY (owner_id) REFERENCES public.users(id);


--
-- Name: ai_sessions fk_sessions_material; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_sessions
    ADD CONSTRAINT fk_sessions_material FOREIGN KEY (material_id) REFERENCES public.materials(id) ON DELETE CASCADE;


--
-- Name: ai_sessions fk_sessions_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_sessions
    ADD CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- PostgreSQL database dump complete
--

-- 補齊外鍵索引，加速關聯查詢與刪除效能
CREATE INDEX idx_channels_server_id ON public.channels USING btree (server_id);
CREATE INDEX idx_materials_message_id ON public.materials USING btree (message_id);
CREATE INDEX idx_material_questions_material_id ON public.material_questions USING btree (material_id);
CREATE INDEX idx_quiz_questions_quiz_id ON public.quiz_questions USING btree (quiz_id);
CREATE INDEX idx_server_members_user_id ON public.server_members USING btree (user_id);
CREATE INDEX idx_messages_user_id ON public.messages USING btree (user_id);
CREATE INDEX idx_user_identities_user_id ON public.user_identities USING btree (user_id);
CREATE INDEX idx_servers_owner_id ON public.servers USING btree (owner_id);
CREATE INDEX idx_quizzes_material_id ON public.quizzes USING btree (material_id);
CREATE INDEX idx_ai_sessions_material_id ON public.ai_sessions USING btree (material_id);


