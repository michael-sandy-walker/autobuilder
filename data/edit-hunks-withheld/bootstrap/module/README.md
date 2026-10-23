# edit-hunks

The parser of a code-writing model's answer, extracted from Autobuilder. EvolutionResponseParser reads the
fenced code blocks of an answer -- each headed by a language tag and the file's path in brackets -- into the
files the answer adds or changes, the files it deletes, and a summary; EvolutionOutcome and FileMutation are
what it returns. Pure: the answer's text in, values out.
