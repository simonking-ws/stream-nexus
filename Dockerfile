# 多阶段构建：构建期用带 Maven 的 JDK 镜像，运行期只留 JRE，镜像更小、攻击面更小
#
# 构建上下文必须是仓库根目录（而不是 nexus-sse/）：
# nexus-sse 的父 POM 在根目录，且它依赖 nexus-common，根目录打包才能一次带上
#   docker build -t stream-nexus:1.0.0-SNAPSHOT .
# 或直接用 scripts/docker-build.sh | scripts/docker-build.cmd

FROM maven:3.9-eclipse-temurin-17 AS build

# jar 版本号，与 <version> 保持一致；改版本时用 --build-arg JAR_VERSION=x 覆盖
ARG JAR_VERSION=1.0.0-SNAPSHOT

WORKDIR /build

# 先只拷 POM 再拷源码：依赖没变时复用缓存层，不必每次重拉依赖
COPY pom.xml ./
COPY nexus-common/pom.xml nexus-common/
COPY nexus-sse/pom.xml nexus-sse/

COPY nexus-common/src nexus-common/src
COPY nexus-sse/src nexus-sse/src

# -pl nexus-sse -am：只构建可执行的推送服务，并连带构建它依赖的 nexus-common
# -ntp：不刷传输日志，构建日志干净些
RUN mvn -B -ntp -DskipTests -pl nexus-sse -am package \
    # 统一成固定文件名，最终阶段不必再猜版本号（也避开了 *.jar.original）
    && cp nexus-sse/target/nexus-sse-${JAR_VERSION}.jar /app.jar

FROM eclipse-temurin:17-jre-jammy

# SERVER_PORT 走 Spring 宽松绑定（SERVER_PORT -> server.port），改端口不必重新打镜像
ENV TZ=Asia/Shanghai \
    SERVER_PORT=8088 \
    # 容器感知 JDK17 默认开启，这里只给个稳妥的堆上限；JAVA_OPTS 可在运行时整体覆盖
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"

RUN groupadd --system --gid 1000 nexus \
    && useradd --system --uid 1000 --gid nexus --no-create-home nexus \
    && mkdir -p /app && chown nexus:nexus /app

WORKDIR /app

COPY --from=build /app.jar /app/app.jar

# 非 root 运行
USER nexus

EXPOSE 8088

# 无 curl / wget 的基础镜像，用 bash 的 /dev/tcp 直接探活：
# 探 /sse/admin/apps（无需鉴权），能连上且返回 HTTP 响应行即视为健康
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${SERVER_PORT:-8088} \
        && printf "GET /sse/admin/apps HTTP/1.0\r\n\r\n" >&3 \
        && grep -q "HTTP/1" <&3'

# exec 保证 java 进程是 1 号进程，能收到 SIGTERM 走优雅停机
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
