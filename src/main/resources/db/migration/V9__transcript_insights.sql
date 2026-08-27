-- Обработка расшифровки языковой моделью: выжимка и разбор по теме.
--
-- Отдельная таблица, а не колонки в jobs: обработок у одной расшифровки много.
-- Человек просит выжимку покороче, потом подлиннее, потом спрашивает про
-- Альцгеймера — и всё это разные строки с разными параметрами, которые он
-- сравнивает между собой.
--
-- Состояние здесь такое же, как у задач, и по той же причине: модель занимает
-- видеокарту, ждать её в http-запросе нельзя, а очередь в памяти не пережила бы
-- перезапуск. Строка ставится в QUEUED, воркер берёт её тем же
-- FOR UPDATE SKIP LOCKED, что и расшифровки.

create table transcript_insights (
    id          bigserial primary key,

    job_id      uuid not null references jobs (id) on delete cascade,

    -- SUMMARY — выжимка, TOPIC — «найди всё про...»
    kind        text not null check (kind in ('SUMMARY', 'TOPIC')),

    -- Насколько ужать, в процентах от исходного текста. Только у SUMMARY
    ratio       integer,

    -- О чём спрашивали. Только у TOPIC
    topic       text,

    state       text not null check (state in ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),

    -- Ответ модели как есть. Разметку из него страница собирает сама: таймкоды
    -- сверяются с сегментами, и выдуманные до ссылок не доходят
    text        text,

    -- Какой моделью посчитано: ответы разных моделей несравнимы, а модель на
    -- домашней машине меняется чаще, чем что-либо ещё
    model       text,

    error       text,

    created_at  timestamptz not null default now(),
    finished_at timestamptz
);

-- Страница расшифровки показывает свои обработки, новые сверху
create index transcript_insights_job_idx on transcript_insights (job_id, created_at desc);

-- Очередь: воркер берёт самую старую ожидающую
create index transcript_insights_queue_idx on transcript_insights (created_at)
    where state = 'QUEUED';
