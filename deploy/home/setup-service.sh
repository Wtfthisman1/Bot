#!/usr/bin/env bash
# Ставит бота под systemd, чтобы он поднимался сам после перезагрузки.
#
# Запускать от root: sudo bash deploy/home/setup-service.sh
# Скрипт идемпотентный — повторный запуск просто обновляет юнит.
set -euo pipefail

PROJECT_DIR="${PROJECT_DIR:-/home/dmitry/IdeaProjects/Bot}"
RUN_USER="${RUN_USER:-dmitry}"
UNIT_NAME="transcribot.service"
UNIT_SRC="${PROJECT_DIR}/deploy/home/${UNIT_NAME}"
UNIT_DST="/etc/systemd/system/${UNIT_NAME}"
JAR="${PROJECT_DIR}/build/libs/bot.jar"

die() { echo "ОШИБКА: $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "нужен root: sudo bash $0"
[[ -f "$UNIT_SRC" ]] || die "не нашёл юнит: $UNIT_SRC"
[[ -f "$JAR" ]] || die "не собран jar: $JAR (запустите ./gradlew bootJar)"
[[ -f "${PROJECT_DIR}/.env" ]] || die "нет ${PROJECT_DIR}/.env — без BOT_TOKEN бот не стартует"
id "$RUN_USER" >/dev/null 2>&1 || die "нет пользователя $RUN_USER"

# Бот, запущенный руками, займёт порт 8080 и служба не поднимется —
# гасим его до, а не после
if pgrep -f "java -jar .*bot\.jar" >/dev/null 2>&1; then
    echo "Останавливаю бота, запущенного вручную"
    pkill -f "java -jar .*bot\.jar" || true
    for _ in $(seq 1 15); do
        pgrep -f "java -jar .*bot\.jar" >/dev/null 2>&1 || break
        sleep 1
    done
fi

# Привязка к адресу туннеля не должна зависеть от того, поднят ли wg0 прямо
# сейчас: без ip_nonlocal_bind служба с SERVER_ADDRESS=10.8.0.2 не стартует,
# пока NetworkManager держит адрес снятым
SYSCTL_SRC="${PROJECT_DIR}/deploy/home/99-transcribot-bind.conf"
if [[ -f "$SYSCTL_SRC" ]]; then
    install -m 644 "$SYSCTL_SRC" /etc/sysctl.d/99-transcribot-bind.conf
    sysctl -q -p /etc/sysctl.d/99-transcribot-bind.conf
    echo "Разрешена привязка к адресу туннеля (net.ipv4.ip_nonlocal_bind=1)"
fi

install -m 644 "$UNIT_SRC" "$UNIT_DST"
sed -i "s#^WorkingDirectory=.*#WorkingDirectory=${PROJECT_DIR}#" "$UNIT_DST"
sed -i "s#^ExecStart=.*#ExecStart=/usr/bin/java -jar ${JAR}#" "$UNIT_DST"
sed -i "s#^User=.*#User=${RUN_USER}#" "$UNIT_DST"
sed -i "s#^Group=.*#Group=${RUN_USER}#" "$UNIT_DST"
sed -i "s#^Environment=HOME=.*#Environment=HOME=$(getent passwd "$RUN_USER" | cut -d: -f6)#" "$UNIT_DST"

systemctl daemon-reload
systemctl enable --now "$UNIT_NAME"

echo "Жду, пока бот займёт порт 8080"
for _ in $(seq 1 30); do
    if ss -ltn | grep -q ':8080'; then
        echo "Готово: служба ${UNIT_NAME} включена, бот слушает 8080"
        systemctl --no-pager --lines=0 status "$UNIT_NAME" || true
        exit 0
    fi
    sleep 1
done

die "служба запущена, но порт 8080 не открылся — смотрите: journalctl -u ${UNIT_NAME} -n 50"
