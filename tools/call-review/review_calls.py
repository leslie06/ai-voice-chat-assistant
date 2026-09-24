#!/usr/bin/env python3
"""
通话录音体检: 不用人工标注, 直接从双声道录音里量出电话客服的体感问题。

FreeSWITCH 的 record_session 录的是双声道 wav: 左 = 来电方, 右 = AI(见 deploy/freeswitch/conf/dialplan.xml)。
两边各自切出"在说话"的片段, 就能按时间轴量:

  响应延迟    客户说完 → AI 开口。电话里超过 2 秒客户就开始"喂? 喂?"
  插话        客户在 AI 说话时开口, AI 多久停下; 超过 MISSED_BARGE_S 还在说 = 没理会客户(插不进话)
  冷场        通话中两边都不出声超过 DEAD_AIR_S(AI 卡住了、或者没听到客户说话)
  开场        接通到 AI 第一次出声

用法:
  python3 review_calls.py <录音目录> --out <输出目录> [--clips]

输出目录里:
  report.md   汇总 + 每通的问题清单(按问题多少排序)
  calls.csv   每通一行的指标
  clips/ + clips.csv (--clips 时) 每段客户说的话切成单独的 wav, 供 asr_eval.py 识别和人工标注

录音里是客户的声音和手机号, 属于个人信息: 只在本机处理, 输出目录不要提交进仓库、不要外传。
"""
import argparse
import csv
import statistics
import sys
import wave
from pathlib import Path

import numpy as np

FRAME_S = 0.02           # 20ms 一帧, 与电话打包时长一致
MIN_SEG_S = 0.2          # 短于这个的"声音"当噪声(咔哒声、摘机冲击的一部分)
WORD_GAP_S = 0.4         # 同一句话里字与字的停顿, 小于它的空隙合并
UTTER_GAP_S = 0.8        # 一句话说完的判断: 停顿超过它算下一句(与 VCA 语义判停的上限一致)
MAX_REPLY_WAIT_S = 8.0   # 客户说完这么久 AI 还不出声, 不算"回应"而算冷场
BARGE_MIN_S = 0.4        # 客户在 AI 说话时开口至少这么长才算插话("嗯"之类的附和不算)
MISSED_BARGE_S = 2.0     # 客户插话后 AI 还继续说这么久 = 插话没被理会
DEAD_AIR_S = 5.0         # 通话中两边都安静这么久 = 冷场


def read_stereo(path):
    """返回 (左声道, 右声道, 采样率), 值域 [-1, 1]"""
    with wave.open(str(path), "rb") as w:
        if w.getsampwidth() != 2:
            raise ValueError("只支持 16bit PCM")
        ch, rate, n = w.getnchannels(), w.getframerate(), w.getnframes()
        data = np.frombuffer(w.readframes(n), dtype="<i2").astype(np.float32) / 32768.0
    if ch == 1:
        raise ValueError("单声道录音分不出客户和 AI(需要 RECORD_STEREO=true 录的)")
    data = data.reshape(-1, ch)
    return data[:, 0], data[:, 1], rate


def frame_rms(x, rate):
    n = int(rate * FRAME_S)
    usable = len(x) // n * n
    frames = x[:usable].reshape(-1, n)
    return np.sqrt(np.mean(frames ** 2, axis=1))


def segments(x, rate, gap_s, floor=0.01):
    """
    切出"在说话"的时间段 [(开始秒, 结束秒)]。

    门限自适应: 取整路的底噪(20 分位)的 4 倍, 但不低于 floor —— 线路底噪每家店都不一样,
    而 AI 的声音漏进客户声道的回声(实测 0.000~0.006)要挡在门限之下。
    """
    rms = frame_rms(x, rate)
    if len(rms) == 0:
        return []
    thr = max(floor, float(np.percentile(rms, 20)) * 4)
    voiced = rms > thr
    segs, start = [], None
    for i, v in enumerate(voiced):
        t = i * FRAME_S
        if v and start is None:
            start = t
        elif not v and start is not None:
            segs.append([start, t])
            start = None
    if start is not None:
        segs.append([start, len(voiced) * FRAME_S])
    merged = []
    for s in segs:
        if merged and s[0] - merged[-1][1] < gap_s:
            merged[-1][1] = s[1]
        else:
            merged.append(s)
    return [(round(a, 2), round(b, 2)) for a, b in merged if b - a >= MIN_SEG_S]


def overlaps(t, segs):
    return any(a <= t < b for a, b in segs)


def analyze(path):
    left, right, rate = read_stereo(path)
    duration = len(left) / rate
    caller = segments(left, rate, UTTER_GAP_S)
    ai_words = segments(right, rate, WORD_GAP_S)
    ai = segments(right, rate, UTTER_GAP_S)

    # 响应延迟: 客户这句说完、AI 当时没在说话, 到 AI 下一次开口; 中间客户又开口的不算(那是客户自己接着说)
    latencies = []
    for _, end in caller:
        if overlaps(end, ai):
            continue
        nxt = next((a for a, _ in ai_words if a >= end), None)
        if nxt is None or nxt - end > MAX_REPLY_WAIT_S:
            continue
        if any(end < c0 < nxt for c0, _ in caller):
            continue
        latencies.append(round(nxt - end, 2))

    # 插话: 客户在 AI 说话中途开口(不是和 AI 同时起头), 量 AI 多久停下
    barge_stops, missed = [], []
    for c0, c1 in caller:
        if c1 - c0 < BARGE_MIN_S:
            continue
        host = next(((a0, a1) for a0, a1 in ai if a0 + 0.3 < c0 < a1), None)
        if host is None:
            continue
        stop = round(host[1] - c0, 2)
        barge_stops.append(stop)
        if stop > MISSED_BARGE_S:
            missed.append(round(c0, 1))

    # 冷场: 第一次有人说话到最后一次之间, 两边都没声的空档
    both = sorted(caller + ai_words)
    dead_air = []
    if both:
        cursor = both[0][1]
        for a, b in both[1:]:
            if a - cursor > DEAD_AIR_S:
                dead_air.append((round(cursor, 1), round(a - cursor, 1)))
            cursor = max(cursor, b)

    first_ai = ai_words[0][0] if ai_words else None
    return {
        "call": Path(path).stem,
        "duration_s": round(duration, 1),
        "caller_talk_s": round(sum(b - a for a, b in caller), 1),
        "ai_talk_s": round(sum(b - a for a, b in ai_words), 1),
        "caller_utterances": len(caller),
        "first_ai_s": None if first_ai is None else round(first_ai, 2),
        "reply_latencies": latencies,
        "barge_stops": barge_stops,
        "missed_barges_at": missed,
        "dead_air": dead_air,
        "_caller_segments": caller,
        "_rate": rate,
        "_left": left,
    }


def pct(values, q):
    if not values:
        return None
    v = sorted(values)
    return v[min(len(v) - 1, int(round(q / 100 * (len(v) - 1))))]


def fmt(v, unit="s"):
    return "—" if v is None else f"{v:.2f}{unit}"


def export_clips(results, out_dir):
    """每段客户说的话切成单独的 8k 单声道 wav, 列进 clips.csv(识别结果与人工标注各留一列)"""
    clip_dir = out_dir / "clips"
    clip_dir.mkdir(parents=True, exist_ok=True)
    rows = []
    for r in results:
        rate, left = r["_rate"], r["_left"]
        for i, (a, b) in enumerate(r["_caller_segments"], 1):
            # 前后各留 0.2 秒, 别把第一个字的起音切掉
            s, e = max(0, int((a - 0.2) * rate)), min(len(left), int((b + 0.2) * rate))
            name = f"{r['call'][:8]}_{i:02d}.wav"
            pcm = (np.clip(left[s:e], -1, 1) * 32767).astype("<i2").tobytes()
            with wave.open(str(clip_dir / name), "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(rate)
                w.writeframes(pcm)
            rows.append({"clip": name, "call": r["call"], "start_s": a, "end_s": b, "hyp": "", "ref": ""})
    with open(out_dir / "clips.csv", "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=["clip", "call", "start_s", "end_s", "hyp", "ref"])
        w.writeheader()
        w.writerows(rows)
    return len(rows)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("recordings", help="录音目录(FreeSWITCH 的 recordings)")
    ap.add_argument("--out", required=True, help="输出目录")
    ap.add_argument("--clips", action="store_true", help="把客户说的每段话切出来, 供识别评测与人工标注")
    ap.add_argument("--min-duration", type=float, default=10.0, help="短于这么多秒的通话不看(秒挂/拨错)")
    args = ap.parse_args()

    files = sorted(Path(args.recordings).glob("*.wav"))
    if not files:
        sys.exit(f"{args.recordings} 里没有 wav")
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    results, skipped = [], []
    for f in files:
        try:
            r = analyze(f)
        except (ValueError, wave.Error, EOFError) as e:
            skipped.append(f"{f.name}: {e}")
            continue
        if r["duration_s"] < args.min_duration:
            skipped.append(f"{f.name}: 只有 {r['duration_s']} 秒")
            continue
        results.append(r)
    if not results:
        sys.exit("没有可分析的通话: " + "; ".join(skipped[:5]))

    lat = [x for r in results for x in r["reply_latencies"]]
    stops = [x for r in results for x in r["barge_stops"]]
    missed = sum(len(r["missed_barges_at"]) for r in results)
    dead = sum(len(r["dead_air"]) for r in results)
    first = [r["first_ai_s"] for r in results if r["first_ai_s"] is not None]

    with open(out / "calls.csv", "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["call", "duration_s", "caller_talk_s", "ai_talk_s", "caller_utterances", "first_ai_s",
                    "reply_p50_s", "reply_max_s", "barges", "missed_barges", "dead_air"])
        for r in results:
            w.writerow([r["call"], r["duration_s"], r["caller_talk_s"], r["ai_talk_s"], r["caller_utterances"],
                        r["first_ai_s"], pct(r["reply_latencies"], 50), max(r["reply_latencies"], default=None),
                        len(r["barge_stops"]), len(r["missed_barges_at"]), len(r["dead_air"])])

    lines = [
        "# 通话录音体检", "",
        f"分析 {len(results)} 通(跳过 {len(skipped)} 通: 太短或不是双声道)。", "",
        "| 指标 | 值 | 说明 |", "|---|---|---|",
        f"| 响应延迟 p50 / p90 / 最大 | {fmt(pct(lat, 50))} / {fmt(pct(lat, 90))} / {fmt(max(lat, default=None))} "
        f"| 客户说完到 AI 出声, 共 {len(lat)} 次。超过 2 秒客户会开始\"喂?\" |",
        f"| 开场出声 p50 | {fmt(pct(first, 50))} | 接通到 AI 第一次出声(含 {0.8:.1f} 秒线路稳定等待) |",
        f"| 插话后 AI 停下 p50 / p90 | {fmt(pct(stops, 50))} / {fmt(pct(stops, 90))} | 共 {len(stops)} 次插话 |",
        f"| 插话没被理会 | {missed} 次 | AI 在客户开口后还说了 {MISSED_BARGE_S:.0f} 秒以上 |",
        f"| 冷场 | {dead} 次 | 两边都没声超过 {DEAD_AIR_S:.0f} 秒 |",
        "",
        "## 有问题的通话", "",
        "| 通话 | 时长 | 最慢响应 | 没理会的插话(秒) | 冷场(起点秒, 时长) |", "|---|---|---|---|---|",
    ]
    def badness(r):
        return (len(r["missed_barges_at"]) * 3 + len(r["dead_air"]) * 2
                + sum(1 for x in r["reply_latencies"] if x > 2.0))
    flagged = [r for r in sorted(results, key=badness, reverse=True) if badness(r) > 0]
    for r in flagged:
        lines.append(f"| {r['call']} | {r['duration_s']}s | {fmt(max(r['reply_latencies'], default=None))} "
                     f"| {', '.join(map(str, r['missed_barges_at'])) or '—'} "
                     f"| {', '.join(f'{a}s/{d}s' for a, d in r['dead_air']) or '—'} |")
    if not flagged:
        lines.append("| (没有) | | | | |")
    lines += ["", "录音文件名就是通话 id, 可以在 VCA 日志和 conversation_turn 表里按它查到这通电话的识别文字与回复。"]
    if skipped:
        lines += ["", "<details><summary>跳过的通话</summary>", ""] + [f"- {s}" for s in skipped] + ["", "</details>"]

    if args.clips:
        n = export_clips(results, out)
        lines += ["", f"已切出 {n} 段客户说的话到 clips/, 清单 clips.csv。下一步: python3 asr_eval.py {out}/clips.csv"]

    (out / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines[:12]))
    print(f"\n完整报告: {out / 'report.md'}")


if __name__ == "__main__":
    main()
