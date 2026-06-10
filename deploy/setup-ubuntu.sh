#!/usr/bin/env bash
# Ubuntu/Debian 一键安装 JDK17 + Caddy，并把 VCA 装成 systemd 服务。
# 用法（服务器上，root 执行）：
#   1) 已把 app.jar 放到 /opt/vca/app.jar
#   2) 已把 Caddyfile 放到 /etc/caddy/Caddyfile（并改好域名）
#   3) bash setup-ubuntu.sh
set -euo pipefail

DOMAIN="${1:-}"
if [ -z "$DOMAIN" ]; then echo "用法: bash setup-ubuntu.sh your-domain.com"; exit 1; fi

echo "==> 2G 内存兜底：若无 swap 则建 2G swap"
if ! swapon --show | grep -q .; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  grep -q '/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  echo "    已创建 2G swap"
fi

echo "==> 安装 JDK 17"
apt-get update -y
apt-get install -y openjdk-17-jre-headless curl debian-keyring debian-archive-keyring apt-transport-https

echo "==> 安装 Caddy（官方源）"
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
apt-get update -y
apt-get install -y caddy

echo "==> 创建运行用户与目录"
id vca >/dev/null 2>&1 || useradd -r -s /usr/sbin/nologin vca
mkdir -p /opt/vca
chown -R vca:vca /opt/vca

echo "==> 写入环境文件 /etc/vca.env（请稍后填入真实 key）"
if [ ! -f /etc/vca.env ]; then
  cat > /etc/vca.env <<'EOF'
SPRING_PROFILES_ACTIVE=real
SERVER_PORT=8080
DASHSCOPE_API_KEY=sk-改成你的阿里云key
DEEPSEEK_ENABLED=true
DEEPSEEK_API_KEY=sk-改成你的deepseek-key
# 服务器直连 DeepSeek：必须置空，禁用默认的本地 7890 代理
DEEPSEEK_PROXY=
EOF
  chmod 600 /etc/vca.env
  echo "    已生成 /etc/vca.env —— 记得编辑填入真实 key！"
fi

echo "==> 安装 systemd 服务"
cp /root/vca.service /etc/systemd/system/vca.service 2>/dev/null || true
systemctl daemon-reload
systemctl enable --now vca

echo "==> 配置 Caddy（自动 HTTPS + WebSocket）"
# 若 /etc/caddy/Caddyfile 里还是占位域名，则用传入域名覆盖
cat > /etc/caddy/Caddyfile <<EOF
$DOMAIN {
    encode zstd gzip
    reverse_proxy localhost:8080
}
EOF
systemctl restart caddy

echo
echo "==> 完成。检查："
echo "   journalctl -u vca -f          # 看 app 日志"
echo "   systemctl status caddy        # 看反代/证书"
echo "   手机打开： https://$DOMAIN"
echo
echo "‼ 别忘了：1) 编辑 /etc/vca.env 填 key 后  systemctl restart vca"
echo "          2) 安全组放行 80/443，且 8080 不要对公网开"
