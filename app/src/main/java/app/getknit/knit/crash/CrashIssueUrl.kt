package app.getknit.knit.crash

import java.net.URLEncoder

/**
 * Builds a prefilled URL for Knit's own GitHub bug-report form.
 *
 * The app never posts anything. This produces a link; the user's browser opens the form and the user
 * submits it, so there is no token, no API call, and no automatic egress.
 *
 * The query keys are the `id`s of the fields in `.github/ISSUE_TEMPLATE/bug_report.yml` — that file and
 * this one are a contract, and `CrashIssueUrlTest` is what breaks if it is edited out from under us.
 *
 * **Steps to reproduce and What you expected are deliberately not prefilled.** Both are `required` in
 * the template, so GitHub refuses to submit until the reporter writes them — which is precisely the gap
 * the needs-info bot flagged on issue #9, where both were answered by restating the question. Everything
 * the *app* can know arrives filled in; the two things only a human knows stay mandatory.
 *
 * Pure Kotlin — no `android.net.Uri`, which is stubbed to `null` under `isReturnDefaultValues` and would
 * make this untestable off-device.
 */
object CrashIssueUrl {
    /**
     * GitHub rejects prefill URLs beyond roughly 8 KB. Percent-encoding roughly triples a stack trace
     * (`\n`, `\t` and every space become three characters), so the excerpt is fitted against the
     * **encoded** length — a raw-length budget would overshoot by 3x and 414 on exactly the long traces
     * that matter most.
     */
    const val MAX_URL_CHARS = 7_000

    /** Marks an issue as app-filed, for the triage workflow. Mirrors `needs-info.yml`'s own comment marker. */
    const val MARKER = "<!-- knit-crash-report -->"

    private const val TEMPLATE = "bug_report.yml"
    private const val TRUNCATED = "... truncated, full trace on clipboard"

    /** Exactly one of the template dropdown's option strings; the reporter narrows it if they know. */
    private const val RADIOS_UNKNOWN = "Not sure"

    private const val SUMMARY = "Knit closed unexpectedly. Crash details were captured on-device and are below."
    private const val ACTUAL = "The app crashed. The stack trace is under \"Logs / screenshots\"."

    /**
     * The prefilled new-issue URL for [ref], carrying as much of [text] as the budget allows. [issuesBase]
     * is the repository's `.../issues/new`.
     */
    fun forReport(
        issuesBase: String,
        ref: CrashReportRef,
        text: String,
    ): String {
        val fixed =
            listOf(
                "template" to TEMPLATE,
                "title" to "[Bug]: Crash - ${ref.summary}",
                "summary" to SUMMARY,
                "actual" to ACTUAL,
                "version" to ref.appVersion,
                "devices" to listOf(ref.device, ref.androidVersion).filter { it.isNotBlank() }.joinToString(", "),
                "radios" to RADIOS_UNKNOWN,
            )
        val headroom = MAX_URL_CHARS - build(issuesBase, fixed + ("logs" to logs(""))).length
        return build(issuesBase, fixed + ("logs" to logs(fit(text, headroom))))
    }

    /** Assembles the query. Blank values are dropped rather than emitted empty, so the form shows its own placeholder. */
    internal fun build(
        issuesBase: String,
        fields: List<Pair<String, String>>,
    ): String =
        fields
            .filter { it.second.isNotBlank() }
            .joinToString("&", prefix = "$issuesBase?") { (key, value) -> "$key=${encode(value)}" }

    /** `URLEncoder` emits `+` for a space, which GitHub renders literally; the form wants `%20`. */
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun logs(excerpt: String): String =
        buildString {
            appendLine(MARKER)
            appendLine("Captured by Knit on-device and redacted before leaving the phone.")
            appendLine("The full trace is on your clipboard - paste it below to replace this excerpt.")
            appendLine()
            appendLine("```text")
            appendLine(excerpt)
            appendLine("```")
        }

    /**
     * The longest prefix of [text] whose *encoded* form fits in [budget], truncating the tail so the
     * header and top frames survive. Steps down by whole lines: a trace cut mid-frame reads as corrupt.
     */
    private fun fit(
        text: String,
        budget: Int,
    ): String {
        if (budget <= 0) return TRUNCATED
        if (encode(text).length <= budget) return text
        // The marker is appended on its own line, so its separator counts against the budget too.
        val tail = "\n" + TRUNCATED
        val tailCost = encode(tail).length
        val kept = StringBuilder()
        for (line in text.lineSequence()) {
            val candidate = if (kept.isEmpty()) line else "$kept\n$line"
            if (encode(candidate).length + tailCost > budget) break
            kept.setLength(0)
            kept.append(candidate)
        }
        return if (kept.isEmpty()) TRUNCATED else kept.toString() + tail
    }
}
