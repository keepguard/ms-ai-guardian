#!/bin/bash
# =============================================================================
# 🚀 MINI MANUAL DE USO — script-deploy-github-ms-ai-guardian.sh
# =============================================================================
#
#   1. Apenas commit e push na branch atual (ex: develop):
#      $ ./script-deploy-github-ms-ai-guardian.sh
#
#   2. Commit, push na branch atual + Merge para 'main':
#      $ ./script-deploy-github-ms-ai-guardian.sh merge main
#
#   3. PRODUÇÃO (Commit + Push + Merge para 'main' + Build JAR/Docker linux/amd64 + Push GHCR + Deploy K8s):
#      $ ./script-deploy-github-ms-ai-guardian.sh prod
#
#   4. Opcional: Subir container no Docker Compose local:
#      $ ./script-deploy-github-ms-ai-guardian.sh up
#
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_NAME="ms-ai-guardian"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
DOCKER_COMPOSE_DIR="${PROJECT_ROOT}/docker"
DOCKER_COMPOSE_FILE="${DOCKER_COMPOSE_DIR}/docker-compose.yml"
KUBECONFIG_FILE="${PROJECT_ROOT}/docker/keepguard-kubeconfig.yaml"
NAMESPACE="${K8S_NAMESPACE:-keepguard}"
REGISTRY="ghcr.io/keepguard"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }
log_step() { echo -e "${BLUE}[STEP]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }

MERGE_TARGET=""
DEPLOY_DOCKER=false
TRIGGER_PROD=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        up)
            DEPLOY_DOCKER=true
            shift
            ;;
        prod)
            MERGE_TARGET="main"
            TRIGGER_PROD=true
            shift
            ;;
        merge)
            MERGE_TARGET="${2:-main}"
            shift 2
            ;;
        *)
            shift
            ;;
    esac
done

cd "${SCRIPT_DIR}"

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    log_error "Diretório não é um repositório git."
    exit 1
fi

CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
log_step "1/3 Verificando repositório Git na branch '${CURRENT_BRANCH}'..."
git add -A
if ! git diff --cached --quiet; then
    log_info "Criando commit com alterações pendentes..."
    git commit -m "feat(${SERVICE_NAME}): update ${SERVICE_NAME} $(date +'%Y-%m-%d %H:%M')"
    log_info "Fazendo push para branch '${CURRENT_BRANCH}'..."
    git push origin "${CURRENT_BRANCH}"
else
    log_info "Nenhuma alteração pendente na branch '${CURRENT_BRANCH}'."
    git push origin "${CURRENT_BRANCH}" || true
fi

LOCAL_SHA=$(git rev-parse --short HEAD)

if [ -n "$MERGE_TARGET" ] && [ "$MERGE_TARGET" != "$CURRENT_BRANCH" ]; then
    log_step "2/3 Promovendo branch '${CURRENT_BRANCH}' para '${MERGE_TARGET}'..."
    git checkout "${MERGE_TARGET}"
    git pull origin "${MERGE_TARGET}" --rebase || true
    git merge "${CURRENT_BRANCH}" -m "chore(merge): merge branch '${CURRENT_BRANCH}' into ${MERGE_TARGET}"
    git push origin "${MERGE_TARGET}"
    BUILD_SHA=$(git rev-parse --short HEAD)
    log_info "Retornando checkout com segurança para '${CURRENT_BRANCH}'..."
    git checkout "${CURRENT_BRANCH}"
else
    BUILD_SHA="${LOCAL_SHA}"
fi

echo
log_info "============================================"
log_info "  Status ${SERVICE_NAME}"
log_info "============================================"
log_info "Branch de Trabalho : ${CURRENT_BRANCH}"
log_info "Commit SHA         : ${BUILD_SHA}"
log_info "Deploy Produção    : ${TRIGGER_PROD}"
log_info "Deploy Local (up)  : ${DEPLOY_DOCKER}"
log_info "============================================"
echo

if [ "$DEPLOY_DOCKER" = true ]; then
    log_step "Compilando JAR localmente e recriando container Docker local..."
    mvn clean package -DskipTests
    if command -v docker >/dev/null 2>&1; then
        DOCKER_BUILDKIT=1 docker build -f Dockerfile -t "${REGISTRY}/${SERVICE_NAME}:local" .
        if [ -d "${DOCKER_COMPOSE_DIR}" ] && [ -f "${DOCKER_COMPOSE_FILE}" ]; then
            cd "${DOCKER_COMPOSE_DIR}"
            docker compose up -d --force-recreate "${SERVICE_NAME}" || true
            log_success "Container ${SERVICE_NAME} recriado localmente!"
        fi
    else
        log_warn "Docker não encontrado/ativo para subir localmente."
    fi
fi

if [ "$TRIGGER_PROD" = true ]; then
    log_step "3/3 Pipeline de Produção (Build Local rápido + Deploy K8s)..."
    log_info "Compilando aplicação Java com Maven..."
    mvn clean package -DskipTests

    IMAGE_TAG="${REGISTRY}/${SERVICE_NAME}:${BUILD_SHA}"
    IMAGE_LATEST="${REGISTRY}/${SERVICE_NAME}:latest"
    IMAGE_MAIN_LATEST="${REGISTRY}/${SERVICE_NAME}:main-latest"

    log_info "Construindo imagem Docker para linux/amd64 (BuildKit)..."
    DOCKER_BUILDKIT=1 docker build \
        --platform linux/amd64 \
        -f Dockerfile \
        -t "${IMAGE_TAG}" \
        -t "${IMAGE_LATEST}" \
        -t "${IMAGE_MAIN_LATEST}" .

    log_info "Publicando imagem no GHCR (${IMAGE_TAG})..."
    docker push "${IMAGE_TAG}"
    docker push "${IMAGE_LATEST}"
    docker push "${IMAGE_MAIN_LATEST}"

    if [ -f "$KUBECONFIG_FILE" ]; then
        export KUBECONFIG="$KUBECONFIG_FILE"
    elif [ -f "$HOME/.kube/config" ]; then
        export KUBECONFIG="$HOME/.kube/config"
    fi

    if command -v kubectl >/dev/null 2>&1; then
        log_step "Atualizando Kubernetes Produção (${NAMESPACE})..."
        kubectl set image "deployment/${SERVICE_NAME}" "${SERVICE_NAME}=${IMAGE_TAG}" -n "${NAMESPACE}"
        log_info "Aguardando rollout em produção..."
        kubectl rollout status "deployment/${SERVICE_NAME}" -n "${NAMESPACE}" --timeout=300s
        log_success "Deploy de ${SERVICE_NAME} em produção concluído com sucesso!"
    else
        log_warn "kubectl não encontrado. Imagem publicada com sucesso no GHCR."
    fi
fi

log_success "============================================"
log_success "  Operação concluída com sucesso!"
log_success "============================================"
