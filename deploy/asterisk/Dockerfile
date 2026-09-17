# 本地联调用的 Asterisk。只当"SIP/RTP ↔ AudioSocket"的协议转换器, 不写业务逻辑。
# 用 Ubuntu 自带的包(Asterisk 20 LTS), 里面已含 app_audiosocket / res_audiosocket / func_uuid。
FROM ubuntu:24.04

RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends asterisk \
 && rm -rf /var/lib/apt/lists/*

# 模板放在镜像外挂载的 /conf 里, 启动时渲染进 /etc/asterisk
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
