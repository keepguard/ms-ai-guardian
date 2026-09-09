#!/bin/bash
# =============================================================================
# 🚀 script-deploy-k8s-prod.sh
# Atualiza o Pod no Kubernetes de Produção (Hostinger) diretamente do GHCR.
#
# Uso:
#   ./script-deploy-k8s-prod.sh            # Auto-detecta o último Commit SHA local
#   ./script-deploy-k8s-prod.sh <tag>      # Aplica uma versão/tag específica
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
SERVICE_NAME="$(basename "${SCRIPT_DIR}")"
KUBECONFIG_FILE="${PROJECT_ROOT}/docker/keepguard-kubeconfig.yaml"
NAMESPACE="${K8S_NAMESPACE:-keepguard}"
REGISTRY="ghcr.io/keepguard"

if [ -n "${1:-}" ]; then
    VERSION="$1"
elif git -C "${SCRIPT_DIR}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    VERSION=$(git -C "${SCRIPT_DIR}" rev-parse --short HEAD)
else
    VERSION="latest"
fi
IMAGE_TAG="${REGISTRY}/${SERVICE_NAME}:${VERSION}"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

echo -e "${BLUE}${BOLD}======================================================================${NC}"
echo -e "${BLUE}${BOLD}    🛡️  KeepGuard — Deploy Produção K8s — ${SERVICE_NAME}            ${NC}"
echo -e "${BLUE}${BOLD}======================================================================${NC}"

if [ -f "$KUBECONFIG_FILE" ]; then
    export KUBECONFIG="$KUBECONFIG_FILE"
elif [ -f "$HOME/.kube/config" ]; then
    export KUBECONFIG="$HOME/.kube/config"
fi

if ! command -v kubectl &> /dev/null; then
    echo -e "${RED}❌ Erro: 'kubectl' não encontrado no PATH.${NC}"
    exit 1
fi

echo -e "${CYAN}📌 Serviço    :${NC} ${BOLD}${SERVICE_NAME}${NC}"
echo -e "${CYAN}📌 Namespace  :${NC} ${BOLD}${NAMESPACE}${NC}"
echo -e "${CYAN}📌 Commit/Tag :${NC} ${GREEN}${BOLD}${VERSION}${NC}"
echo -e "${CYAN}📌 Imagem GHCR:${NC} ${BOLD}${IMAGE_TAG}${NC}"
echo ""

echo -e "${CYAN}🚀 Atualizando deployment/${SERVICE_NAME} para ${IMAGE_TAG}...${NC}"
kubectl set image "deployment/${SERVICE_NAME}" "${SERVICE_NAME}=${IMAGE_TAG}" -n "${NAMESPACE}"

if [ "$VERSION" = "latest" ]; then
    echo -e "${CYAN}🔄 Disparando rollout restart para baixar a última versão do GitHub...${NC}"
    kubectl rollout restart "deployment/${SERVICE_NAME}" -n "${NAMESPACE}"
fi

echo -e "${YELLOW}⏳ Aguardando conclusão do rollout em Produção...${NC}"
kubectl rollout status "deployment/${SERVICE_NAME}" -n "${NAMESPACE}" --timeout=360s

echo ""
echo -e "${GREEN}${BOLD}======================================================================${NC}"
echo -e "${GREEN}${BOLD}  ✅ ${SERVICE_NAME} atualizado com sucesso em PRODUÇÃO (${VERSION})! ${NC}"
echo -e "${GREEN}${BOLD}======================================================================${NC}"
