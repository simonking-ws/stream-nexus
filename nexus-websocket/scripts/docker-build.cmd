@echo off
REM 构建 nexus-websocket 的 Docker 镜像（Windows）
REM
REM 构建上下文必须是仓库根目录（nexus-websocket 的父 POM 在根目录，且它依赖 nexus-common），
REM 脚本会自动切到根目录，再用 -f nexus-websocket\Dockerfile 指定本模块的 Dockerfile。
REM
REM 可选环境变量：
REM   IMAGE_NAME   镜像名，默认 simonking/nexus-websocket
REM   IMAGE_TAG    镜像标签，默认 1.0.0
REM   JAR_VERSION  jar 版本号，默认 1.0.0（需与 pom.xml 的 <version> 一致）
REM
REM 用法：
REM   nexus-websocket\scripts\docker-build.cmd
REM   set IMAGE_TAG=v1.2.0 && nexus-websocket\scripts\docker-build.cmd

setlocal
if "%IMAGE_NAME%"=="" set IMAGE_NAME=simonking/nexus-websocket
if "%IMAGE_TAG%"=="" set IMAGE_TAG=1.0.0
if "%JAR_VERSION%"=="" set JAR_VERSION=1.0.0

pushd "%~dp0..\.."

echo ==> 构建镜像 %IMAGE_NAME%:%IMAGE_TAG% （jar 版本 %JAR_VERSION%）

docker build --build-arg JAR_VERSION=%JAR_VERSION% -f nexus-websocket\Dockerfile -t %IMAGE_NAME%:%IMAGE_TAG% -t %IMAGE_NAME%:latest .
if errorlevel 1 (
  echo 构建失败
  popd
  endlocal
  exit /b 1
)

echo ==> 完成，启动试试：
echo     docker run --rm -p 8089:8089 -p 9090:9090 -p 9091:9091 %IMAGE_NAME%:%IMAGE_TAG%
echo     cd nexus-websocket ^&^& docker compose up -d

popd
endlocal
