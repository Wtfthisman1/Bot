#!/usr/bin/env bash
# Ставит ежедневную резервную копию под systemd и сразу снимает первую —
# «настроено» и «работает» это разные вещи, и узнавать разницу лучше сейчас.
#
# Запускать от root: sudo bash deploy/home/setup-backup.sh
# Скрипт идемпотентный: повторный запуск обновляет юниты, но не трогает
# /etc/transcribot/backup.env — правки в нём переживают переустановку.
set -euo pipefail

PROJECT_DIR="${PROJECT_DIR:-/home/dmitry/IdeaProjects/Bot}"
CONF_DIR=/etc/transcribot
CONF="$CONF_DIR/backup.env"
UNITS=(transcribot-backup.service transcribot-backup.timer)
BACKUP_KEEP="${BACKUP_KEEP:-14}"

die() { echo "ОШИБКА: $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "нужен root: sudo bash $0"
[[ -x "$PROJECT_DIR/deploy/home/backup.sh" ]] || die "не нашёл $PROJECT_DIR/deploy/home/backup.sh"
for u in "${UNITS[@]}"; do
    [[ -f "$PROJECT_DIR/deploy/home/$u" ]] || die "не нашёл юнит $u"
done

mkdir -p "$CONF_DIR"
if [[ -f $CONF ]]; then
    echo "Настройки уже есть, оставляю как есть: $CONF"
else
    cat > "$CONF" <<CONFEOF
# Настройки резервного копирования Transcribot (читает deploy/home/backup.sh).
PROJECT_DIR=$PROJECT_DIR
BACKUP_DIR=/var/backups/transcribot
# Сколько копий держать
BACKUP_KEEP=$BACKUP_KEEP
CONFEOF
    chmod 600 "$CONF"
    echo "Записал $CONF"
fi

for u in "${UNITS[@]}"; do
    install -m 644 "$PROJECT_DIR/deploy/home/$u" "/etc/systemd/system/$u"
done
sed -i "s#^ExecStart=.*#ExecStart=${PROJECT_DIR}/deploy/home/backup.sh#" \
    /etc/systemd/system/transcribot-backup.service

systemctl daemon-reload
systemctl enable --now transcribot-backup.timer

echo
echo "Первая копия — прямо сейчас, чтобы не выяснять на пожаре, что она не снимается"
if systemctl start transcribot-backup.service; then
    journalctl -u transcribot-backup -n 20 --no-pager -o cat
else
    journalctl -u transcribot-backup -n 30 --no-pager -o cat
    die "первая копия не снялась — смотрите журнал выше"
fi

echo
systemctl list-timers transcribot-backup.timer --no-pager
