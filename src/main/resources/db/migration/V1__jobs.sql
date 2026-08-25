-- Очередь задач переезжает из памяти в базу.
--
-- Раньше очередь жила в LinkedBlockingQueue: перезапуск бота — и всё, что не
-- успело обработаться, исчезало молча. Пользователь ждал расшифровку, которой
-- уже никто не занимался.
--
-- Задача описывается двумя колонками: stage — что с ней делать (скачать или
-- расшифровать), state — что с ней происходит сейчас. Одна колонка на оба
-- смысла мешала бы выбирать задачи: «скачать» и «в работе» — не альтернативы.

create table jobs (
    id              uuid        primary key,

    -- Владелец задачи. Пара «тип + идентификатор» вместо chat_id: дальше
    -- владельцем станет аккаунт сайта, у которого чата в Telegram нет вовсе.
    owner_type      text        not null check (owner_type in ('TELEGRAM', 'ACCOUNT')),
    owner_id        text        not null,

    stage           text        not null check (stage in ('DOWNLOAD', 'TRANSCRIBE')),
    state           text        not null check (state in ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),

    url             text,
    media_kind      text        not null check (media_kind in ('AUDIO', 'VIDEO')),
    file_path       text,
    transcript_path text,

    -- Задача чистого скачивания: результат уходит ссылкой, а не расшифровкой
    download_id     text,

    error           text,
    -- Сколько раз задачу забирали в работу. Больше одного — значит, воркер
    -- умер на середине и её подобрали заново
    attempts        integer     not null default 0,

    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    started_at      timestamptz,
    finished_at     timestamptz,

    -- То, что раньше проверял конструктор ProcessingJob. В базе это уместнее:
    -- запись без url или без файла бессмысленна независимо от того, кто её создал
    constraint jobs_download_needs_url    check (stage <> 'DOWNLOAD'   or url is not null),
    constraint jobs_transcribe_needs_file check (stage <> 'TRANSCRIBE' or file_path is not null)
);

-- Воркеры берут самую старую задачу в очереди: индекс ровно под этот запрос.
-- Частичный — потому что завершённых записей со временем станет большинство,
-- а искать среди них не нужно никогда.
create index jobs_queue_idx on jobs (created_at) where state = 'QUEUED';

-- «Что у меня сейчас в работе» — запрос статуса и, дальше, история на сайте
create index jobs_owner_idx on jobs (owner_type, owner_id, created_at desc);
