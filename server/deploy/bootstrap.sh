#!/usr/bin/env bash
# First setup of a fresh Debian 13 VPS for the Descente-Canyon server. Run it once, as root:
#   bash bootstrap.sh "<your SSH public key>" [<IPv4 CIDR allowed to reach SSH, default: any>]
# Then follow server/README.md ("Mise en service").
set -euo pipefail

SSH_KEY="${1:?usage: bootstrap.sh '<SSH public key>' [ssh-cidr]}"
SSH_CIDR="${2:-}"
REPO_URL="${DC_REPO_URL:-https://github.com/Plinz/descente-canyon-app.git}"

[[ $(id -u) -eq 0 ]] || { echo "Run as root." >&2; exit 1; }
. /etc/os-release
[[ "${ID:-}" == "debian" ]] || echo "Warning: written for Debian 13, found ${PRETTY_NAME:-unknown}." >&2

export DEBIAN_FRONTEND=noninteractive
timedatectl set-timezone UTC
apt-get update
apt-get -y full-upgrade
apt-get install -y --no-install-recommends \
    ca-certificates curl git sudo nftables unattended-upgrades apt-listchanges \
    docker.io docker-compose restic sqlite3
docker compose version >/dev/null 2>&1 || {
    echo "'docker compose' is missing: install the Compose plugin (docs.docker.com/compose/install/linux)." >&2
    exit 1
}

# 1. Service account: the only one allowed through SSH, key only.
id dc &>/dev/null || adduser --disabled-password --gecos "" dc
usermod -aG docker,sudo dc
install -d -m 700 -o dc -g dc /home/dc/.ssh
printf '%s\n' "$SSH_KEY" > /home/dc/.ssh/authorized_keys
chown dc:dc /home/dc/.ssh/authorized_keys
chmod 600 /home/dc/.ssh/authorized_keys
if [[ -t 0 ]]; then
    echo "Choose the sudo password of the dc account:"
    passwd dc
else
    echo "No terminal: set the sudo password of dc yourself afterwards (passwd dc)." >&2
fi

# 2. SSH: keys only, no root, dc only.
cat > /etc/ssh/sshd_config.d/10-dc.conf <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
AllowUsers dc
X11Forwarding no
MaxAuthTries 3
EOF
sshd -t
systemctl reload ssh
echo "SSH now accepts the dc account only, by key. Test 'ssh dc@<server>' in a NEW terminal before closing this one."

# 3. Firewall: SSH, HTTP and HTTPS (HTTP/3 too) in, nothing else. No "flush ruleset": Docker
#    keeps its own tables when this file is reloaded.
if [[ -n "$SSH_CIDR" ]]; then
    SSH_RULE="ip saddr $SSH_CIDR tcp dport 22 accept"
else
    SSH_RULE="tcp dport 22 accept"
fi
cat > /etc/nftables.conf <<EOF
#!/usr/sbin/nft -f
table inet dc_filter
delete table inet dc_filter
table inet dc_filter {
	chain input {
		type filter hook input priority filter; policy drop;
		ct state established,related accept
		ct state invalid drop
		iif "lo" accept
		meta l4proto { icmp, ipv6-icmp } accept
		$SSH_RULE
		tcp dport { 80, 443 } accept
		udp dport 443 accept
	}
}
EOF
nft -c -f /etc/nftables.conf
systemctl enable --now nftables
nft -f /etc/nftables.conf

# 4. Security updates every day, reboot at 03:30 UTC when one needs it.
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF
cat > /etc/apt/apt.conf.d/52dc-unattended <<'EOF'
Unattended-Upgrade::Automatic-Reboot "true";
Unattended-Upgrade::Automatic-Reboot-Time "03:30";
EOF
systemctl enable --now unattended-upgrades

# 5. Swap: the catalog job reads the whole catalog in memory (about 1 GB at its peak).
if ! swapon --show | grep -q .; then
    fallocate -l 2G /swapfile
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# 6. Folders and the app repository (scraper, catalog generator, Room schemas, server code).
install -d -m 755 -o dc -g dc /srv/dc /srv/dc/publish /srv/dc/publish/v1
install -d -m 750 -o dc -g dc /srv/dc/data
install -d -m 700 -o dc -g dc /srv/dc/secrets
[[ -d /srv/dc/repo/.git ]] || sudo -u dc git clone --quiet "$REPO_URL" /srv/dc/repo

# 7. Timers (enabled later, once the configuration is in place).
install -m 644 /srv/dc/repo/server/deploy/systemd/dc-*.service /srv/dc/repo/server/deploy/systemd/dc-*.timer /etc/systemd/system/
systemctl daemon-reload

echo "Done. Next steps: server/README.md, section « Mise en service »."
