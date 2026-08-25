-- Аккаунты сайта.
--
-- Пока единственный вход в бота — Telegram, и владельцем задачи выступает чат.
-- Аккаунт нужен там, где чата нет: на сайте. Задачи уже умеют принадлежать
-- владельцу любого типа (owner_type), поэтому дальше остаётся только заводить
-- записи в этой таблице и подставлять их идентификатор.

create table accounts (
    id            uuid        primary key,

    -- Хранится в нижнем регистре: почта регистронезависима, а два аккаунта на
    -- «Ivan@» и «ivan@» — это один человек, который потом не может войти
    email         text        not null unique,
    password_hash text        not null,
    display_name  text,

    created_at    timestamptz not null default now(),
    -- Отметка последнего входа: пригодится, чтобы понимать, живой ли аккаунт
    last_login_at timestamptz,

    constraint accounts_email_lowercase check (email = lower(email))
);
