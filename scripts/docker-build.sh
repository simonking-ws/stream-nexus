#!/usr/bin/env bash
# 构建 Docker 镜像（构建上下文 = 仓库根目录，脚本会自动切到根目录）
#
# 可选环境变量：
#   IMAGE_NAME   镜像名，默认 simonking/stream-nexus
#   IMAGE_TAG    镜像标签，默认 1.0.0-SNAPSHOT
#   JAR_VERSION  jar 版本号，默认 1.0.0-SNAPSHOT（需与 pom.xml 的 <version> 一致）
#
# 用法：
#   ./scripts/docker-build.sh
#   IMAGE_TAG=v1.2.0 ./scripts/docker-build.sh

set -euo pipefail

cd "$(dirname "$0")/.."

IMAGE_NAME="${IMAGE_NAME:-simonking/stream-nexus}"
IMAGE_TAG="${IMAGE_TAG:-1.0.0-SNAPSHOT}"
JAR_VERSION="${JAR_VERSION:-1.0.0-SNAPSHOT}"

echo "==> 构建镜像 ${IMAGE_NAME}:${IMAGE_TAG}（jar 版本 ${JAR_VERSION}）"

docker build \
  --build-arg "JAR_VERSION=${JAR_VERSION}" \
  -t "${IMAGE_NAME}:${IMAGE_TAG}" \
  -t "${IMAGE_NAME}:latest" \
  .

echo "==> 完成，启动试试："
echo "    docker run --rm -p 8088:8088 ${IMAGE_NAME}:${IMAGE_TAG}"
echo "    # 或用编排：docker compose up -d"
