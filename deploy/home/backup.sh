#!/usr/bin/env bash
# Резервная копия домашней половины: дамп базы плюс тексты расшифровок.
#
# Медиафайлы не сохраняются намеренно: их 2,8 ГБ, они и так удаляются через
# 14 дней (FileCleanupWorker), а исходник всегда можно прислать заново.
# Невосстановимое — аккаунты, история задач, сегменты расшифровок и выжимки —
# лежит в базе и весит вместе с текстами единицы мегабайт.
#
# Ставится таймером: sudo bash deploy/home/setup-backup.sh
# Руками: sudo bash deploy/home/backup.sh
set -euo pipefail

# Настройки можно переопределить в /etc/transcribot/backup.env, не трогая репозиторий
CONF=/etc/transcribot/backup.env
# shellcheck source=/dev/null
[[ -f $CONF ]] && . "$CONF"

PROJECT_DIR="${PROJECT_DIR:-/home/dmitry/IdeaProjects/Bot}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/transcribot}"
DB_CONTAINER="${DB_CONTAINER:-bot-postgres}"
TRANSCRIPT_DIR="${TRANSCRIPT_DIR:-$PROJECT_DIR/upload/videos}"
KEEP="${BACKUP_KEEP:-14}"

die() { echo "ОШИБКА: $*" >&2; exit 1; }
say() { echo "$*"; }

[[ $EUID -eq 0 ]] || die "нужен root: sudo bash $0"

# Имя базы и пользователя берём из того же .env, что читает приложение:
# два источника правды разъезжаются молча, и узнаёшь об этом при восстановлении
env_value() {
    [[ -f "$PROJECT_DIR/.env" ]] || return 0
    grep -E "^[[:space:]]*$1=" "$PROJECT_DIR/.env" | tail -1 | cut -d= -f2- | tr -d "\"'" || true
}
DB_NAME="${DB_NAME:-$(env_value DB_NAME)}"
DB_USER="${DB_USER:-$(env_value DB_USER)}"
DB_NAME="${DB_NAME:-bot_db}"
DB_USER="${DB_USER:-bot_user}"

running=$(docker inspect -f '{{.State.Running}}' "$DB_CONTAINER" 2>/dev/null || echo false)
[[ $running == true ]] || die "контейнер $DB_CONTAINER не запущен — копия не снята (docker compose up -d postgres)"

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"   # в дампе почта и хеши паролей — не для чужих глаз

stamp=$(date +%Y%m%d-%H%M)
archive="$BACKUP_DIR/transcribot-$stamp.tar.gz"
# Временный каталог рядом с целью: mv в пределах одной ФС атомарен, и
# оборванная на середине копия никогда не выглядит готовой
work=$(mktemp -d "$BACKUP_DIR/.work-XXXXXX")
trap 'rm -rf "$work"' EXIT

# -Fc, а не текстовый SQL: сжат, восстанавливается выборочно (pg_restore -t)
# и переживает смену версии Postgres лучше, чем plain-дамп
say "Дамп базы $DB_NAME"
docker exec "$DB_CONTAINER" pg_dump -U "$DB_USER" -Fc "$DB_NAME" > "$work/db.dump" \
    || die "pg_dump не отработал"
[[ -s "$work/db.dump" ]] || die "дамп пустой — копию не сохраняю"

say "Тексты расшифровок из $TRANSCRIPT_DIR"
if [[ -d $TRANSCRIPT_DIR ]]; then
    (cd "$TRANSCRIPT_DIR" && find . -type f \
        \( -name '*.txt' -o -name '*.srt' -o -name '*.vtt' \
           -o -name '*.json' -o -name '*.tsv' -o -name '*.docx' \) -print0 \
        | tar --null -T - -czf "$work/transcripts.tar.gz")
else
    say "  каталога нет — пропускаю"
    tar -czf "$work/transcripts.tar.gz" -T /dev/null
fi

# Памятка внутри архива: через полгода никто не вспомнит, чем и откуда это снято
{
    echo "снято:     $(date --iso-8601=seconds)"
    echo "машина:    $(hostname)"
    echo "база:      $DB_NAME (пользователь $DB_USER)"
    echo "postgres:  $(docker exec "$DB_CONTAINER" pg_dump --version | tail -1)"
    echo "коммит:    $(git -C "$PROJECT_DIR" rev-parse --short HEAD 2>/dev/null || echo неизвестен)"
    echo "восстановление: deploy/README.md, раздел «Резервные копии»"
} > "$work/MANIFEST"

tar -czf "$archive.part" -C "$work" MANIFEST db.dump transcripts.tar.gz
mv "$archive.part" "$archive"
chmod 600 "$archive"
say "Готово: $archive ($(du -h "$archive" | cut -f1))"

# Ротация только после удачной копии: неудачный запуск не должен доедать старые
old=$(ls -1t "$BACKUP_DIR"/transcribot-*.tar.gz 2>/dev/null | tail -n +$((KEEP + 1)) || true)
if [[ -n $old ]]; then
    while read -r f; do rm -f "$f" && say "убрал старое: $(basename "$f")"; done <<< "$old"
fi

# Копия никуда не уезжает: база с почтой и хешами паролей остаётся на этой
# машине. Значит, единственная точка отказа — диск: сдохнет он, сдохнут и копии.
# Внешний носитель подключать руками, когда захочется, — это осознанный выбор
# пользователя, а не то, что скрипт делает сам.
say "Копий за пределами этой машины нет — это решение, а не забывчивость"
