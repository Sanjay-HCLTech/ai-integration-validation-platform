# Reference Log Samples

Place canonical remote-to-local extraction samples in this folder.

These files are the reference contract for the LogAnalyzer block extraction rules:

- Keep the full logical block, not isolated grep lines.
- Preserve compact flows such as `REQUEST -> REPLY`.
- Preserve publish flows such as `REQUEST -> PUBLISH`.
- Preserve failure blocks such as `REQUEST ERROR` with stack evidence.
- Keep JobID/CorrID evidence available for correlation.

Run this from the project root after adding or replacing samples:

```powershell
.\verify-reference-log-samples.ps1
```

The verifier checks every `*.log` file in this folder and regenerates `sample-log-manifest.csv` from the actual file contents. Curated `BizKey`, `Service`, and `Operation` values live in `verify-reference-log-samples.ps1` so noisy payload suffixes do not overwrite the business reference metadata. If a sample fails the contract, fix the sample or update the extraction rules deliberately.

After generating logs for a BookingID, compare the local evidence files against
these canonical samples:

```powershell
.\compare-generated-logs.ps1 -BookingId 31835146
```

The comparator checks files under `C:\logs\<BookingID>\` against matching files
in this folder. Generated files should be physically and logically comparable to
the reference logs: no legacy grep prefixes, no missing business phases, and no
suspiciously short chunks unless the real flow is intentionally shorter.

These logs may contain sensitive data. The `.gitignore` in this folder prevents log and zip files from being committed by default.
