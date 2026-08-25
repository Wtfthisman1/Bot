-- Ссылки на скачивание переезжают из TSV-файла в базу.
--
-- Файл появился как костыль: без него все выданные ссылки умирали на первом же
-- перезапуске бота. Костыль работал, но состояние оказалось размазано — часть
-- в базе, часть в файле рядом с видео. Для фазы 2, где бот и воркер разъедутся
-- по разным машинам, это тупик: файл на одной машине второй не виден.

create table download_tokens (
    token      text        primary key,

    -- Владелец ссылки: нужен и для аудита, и чтобы дальше показывать
    -- «мои файлы» на сайте
    owner_type text        not null check (owner_type in ('TELEGRAM', 'ACCOUNT')),
    owner_id   text        not null,

    file_path  text        not null,
    expires_at timestamptz not null,
    created_at timestamptz not null default now()
);

-- Чистка просроченных ходит по сроку годности
create index download_tokens_expiry_idx on download_tokens (expires_at);
