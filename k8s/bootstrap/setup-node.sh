#!/usr/bin/env bash
# ==============================================================================
# ClassCord k3s 節點建置腳本
#
# 用途：重建或新增節點時，把「這是一台全新機器 -> 變成叢集節點」這個過程
# 完整記錄下來，取代散落在操作歷史裡的手動指令。
#
# 不會被任何 CI/CD 自動執行，只在需要重建/新增節點時手動在該台 Droplet 上執行。
# ==============================================================================
set -euo pipefail

usage() {
  cat <<EOF
用法：
  在 Droplet 1（主節點）執行：
    K3S_TOKEN=<自訂一組 token> ./setup-node.sh server

  在 Droplet 2（工作節點）執行：
    K3S_URL=https://\${DROPLET1_PRIVATE_IP}:6443 K3S_TOKEN=<跟 server 相同的 token> ./setup-node.sh agent

環境變數：
  DROPLET1_PRIVATE_IP   Droplet 1 的 VPC 內網 IP（預設 10.104.0.4）
  DROPLET2_PRIVATE_IP   Droplet 2 的 VPC 內網 IP（預設 10.104.0.2）
  K3S_TOKEN             叢集共用 token，server/agent 必須一致，不要寫死在這支腳本裡
  K3S_URL               只有 agent 需要，指向 server 的 6443 埠

前置條件（DigitalOcean Cloud Firewall）：
  兩台 Droplet 之間的 VPC 內網要放行 UDP 8472（flannel VXLAN 用），
  否則跨節點的 Pod 網路不通。這是雲端防火牆設定，不在這支腳本的範圍內，
  記得手動確認。
EOF
}

ROLE="${1:-}"
DROPLET1_PRIVATE_IP="${DROPLET1_PRIVATE_IP:-10.104.0.4}"
DROPLET2_PRIVATE_IP="${DROPLET2_PRIVATE_IP:-10.104.0.2}"

case "$ROLE" in
  server)
    : "${K3S_TOKEN:?請設定 K3S_TOKEN}"
    # --node-ip：指定 VPC 內網 IP，不是公開 IP，否則節點間會嘗試走公網互通
    # --flannel-iface=eth1：DigitalOcean 的 Droplet 有兩張網卡，eth0 是公網、
    #   eth1 才是內網 VPC，flannel 預設會猜錯網卡，導致跨節點 Pod 網路不通
    # --disable=traefik --disable=servicelb：k3s 內建的這兩個元件會搶佔
    #   80/443 port，跟主機本身的 nginx 衝突，一律停用
    # --node-label=workload=main：main-service/gateway/nacos 都靠這個
    #   nodeSelector 釘在這個節點，label 沒打上去它們會永遠 Pending
    curl -sfL https://get.k3s.io | K3S_TOKEN="$K3S_TOKEN" sh -s - server \
      --node-ip="$DROPLET1_PRIVATE_IP" \
      --flannel-iface=eth1 \
      --disable=traefik \
      --disable=servicelb \
      --node-label=workload=main
    ;;

  agent)
    : "${K3S_TOKEN:?請設定 K3S_TOKEN}"
    : "${K3S_URL:?請設定 K3S_URL，例如 https://${DROPLET1_PRIVATE_IP}:6443}"
    # ai-service 靠 workload=ai 這個 nodeSelector 釘在這個節點
    curl -sfL https://get.k3s.io | K3S_URL="$K3S_URL" K3S_TOKEN="$K3S_TOKEN" sh -s - agent \
      --node-ip="$DROPLET2_PRIVATE_IP" \
      --flannel-iface=eth1 \
      --node-label=workload=ai
    ;;

  relabel)
    # 節點已經是叢集一員、只是忘了打 label 時用這個，不用重裝 k3s。
    # --overwrite 讓這個指令可以重複執行，不會因為 label 已存在而報錯。
    : "${NODE_NAME:?請設定 NODE_NAME，例如 droplet1}"
    : "${WORKLOAD_LABEL:?請設定 WORKLOAD_LABEL，例如 main 或 ai}"
    sudo k3s kubectl label node "$NODE_NAME" "workload=$WORKLOAD_LABEL" --overwrite
    ;;

  *)
    usage
    exit 1
    ;;
esac
