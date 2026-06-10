#!/usr/bin/env bash
# 一键启动: 加载 .env 里的密钥/配置, 然后跑 Spring Boot。
# 用法: ./run.sh
set -euo pipefail

cd "$(dirname "$0")"

if [[ ! -f .env ]]; then
  echo "✗ 未找到 .env。请先: cp .env.example .env 并填入真实 key" >&2
  exit 1
fi

# 加载 .env (忽略注释行和空行), 导出为环境变量
set -a
# shellcheck disable=SC1091
source .env
set +a

if [[ -z "${DEEPSEEK_API_KEY:-}" || -z "${DASHSCOPE_API_KEY:-}" ]]; then
  echo "✗ .env 里 DEEPSEEK_API_KEY / DASHSCOPE_API_KEY 不能为空" >&2
  exit 1
fi

if [[ "${KIMI_ENABLED:-false}" == "true" && -z "${KIMI_API_KEY:-${MOONSHOT_API_KEY:-}}" ]]; then
  echo "✗ 已启用 Kimi, 但 .env 里 KIMI_API_KEY / MOONSHOT_API_KEY 为空" >&2
  exit 1
fi

echo "✓ 已加载 .env, 打包中 (首次较慢, 之后增量很快)…"
./mvnw -q -pl vca-bootstrap -am package -DskipTests

JAR=$(ls vca-bootstrap/target/vca-bootstrap-*.jar 2>/dev/null | grep -vE 'original|sources|javadoc' | head -1)
if [[ -z "$JAR" ]]; then
  echo "✗ 未找到可执行 jar, 打包可能失败" >&2
  exit 1
fi

echo "✓ 启动 (real profile): $JAR"
exec java -jar "$JAR"
