#!/bin/sh
# RikkaHub Remote Debug v2 - 更稳的隧道方案
set -e

echo ""
echo "============================================"
echo "  RikkaHub Remote Debug Tunnel v2"
echo "============================================"
echo ""

ARCH=$(uname -m)
case "$ARCH" in
    aarch64|arm64) CF_ARCH="arm64" ;;
    x86_64|amd64)  CF_ARCH="amd64" ;;
    *)             CF_ARCH="$ARCH" ;;
esac
echo "[1] Arch: $ARCH → cloudflared-$CF_ARCH"

# 临时目录
TMPDIR="${TMPDIR:-/tmp}/rikkahub-debug"
mkdir -p "$TMPDIR"
cd "$TMPDIR"

# 下载 cloudflared
if [ ! -f cloudflared ]; then
    echo "[2] Downloading cloudflared..."
    curl -sL -o cloudflared "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-$CF_ARCH" || \
    curl -sL -o cloudflared "https://github.com/cloudflare/cloudflared/releases/download/2025.12.0/cloudflared-linux-$CF_ARCH"
    chmod +x cloudflared
    echo "     Done ($(du -h cloudflared | cut -f1))"
fi
export PATH="$TMPDIR:$PATH"

# 写 web 服务器脚本
cat > server.py << 'PYEOF'
import http.server, json, socket, subprocess, sys, os, time
WORKSPACE_ID = os.environ.get('RIKKAHUB_WORKSPACE_ID', 'remote-device')
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8888
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/':
            self.send_response(200)
            self.send_header('Content-type', 'text/plain')
            self.end_headers()
            self.wfile.write(f"RikkaHub Remote\nHost: {socket.gethostname()}\n".encode())
        elif self.path == '/info':
            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.end_headers()
            d = {'hostname': socket.gethostname(), 'arch': os.uname().machine, 'pid': os.getpid(),
                 'cwd': os.getcwd(), 'env': dict(os.environ)}
            self.wfile.write(json.dumps(d).encode())
        elif self.path.startswith('/exec/'):
            cmd = self.path[6:]
            self.send_response(200)
            self.send_header('Content-type', 'text/plain')
            self.end_headers()
            try:
                r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=30)
                self.wfile.write(f"Exit: {r.returncode}\n\nSTDOUT:\n{r.stdout}\n\nSTDERR:\n{r.stderr}".encode())
            except Exception as e:
                self.wfile.write(f"Error: {e}".encode())
        else:
            self.send_response(404)
            self.end_headers()
    def log_message(self, *a): pass
http.server.HTTPServer(('0.0.0.0', PORT), Handler).serve_forever()
PYEOF

# 启动 web 服务器（后台）
echo "[3] Starting web server..."
python3 server.py 8888 > /tmp/rikkahub-server.log 2>&1 &
echo "     PID: $!"
sleep 1
curl -s -o /dev/null -w "     HTTP %{http_code}\n" http://localhost:8888/

# 启动 cloudflared（前台！重要）
echo ""
echo "[4] Starting Cloudflare Tunnel (foreground)..."
echo "     Waiting for URL..."
echo ""

# 先启动 cloudflared 后台，抓 URL，然后切前台
cloudflared tunnel --url http://localhost:8888 --no-autoupdate --protocol http2 > /tmp/rikkahub-cf.log 2>&1 &
CF_PID=$!

# 等待 URL
URL=""
for i in $(seq 1 30); do
    URL=$(grep -oP 'https?://[a-z0-9-]+\.trycloudflare\.com' /tmp/rikkahub-cf.log 2>/dev/null | head -1)
    [ -n "$URL" ] && break
    echo -n "."
    sleep 1
done
echo ""

if [ -z "$URL" ]; then
    echo "     ❌ Tunnel failed!"
    cat /tmp/rikkahub-cf.log
    exit 1
fi

echo ""
echo "╔════════════════════════════════════════════╗"
echo "║     ✅ TUNNEL READY!                      ║"
echo "╚════════════════════════════════════════════╝"
echo ""
echo "  🔗 URL: $URL"
echo "  ⚠️  复制这个 URL 发给 AI，然后不要关闭本终端！"
echo "  ⚠️  关闭终端会导致隧道断开"
echo ""

# 切换到前台显示 cloudflared 日志
echo "--- cloudflared 实时日志 (按 Ctrl+C 停止) ---"
echo ""
tail -f /tmp/rikkahub-cf.log &
TAIL_PID=$!

# 保持运行，同时监控进程存活
while true; do
    if ! kill -0 $CF_PID 2>/dev/null; then
        echo ""
        echo "⚠️  cloudflared 进程已退出！"
        echo "   检查上方日志"
        break
    fi
    sleep 5
done

wait
