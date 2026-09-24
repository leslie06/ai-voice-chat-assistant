"""review_calls.py 的自测: 用时间轴已知的合成通话(音调代替人声), 验证量出来的就是这些时刻。

    python3 -m unittest tools/call-review/test_review_calls.py
"""
import sys
import tempfile
import unittest
import wave
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import review_calls as rc  # noqa: E402

RATE = 8000


def tone(track, start, end, freq, amp=0.3, word_gap=True):
    """在 [start, end) 放一段"话": 音调, 每 0.6 秒夹一个 0.15 秒的字间停顿"""
    t = np.arange(int(start * RATE), int(end * RATE))
    sig = amp * np.sin(2 * np.pi * freq * t / RATE)
    if word_gap:
        sig[((t / RATE - start) % 0.6) > 0.45] = 0
    track[t] = sig


def write_call(path, caller_spans, ai_spans, duration=40.0):
    left = np.zeros(int(duration * RATE), dtype=np.float32)
    right = np.zeros_like(left)
    left += 0.002 * np.random.default_rng(1).standard_normal(len(left)).astype(np.float32)   # 线路底噪
    for a, b in caller_spans:
        tone(left, a, b, 220)
    for a, b in ai_spans:
        tone(right, a, b, 330)
    stereo = np.stack([left, right], axis=1)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes((np.clip(stereo, -1, 1) * 32767).astype("<i2").tobytes())


class ReviewCallsTest(unittest.TestCase):

    def test_measures_latency_barge_in_and_dead_air(self):
        caller = [(4.0, 6.0), (11.0, 12.5), (14.0, 15.0), (18.0, 19.0), (21.5, 23.0), (33.0, 34.0)]
        ai = [(0.8, 3.0), (7.2, 10.0), (13.0, 14.4), (20.5, 26.0), (35.0, 36.0)]
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "11111111-2222-3333-4444-555555555555.wav"
            write_call(p, caller, ai)
            r = rc.analyze(p)

        self.assertEqual(len(r["_caller_segments"]), 6)
        self.assertEqual(r["first_ai_s"], 0.8)
        # 说完 → AI 出声: 1.2 / 0.5 / 1.5 / 1.0; 14~15 那句之后客户自己又开口了, 不算; 21.5~23 那句说完时 AI 还在说, 不算
        self.assertEqual([round(x, 1) for x in r["reply_latencies"]], [1.2, 0.5, 1.5, 1.0])
        # 两次插话: 一次 0.4 秒就停了, 一次 AI 接着说了 4.5 秒 = 没理会
        self.assertEqual([round(x, 1) for x in r["barge_stops"]], [0.4, 4.5])
        self.assertEqual(r["missed_barges_at"], [21.5])
        # 26 秒到 33 秒两边都没声
        self.assertEqual(r["dead_air"], [(26.0, 7.0)])

    def test_ai_echo_on_caller_channel_is_not_speech(self):
        """AI 的声音漏进客户声道(实测 0.000~0.006 的回声)不能被当成客户在说话"""
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "call.wav"
            write_call(p, [(4.0, 6.0)], [(0.8, 3.0), (7.0, 12.0)])
            with wave.open(str(p), "rb") as w:
                data = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32).reshape(-1, 2)
            data[:, 0] += data[:, 1] * 0.02   # 回声
            with wave.open(str(p), "wb") as w:
                w.setnchannels(2)
                w.setsampwidth(2)
                w.setframerate(RATE)
                w.writeframes(np.clip(data, -32768, 32767).astype("<i2").tobytes())
            r = rc.analyze(p)
        self.assertEqual(len(r["_caller_segments"]), 1)
        self.assertEqual(r["barge_stops"], [])


if __name__ == "__main__":
    unittest.main()
