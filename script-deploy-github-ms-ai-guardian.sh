#!/bin/bash

# =============================================================================
# Script de Deploy Automatizado para Microsserviços Java
# =============================================================================
# Uso:
#   ./script-deploy-github-ms-ai-guardian.sh up             # Incrementa patch no POM + Deploy Docker
#   ./script-deploy-github-ms-ai-guardian.sh 1.0.5 up       # Define versão explícita + Deploy Docker
#   ./script-deploy-github-ms-ai-guardian.sh                # Incrementa patch no POM + GitHub Registry apenas
#   ./script-deploy-github-ms-ai-guardian.sh --current up   # Mantém versão atual do POM + Deploy Docker
# =============================================================================

set -e

SERVICE_NAME="ms-ai-guardian"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
POM_FILE="${SCRIPT_DIR}/pom.xml"
DOCKER_COMPOSE_FILE="${PROJECT_ROOT}/docker/docker-compose.yml"
DOCKERFILE_PATH="${SCRIPT_DIR}/Dockerfile"
TARGET_DIR="${SCRIPT_DIR}/target"

# Cores para output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }
log_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
log_step() { echo -e "${CYAN}[STEP]${NC} $1"; }

# Extrair versão atual do POM
get_current_version() {
    grep -A 5 "<artifactId>${SERVICE_NAME}</artifactId>" "$POM_FILE" | grep "<version>" | head -1 | sed 's/.*<version>\(.*\)<\/version>.*/\1/' | xargs
}

# Incrementar versão patch (+1)
increment_version() {
    local version=$1
    local major=$(echo "$version" | cut -d. -f1)
    local minor=$(echo "$version" | cut -d. -f2)
    local patch_part=$(echo "$version" | cut -d. -f3)
    local patch=$(echo "$patch_part" | cut -d- -f1)
    local suffix=$(echo "$version" | grep -o -- "-.*" || true)

    patch=$((patch + 1))
    if [ -n "$suffix" ]; then
        echo "${major}.${minor}.${patch}${suffix}"
    else
        echo "${major}.${minor}.${patch}"
    fi
}

# Atualizar versão no pom.xml
update_pom_version() {
    local new_version=$1
    log_info "Atualizando POM para versão: ${new_version}"

    if [[ "$OSTYPE" == "darwin"* ]]; then
        sed -i '' "/<artifactId>${SERVICE_NAME}<\/artifactId>/,/<version>/s|<version>.*</version>|<version>${new_version}</version>|" "$POM_FILE"
    else
        sed -i "/<artifactId>${SERVICE_NAME}<\/artifactId>/,/<version>/s|<version>.*</version>|<version>${new_version}</version>|" "$POM_FILE"
    fi

    log_success "POM atualizado para: ${new_version}"
}

# Limpeza e restauração segura do Dockerfile
cleanup() {
    if [ -f "${DOCKERFILE_PATH}.bak" ]; then
        mv "${DOCKERFILE_PATH}.bak" "${DOCKERFILE_PATH}"
    fi
}
trap cleanup EXIT INT TERM


# Commita e faz push das alterações do repositório do serviço após o release
commit_and_push_release() {
    local release_version=$1
    local repo_dir=${2:-"${SCRIPT_DIR}"}

    log_step "Commit e push das alterações (Release ${release_version})..."

    if ! git -C "${repo_dir}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        log_warning "Diretório não é um repositório git: ${repo_dir}. Pulando commit/push."
        return 0
    fi

    pushd "${repo_dir}" > /dev/null

    git add -A
    if git diff --cached --quiet; then
        log_info "Nenhuma alteração pendente para commit."
        popd > /dev/null
        return 0
    fi

    if ! git commit -m "$(cat <<EOF
Release ${release_version}

EOF
)"; then
        log_error "Falha ao criar commit do release ${release_version}"
        popd > /dev/null
        return 1
    fi

    if ! git push; then
        log_error "Falha ao fazer push do release ${release_version}"
        popd > /dev/null
        return 1
    fi

    log_success "Commit e push concluídos (Release ${release_version})"
    popd > /dev/null
    return 0
}

# Analisar parâmetros
DEPLOY_DOCKER=false
TARGET_VERSION=""
KEEP_CURRENT_VERSION=false

for arg in "$@"; do
    if [ "$arg" = "up" ]; then
        DEPLOY_DOCKER=true
    elif [ "$arg" = "--current" ]; then
        KEEP_CURRENT_VERSION=true
    elif [[ "$arg" =~ ^[0-9]+\.[0-9]+ ]]; then
        TARGET_VERSION="$arg"
    fi
done

CURRENT_VERSION=$(get_current_version)
if [ -z "$CURRENT_VERSION" ]; then
    log_error "Não foi possível extrair a versão do pom.xml"
    exit 1
fi

if [ -n "$TARGET_VERSION" ]; then
    VERSION="$TARGET_VERSION"
    update_pom_version "$VERSION"
elif [ "$KEEP_CURRENT_VERSION" = true ]; then
    VERSION="$CURRENT_VERSION"
    log_info "Mantendo versão atual do POM: ${VERSION}"
else
    VERSION=$(increment_version "$CURRENT_VERSION")
    update_pom_version "$VERSION"
fi

REGISTRY="ghcr.io/keepguard"
IMAGE_NAME="${REGISTRY}/${SERVICE_NAME}"
IMAGE_TAG="${IMAGE_NAME}:${VERSION}"
IMAGE_LATEST="${IMAGE_NAME}:latest"

log_info "============================================"
log_info "  Deploy ${SERVICE_NAME}"
log_info "============================================"
log_info "Versão POM:  ${VERSION}"
log_info "Deploy Docker: ${DEPLOY_DOCKER}"
log_info "Imagem Tag:  ${IMAGE_TAG}"
log_info "============================================"

# 1. Build Maven
log_step "1/5 Executando Maven clean package..."
cd "${SCRIPT_DIR}"
mvn clean package -DskipTests

JAR_FILE="${TARGET_DIR}/${SERVICE_NAME}-${VERSION}.jar"
if [ ! -f "$JAR_FILE" ]; then
    log_error "JAR não encontrado: ${JAR_FILE}"
    exit 1
fi
log_success "Build Maven concluído com sucesso: $(basename "$JAR_FILE")"

# 2. Prepara Dockerfile
log_step "2/5 Preparando Dockerfile..."
cp "${DOCKERFILE_PATH}" "${DOCKERFILE_PATH}.bak"
sed -E "s|target/${SERVICE_NAME}[^ ]+\.jar|target/${SERVICE_NAME}-${VERSION}.jar|g" "${DOCKERFILE_PATH}.bak" > "${DOCKERFILE_PATH}"

# 3. Build Docker Image (linux/amd64)
log_step "3/5 Construindo imagem Docker (linux/amd64)..."
docker build --platform linux/amd64 -t "${IMAGE_TAG}" -t "${IMAGE_LATEST}" .
log_success "Imagem Docker construída com sucesso: ${IMAGE_TAG}"

# 4. Push para GitHub Container Registry
log_step "4/5 Fazendo push para GitHub Container Registry..."
docker push "${IMAGE_TAG}"
docker push "${IMAGE_LATEST}"
log_success "Push concluído com sucesso"

# 5. Atualização e Deploy Docker Compose
if [ -f "$DOCKER_COMPOSE_FILE" ]; then
    log_step "5/5 Atualizando docker-compose.yml..."
    if [[ "$OSTYPE" == "darwin"* ]]; then
        sed -i '' "s|image: ${REGISTRY}/${SERVICE_NAME}:.*|image: ${IMAGE_TAG}|g" "${DOCKER_COMPOSE_FILE}"
    else
        sed -i "s|image: ${REGISTRY}/${SERVICE_NAME}:.*|image: ${IMAGE_TAG}|g" "${DOCKER_COMPOSE_FILE}"
    fi
    log_success "docker-compose.yml atualizado para ${IMAGE_TAG}"
fi

if [ "$DEPLOY_DOCKER" = true ]; then
    log_info "Iniciando container no Docker Compose..."
    cd "${PROJECT_ROOT}/docker"
    docker compose up -d "${SERVICE_NAME}"
    log_success "Container ${SERVICE_NAME} recriado com sucesso"

    log_info "Aguardando container ${SERVICE_NAME}..."
    CONTAINER_NAME=$(docker ps --filter "name=${SERVICE_NAME}" --format "{{.Names}}" | head -1)
    if [ -n "$CONTAINER_NAME" ]; then
        for i in {1..15}; do
            STATUS=$(docker inspect --format='{{json .State.Health.Status}}' "$CONTAINER_NAME" 2>/dev/null || echo '"running"')
            if [ "$STATUS" = '"healthy"' ] || [ "$STATUS" = '"running"' ]; then
                log_success "Container ${CONTAINER_NAME} está saudável (${STATUS})!"
                break
            fi
            sleep 2
        done
    fi
fi

# 6. Commit e push das alterações no repositório do serviço
commit_and_push_release "${VERSION}" "${SCRIPT_DIR}"

log_success "============================================"
log_success "  Deploy de ${SERVICE_NAME} finalizado com sucesso!"
log_success "============================================"
log_info "Imagem: ${IMAGE_TAG}"
log_info "Latest: ${IMAGE_LATEST}"
log_success "============================================"
