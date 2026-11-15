package io.github.aedev.flow.data.update

private val RunHeading = Regex("""^#\s+Run\s+(\d+)\s*$""")
private val SectionHeading = Regex("""^##\s+(.+?)\s*$""")
private val SectionOrder = listOf("New", "Improved", "Fixed")

/**
 * The nightly release body cut down to the runs newer than [installedRun]. CI groups the body under
 * `# Run N` headings by the nightly that first shipped each note; the kept groups are merged into one
 * set of sections so the update page reads as a single list. A body without run groups is returned
 * as it is.
 */
internal fun filterNightlyNotes(
    markdown: String,
    installedRun: Int,
): String {
    val lines = markdown.lines()
    val firstRun = lines.indexOfFirst { RunHeading.matches(it.trim()) }
    if (firstRun < 0) return markdown

    val intro = lines.take(firstRun).joinToString("\n").trim()
    val prelude = mutableListOf<String>()
    val sections = linkedMapOf<String, MutableList<String>>()
    var keep = false
    var section: String? = null
    for (raw in lines.drop(firstRun)) {
        val line = raw.trim()
        val run = RunHeading.matchEntire(line)
        if (run != null) {
            keep = run.groupValues[1].toInt() > installedRun
            section = null
            continue
        }
        if (!keep || line.isEmpty()) continue
        val heading = SectionHeading.matchEntire(line)
        if (heading != null) {
            section = heading.groupValues[1].also { sections.getOrPut(it) { mutableListOf() } }
        } else {
            section?.let { sections.getValue(it) += line } ?: prelude.add(line)
        }
    }

    val ordered = SectionOrder.filter { it in sections } + sections.keys.filterNot { it in SectionOrder }
    val blocks =
        buildList {
            if (intro.isNotEmpty()) add(intro)
            if (prelude.isNotEmpty()) add(prelude.joinToString("\n"))
            ordered.forEach { title ->
                val items = sections.getValue(title)
                if (items.isNotEmpty()) add((listOf("## $title") + items).joinToString("\n"))
            }
        }
    return blocks.joinToString("\n\n")
}
