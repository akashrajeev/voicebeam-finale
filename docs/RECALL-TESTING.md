# RECALL-2.1 phone checks

Install over RECALL-2. Do not uninstall: same stable signer, SQLite v3 to v4 migration preserves recordings/models and adds session duration. Old durations derive from replay-window endpoints.

- Record known single-speaker speech for 70 seconds, then Pause. Verify live elapsed time and saved duration, one conversation transcript, 0:00/0:24/0:48 replay anchors, no claim that windows are speakers. Exact 2-12 word matching overlaps are hidden in the combined view; raw text and audio are unchanged. Punctuation/case are normalized only for comparison.
- Ask: "so what did we do today tell me". It should describe discussed topics across the session, with exact evidence/replay for each generated statement, not just quote the intro. Ask a specific later-clip fact and an unsupported fact. Verify today-scoped Ask doesn't pull yesterday.
- Gemma generation uses up to 8 KB transcript batches; longer recaps return several groups of cited statements, not one global merged summary. All recorded transcript sources enter the batches. Specific questions retrieve six full source clips.
- Generated answer evidence is mechanically checked as exact source quotes and existing source IDs. This DOES NOT prove semantic entailment or ASR truth; inspect evidence/replay. Invalid generation attempts an extract fallback; unsupported output can abstain.
- Clips with empty/malformed notes get unchanged source-sentence key points, not inferred actions. Existing zero-note clips display source fallback key points in the conversation view.
- Silence/crowd/repeated-loop guards unchanged; speech detection is not intended-speaker selection or a truth detector.
- No fixed session cap; Android/process interruption, available storage, battery and heat still limit continuous recording. Duration is captured-sample time, not processing time.
- Listen DSP untouched. Phone speed, sustained throughput, long-session layout and semantic answer quality need testing. Emulator cannot launch on the current host (insufficient RAM).
