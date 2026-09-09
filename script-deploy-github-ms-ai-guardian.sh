#!/bin/bash
# =============================================================================
# 🚀 MINI MANUAL DE USO — script-deploy-github-ms-ai-guardian.sh (ms-ai-guardian)
# =============================================================================
# Este script automatiza o ciclo completo de desenvolvimento, build e deploy
# do serviço ms-ai-guardian.
#
# 🌿 FLUXO DE BRANCHES RECOMENDADO:
#   - Por padrão, o trabalho diário ocorre na branch 'develop'.
#   - O script detecta automaticamente em qual branch você está.
#   - Se houver alterações locais não comitadas, cria o commit e faz push na branch ativa.
#
# 📋 OPÇÕES E EXEMPLOS DE USO:
#
#   1. Apenas commit, push e build da imagem da branch atual no GHCR:
#      $ ./script-deploy-github-ms-ai-guardian.sh
#      -> Envia para a branch ativa (ex: develop) e gera as tags:
#         ghcr.io/keepguard/ms-ai-guardian:develop-<sha>
#         ghcr.io/keepguard/ms-ai-guardian:develop-latest
#
#   2. Commit, push, build e deploy imediato no Docker Compose local:
#      $ ./script-deploy-github-ms-ai-guardian.sh up
#      -> Atualiza o docker-compose.yml local para a tag gerada e recria o container.
#
#   3. Commit na branch atual + Merge automático na 'main' (versão Produção):
#      $ ./script-deploy-github-ms-ai-guardian.sh merge main
#      -> Comita na branch atual, faz checkout da 'main', mescla sem necessidade
#         de PR manual, faz push na 'main', constrói as tags de produção
#         (:<sha> e :latest), e retorna com segurança para a branch de trabalho!
#
#   4. Merge na 'main' + deploy imediato no Docker Compose local:
#      $ ./script-deploy-github-ms-ai-guardian.sh merge main up
#
# 🚢 DEPLOY EM PRODUÇÃO (KUBERNETES HOSTINGER):
#   - Após o merge em 'main', aplique a versão no cluster executando:
#      $ ./script-deploy-k8s-prod.sh
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
SERVICE_NAME="ms-ai-guardian"
DOCKER_COMPOSE_DIR="${PROJECT_ROOT}/docker"
DOCKER_COMPOSE_FILE="${DOCKER_COMPOSE_DIR}/docker-compose.yml"
REGISTRY="ghcr.io/keepguard"
# Cores
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

while [[ $# -gt 0 ]]; do
    case "$1" in
        up)
            DEPLOY_DOCKER=true
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
    log_error "Diretório não é um repositório git válido."
    exit 1
fi

CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)

# 1. Commit e Push na branch ativa
log_step "1/5 Verificando repositório Git na branch '${CURRENT_BRANCH}'..."
git add -A
if ! git diff --cached --quiet; then
    log_info "Criando commit com alterações pendentes..."
    git commit -m "feat(${SERVICE_NAME}): update ${SERVICE_NAME} $(date +'%Y-%m-%d %H:%M')"
    log_info "Fazendo push para branch '${CURRENT_BRANCH}'..."
    git push origin "${CURRENT_BRANCH}"
else
    log_info "Nenhuma alteração pendente na branch '${CURRENT_BRANCH}'."
fi
LOCAL_SHA=$(git rev-parse --short HEAD)

# 2. Se solicitado merge para outra branch (ex: merge main)
if [ -n "$MERGE_TARGET" ] && [ "$MERGE_TARGET" != "$CURRENT_BRANCH" ]; then
    log_step "Promovendo branch '${CURRENT_BRANCH}' para '${MERGE_TARGET}'..."
    git checkout "${MERGE_TARGET}"
    git pull origin "${MERGE_TARGET}" --rebase || true
    git merge "${CURRENT_BRANCH}" -m "chore(merge): merge branch '${CURRENT_BRANCH}' into ${MERGE_TARGET}"
    git push origin "${MERGE_TARGET}"
    BUILD_SHA=$(git rev-parse --short HEAD)
    BUILD_BRANCH="${MERGE_TARGET}"
    log_info "Retornando checkout com segurança para a branch de trabalho '${CURRENT_BRANCH}'..."
    git checkout "${CURRENT_BRANCH}"
else
    BUILD_SHA="${LOCAL_SHA}"
    BUILD_BRANCH="${CURRENT_BRANCH}"
fi

# 3. Definição das tags do GHCR
if [ "$BUILD_BRANCH" = "main" ]; then
    PRIMARY_TAG="${REGISTRY}/${SERVICE_NAME}:${BUILD_SHA}"
    LATEST_TAG="${REGISTRY}/${SERVICE_NAME}:latest"
    BRANCH_TAG="${REGISTRY}/${SERVICE_NAME}:main-${BUILD_SHA}"
    BRANCH_LATEST="${REGISTRY}/${SERVICE_NAME}:main-latest"
else
    PRIMARY_TAG="${REGISTRY}/${SERVICE_NAME}:${BUILD_BRANCH}-${BUILD_SHA}"
    LATEST_TAG="${REGISTRY}/${SERVICE_NAME}:${BUILD_BRANCH}-latest"
    BRANCH_TAG="${PRIMARY_TAG}"
    BRANCH_LATEST="${LATEST_TAG}"
fi

log_info "============================================"
log_info "  Deploy ${SERVICE_NAME}"
log_info "============================================"
log_info "Branch de Trabalho : ${CURRENT_BRANCH}"
log_info "Branch de Build    : ${BUILD_BRANCH}"
log_info "Commit SHA         : ${BUILD_SHA}"
log_info "Deploy Docker Local: ${DEPLOY_DOCKER}"
log_info "Imagem Tag         : ${PRIMARY_TAG}"
log_info "Imagem Latest      : ${LATEST_TAG}"
log_info "============================================"
# 2. Build Maven (Java 25)
log_step "2/5 Executando Maven clean package..."
mvn clean package -DskipTests

JAR_FILE=$(ls -1 target/${SERVICE_NAME}-*.jar target/*.jar 2>/dev/null | grep -v '\-sources\.jar' | grep -v '\-javadoc\.jar' | head -n 1)
if [ -z "${JAR_FILE}" ] || [ ! -f "${JAR_FILE}" ]; then
    log_error "JAR não encontrado em target/"
    exit 1
fi
log_success "JAR compilado com sucesso: $(basename "$JAR_FILE")"

# 3. Build da Imagem Docker (linux/amd64)
log_step "3/5 Preparando Dockerfile e construindo imagem Docker (linux/amd64)..."
DOCKERFILE_PATH="${SCRIPT_DIR}/Dockerfile"
cp "${DOCKERFILE_PATH}" "${DOCKERFILE_PATH}.bak"
trap 'mv "${DOCKERFILE_PATH}.bak" "${DOCKERFILE_PATH}" 2>/dev/null || true' EXIT

sed -E "s|COPY target/.*\.jar app\.jar|COPY ${JAR_FILE} app.jar|g" "${DOCKERFILE_PATH}.bak" > "${DOCKERFILE_PATH}"

if [ "$BUILD_BRANCH" = "main" ]; then
    docker build --platform linux/amd64 -t "${PRIMARY_TAG}" -t "${LATEST_TAG}" -t "${BRANCH_TAG}" -t "${BRANCH_LATEST}" .
else
    docker build --platform linux/amd64 -t "${PRIMARY_TAG}" -t "${LATEST_TAG}" .
fi
mv "${DOCKERFILE_PATH}.bak" "${DOCKERFILE_PATH}"
log_success "Imagem Docker construída com sucesso"

# 4. Push para GitHub Container Registry
log_step "4/5 Fazendo push para o GitHub Container Registry..."
docker push "${PRIMARY_TAG}"
docker push "${LATEST_TAG}"
if [ "$BUILD_BRANCH" = "main" ]; then
    docker push "${BRANCH_TAG}"
    docker push "${BRANCH_LATEST}"
fi
log_success "Push concluído para as tags do GHCR"
# 5. Atualização e Deploy Docker Compose Local (se solicitado 'up')
if [ "$DEPLOY_DOCKER" = true ]; then
    log_step "Atualizando docker-compose.yml e subindo container local..."
    if [ -f "$DOCKER_COMPOSE_FILE" ]; then
        if [[ "$OSTYPE" == "darwin"* ]]; then
            sed -i '' "s|image: ${REGISTRY}/${SERVICE_NAME}:.*|image: ${PRIMARY_TAG}|g" "${DOCKER_COMPOSE_FILE}"
        else
            sed -i "s|image: ${REGISTRY}/${SERVICE_NAME}:.*|image: ${PRIMARY_TAG}|g" "${DOCKER_COMPOSE_FILE}"
        fi
        log_info "docker-compose.yml atualizado para ${PRIMARY_TAG}"
    fi

    cd "${DOCKER_COMPOSE_DIR}"
    docker compose pull "${SERVICE_NAME}" || true
    docker compose up -d --force-recreate "${SERVICE_NAME}"
    log_success "Container ${SERVICE_NAME} recriado com sucesso no Docker local!"
else
    log_step "Deploy Docker local ignorado (use './${SCRIPT_NAME:-script-deploy.sh} up' para subir local)"
fi

log_success "============================================"
log_success "  Deploy de ${SERVICE_NAME} finalizado com sucesso!"
log_success "============================================"
