#!/usr/bin/env bash
# Ставит на VPS половину «приём запросов»: Java, служба, файл с секретами.
#
# Запускать от root на самом VPS:
#   BOT_TOKEN=... HOME_API_KEY=... bash /root/setup-bot.sh
#
# Скрипт идемпотентный: повторный запуск обновляет юнит и не трогает уже
# записанные секреты. Сам jar он не собирает — его кладут отдельно
# (см. deploy/README.md), потому что собирать на 960 МБ памяти нечем.
set -euo pipefail

APP_DIR="/opt/transcribot"
ENV_DIR="/etc/transcribot"
ENV_FILE="${ENV_DIR}/bot.env"
UNIT_NAME="transcribot-bot.service"
UNIT_DST="/etc/systemd/system/${UNIT_NAME}"
UNIT_SRC="$(dirname "$(readlink -f "$0")")/transcribot-bot.service"

HOME_WG_IP="${HOME_WG_IP:-10.8.0.2}"
RUN_USER="transcribot"

die() { echo "ОШИБКА: $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "нужен root: bash $0"
[[ -f "$UNIT_SRC" ]] || die "не нашёл юнит рядом со скриптом: $UNIT_SRC"

# ── пользователь и каталоги ───────────────────────────────────────────────
id "$RUN_USER" >/dev/null 2>&1 || useradd --system --home-dir "$APP_DIR" --shell /usr/sbin/nologin "$RUN_USER"
install -d -o "$RUN_USER" -g "$RUN_USER" -m 755 "$APP_DIR"
install -d -o root -g root -m 750 "$ENV_DIR"

# ── Java ──────────────────────────────────────────────────────────────────
if ! command -v java >/dev/null 2>&1; then
    echo "Ставлю JRE"
    apt-get update -qq
    # headless: серверу не нужны ни шрифты, ни графика — это сотни мегабайт
    apt-get install -y -qq default-jre-headless
fi

# ── секреты ───────────────────────────────────────────────────────────────
# Файл не перезаписывается: на втором запуске скрипта токен уже на месте,
# и затирать его пустотой из окружения — верный способ уронить бота
if [[ ! -f "$ENV_FILE" ]]; then
    [[ -n "${BOT_TOKEN:-}" ]] || die "нет BOT_TOKEN — без него бот не стартует"
    [[ -n "${HOME_API_KEY:-}" ]] || die "нет HOME_API_KEY — тот же ключ должен стоять дома"

    umask 077
    cat > "$ENV_FILE" <<ENV
# Половина «приём запросов»: базы и файлов здесь нет
SPRING_PROFILES_ACTIVE=bot

BOT_TOKEN=${BOT_TOKEN}
ADMIN_CHAT_ID=${ADMIN_CHAT_ID:-}

# Дом за туннелем. Наружу этот адрес не выставлен — nginx о нём не знает.
HOME_API_URL=http://${HOME_WG_IP}:8080
HOME_API_KEY=${HOME_API_KEY}

# Слушаем только петлю: сайт появится позже и пойдёт через nginx, а сейчас
# наружу торчать нечему
SERVER_ADDRESS=127.0.0.1
ENV
    chown root:root "$ENV_FILE"
    chmod 640 "$ENV_FILE"
    chgrp "$RUN_USER" "$ENV_FILE"
    echo "Записал $ENV_FILE"
else
    echo "Оставляю существующий $ENV_FILE без изменений"
fi

# ── служба ────────────────────────────────────────────────────────────────
install -m 644 "$UNIT_SRC" "$UNIT_DST"
systemctl daemon-reload

if [[ ! -f "${APP_DIR}/bot.jar" ]]; then
    cat <<TXT

Служба поставлена, но jar ещё не залит. С домашней машины:

  ./gradlew bootJar
  ~/.vps-transcribot/put.py build/libs/bot.jar /opt/transcribot/bot.jar
  echo 'systemctl restart ${UNIT_NAME}' | ~/.vps-transcribot/rsh.py

TXT
    exit 0
fi

chown "$RUN_USER":"$RUN_USER" "${APP_DIR}/bot.jar"
systemctl enable --now "$UNIT_NAME"
systemctl --no-pager --lines=0 status "$UNIT_NAME" || true
