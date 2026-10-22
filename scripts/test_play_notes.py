import tempfile
import unittest
from pathlib import Path
from unittest import mock

import play_notes
from play_notes import FALLBACK, PLAY_LIMIT, play_notes as notes, plain_text, truncate


class PlayNotesTest(unittest.TestCase):
    def test_markdown_becomes_plain_text(self):
        markdown = (
            '# wooloo mobile 0.1.2\n\n'
            '<!-- draft -->\n'
            '## What\'s changed\n\n'
            '- **Space screen**: one list grouped by tab ([#23](https://github.com/x/y/issues/23))\n'
            '* Keeps the `SSH` connection across *pane* switches\n'
            '> Gboard keeps the word you are editing\n\n\n\n'
            '```\ncode fence\n```\n'
            '---\n'
            '![shot](docs/a.png) Host GPU usage\n'
        )
        self.assertEqual(
            "wooloo mobile 0.1.2\n\nWhat's changed\n\n"
            '• Space screen: one list grouped by tab (#23)\n'
            '• Keeps the SSH connection across pane switches\n'
            'Gboard keeps the word you are editing\n\n'
            'code fence\n'
            'shot Host GPU usage',
            plain_text(markdown))

    def test_github_generated_notes_drop_attribution_and_compare_link(self):
        generated = (
            "## What's Changed\n"
            '* Show a space as one list grouped by tab by @rarce in https://github.com/rarce/wooloo-android/pull/29\n'
            '* Bump a dependency by @dependabot[bot] in https://github.com/rarce/wooloo-android/pull/40\n\n'
            '**Full Changelog**: https://github.com/rarce/wooloo-android/compare/v0.1.1...v0.1.2\n'
        )
        self.assertEqual(
            "What's Changed\n• Show a space as one list grouped by tab\n• Bump a dependency",
            plain_text(generated))

    def test_snake_case_and_arithmetic_survive(self):
        self.assertEqual('host_stats keeps 2 * 3 and a*b', plain_text('host_stats keeps 2 * 3 and a*b'))

    def test_short_text_is_unchanged(self):
        self.assertEqual('Fixes.', truncate('Fixes.'))
        exact = 'a' * PLAY_LIMIT
        self.assertEqual(exact, truncate(exact))

    def test_long_text_is_cut_on_a_word_boundary_within_the_limit(self):
        text = ' '.join(['word'] * 200)
        cut = truncate(text)
        self.assertLessEqual(len(cut), PLAY_LIMIT)
        self.assertTrue(cut.endswith('word…'))
        self.assertNotIn('wor…', cut.replace('word…', ''))
        # A single word longer than the limit is still bounded.
        self.assertEqual(PLAY_LIMIT, len(truncate('x' * 2000)))
        # Trailing punctuation before the cut is not left dangling before the ellipsis.
        self.assertTrue(truncate('one, ' * 200).endswith('one…'))

    def test_counts_characters_not_bytes(self):
        text = 'ñandú ' * 100
        cut = truncate(text)
        self.assertLessEqual(len(cut), PLAY_LIMIT)
        self.assertGreater(len(cut.encode('utf-8')), PLAY_LIMIT)

    def test_empty_or_markup_only_notes_use_a_generic_line(self):
        for markdown in ['', '  \n', '<!-- nothing -->', '---\n']:
            with self.subTest(markdown=markdown):
                self.assertEqual(FALLBACK, notes(markdown))

    def test_cli_writes_the_play_whatsnew_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp, 'notes.md')
            source.write_text('## Fixes\n\n- Faster pane switches\n', encoding='utf-8')
            output = Path(tmp, 'whatsnew', 'whatsnew-en-US')
            with mock.patch('sys.argv', ['play_notes.py', '--input', str(source), '--output', str(output)]):
                play_notes.main()
            self.assertEqual('Fixes\n\n• Faster pane switches', output.read_text(encoding='utf-8'))
            missing = Path(tmp, 'other', 'whatsnew-en-US')
            with mock.patch('sys.argv', ['play_notes.py', '--input', str(Path(tmp, 'absent.md')), '--output', str(missing)]):
                play_notes.main()
            self.assertEqual(FALLBACK, missing.read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
