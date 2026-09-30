@echo off
REM 构建 nexus-sse 的 Docker 镜像（Windows）
REM
REM 构建上下文必须是仓库根目录（nexus-sse 的父 POM 在根目录，且它依赖 nexus-common），
REM 脚本会自动切到根目录，再用 -f nexus-sse\Dockerfile 指定本模块的 Dockerfile。
REM
REM 只保留 :latest 镜像：构建后自动删除被新 latest 顶替掉的旧镜像，避免旧版本堆积。
REM
REM 可选环境变量：
REM   IMAGE_NAME   镜像名，默认 simonking/nexus-sse
REM   JAR_VERSION  jar 版本号，默认 1.0.0（需与 pom.xml 的 <version> 一致）
REM
REM 用法：
REM   nexus-sse\scripts\docker-build.cmd

setlocal enabledelayedexpansion
if "%IMAGE_NAME%"=="" set IMAGE_NAME=simonking/nexus-sse
if "%JAR_VERSION%"=="" set JAR_VERSION=1.0.0

pushd "%~dp0..\.."

REM 记录构建前的 latest 镜像 ID，构建后把旧的这份删掉，做到只保留最新一次构建的 latest
set OLD_IMAGE_ID=
for /f "del=" %%i in ('docker images -q %IMAGE_NAME%:latest') do if not defined OLD_IMAGE_ID set "OLD_IMAGE_ID=%%i"

echo ==> 构建镜像 %IMAGE_NAME%:latest （jar 版本 %JAR_VERSION%）

docker build --build-arg JAR_VERSION=%JAR_VERSION% -f nexus-sse\Dockerfile -t %IMAGE_NAME%:latest .
if errorlevel 1 (
  echo 构建失败
  popd
  endlocal
  exit /b 1
)

REM 清理：删除已被新 latest 顶替掉的旧镜像（仅针对本 IMAGE_NAME，不影响其他项目）
if defined OLD_IMAGE_ID (
  set NEW_IMAGE_ID=
  for /f "del=" %%i in ('docker images -q %IMAGE_NAME%:latest') do if not defined NEW_IMAGE_ID set "NEW_IMAGE_ID=%%i"
  if not "!NEW_IMAGE_ID!"=="!OLD_IMAGE_ID!" (
    echo ==> 清理旧镜像 !OLD_IMAGE_ID!，仅保留 latest
    docker rmi -f !OLD_IMAGE_ID! >nul 2>&1
  )
)

echo ==> 完成，启动试试：
echo     docker run --rm -p 8088:8088 %IMAGE_NAME%:latest
echo     cd nexus-sse ^&^& docker compose up -d

popd
endlocal
