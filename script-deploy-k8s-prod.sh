#!/bin/bash
# =============================================================================
# 🚀 script-deploy-k8s-prod.sh
# Atualiza o Pod no Kubernetes de Produção (Hostinger) diretamente do GHCR.
#
# Uso:
#   ./script-deploy-k8s-prod.sh            # Baixa e aplica SEMPRE a versão :latest
#   ./script-deploy-k8s-prod.sh 1.0.5      # Aplica uma versão/tag específica
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
SERVICE_NAME="$(basename "${SCRIPT_DIR}")"
KUBECONFIG_FILE="${PROJECT_ROOT}/docker/keepguard-kubeconfig.yaml"
NAMESPACE="${K8S_NAMESPACE:-keepguard}"
REGISTRY="ghcr.io/keepguard"

# Se o usuário passou um argumento de versão, usa ele; senão usa latest
VERSION="${1:-latest}"
IMAGE_TAG="${REGISTRY}/${SERVICE_NAME}:${VERSION}"

# Cores para terminal
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

# 1. Configurar Kubeconfig
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
echo -e "${CYAN}📌 Versão/Tag :${NC} ${GREEN}${BOLD}${VERSION}${NC}"
echo -e "${CYAN}📌 Imagem GHCR:${NC} ${BOLD}${IMAGE_TAG}${NC}"
echo ""

# 2. Configurar a imagem no deployment
echo -e "${CYAN}🚀 Atualizando deployment/${SERVICE_NAME} para ${IMAGE_TAG}...${NC}"
kubectl set image "deployment/${SERVICE_NAME}" "${SERVICE_NAME}=${IMAGE_TAG}" -n "${NAMESPACE}"

# 3. Se for :latest, dispara rollout restart para forçar o download da imagem mais recente
if [ "$VERSION" = "latest" ]; then
    echo -e "${CYAN}🔄 Disparando rollout restart para baixar a última versão do GitHub...${NC}"
    kubectl rollout restart "deployment/${SERVICE_NAME}" -n "${NAMESPACE}"
fi

# 4. Aguardar o término do Rolling Update
echo -e "${YELLOW}⏳ Aguardando conclusão do rollout em Produção...${NC}"
kubectl rollout status "deployment/${SERVICE_NAME}" -n "${NAMESPACE}" --timeout=180s

echo ""
echo -e "${GREEN}${BOLD}======================================================================${NC}"
echo -e "${GREEN}${BOLD}  ✅ ${SERVICE_NAME} atualizado com sucesso em PRODUÇÃO (${VERSION})! ${NC}"
echo -e "${GREEN}${BOLD}======================================================================${NC}"
