#!/bin/bash
# VCP Network Auto-Switcher
# 自动检测 Win Hub 是否在局域网内，切换 hosts 解析
# 使用方式：
#   手动运行：bash vcp-network-switcher.sh
#   自动运行：可配置为 cron 任务或 launchd 服务

VCP_DOMAIN="mywinvcp.iepose.cn"
WIN_LAN_IP="192.168.3.150"
HOSTS_FILE="/etc/hosts"
HOSTS_TAG="# VCP-AUTO-SWITCH"
PING_TIMEOUT=1  # 秒

# 检测 Win Hub 是否在局域网可达
check_lan() {
    ping -c 1 -W $PING_TIMEOUT "$WIN_LAN_IP" > /dev/null 2>&1
    return $?
}

# 获取当前 hosts 状态
get_current_mode() {
    if grep -q "$HOSTS_TAG" "$HOSTS_FILE" 2>/dev/null; then
        echo "lan"
    else
        echo "wan"
    fi
}

# 切换到局域网模式
switch_to_lan() {
    local current=$(get_current_mode)
    if [ "$current" = "lan" ]; then
        echo "✅ 已经是局域网模式 ($WIN_LAN_IP)"
        return 0
    fi
    echo "🔄 切换到局域网模式: $VCP_DOMAIN → $WIN_LAN_IP"
    echo "$WIN_LAN_IP  $VCP_DOMAIN  $HOSTS_TAG" | sudo tee -a "$HOSTS_FILE" > /dev/null
    # 刷新 DNS 缓存
    sudo dscacheutil -flushcache
    sudo killall -HUP mDNSResponder 2>/dev/null
    echo "✅ 已切换到局域网模式"
}

# 切换到公网模式（走内网穿透）
switch_to_wan() {
    local current=$(get_current_mode)
    if [ "$current" = "wan" ]; then
        echo "✅ 已经是公网模式 (内网穿透)"
        return 0
    fi
    echo "🔄 切换到公网模式: $VCP_DOMAIN → DNS 解析"
    sudo sed -i '' "/$HOSTS_TAG/d" "$HOSTS_FILE"
    # 刷新 DNS 缓存
    sudo dscacheutil -flushcache
    sudo killall -HUP mDNSResponder 2>/dev/null
    echo "✅ 已切换到公网模式"
}

# 自动检测并切换
auto_switch() {
    if check_lan; then
        echo "📶 Win Hub 在局域网可达 ($WIN_LAN_IP)"
        switch_to_lan
    else
        echo "🌐 Win Hub 不在局域网，使用内网穿透"
        switch_to_wan
    fi
}

# 主逻辑
case "${1:-auto}" in
    lan)
        switch_to_lan
        ;;
    wan)
        switch_to_wan
        ;;
    auto)
        auto_switch
        ;;
    status)
        current=$(get_current_mode)
        echo "当前模式: $current"
        echo "域名: $VCP_DOMAIN"
        if [ "$current" = "lan" ]; then
            echo "解析: $WIN_LAN_IP (局域网)"
        else
            echo "解析: DNS (内网穿透)"
        fi
        if check_lan; then
            echo "局域网: ✅ 可达"
        else
            echo "局域网: ❌ 不可达"
        fi
        ;;
    *)
        echo "用法: $0 {auto|lan|wan|status}"
        echo "  auto   - 自动检测并切换（默认）"
        echo "  lan    - 强制局域网模式"
        echo "  wan    - 强制公网模式"
        echo "  status - 查看当前状态"
        ;;
esac
