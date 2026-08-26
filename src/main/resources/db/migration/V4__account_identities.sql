-- Вход на сайт не только паролем.
--
-- Раньше аккаунт был возможен ровно один: почта плюс пароль. Регистрация
-- разрешена ещё через Google и Telegram, а у них ни того, ни другого нет —
-- пароль там не заводится вовсе, а почты может не быть (Telegram её не отдаёт).
-- Поэтому обе колонки перестают быть обязательными, а внешние личности
-- переезжают в отдельную таблицу: их у одного человека несколько, и все они
-- ведут в один аккаунт.
--
-- Телефона здесь нет сознательно: регистрация по номеру требует платного
-- SMS-провайдера, и решено её не делать вовсе.

alter table accounts alter column password_hash drop not null;
alter table accounts alter column email         drop not null;

create table account_identities (
    id               uuid        primary key,
    account_id       uuid        not null references accounts(id) on delete cascade,

    provider         text        not null check (provider in ('GOOGLE', 'TELEGRAM')),
    -- Идентификатор у провайдера: sub у Google, id пользователя у Telegram.
    -- Для приватного чата telegram id совпадает с chat id — именно поэтому
    -- вход через виджет попадает в тот же аккаунт, что и переписка с ботом
    provider_user_id text        not null,

    created_at       timestamptz not null default now(),

    -- Одна внешняя личность — один аккаунт. Иначе чужой вход через Telegram
    -- мог бы завести второй аккаунт на те же задачи
    constraint account_identities_unique unique (provider, provider_user_id)
);

create index account_identities_account_idx on account_identities (account_id);

-- Коды привязки: человек берёт код в кабинете и присылает его боту, чтобы
-- переписка и аккаунт стали одним владельцем. Живут минуты и гасятся при
-- использовании — иначе подсмотренный код работал бы вечно
create table account_link_codes (
    code       text        primary key,
    account_id uuid        not null references accounts(id) on delete cascade,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    used_at    timestamptz
);
