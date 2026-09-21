@echo off
REM 构建 Docker 镜像（Windows；构建上下文 = 仓库根目录）
REM
REM 可选环境变量：
REM   IMAGE_NAME   镜像名，默认 simonking/stream-nexus
REM   IMAGE_TAG    镜像标签，默认 1.0.0-SNAPSHOT
REM   JAR_VERSION  jar 版本号，默认 1.0.0-SNAPSHOT（需与 pom.xml 的 <version> 一致）
REM
REM 用法：
REM   scripts\docker-build.cmd
REM   set IMAGE_TAG=v1.2.0 && scripts\docker-build.cmd

setlocal
if "%IMAGE_NAME%"=="" set IMAGE_NAME=simonking/stream-nexus
if "%IMAGE_TAG%"=="" set IMAGE_TAG=1.0.0-SNAPSHOT
if "%JAR_VERSION%"=="" set JAR_VERSION=1.0.0-SNAPSHOT

pushd "%~dp0.."

echo ==> 构建镜像 %IMAGE_NAME%:%IMAGE_TAG% （jar 版本 %JAR_VERSION%）

docker build --build-arg JAR_VERSION=%JAR_VERSION% -t %IMAGE_NAME%:%IMAGE_TAG% -t %IMAGE_NAME%:latest .
if errorlevel 1 (
  echo 构建失败
  popd
  endlocal
  exit /b 1
)

echo ==> 完成，启动试试：
echo     docker run --rm -p 8088:8088 %IMAGE_NAME%:%IMAGE_TAG%
echo     docker compose up -d

popd
endlocal
