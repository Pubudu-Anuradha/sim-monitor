# Carrier replies

`samples.jsonl` is the parser test data: real carrier message **formats** (wording, spacing and
punctuation exactly as received) with invented amounts and dates. The server tests load every
`*.jsonl` here.

Real captures from phones (the Diagnostics tab's Share, or
`adb exec-out run-as app.simmonitor cat files/probe-log.jsonl`) go in `local/`, which is
gitignored: they contain real balances and the names of other SMS senders. When a carrier changes its
wording, copy the new format into `samples.jsonl` with the numbers changed, then fix the parser.
