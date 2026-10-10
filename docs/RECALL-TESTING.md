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

## RECALL-2.2
- Install over 2.1: versionCode 122, versionName RECALL-2.2, visible in Recall header. APK inside ZIP is VoiceBeam-RECALL-2.2.apk to avoid selecting an older app-debug.apk. CI asserts embedded APK version and signer.
- Daily recap replaces the oldest-first six-extract display. Each conversation from the selected day is summarized with validated source citations. Recomputes after Pause and queued transcription; changed sources clear stale results. Cache is in-memory only: reopening after process restart regenerates. No daily generation while actively capturing; use Pause to update. Long days can take time and produce several statements per conversation.
- Ask status no longer leaks into Home. Extract fallback explains invalid JSON/no answer/failed evidence checks. Speak/spoke questions select broad summary handling.

## RECALL-2.3
- Same signer, code 123. Transcript instructions moved to system context, contextual spelling hints removed. Long 8-word exact instruction overlaps or distinctive hint/command echo reject the entire result rather than guessing which speech prefix was real. Genuine audio reading those instructions can be held too: replay is the authority.
- Original WAV and contaminated raw model output retained for review; embeddings/notes cleared and contaminated text excluded from combined transcript, Ask and recap. Retry transcribes original WAV afresh. Existing contaminated clips quarantined at startup.
- Native generate-call watchdog requests cancellation at 45 s (transcription 60 s). Recap wall deadline 120 s includes inference wait; only cancels its own operation. Progress shows elapsed/current conversation, partial results retained with coverage. No unbounded recap restart; timeout/failure can be retried.
- If native cancellation fails to return, the UI reports cancellation and the mutex remains held: no unsafe parallel model calls. Force-close/reopen is the recovery then; recordings remain saved. Initialization/embedding are not proven cancellable.
- Retest the photographed 19 s clip, silence/crowd and known model names. Inspect old source 12 quarantine; Retry; generated recap across four conversations should finish or explicitly fail within two minutes. Sustained native cancellation and prompt-placement accuracy require phone testing.

### Per-source recap and continuous transcript
- Conversation text is one flowing text surface, with tiny clickable timestamp-only anchors. Speaker names remain manual, not automatic diarization. Internal IDs remain in raw processing and Ask evidence.
- Recap generates at most one short answer per audio window, displays by conversation/time, evidence collapsed by default. No UI text truncation. The earlier secondary text was an exact supporting quote, not another generated statement.
- Successful mini-summaries stored privately in SQLite v5 alongside the exact input transcript; migration preserves existing data. Restart/Retry resumes changed or unfinished windows. Day roll-up is an ordered collection, not another large generation call. Greater source count still costs more total time; two-minute runs can finish partially and resume.
- Retest 4 conversations / 12 sources: monitor source count, full completion or timeout; restart and confirm saved minis return without recomputing. Old instruction-contaminated windows are excluded, so counts can be lower until retried.
