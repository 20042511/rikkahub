package me.rerere.workspace

/**
 * 可用的 Linux 发行版配置
 *
 * 每个发行版包含：
 * - 名称和描述
 * - Rootfs 下载 URL
 * - 安装后执行的开发工具引导脚本
 * - 包管理器类型
 */
data class DistroConfig(
    val id: String,
    val name: String,
    val description: String,
    val rootfsUrl: String,
    val packageManager: String,
    val bootstrapScript: String,
    /** 基础体积提示 */
    val baseSizeHint: String,
    /** 是否推荐用于开发 */
    val recommendedForDev: Boolean = false,
) {
    companion object {
        /** Ubuntu 24.04 LTS (ARM64) - 当前默认 */
        val UBUNTU_24_04 = DistroConfig(
            id = "ubuntu-24.04",
            name = "Ubuntu 24.04 LTS",
            description = "Ubuntu 24.04 LTS (Noble Numbat) base system. Compatible with most tools via apt.",
            rootfsUrl = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz",
            packageManager = "apt",
            bootstrapScript = UBUNTU_BOOTSTRAP,
            baseSizeHint = "~30MB base + ~200MB with dev tools",
            recommendedForDev = true,
        )

        /** Debian Bookworm (ARM64) */
        val DEBIAN_BOOKWORM = DistroConfig(
            id = "debian-bookworm",
            name = "Debian Bookworm",
            description = "Debian 12 (Bookworm). Stable, lightweight, excellent proot compatibility. Recommended for development.",
            rootfsUrl = "https://github.com/20042511/rikkahub/releases/download/rootfs/debian-bookworm-arm64.tar.gz",
            packageManager = "apt",
            bootstrapScript = DEBIAN_BOOTSTRAP,
            baseSizeHint = "~25MB base + ~180MB with dev tools",
            recommendedForDev = true,
        )

        /** 所有可用发行版 */
        val ALL: List<DistroConfig> = listOf(
            UBUNTU_24_04,
            DEBIAN_BOOKWORM,
        )

        /** 默认发行版 */
        val DEFAULT: DistroConfig = DEBIAN_BOOKWORM

        fun fromId(id: String): DistroConfig? = ALL.find { it.id == id }
    }
}

/**
 * Ubuntu 24.04 开发工具引导脚本
 *
 * 在 rootfs 安装完成后执行，安装常用开发工具。
 */
private val UBUNTU_BOOTSTRAP = """
# 配置 apt 国内镜像（加速）
cat > /etc/apt/sources.list << 'EOF'
deb http://archive.ubuntu.com/ubuntu/ noble main restricted universe multiverse
deb http://archive.ubuntu.com/ubuntu/ noble-updates main restricted universe multiverse
deb http://archive.ubuntu.com/ubuntu/ noble-security main restricted universe multiverse
EOF

# 更新包列表
apt-get update -qq

# 安装基础开发工具（静默安装）
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
    bash-completion \
    ca-certificates \
    curl \
    git \
    jq \
    nano \
    python3 \
    python3-pip \
    tree \
    unzip \
    vim \
    wget \
    2>/dev/null || true

# 清理缓存减少空间
apt-get clean
rm -rf /var/lib/apt/lists/*

# 配置 Git
git config --global user.name "RikkaHub Agent"
git config --global user.email "agent@rikkahub"

# 设置 PS1 提示符
echo 'export PS1="\\w \\$ "' >> /root/.bashrc
echo 'alias ll="ls -la"' >> /root/.bashrc
echo 'export EDITOR=vim' >> /root/.bashrc

# 创建必要目录
mkdir -p /workspace /tmp /tool_outputs

echo "Ubuntu bootstrap complete"
""".trimIndent()

/**
 * Debian Bookworm 开发工具引导脚本
 *
 * 比 Ubuntu 更轻量，稳定性更好，apt 生态完整。
 */
private val DEBIAN_BOOTSTRAP = """
# 配置 apt 源
cat > /etc/apt/sources.list << 'EOF'
deb http://deb.debian.org/debian/ bookworm main contrib non-free non-free-firmware
deb http://deb.debian.org/debian/ bookworm-updates main contrib non-free non-free-firmware
deb http://deb.debian.org/debian-security/ bookworm-security main contrib non-free non-free-firmware
EOF

# 更新包列表
apt-get update -qq

# 安装基础开发工具
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
    bash-completion \
    ca-certificates \
    curl \
    git \
    jq \
    nano \
    python3 \
    python3-pip \
    tree \
    unzip \
    vim \
    wget \
    2>/dev/null || true

# 清理缓存
apt-get clean
rm -rf /var/lib/apt/lists/*

# 配置 Git
git config --global user.name "RikkaHub Agent"
git config --global user.email "agent@rikkahub"

# Shell 配置
echo 'export PS1="\\w \\$ "' >> /root/.bashrc
echo 'alias ll="ls -la"' >> /root/.bashrc
echo 'export EDITOR=vim' >> /root/.bashrc

# 必要目录
mkdir -p /workspace /tmp /tool_outputs

echo "Debian bootstrap complete"
""".trimIndent()
