# Triage evaluation

Does the System One triage of `heal` (`heal.json` `triage`) start a failing check at the right model and effort? This
directory measures it against real failures, so a change to the prompt (the question, the level descriptions, what `brief`
shows) is judged by numbers, not by a feeling.

```
collect.py   real failures of your CI -> data/cases.json   (needs gh; data/ is not committed: it holds your CI logs)
labels.json  case id -> {level, basis, by, set}             (committed: the labels, by CI run id)
eval.py      the prompt of heal.json (or a variant file) against the labelled cases; needs Ollama and the triage model
fixtures/    28 hand-written failures with their labels, the set the first prompt was tuned on (a) and a held-out one (b)
selftest.sh  offline test of eval.py with a stub server
```

```bash
./collect.py                                  # downloads the failed logs, writes data/cases.json
./eval.py --split test --misses               # the committed prompt against the held-out labelled cases
./eval.py --variant heal.json --variant my-variant.json --split dev    # compare prompts while tuning (on dev only)
./eval.py --cases fixtures/synthetic-b.json   # the synthetic held-out set
```

## What it found (54 real failures, `kev:4b-q4_K_M`, labels by reading, so treat one case as noise)

| variant (`variants/`) | exact | within one | on 3 classes | per call |
|---|---|---|---|---|
| seven levels, old one-line descriptions, problem lines | 14 | 43 | | 10 s |
| seven levels, long descriptions, problem lines | 18 | 35 | | 17 s |
| seven levels, long descriptions, changed files and log end (5000 chars) | 19 | 37 | | 35 s |
| seven levels, 2,500 chars (`seven-mid`) | 19 | 38 | 32 | 22 s |
| four levels, problem lines (`four-short`) | 24 | 43 | 29 | 8 s |
| four levels, 2,500 chars (`four-mid`) | 22 | 41 | 28 | 18 s |
| three levels, 2,500 chars (`three-mid`) | 31 | 49 | 31 | 18 s |
| **three levels, problem lines (`three-short`, what `heal.json` has)** | **33** | **50** | **33** | **7 s** |

Fewer levels and a shorter brief both helped; the log end and the changed files added time and no accuracy. The model's confidence did not
separate right answers from wrong ones, so the confidence floor does nothing with three levels.

## Labels

A label is one of `trivial, easy, simple, moderate, average, hard, veryhard` (the levels of `heal.json`, which map to a start
model and effort). `basis` says how it was decided:

- `fix`: what the next green commit on the branch changed (the case's `next`: files and lines of the change, and how many
  failed runs it took). Small and quick means a low level, a wide change or several rounds a high one. It is a hint, not the
  truth: a fix commit often carries other work.
- `reading`: the label comes from reading the log and the change. This is a judgement.

`by` names who labelled (a person or a model). **Tune on `set: dev`, report on `set: test`.** A prompt that was written while
looking at a case scores well on it for that reason; the test set is for the one honest number.

## What to read in the result

- `within1` matters most: a miss by one level costs one extra attempt (the ladder goes on to the next rung), a miss by two or
  more costs money (`over2`) or time (`under2`).
- `policy-exact` is what heal would do, with the confidence floor (`minProbability`) sending a vague answer to the default level.
- Labels and cases are few; a difference of one case is noise. Collect more (`collect.py --limit`) before drawing a conclusion.
