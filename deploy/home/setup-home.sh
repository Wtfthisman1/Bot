#!/usr/bin/env bash
# Домашняя сторона туннеля. Запускать от root:
#
#   sudo VPS_PUBKEY=<...> VPS_IP=<...> bash deploy/home/setup-home.sh
#
# Приватный ключ берётся из ~/.vps-transcribot/wg-home.key того пользователя,
# который вызвал sudo, — он сгенерирован заранее и наружу не уезжал.
set -euo pipefail

VPS_PUBKEY="${VPS_PUBKEY:?укажите VPS_PUBKEY — публичный ключ VPS}"
VPS_IP="${VPS_IP:?укажите VPS_IP}"

# Под sudo $HOME указывает на /root, а ключ лежит у обычного пользователя.
CALLER_HOME=$(getent passwd "${SUDO_USER:-$(whoami)}" | cut -d: -f6)
KEY="${CALLER_HOME}/.vps-transcribot/wg-home.key"
[ -f "$KEY" ] || { echo "не нашёл приватный ключ: $KEY"; exit 1; }

say() { echo; echo "── $* ──"; }

say "пакеты"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq wireguard >/dev/null
echo "wireguard на месте"

say "конфиг туннеля"
umask 077
mkdir -p /etc/wireguard
cat > /etc/wireguard/wg0.conf <<EOF
# Сгенерировано setup-home.sh — правки руками перезатрутся.
[Interface]
Address = 10.8.0.2/24
PrivateKey = $(cat "$KEY")

[Peer]
PublicKey = ${VPS_PUBKEY}
Endpoint = ${VPS_IP}:51820
# Через туннель ходит только сам VPS: гнать весь трафик машины через него не
# нужно и вредно — скачивание с YouTube должно идти напрямую, иначе упрётся
# в канал VPS и съест его трафик.
AllowedIPs = 10.8.0.1/32
# Машина за NAT: без периодических пакетов роутер забудет соответствие,
# и VPS не сможет достучаться до неё первым.
PersistentKeepalive = 25
EOF
chmod 600 /etc/wireguard/wg0.conf

say "запуск"
systemctl enable wg-quick@wg0 >/dev/null 2>&1
systemctl restart wg-quick@wg0
sleep 2
wg show wg0

say "проверка связи с VPS через туннель"
if ping -c 3 -W 3 10.8.0.1 >/dev/null 2>&1; then
    echo "10.8.0.1 отвечает — туннель поднялся"
else
    echo "10.8.0.1 молчит. Смотрите: journalctl -u wg-quick@wg0 -n 30"
    exit 1
fi
