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

# 2b. Recursos, JVM e probes (boot Java 25 não cabe em 512Mi + Xmx512m; Ollama não pode comer 2 CPUs)
echo -e "${CYAN}⚙️  Ajustando CPU/memória, JAVA_OPTS, profile prod e probes...${NC}"
kubectl set resources "deployment/${SERVICE_NAME}" -n "${NAMESPACE}" \
  --requests=cpu=250m,memory=512Mi \
  --limits=cpu=1,memory=1Gi

kubectl set env "deployment/${SERVICE_NAME}" -n "${NAMESPACE}" \
  SPRING_PROFILES_ACTIVE=prod \
  JAVA_OPTS='-XX:MaxRAMPercentage=70.0 -XX:InitialRAMPercentage=25.0 -XX:+UseG1GC -XX:+UseContainerSupport -XX:+ExitOnOutOfMemoryError' \
  APP_GUARDIAN_ANTHROPIC_ENABLED=false \
  APP_GUARDIAN_LLM_TIMEOUT_SECONDS=45 \
  APP_GUARDIAN_LLM_CODEGEN_TIMEOUT_SECONDS=90 \
  APP_GUARDIAN_LLM_MAX_TOKENS=4096

if kubectl get secret keepguard-openai -n "${NAMESPACE}" >/dev/null 2>&1; then
  echo -e "${CYAN}⚙️  LLM: OpenAI (secret keepguard-openai)${NC}"
  kubectl set env "deployment/${SERVICE_NAME}" -n "${NAMESPACE}" --from=secret/keepguard-openai
  kubectl set env "deployment/${SERVICE_NAME}" -n "${NAMESPACE}" \
    APP_GUARDIAN_LLM_PROVIDER=openai \
    APP_GUARDIAN_OLLAMA_ENABLED=false \
    APP_GUARDIAN_OPENAI_ENABLED=true \
    SPRING_AI_OPENAI_MODEL=gpt-4o-mini
else
  echo -e "${YELLOW}⚙️  LLM: Ollama (secret keepguard-openai ausente)${NC}"
  kubectl set env "deployment/${SERVICE_NAME}" -n "${NAMESPACE}" \
    APP_GUARDIAN_LLM_PROVIDER=ollama \
    APP_GUARDIAN_OLLAMA_ENABLED=true \
    APP_GUARDIAN_OPENAI_ENABLED=false \
    APP_GUARDIAN_LLM_MAX_TOKENS=256
fi

kubectl patch "deployment/${SERVICE_NAME}" -n "${NAMESPACE}" --type=strategic -p "$(cat <<'EOF'
spec:
  replicas: 1
  template:
    spec:
      containers:
      - name: ms-ai-guardian
        startupProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8088
          initialDelaySeconds: 20
          periodSeconds: 10
          failureThreshold: 36
          timeoutSeconds: 3
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8088
          periodSeconds: 20
          failureThreshold: 3
          timeoutSeconds: 3
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8088
          periodSeconds: 10
          failureThreshold: 3
          timeoutSeconds: 3
EOF
)"

# Não altera o deployment do Ollama: um replica novo não cabe neste nó.

# 3. Se for :latest, dispara rollout restart para forçar o download da imagem mais recente
if [ "$VERSION" = "latest" ]; then
    echo -e "${CYAN}🔄 Disparando rollout restart para baixar a última versão do GitHub...${NC}"
    kubectl rollout restart "deployment/${SERVICE_NAME}" -n "${NAMESPACE}"
fi

# 4. Aguardar o término do Rolling Update
echo -e "${YELLOW}⏳ Aguardando conclusão do rollout em Produção...${NC}"
kubectl rollout status "deployment/${SERVICE_NAME}" -n "${NAMESPACE}" --timeout=420s

echo ""
echo -e "${GREEN}${BOLD}======================================================================${NC}"
echo -e "${GREEN}${BOLD}  ✅ ${SERVICE_NAME} atualizado com sucesso em PRODUÇÃO (${VERSION})! ${NC}"
echo -e "${GREEN}${BOLD}======================================================================${NC}"
