#!/usr/bin/env bash
# Настройка VPS-релея. Запускается на VPS от root, можно повторять — скрипт
# идемпотентный: уже сделанные шаги пропускаются, ключи не перегенерируются.
#
#   DOMAIN=transcribot.site HOME_PUBKEY=<...> EMAIL=<...> bash setup-vps.sh
#
# Сертификат выпускается только если домен уже резолвится в этот сервер:
# Let's Encrypt проверяет владение через HTTP, и без рабочего DNS шаг падает.
# Поэтому он вынесен в конец и при неготовом DNS просто пропускается.
set -euo pipefail

DOMAIN="${DOMAIN:?укажите DOMAIN}"
HOME_PUBKEY="${HOME_PUBKEY:?укажите HOME_PUBKEY — публичный ключ домашней машины}"
EMAIL="${EMAIL:-}"
WWW_MISSING=0
HOME_WG_IP="10.8.0.2"
VPS_WG_IP="10.8.0.1"

say() { echo; echo "── $* ──"; }

say "пакеты"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq wireguard nginx certbot ufw curl >/dev/null
echo "wireguard, nginx, certbot, ufw на месте"

say "ключи WireGuard"
# umask сужается только на время работы с ключами и восстанавливается сразу
# после: иначе он утечёт на остальные шаги и, например, каталог для проверки
# домена создастся с правами 700, куда nginx под www-data не войдёт.
OLD_UMASK=$(umask)
umask 077
mkdir -p /etc/wireguard
if [ ! -f /etc/wireguard/privatekey ]; then
    wg genkey | tee /etc/wireguard/privatekey | wg pubkey > /etc/wireguard/publickey
    echo "ключевая пара создана"
else
    echo "ключи уже были, оставляю"
fi
VPS_PRIV=$(cat /etc/wireguard/privatekey)
VPS_PUB=$(cat /etc/wireguard/publickey)

say "туннель"
cat > /etc/wireguard/wg0.conf <<EOF
# Сгенерировано setup-vps.sh — правки руками перезатрутся.
[Interface]
Address = ${VPS_WG_IP}/24
ListenPort = 51820
PrivateKey = ${VPS_PRIV}

[Peer]
# Домашняя машина с ботом. Endpoint не указан сознательно: домашний адрес
# динамический, соединение всегда инициирует домашняя сторона, а VPS
# запоминает, откуда она пришла.
PublicKey = ${HOME_PUBKEY}
AllowedIPs = ${HOME_WG_IP}/32
EOF
chmod 600 /etc/wireguard/wg0.conf
umask "$OLD_UMASK"
systemctl enable wg-quick@wg0 >/dev/null 2>&1
systemctl restart wg-quick@wg0
wg show wg0 | head -5

say "файрвол"
# Порядок важен: правило для SSH до включения ufw, иначе закроем себе вход.
ufw allow 22/tcp    >/dev/null
ufw allow 80/tcp    >/dev/null
ufw allow 443/tcp   >/dev/null
ufw allow 51820/udp >/dev/null
# Политика «deny incoming» распространяется и на пакеты, которые пришли уже
# расшифрованными из туннеля, — без этого правила nginx получает
# «No route to host» при обращении к домашней машине. Внутри wg0 может быть
# только наш пир: посторонний туда не попадёт без приватного ключа.
ufw allow in on wg0 comment "трафик внутри WireGuard" >/dev/null
ufw --force enable  >/dev/null
ufw status numbered | grep -E "22|80|443|51820|wg0"

say "ssh: вход только по ключу"
# Ставится после ufw и до всего остального: порт 22 открыт всему интернету, и
# перебор в него идёт непрерывно. Файл кладём рядом с ключом, который уже
# работает, — иначе следующая строка закрыла бы вход самому себе.
if [[ -f /root/.ssh/authorized_keys && -s /root/.ssh/authorized_keys ]]; then
    install -m 644 -o root -g root "${SSHD_CONF_SRC:-/root/sshd-hardening.conf}" \
        /etc/ssh/sshd_config.d/99-transcribot.conf
    if sshd -t; then
        systemctl reload ssh
        say "ssh: пароль выключен, вход только по ключу"
    else
        rm -f /etc/ssh/sshd_config.d/99-transcribot.conf
        echo "ОШИБКА: sshd не принял конфиг, закалка отменена" >&2
    fi
else
    echo "ПРОПУСК: в /root/.ssh/authorized_keys нет ключа — выключать пароль нельзя" >&2
fi

say "nginx: временный HTTP-сайт под проверку домена"
mkdir -p /var/www/certbot
# Каталог должен быть читаем для nginx (www-data), иначе Let's Encrypt
# получит 403 вместо файла подтверждения.
chmod 755 /var/www /var/www/certbot
# Пока сертификата нет, HTTPS-секцию включать нельзя: nginx не стартует со
# ссылкой на несуществующий файл ключа.
cat > /etc/nginx/sites-available/bot <<EOF
server {
    listen 80;
    listen [::]:80;
    server_name ${DOMAIN} www.${DOMAIN};
    location /.well-known/acme-challenge/ { root /var/www/certbot; }
    location / { return 404; }
}
EOF
ln -sf /etc/nginx/sites-available/bot /etc/nginx/sites-enabled/bot
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx
echo "HTTP-сайт поднят"

say "страница на случай спящего дома"
mkdir -p /var/www/transcribot
chmod 755 /var/www/transcribot
# Отдаётся вместо 502, когда домашняя машина выключена. Лежит на VPS, потому
# что показать её должен как раз тот, кто до дома не достучался.
cat > /var/www/transcribot/asleep.html <<'HTML'
<!DOCTYPE html>
<html lang="ru">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
    <meta name="theme-color" content="#0c0c0d">
    <title>Рабочая машина спит — Транскрибот</title>
    <!-- Те же цвета, что и на сайте: человек не должен решить, что попал
         не туда. Файл лежит на VPS отдельно от приложения, поэтому стили
         повторены здесь целиком — общего site.css отсюда не достать -->
    <style>
        :root { color-scheme: dark; }
        body { margin:0; min-height:100vh; display:flex; align-items:center;
               justify-content:center; padding:24px;
               background:#0c0c0d; color:#f2efea;
               font:16px/1.6 -apple-system, BlinkMacSystemFont, "Segoe UI",
                    Roboto, "Helvetica Neue", Arial, sans-serif;
               -webkit-font-smoothing:antialiased; }
        main { max-width:440px; padding:32px 26px;
               background:#151517; border:1px solid #232326; border-radius:16px; }
        svg { display:block; fill:#c8a96b; margin-bottom:18px; }
        h1 { margin:0 0 14px; font:400 26px/1.25 ui-serif, Georgia,
             "Times New Roman", serif; letter-spacing:-0.01em; }
        p { margin:0 0 12px; color:#9b968e; }
        p:last-child { margin-bottom:0; }
    </style>
</head>
<body>
<main>
    <svg width="22" height="22" viewBox="0 0 32 32" aria-hidden="true" focusable="false">
        <rect x="6.6" y="13" width="2.6" height="6" rx="1.3"/>
        <rect x="11.8" y="8.5" width="2.6" height="15" rx="1.3"/>
        <rect x="17" y="11" width="2.6" height="10" rx="1.3"/>
        <rect x="22.2" y="14.2" width="2.6" height="3.6" rx="1.3"/>
    </svg>
    <h1>Рабочая машина сейчас спит</h1>
    <p>Сайт живёт на домашнем компьютере с видеокартой — он включён не круглосуточно.
        Загляните позже.</p>
    <p>Задачу можно поставить прямо сейчас через бота в Telegram: он принимает
        её и запускает, как только машина проснётся.</p>
</main>
</body>
</html>
HTML
echo "готово: /var/www/transcribot/asleep.html"

say "проверка DNS"
RESOLVED=$(getent hosts "$DOMAIN" | awk '{print $1}' | head -1 || true)
MYIP=$(curl -s --max-time 10 https://api.ipify.org || echo "?")
echo "${DOMAIN} резолвится в: ${RESOLVED:-<ничего>}"
echo "мой внешний IP:        ${MYIP}"

if [ -z "$RESOLVED" ] || [ "$RESOLVED" != "$MYIP" ]; then
    echo
    echo "!! DNS ещё не указывает сюда — сертификат пропускаю."
    echo "   Почините A-запись и запустите скрипт повторно, он продолжит с этого места."
    echo
    echo "ПУБЛИЧНЫЙ КЛЮЧ VPS: ${VPS_PUB}"
    exit 0
fi

say "сертификат Let's Encrypt"
if [ ! -d "/etc/letsencrypt/live/${DOMAIN}" ]; then
    ACME_MAIL=(--register-unsafely-without-email)
    [ -n "$EMAIL" ] && ACME_MAIL=(-m "$EMAIL")

    # www включаем в сертификат только если он резолвится: Let's Encrypt
    # проверяет каждое имя отдельно, и одно нерабочее валит весь выпуск.
    NAMES=(-d "$DOMAIN")
    WWW_IP=$(getent hosts "www.${DOMAIN}" | awk '{print $1}' | head -1 || true)
    if [ "$WWW_IP" = "$MYIP" ]; then
        NAMES+=(-d "www.${DOMAIN}")
        echo "www резолвится сюда — включаю в сертификат"
    else
        echo "www не резолвится сюда — выпускаю сертификат только на ${DOMAIN}"
        WWW_MISSING=1
    fi

    certbot certonly --webroot -w /var/www/certbot "${NAMES[@]}" \
        --agree-tos --non-interactive "${ACME_MAIL[@]}"
else
    echo "сертификат уже выпущен"
    grep -q "www.${DOMAIN}" <(certbot certificates 2>/dev/null) || WWW_MISSING=1
fi

say "nginx: боевой конфиг"
# nginx 1.25 переехал с «listen ... http2» на отдельную директиву «http2 on».
# Ubuntu 24.04 везёт 1.24, поэтому выбираем форму по версии, а не наугад.
NGINX_VER=$(nginx -v 2>&1 | grep -oE "[0-9]+\.[0-9]+\.[0-9]+")
if [ "$(printf "%s\n1.25.1" "$NGINX_VER" | sort -V | head -1)" = "1.25.1" ]; then
    LISTEN_BLOCK='listen 443 ssl;\n    listen [::]:443 ssl;\n    http2 on;'
else
    LISTEN_BLOCK='listen 443 ssl http2;\n    listen [::]:443 ssl http2;'
fi
echo "nginx ${NGINX_VER}"
sed -e "s/FILES_DOMAIN/${DOMAIN}/g" \
    -e "s/HOME_WG_IP/${HOME_WG_IP}/g" \
    -e "s|LISTEN_443_BLOCK|${LISTEN_BLOCK}|g" \
    /root/nginx-bot.conf.template > /etc/nginx/sites-available/bot

# Сертификата на www нет — убираем его из server_name, иначе браузер получит
# предупреждение о несовпадении имени вместо понятной ошибки.
if [ "${WWW_MISSING:-0}" = "1" ]; then
    sed -i "s/ www\.${DOMAIN};/;/g" /etc/nginx/sites-available/bot
    echo "www убран из конфига nginx"
fi
nginx -t && systemctl reload nginx

say "готово"
echo "ПУБЛИЧНЫЙ КЛЮЧ VPS: ${VPS_PUB}"
echo "адрес: https://${DOMAIN}"
