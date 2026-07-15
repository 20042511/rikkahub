#!/bin/sh
# RikkaHub Remote Debug - 另一台手机的引导脚本
# 在另一台手机的 RikkaHub 终端里运行：
#   curl -sL https://raw.githubusercontent.com/20042511/rikkahub/escape/remote-bootstrap.sh | sh
# 或手动复制粘贴执行

set -e

echo ""
echo "============================================"
echo "  RikkaHub Remote Debug Tunnel Setup"
echo "============================================"
echo ""

# 检测架构
ARCH=$(uname -m)
case "$ARCH" in
    aarch64|arm64) CLOUDFLARE_ARCH="arm64" ;;
    x86_64|amd64)  CLOUDFLARE_ARCH="amd64" ;;
    *)             CLOUDFLARE_ARCH="$ARCH" ;;
esac
echo "[1] Detected arch: $ARCH → cloudflared-$CLOUDFLARE_ARCH"

# 创建临时目录
TMPDIR="${TMPDIR:-/tmp}/rikkahub-debug"
mkdir -p "$TMPDIR"
cd "$TMPDIR"

# 下载 cloudflared（如果不存在）
if ! command -v cloudflared >/dev/null 2>&1; then
    echo "[2] Downloading cloudflared (Linux $CLOUDFLARE_ARCH)..."
    
    # 从官方 GitHub Release 下载
    CF_URL="https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-$CLOUDFLARE_ARCH"
    
    echo "     URL: $CF_URL"
    HTTP_CODE=$(curl -s -L -o cloudflared -w "%{http_code}" --connect-timeout 10 "$CF_URL" 2>/dev/null)
    
    if [ "$HTTP_CODE" != "200" ]; then
        echo "     ⚠️ 官方下载失败 (HTTP $HTTP_CODE)，尝试备选..."
        # 备选: 从已知版本下载
        CF_URL="https://github.com/cloudflare/cloudflared/releases/download/2025.12.0/cloudflared-linux-$CLOUDFLARE_ARCH"
        HTTP_CODE=$(curl -s -L -o cloudflared -w "%{http_code}" --connect-timeout 10 "$CF_URL" 2>/dev/null)
    fi
    
    if [ ! -s cloudflared ]; then
        echo "     ❌ 下载失败！请手动下载:"
        echo "     https://github.com/cloudflare/cloudflared/releases"
        exit 1
    fi
    
    chmod +x cloudflared
    echo "     ✅ Downloaded $(du -h cloudflared | cut -f1)"
else
    echo "[2] cloudflared already installed, using system one"
    CLOUDFLARE_BIN=$(command -v cloudflared)
fi

# 设置 PATH 包含当前目录
export PATH="$TMPDIR:$PATH"

# 写一个简单的 web 服务器脚本
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
            info = f"RikkaHub Remote Device\nHostname: {socket.gethostname()}\nWorkspace: {WORKSPACE_ID}\nDate: {time.ctime()}\n"
            self.wfile.write(info.encode())
        elif self.path == '/info':
            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.end_headers()
            data = {
                'hostname': socket.gethostname(),
                'workspace': WORKSPACE_ID,
                'arch': os.uname().machine,
                'uptime': time.time(),
                'python': sys.version
            }
            self.wfile.write(json.dumps(data).encode())
        elif self.path == '/env':
            self.send_response(200)
            self.send_header('Content-type', 'text/plain')
            self.end_headers()
            for k, v in sorted(os.environ.items()):
                self.wfile.write(f"{k}={v}\n".encode())
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

server = http.server.HTTPServer(('0.0.0.0', PORT), Handler)
print(f"Server running on port {PORT}", flush=True)
server.serve_forever()
PYEOF

# 启动服务器
echo "[3] Starting web server on port 8888..."
python3 server.py 8888 > /tmp/rikkahub-server.log 2>&1 &
SERVER_PID=$!
sleep 1

# 验证
if curl -s -o /dev/null -w "HTTP %{http_code}\n" http://localhost:8888/ 2>/dev/null; then
    echo "     ✅ Web server OK (PID: $SERVER_PID)"
else
    echo "     ❌ Web server failed"
    exit 1
fi

# 启动 cloudflared tunnel
echo "[4] Starting Cloudflare Tunnel..."
cloudflared tunnel --url http://localhost:8888 --no-autoupdate --protocol http2 > /tmp/rikkahub-cf.log 2>&1 &
CF_PID=$!
echo "     Cloudflared PID: $CF_PID"

# 等待隧道建立
echo "[5] Waiting for tunnel..."
TUNNEL_URL=""
for i in $(seq 1 30); do
    TUNNEL_URL=$(grep -oP 'https?://[a-z0-9-]+\.trycloudflare\.com' /tmp/rikkahub-cf.log 2>/dev/null | head -1)
    if [ -n "$TUNNEL_URL" ]; then
        break
    fi
    echo -n "."
    sleep 1
done
echo ""

if [ -z "$TUNNEL_URL" ]; then
    echo "     ❌ Tunnel failed"
    cat /tmp/rikkahub-cf.log
    exit 1
fi

echo ""
echo "╔════════════════════════════════════════════╗"
echo "║     ✅ TUNNEL READY!                      ║"
echo "╚════════════════════════════════════════════╝"
echo ""
echo "  🔗 URL: $TUNNEL_URL"
echo "  ⚠️  把这个 URL 发给你的 AI 助手"
echo ""
echo "  这个通道会保持 10 分钟"
echo ""

# 保持存活
for i in $(seq 1 20); do
    sleep 30
    echo "[$(date -u +%H:%M:%S)] Alive... ($((600 - i * 30))s remaining)"
done

echo "Tunnel closed"
