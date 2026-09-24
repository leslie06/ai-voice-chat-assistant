# 通话录音回归（call-review）

用线上真实通话的录音，量电话客服的体感问题和识别准确率。两个脚本，都只在本机跑。

| 脚本 | 做什么 | 要不要人工 |
|---|---|---|
| `review_calls.py` | 从双声道录音（左 = 客户，右 = AI）量响应延迟、插话后 AI 多久停下、没被理会的插话、冷场 | 不要 |
| `asr_eval.py` | 用线上同一套识别配置（8k 窄带模型 + 热词表）识别客户说的每段话；填了标注就出字错率和关键词命中率 | 标注 ref 列 |

## 隐私

录音里是客户的声音和手机号（诊所还有病情），属于个人信息：

- 录音和输出目录放在仓库**外面**（例如 `~/vca-review/`），不要提交、不要发给别人；用完删掉。
- `asr_eval.py` 会把片段发给阿里云识别——和线上电话走的是同一个厂商、同一个账号，没有新增第三方。

## 步骤

```bash
# 1. 把服务器上的录音拷到本机(自己执行; 录音只保留 VCA_OPS 配的天数, 见 deploy/ops)
mkdir -p ~/vca-review/rec
scp 'root@<服务器>:/opt/vca/freeswitch/recordings/*.wav' ~/vca-review/rec/

# 2. 体检: 不用标注, 直接出报告; --clips 顺带把客户说的每段话切出来
python3 tools/call-review/review_calls.py ~/vca-review/rec --out ~/vca-review/out --clips
open ~/vca-review/out/report.md

# 3. 识别: 用线上同一张热词表(行业表 id 见 VCA 启动日志"热词表已建: 行业=口腔诊所, id=…")
set -a; source .env; set +a
python3 tools/call-review/asr_eval.py ~/vca-review/out/clips.csv --vocabulary-id <热词表 id>

# 4. 标注: 打开 clips.csv, 边听 clips/ 里的 wav 边在 ref 列写客户实际说的话(听不清的留空), 再跑一次第 3 步出分
```

挑 20 通左右、覆盖不同的店和问题类型（问价格、问时间、预约、转人工）就够做基线。标注好的 `clips.csv` 自己留一份：
以后换识别模型、改热词表、调判停参数，用 `--rerun --out 新文件.csv` 重新识别，和基线比分数。

## 指标怎么读

| 指标 | 目标 | 超了先查什么 |
|---|---|---|
| 响应延迟 p50 | ≤ 1.5 秒（2026-09-22 实测中位 1.4 秒） | VCA 日志里"判停+识别 / LLM 首 token / TTS 首音频"三段耗时，见 `docs/12-freeswitch.md` §8 |
| 没理会的插话 | 0 | 网关的 Network Echo Suppressor 是否关了；`VCA_TELEPHONY_VAD_BARGE_MS` |
| 冷场 | 0 | 那通的 VCA 日志：回合出错、熔断、识别没出结果 |
| 关键词命中率 | 越高越好；洗牙、种植牙这类是重点 | 热词表里有没有这个词、绑的是不是 8k 模型 |

录音文件名就是通话 id，可以在 VCA 日志和 `conversation_turn` 表里查到这通电话的识别文字与回复。

## 自测

```bash
python3 -m unittest tools/call-review/test_review_calls.py tools/call-review/test_asr_eval.py
```

用时间轴已知的合成通话验证量出来的时刻，不需要任何真实录音。
