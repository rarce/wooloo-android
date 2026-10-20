import json
import unittest
from dependency_notices import google_notices


class GoogleNoticeTest(unittest.TestCase):
    def test_byte_offsets_preserve_unicode_and_original_text(self):
        raw = "préface\n© Notice λ\nsuffix".encode("utf-8")
        text = "© Notice λ\n"
        start = len("préface\n".encode("utf-8"))
        index = json.dumps({"Library": {"start": start, "length": len(text.encode("utf-8"))}})
        self.assertEqual([("Library", text)], google_notices(index, raw))

    def test_invalid_bounds_fail_instead_of_silently_truncating_license(self):
        for start, length in [(-1, 2), (0, 0), (0, 100), (2, 3)]:
            with self.subTest(start=start, length=length), self.assertRaises(ValueError):
                google_notices(json.dumps({"Library": {"start": start, "length": length}}), b"text")


if __name__ == "__main__":
    unittest.main()
