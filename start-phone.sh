#!/usr/bin/env bash
# 一键启动"电话客服"模式: 检查 FreeSWITCH → 加载配置 → 起 VCA。
#
# 用法:
#   ./start-phone.sh              # 用现成的 jar 启动(最快)
#   ./start-phone.sh --build      # 先重新打包再启动(改过代码时用)
#   ./start-phone.sh --help
#
# 参数从三处取, 后者覆盖前者: 本文件的默认值 → .env.phone → 当前命令行的环境变量。
# 要长期改某一项, 在仓库根目录建 .env.phone(已 gitignore), 例如:
#   VCA_TELEPHONY_GREETING="您好，这里是美好口腔，请问有什么可以帮您的吗？"
#   VCA_TELEPHONY_KNOWLEDGE_OWNER=11
#   VCA_TELEPHONY_TRANSFER_DIAL_STRING=user/1000
#
# 浏览器那条链路不受影响: 本脚本只是多开了电话接入, 8080 照常可用。
set -euo pipefail

cd "$(dirname "$0")"

JAR_GLOB='vca-bootstrap/target/vca-bootstrap-*.jar'
FS_DIR='deploy/freeswitch'
BUILD=false

for arg in "$@"; do
  case "$arg" in
    --build) BUILD=true ;;
    -h|--help)
      sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "✗ 未知参数: $arg (试 --help)" >&2; exit 1 ;;
  esac
done

# ---- 1. 密钥: 与 run.sh 同一份 .env ----
if [[ ! -f .env ]]; then
  echo "✗ 未找到 .env。请先: cp .env.example .env 并填入真实 key" >&2
  exit 1
fi
set -a
# shellcheck disable=SC1091
source .env
# 电话专用配置(可选)。放在 .env 之后, 所以这里能覆盖 .env 里的同名项。
if [[ -f .env.phone ]]; then
  # shellcheck disable=SC1091
  source .env.phone
fi
set +a

if [[ -z "${DASHSCOPE_API_KEY:-}" ]]; then
  echo "✗ .env 里 DASHSCOPE_API_KEY 为空 —— 识别与合成都走它" >&2
  exit 1
fi

# ---- 2. 默认值。已经设过的(.env.phone 或命令行)一律不动 ----
: "${VCA_TELEPHONY_ENABLED:=true}"
: "${VCA_TELEPHONY_PROVIDER:=freeswitch}"
: "${VCA_TELEPHONY_GREETING:=您好，这里是智能语音助手，请问有什么可以帮您的吗？}"
# 电话要的是首字快: qwen3.7-plus 会先生成一段思考才开口, 电话里那段就是纯等待
: "${VCA_TELEPHONY_LLM_MODEL:=qwen-flash}"
# 模型自带的联网搜索会拖慢回答, 客服场景用不上
: "${QWEN_ENABLE_SEARCH:=false}"
# 软电话采到的麦克风电平比真实电话线低不少, 这三项按软电话调; 接真实线路时建议删掉, 用代码默认值
: "${VCA_TELEPHONY_VAD_SPEECH:=0.01}"
: "${VCA_TELEPHONY_VAD_ONSETMS:=100}"
: "${VCA_TELEPHONY_VAD_BARGE:=0.015}"
# 知识库归属(商家账号 id)与坐席拨号串: 留空则电话里没有知识库、不下发转人工工具
: "${VCA_TELEPHONY_KNOWLEDGE_OWNER:=}"
: "${VCA_TELEPHONY_TRANSFER_DIAL_STRING:=}"
# 堆上限。16G 内存的机器上默认堆能到 4G, 系统内存紧张时容易被 OOM killer 选中
: "${PHONE_JAVA_OPTS:=-Xmx1g}"

export VCA_TELEPHONY_ENABLED VCA_TELEPHONY_PROVIDER VCA_TELEPHONY_GREETING VCA_TELEPHONY_LLM_MODEL \
  QWEN_ENABLE_SEARCH VCA_TELEPHONY_VAD_SPEECH VCA_TELEPHONY_VAD_ONSETMS VCA_TELEPHONY_VAD_BARGE \
  VCA_TELEPHONY_KNOWLEDGE_OWNER VCA_TELEPHONY_TRANSFER_DIAL_STRING

# ---- 3. FreeSWITCH: 没起就起, 起不来直接停(电话进不来, 启动 VCA 没意义) ----
if ! docker info >/dev/null 2>&1; then
  echo "✗ Docker 没运行 —— FreeSWITCH 跑在容器里, 先打开 Docker Desktop" >&2
  exit 1
fi
if [[ "$(docker inspect -f '{{.State.Running}}' vca-freeswitch 2>/dev/null)" != "true" ]]; then
  echo "· FreeSWITCH 未运行, 正在启动…"
  (cd "$FS_DIR" && docker compose up -d >/dev/null)
  for _ in $(seq 1 30); do
    [[ "$(docker inspect -f '{{.State.Running}}' vca-freeswitch 2>/dev/null)" == "true" ]] && break
    sleep 1
  done
  if [[ "$(docker inspect -f '{{.State.Running}}' vca-freeswitch 2>/dev/null)" != "true" ]]; then
    echo "✗ FreeSWITCH 起不来。看日志: docker logs vca-freeswitch" >&2
    exit 1
  fi
fi

# ---- 4. 端口: 占着就退出并说清谁占的。不自动杀进程 ----
for port in 8080 8084; do
  pid=$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null | head -1 || true)
  if [[ -n "$pid" ]]; then
    echo "✗ 端口 $port 已被进程 $pid 占用(8080=网页, 8084=电话接入)。" >&2
    echo "  多半是上一次还开着。停掉它: kill $pid" >&2
    exit 1
  fi
done

# ---- 5. jar ----
if [[ "$BUILD" == true ]]; then
  echo "· 重新打包中(首次较慢, 之后增量很快)…"
  ./mvnw -q -pl vca-bootstrap -am package -DskipTests
fi
JAR=$(ls $JAR_GLOB 2>/dev/null | grep -vE 'original|sources|javadoc' | head -1 || true)
if [[ -z "$JAR" ]]; then
  echo "✗ 未找到可执行 jar。先跑一次: ./start-phone.sh --build" >&2
  exit 1
fi

# ---- 6. 起 ----
echo "✓ FreeSWITCH 已就绪 (SIP 127.0.0.1:5060, 分机 1000, 拨 5000 接 AI / 6000 回声测试)"
echo "✓ 开场白: ${VCA_TELEPHONY_GREETING}"
echo "✓ 对话模型: ${VCA_TELEPHONY_LLM_MODEL}"
if [[ -n "$VCA_TELEPHONY_KNOWLEDGE_OWNER" ]]; then
  echo "✓ 知识库: 账号 ${VCA_TELEPHONY_KNOWLEDGE_OWNER}"
else
  echo "· 知识库: 未配(VCA_TELEPHONY_KNOWLEDGE_OWNER 为空), 商家资料类问题答不了"
fi
if [[ -n "$VCA_TELEPHONY_TRANSFER_DIAL_STRING" ]]; then
  echo "✓ 转人工: ${VCA_TELEPHONY_TRANSFER_DIAL_STRING}"
else
  echo "· 转人工: 未配坐席号码, 不下发该工具(AI 会改说让同事回电)"
fi
echo "✓ 启动 $JAR  (堆上限 ${PHONE_JAVA_OPTS}, Ctrl-C 停止)"
echo

# shellcheck disable=SC2086
exec java $PHONE_JAVA_OPTS -jar "$JAR"
