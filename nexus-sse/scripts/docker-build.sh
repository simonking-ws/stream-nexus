#!/usr/bin/env bash
# 构建 nexus-sse 的 Docker 镜像
#
# 构建上下文必须是仓库根目录（nexus-sse 的父 POM 在根目录，且它依赖 nexus-common），
# 脚本会自动切到根目录，再用 -f nexus-sse/Dockerfile 指定本模块的 Dockerfile。
#
# 只保留 :latest 镜像：构建后自动删除被新 latest 顶替掉的旧镜像，避免旧版本堆积。
#
# 可选环境变量：
#   IMAGE_NAME   镜像名，默认 simonking/nexus-sse
#   JAR_VERSION  jar 版本号，默认 1.0.0（需与 pom.xml 的 <version> 一致）
#
# 用法：
#   ./nexus-sse/scripts/docker-build.sh

set -euo pipefail

cd "$(dirname "$0")/../.."

IMAGE_NAME="${IMAGE_NAME:-simonking/nexus-sse}"
JAR_VERSION="${JAR_VERSION:-1.0.0}"

# 记录构建前的 latest 镜像 ID，构建后把旧的这份删掉，做到只保留最新一次构建的 latest
OLD_IMAGE_ID="$(docker images -q "${IMAGE_NAME}:latest" 2>/dev/null | head -n1 || true)"

echo "==> 构建镜像 ${IMAGE_NAME}:latest（jar 版本 ${JAR_VERSION}）"

docker build \
  --build-arg "JAR_VERSION=${JAR_VERSION}" \
  -f nexus-sse/Dockerfile \
  -t "${IMAGE_NAME}:latest" \
  .

# 清理：删除已被新 latest 顶替掉的旧镜像（仅针对本 IMAGE_NAME，不影响其他项目）
if [[ -n "${OLD_IMAGE_ID}" ]]; then
  NEW_IMAGE_ID="$(docker images -q "${IMAGE_NAME}:latest" 2>/dev/null | head -n1 || true)"
  if [[ "${NEW_IMAGE_ID}" != "${OLD_IMAGE_ID}" ]]; then
    echo "==> 清理旧镜像 ${OLD_IMAGE_ID}，仅保留 latest"
    docker rmi -f "${OLD_IMAGE_ID}" >/dev/null 2>&1 || true
  fi
fi

echo "==> 完成，启动试试："
echo "    docker run --rm -p 8088:8088 ${IMAGE_NAME}:latest"
echo "    # 或用编排：cd nexus-sse && docker compose up -d"
