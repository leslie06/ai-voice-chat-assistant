# 本地联调用的 FreeSWITCH。只做"SIP/RTP ↔ 事件套接字 + UDP 裸音频"的转换, 不写业务逻辑。
#
# 用 Alpine 仓库里的包: 自带 arm64 构建(Apple 芯片可直接跑), 装包一分钟;
# 官方 SignalWire 源要注册令牌, 源码编译要半小时以上。
# 用到的都是 FreeSWITCH 自带能力(mod_event_socket 的 socket 应用 + 内核 unicast), 不需要任何第三方模块。
FROM alpine:3.22

RUN apk add --no-cache freeswitch

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
