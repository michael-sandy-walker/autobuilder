# constraint-samples-withheld -- toy-case versions


## v1 -- 2026-11-02

- reason: T1-GG's third withheld case (user, 2026-11-02: show mechanism 2 on a case other than receiver-questions and edit-hunks): constraint-samples v2 cut to the withheld rung by hand -- its three specification tests moved to bootstrap/withheld (the pre-change RequestBodySynthesizerTest stays as the existing test, the one the loop may not change), the seed states the interface and the values the withheld tests check, the obligations mark 14 requirements 'gate: derived-test'. Yardstick checked: the pristine tree 1/27, the real implementation (3b825688's three classes) 27/27.
- origin-rev: `8c375afda04cff927ba989134cfef2d38c570e14`
- bootstrap digest: `8db07f86650262e0`
