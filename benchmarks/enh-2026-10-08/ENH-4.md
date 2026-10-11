# ENH-4 candidate
- Measured ENH-2 full-GTCRN clean target correlation .992 at6dB vs .956 at12dB; target+noise .974 vs .944. New-user default hearing boost6dB, peak cap retained. Existing saved boost preference preserved; this is not a medical volume recommendation.
- White ENH/enrollment text on dark status panel, fixing low-contrast text observed in actual ENH-2 Pixel screenshot.
- Fixture alignment metric now searches both signs of delay; prior raw/half-mix correlation values may be invalid because initial denoiser buffering skipped frames. Do not choose denoise Light from those values.
- Full denoise default unchanged. Raw scoring/template improvements from ENH-3 retained, not device-verified until a current device test.
- No routing change, no SEP.
