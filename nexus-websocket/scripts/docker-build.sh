#!/usr/bin/env bash
# 构建 nexus-websocket 的 Docker 镜像
#
# 构建上下文必须是仓库根目录（nexus-websocket 的父 POM 在根目录，且它依赖 nexus-common），
# 脚本会自动切到根目录，再用 -f nexus-websocket/Dockerfile 指定本模块的 Dockerfile。
#
# 可选环境变量：
#   IMAGE_NAME   镜像名，默认 simonking/nexus-websocket
#   IMAGE_TAG    镜像标签，默认 1.0.0
#   JAR_VERSION  jar 版本号，默认 1.0.0（需与 pom.xml 的 <version> 一致）
#
# 用法：
#   ./nexus-websocket/scripts/docker-build.sh
#   IMAGE_TAG=v1.2.0 ./nexus-websocket/scripts/docker-build.sh

set -euo pipefail

cd "$(dirname "$0")/../.."

IMAGE_NAME="${IMAGE_NAME:-simonking/nexus-websocket}"
IMAGE_TAG="${IMAGE_TAG:-1.0.0}"
JAR_VERSION="${JAR_VERSION:-1.0.0}"

echo "==> 构建镜像 ${IMAGE_NAME}:${IMAGE_TAG}（jar 版本 ${JAR_VERSION}）"

docker build \
  --build-arg "JAR_VERSION=${JAR_VERSION}" \
  -f nexus-websocket/Dockerfile \
  -t "${IMAGE_NAME}:${IMAGE_TAG}" \
  -t "${IMAGE_NAME}:latest" \
  .

echo "==> 完成，启动试试："
echo "    docker run --rm -p 8089:8089 -p 9090:9090 -p 9091:9091 ${IMAGE_NAME}:${IMAGE_TAG}"
echo "    # 或用编排：cd nexus-websocket && docker compose up -d"
