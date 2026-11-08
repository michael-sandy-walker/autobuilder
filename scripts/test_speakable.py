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

    def test_a_method_of_a_type_is_said_as_type_and_method_not_as_a_file(self):
        # T5-BO: "Add EditHunks.apply(...)" was heard as "Add Edit Hunks" -- the method read as an extension.
        self.assertEqual(self.say('Add QuillPress.dry(String, Map<String,Nib>)'), 'Add Quill Press dry')
        self.assertEqual(self.say('Add QuillPress.isWet(String)'), 'Add Quill Press is Wet')

    def test_a_method_named_without_parentheses_keeps_the_method(self):
        # the run's own text: "Fix EditHunks.isEdit to return false ..." was heard as "Fix Edit Hunks"
        self.assertEqual(self.say('Fix QuillPress.isWet to return false.'), 'Fix Quill Press is Wet to return false.')
        self.assertEqual(self.say('Add QuillPress.dry so that ink dries.'), 'Add Quill Press dry so that ink dries.')

    def test_a_bare_file_name_in_prose_keeps_its_extension_word(self):
        self.assertEqual(self.say('Add tests to QuillPressTest.java.'), 'Add tests to Quill Press Test java.')

    def test_plain_narration_is_unchanged(self):
        for text in ('Cycle merged, continue.', 'Scored 0.84, accepted.', 'Goal fulfilled.'):
            self.assertEqual(self.say(text), text)



def file_lines(cases):
    """T5-BO: "Editing <files>." built with fileNames, then spoken through sayable, as the page does."""
    script = ("const {sayable,fileNames}=require(%s);"
              "const input=JSON.parse(require('fs').readFileSync(0,'utf8'));"
              "process.stdout.write(JSON.stringify(input.map(c=>sayable('Editing '+fileNames(c[0],c[1])+'.'))));"
              ) % json.dumps(os.path.abspath(JS))
    out = subprocess.run(['node', '-e', script], input=json.dumps(cases), capture_output=True, text=True, check=True)
    return json.loads(out.stdout)


@unittest.skipUnless(shutil.which('node'), 'node not installed')
class FileNamesTest(unittest.TestCase):

    def test_one_file_is_said_by_its_own_name(self):
        self.assertEqual(file_lines([[['src/main/java/org/ink/QuillPress.java'], 2]]), ['Editing Quill Press.'])

    def test_two_files_are_joined_with_and(self):
        self.assertEqual(file_lines([[['a/Quill.java', 'b/NibHolder.java'], 2]]), ['Editing Quill and Nib Holder.'])

    def test_more_files_than_the_limit_are_counted(self):
        self.assertEqual(file_lines([[['a/Quill.java', 'b/Nib.java', 'c/Ink.java', 'd/Pot.java'], 2]]),
                         ['Editing Quill, Nib and 2 more.'])

    def test_no_files_say_nothing_about_files(self):
        script = "const {fileNames}=require(%s);process.stdout.write(JSON.stringify([fileNames([],2),fileNames(null,2)]));" \
                 % json.dumps(os.path.abspath(JS))
        out = subprocess.run(['node', '-e', script], capture_output=True, text=True, check=True)
        self.assertEqual(json.loads(out.stdout), ['', ''])



@unittest.skipUnless(shutil.which('node'), 'node not installed')
class HeadlineTest(unittest.TestCase):
    """T5-BO: a new or changed subgoal is announced by its headline, not its whole specification."""

    def headline(self, text):
        script = "const {headline}=require(%s);process.stdout.write(JSON.stringify(headline(require('fs').readFileSync(0,'utf8'))));" \
                 % json.dumps(os.path.abspath(JS))
        out = subprocess.run(['node', '-e', script], input=text, capture_output=True, text=True, check=True)
        return json.loads(out.stdout)

    def test_parenthesised_asides_are_dropped(self):
        self.assertEqual(self.headline('Add QuillPress.dry(String ink, Map<String, String> nibs) returning a value '
                                       'exposing marks() (Map<String,String> path->stroke) and blots() (List<String>).'),
                         'Add QuillPress.dry returning a value exposing marks and blots')

    def test_cut_at_the_first_clause_break_not_at_a_dot_inside_a_name(self):
        self.assertEqual(self.headline('Fix QuillPress.isWet to return false when the nib is dry, and log it. More.'),
                         'Fix QuillPress.isWet to return false when the nib is dry')

    def test_a_colon_keeps_what_it_introduces(self):
        self.assertEqual(self.headline('Add a test file covering: wet detection, blot refusal'),
                         'Add a test file covering: wet detection')

    def test_a_long_headline_is_capped(self):
        self.assertEqual(len(self.headline(' '.join(['word'] * 40)).split(' ')), 24)

    def test_a_trailing_directory_slash_is_not_read_out(self):
        self.assertEqual(sayable(['Add tests in src/test/java/org/ink/quill/ for it'])[0], 'Add tests in quill for it')


if __name__ == '__main__':
    unittest.main()
