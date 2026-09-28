#!/usr/bin/env python3
"""T5-BI: the narrator's text filter (src/main/resources/static/speakable.js), run under node.

Invented coordinates only; each case is one rule of the filter. Skipped when node is absent.
"""
import json
import os
import shutil
import subprocess
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
JS = os.path.join(HERE, '..', 'src', 'main', 'resources', 'static', 'speakable.js')


def sayable(texts):
    script = ("const {sayable}=require(%s);"
              "const input=JSON.parse(require('fs').readFileSync(0,'utf8'));"
              "process.stdout.write(JSON.stringify(input.map(sayable)));") % json.dumps(os.path.abspath(JS))
    out = subprocess.run(['node', '-e', script], input=json.dumps(texts), capture_output=True, text=True, check=True)
    return json.loads(out.stdout)


@unittest.skipUnless(shutil.which('node'), 'node not installed')
class SpeakableTest(unittest.TestCase):

    def say(self, text):
        return sayable([text])[0]

    def test_a_source_path_is_said_as_the_files_own_name(self):
        self.assertEqual(self.say('Implement src/main/java/org/ink/quill/InkWell.java now'), 'Implement Ink Well now')

    def test_a_parameter_list_is_not_read_out_and_an_invocation_index_is_a_case(self):
        self.assertEqual(self.say('dries(String, String, Map<String,Nib>, int)[10] failed'), 'dries, case 10 failed')

    def test_a_qualified_name_is_said_by_its_simple_name(self):
        self.assertEqual(self.say('org.ink.quill.Nib required a bean'), 'Nib required a bean')

    def test_ids_and_markers_are_not_read_out(self):
        self.assertEqual(self.say('goal-2e4ccfc5-b113-4e1a-b862-0ec1e8ea1fd6: [BUILD_FIX] repair the nib'),
                         'build fix: repair the nib')

    def test_an_http_path_is_said_as_words(self):
        self.assertEqual(self.say('GET /quills/{id}/ink answers 404'), 'GET quills id ink answers 404')

    def test_a_versioned_archive_is_said_without_its_version(self):
        self.assertEqual(self.say('no manifest in /tmp/out/target/quill-1.2.3-SNAPSHOT.jar'), 'no manifest in quill')

    def test_a_clause_said_twice_in_a_row_is_said_once(self):
        self.assertEqual(self.say('Nib, Nib, Nib.'), 'Nib.')

    def test_a_long_text_is_capped(self):
        said = self.say(' '.join(['word'] * 90))
        self.assertTrue(said.endswith('... and more.'))
        self.assertLessEqual(len(said.split(' ')), 44)

    def test_plain_narration_is_unchanged(self):
        for text in ('Cycle merged, continue.', 'Scored 0.84, accepted.', 'Goal fulfilled.'):
            self.assertEqual(self.say(text), text)


if __name__ == '__main__':
    unittest.main()
