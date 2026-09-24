"""asr_eval.py 评分部分的自测(不调识别接口)。 python3 -m unittest tools/call-review/test_asr_eval.py"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import asr_eval as ae  # noqa: E402


class AsrEvalScoreTest(unittest.TestCase):

    def test_cer_ignores_punctuation_and_counts_edits(self):
        self.assertEqual(ae.normalize("你好，洗牙 多少钱？"), "你好洗牙多少钱")
        self.assertEqual(ae.edit_distance("洗牙多少钱", "抵押多少钱"), 2)
        self.assertEqual(ae.edit_distance("", "abc"), 3)

    def test_keyword_hit_only_when_said_and_recognized(self):
        rows = [
            {"clip": "a.wav", "ref": "洗牙多少钱", "hyp": "抵押多少钱"},      # 线上实测过的错法
            {"clip": "b.wav", "ref": "种植牙多少钱？", "hyp": "种植牙多少钱"},
            {"clip": "c.wav", "ref": "", "hyp": "听不清的不参与评分"},
        ]
        out = "\n".join(ae.score(rows, ["洗牙", "种植牙", "多少钱", "正畸"]))
        self.assertIn("已标注 2 段, 共 11 字", out)
        self.assertIn("CER = 18.2%", out)                 # 2 / 11
        self.assertIn("关键词命中率 3/4", out)            # 洗牙 0/1, 种植牙 1/1, 多少钱 2/2; 正畸没人说, 不计
        self.assertIn("| 洗牙 | 0/1 | 抵押多少钱 |", out)
        self.assertIn("a.wav: 说的是「洗牙多少钱」, 识别成「抵押多少钱」", out)

    def test_nothing_labeled_yet(self):
        out = ae.score([{"clip": "a.wav", "ref": "", "hyp": "x"}], ["洗牙"])
        self.assertIn("还没有人工标注", out[0])


if __name__ == "__main__":
    unittest.main()
