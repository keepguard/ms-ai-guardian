#!/bin/bash
# =============================================================================
# 🚀 script-deploy-github-ms-ai-guardian.sh
# Build Maven, Push (Commit SHA + latest) e Deploy Local do ms-ai-guardian (Spring Boot)
#
# Uso:
#   ./script-deploy-github-ms-ai-guardian.sh up       # Commit, Push GHCR + Sobe no Docker Local
#   ./script-deploy-github-ms-ai-guardian.sh          # Commit, Push GHCR (sem subir local)
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
SERVICE_NAME="ms-ai-guardian"
DOCKER_COMPOSE_DIR="${PROJECT_ROOT}/docker"
DOCKER_COMPOSE_FILE="${DOCKER_COMPOSE_DIR}/docker-compose.yml"
DOCKERFILE_PATH="${SCRIPT_DIR}/Dockerfile"
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

DEPLOY_DOCKER=false
for arg in "$@"; do
    if [ "$arg" = "up" ]; then
        DEPLOY_DOCKER=true
    fi
done

cd "${SCRIPT_DIR}"

# 1. Commit e Push no Git se houver alterações
log_step "1/5 Verificando repositório Git..."
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    git add -A
    if ! git diff --cached --quiet; then
        log_info "Criando commit com alterações pendentes..."
        git commit -m "feat(${SERVICE_NAME}): deploy ${SERVICE_NAME} $(date +'%Y-%m-%d %H:%M')"
        log_info "Fazendo push para branch main..."
        git push origin main
    else
        log_info "Nenhuma alteração pendente no repositório."
    fi
    VERSION=$(git rev-parse --short HEAD)
else
    log_warn "Diretório não é git. Usando tag 'latest'."
    VERSION="latest"
fi

IMAGE_TAG="${REGISTRY}/${SERVICE_NAME}:${VERSION}"
IMAGE_LATEST="${REGISTRY}/${SERVICE_NAME}:latest"

log_info "============================================"
log_info "  Deploy ${SERVICE_NAME}"
log_info "============================================"
log_info "Commit SHA:    ${VERSION}"
log_info "Deploy Docker: ${DEPLOY_DOCKER}"
log_info "Imagem Tag:    ${IMAGE_TAG}"
log_info "Imagem Latest: ${IMAGE_LATEST}"
log_info "============================================"

# 2. Build Maven
log_step "2/5 Executando Maven clean package..."
mvn clean package -DskipTests

JAR_FILE=$(ls -1 target/${SERVICE_NAME}-*.jar target/*.jar 2>/dev/null | grep -v '\-sources\.jar' | grep -v '\-javadoc\.jar' | head -n 1)
if [ -z "${JAR_FILE}" ] || [ ! -f "${JAR_FILE}" ]; then
    log_error "JAR não encontrado em target/"
    exit 1
fi
log_success "JAR compilado com sucesso: $(basename "$JAR_FILE")"

# 3. Prepara Dockerfile com o JAR correto
log_step "3/5 Preparando Dockerfile e construindo imagem Docker (linux/amd64)..."
cp "${DOCKERFILE_PATH}" "${DOCKERFILE_PATH}.bak"
trap 'mv "${DOCKERFILE_PATH}.bak" "${DOCKERFILE_PATH}" 2>/dev/null || true' EXIT

sed -E "s|COPY target/.*\.jar app\.jar|COPY ${JAR_FILE} app.jar|g" "${DOCKERFILE_PATH}.bak" > "${DOCKERFILE_PATH}"
docker build --platform linux/amd64 -t "${IMAGE_TAG}" -t "${IMAGE_LATEST}" .
mv "${DOCKERFILE_PATH}.bak" "${DOCKERFILE_PATH}"
log_success "Imagem Docker construída com sucesso"

# 4. Push para GitHub Container Registry
log_step "4/5 Fazendo push para o GitHub Container Registry..."
docker push "${IMAGE_TAG}"
docker push "${IMAGE_LATEST}"
log_success "Push concluído para as tags :${VERSION} e :latest"

# 5. Atualização e Deploy Docker Compose Local (se 'up')
if [ "$DEPLOY_DOCKER" = true ]; then
    log_step "5/5 Atualizando docker-compose.yml e subindo container local..."
    if [ -f "$DOCKER_COMPOSE_FILE" ]; then
        if [[ "$OSTYPE" == "darwin"* ]]; then
            sed -i '' "s|image: ${REGISTRY}/${SERVICE_NAME}:.*|image: ${IMAGE_TAG}|g" "${DOCKER_COMPOSE_FILE}"
        else
            sed -i "s|image: ${REGISTRY}/${SERVICE_NAME}:.*|image: ${IMAGE_TAG}|g" "${DOCKER_COMPOSE_FILE}"
        fi
        log_info "docker-compose.yml atualizado para ${IMAGE_TAG}"
    fi

    cd "${DOCKER_COMPOSE_DIR}"
    docker compose pull "${SERVICE_NAME}" || true
    docker compose up -d --force-recreate "${SERVICE_NAME}"
    log_success "Container ${SERVICE_NAME} recriado com sucesso no Docker local!"
else
    log_step "5/5 Deploy Docker local ignorado (use 'up' para subir local)"
fi

log_success "============================================"
log_success "  Deploy de ${SERVICE_NAME} finalizado com sucesso!"
log_success "============================================"
