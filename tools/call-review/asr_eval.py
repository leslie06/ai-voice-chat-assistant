#!/usr/bin/env python3
"""
电话识别评测: 用线上同一套识别配置(8k 窄带模型 + 热词表)识别客户说的每段话, 与人工标注对比。

两步走:
  1. python3 asr_eval.py <clips.csv>
       对 hyp 列为空的片段调识别, 结果写回 hyp 列。clips.csv 由 review_calls.py --clips 生成
  2. 打开 clips.csv, 在 ref 列填上客户实际说的话(边听 clips/ 里的 wav 边写; 听不清的行留空, 不参与评分),
     再跑一次同样的命令: 已有 hyp 的不再重复识别, 直接出分

评分:
  字错率(CER)     标点、空格不计; 按全部标注片段的总编辑距离 / 总字数算
  关键词命中率    客户说了某个关键词(ref 里有), 识别结果里也有, 才算命中。电话线上最容易错的是专有名词
                  (洗牙被听成"抵押"), 整体字错率看不出来, 所以单独算

换识别配置对比时(比如换模型、改热词表), 用 --rerun 重新识别, --out 写到另一个文件, 两份分数放一起看。

需要环境变量 DASHSCOPE_API_KEY(仓库根目录: set -a; source .env; set +a)。
录音片段里是客户的声音, clips.csv 里是客户说的话: 只在本机处理, 不要提交、不要外传。
"""
import argparse
import csv
import os
import re
import sys
from pathlib import Path

DEFAULT_KEYWORDS = ("洗牙,种植牙,正畸,矫正,补牙,拔牙,根管,牙周,烤瓷,贴面,美白,儿童,窝沟封闭,"
                    "预约,转人工,多少钱,价格,优惠,医保,营业时间,地址,停车,试听,课时,学费")
PUNCT = re.compile(r"[\s，。！？、,.!?;；:：\"'“”‘’（）()…—\-]")


def normalize(text):
    return PUNCT.sub("", text or "")


def edit_distance(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def recognize(path, model, vocabulary_id):
    from dashscope.audio.asr import Recognition   # 只有真要识别时才需要这个包
    kwargs = {"language_hints": ["zh"]}
    if vocabulary_id:
        kwargs["vocabulary_id"] = vocabulary_id
    rec = Recognition(model=model, format="wav", sample_rate=8000, callback=None, **kwargs)
    result = rec.call(str(path))
    if result.status_code != 200:
        raise RuntimeError(f"{result.status_code} {result.message}")
    sentences = result.get_sentence() or []
    if isinstance(sentences, dict):
        sentences = [sentences]
    return "".join(s.get("text", "") for s in sentences)


def score(rows, keywords):
    labeled = [r for r in rows if normalize(r.get("ref"))]
    if not labeled:
        return ["还没有人工标注(ref 列为空), 只完成了识别。填好 ref 后再跑一次出分。"]
    dist = sum(edit_distance(normalize(r["ref"]), normalize(r["hyp"])) for r in labeled)
    chars = sum(len(normalize(r["ref"])) for r in labeled)
    lines = [f"已标注 {len(labeled)} 段, 共 {chars} 字, 字错率 CER = {dist / chars:.1%}", ""]
    hits = []
    for kw in keywords:
        said = [r for r in labeled if kw in normalize(r["ref"])]
        if said:
            got = [r for r in said if kw in normalize(r["hyp"])]
            hits.append((kw, len(got), len(said), [r["hyp"] for r in said if r not in got][:3]))
    if hits:
        total_got, total_said = sum(h[1] for h in hits), sum(h[2] for h in hits)
        lines.append(f"关键词命中率 {total_got}/{total_said} = {total_got / total_said:.0%}")
        lines += ["", "| 关键词 | 命中 | 没识别对的样例 |", "|---|---|---|"]
        for kw, got, said, misses in sorted(hits, key=lambda h: h[1] / h[2]):
            lines.append(f"| {kw} | {got}/{said} | {' / '.join(misses) or '—'} |")
    wrong = [r for r in labeled if normalize(r["ref"]) != normalize(r["hyp"])]
    worst = sorted(wrong, key=lambda r: edit_distance(normalize(r["ref"]), normalize(r["hyp"])), reverse=True)[:10]
    if worst:
        lines += ["", "错得最多的片段:", ""] + [f"- {r['clip']}: 说的是「{r['ref']}」, 识别成「{r['hyp']}」" for r in worst]
    return lines


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("clips_csv")
    ap.add_argument("--model", default=os.environ.get("VCA_TELEPHONY_ASR_MODEL") or "paraformer-realtime-8k-v2",
                    help="识别模型, 默认与电话链路一致")
    ap.add_argument("--vocabulary-id", default=os.environ.get("VCA_TELEPHONY_ASR_VOCABULARY_ID", ""),
                    help="热词表 id(线上用的那张; 行业表 id 见 VCA 启动日志\"热词表已建\")")
    ap.add_argument("--keywords", default=DEFAULT_KEYWORDS, help="逗号分隔的关键词")
    ap.add_argument("--rerun", action="store_true", help="已有识别结果的也重新识别(换配置对比时用)")
    ap.add_argument("--out", help="结果写到另一个 csv(默认写回原文件)")
    args = ap.parse_args()

    src = Path(args.clips_csv)
    with open(src, newline="", encoding="utf-8-sig") as f:
        rows = list(csv.DictReader(f))
    clip_dir = src.parent / "clips"

    todo = [r for r in rows if args.rerun or not r.get("hyp")]
    if todo:
        if not os.environ.get("DASHSCOPE_API_KEY"):
            sys.exit("缺 DASHSCOPE_API_KEY: 仓库根目录执行 set -a; source .env; set +a")
        import dashscope
        dashscope.api_key = os.environ["DASHSCOPE_API_KEY"]
        print(f"识别 {len(todo)} 段(模型 {args.model}, 热词表 {args.vocabulary_id or '无'}) …")
        for i, r in enumerate(todo, 1):
            try:
                r["hyp"] = recognize(clip_dir / r["clip"], args.model, args.vocabulary_id)
            except Exception as e:   # 一段失败不影响其余
                r["hyp"] = ""
                print(f"  {r['clip']} 识别失败: {e}")
            if i % 10 == 0:
                print(f"  {i}/{len(todo)}")

    dst = Path(args.out) if args.out else src
    with open(dst, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

    print("\n".join(score(rows, [k.strip() for k in args.keywords.split(",") if k.strip()])))
    print(f"\n识别结果已写入 {dst}")


if __name__ == "__main__":
    main()
