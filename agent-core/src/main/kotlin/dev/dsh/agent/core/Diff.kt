package dev.dsh.agent.core

data class ChangedItem(val ref: String, val field: String, val before: String, val after: String)

data class Change(
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val changed: List<ChangedItem> = emptyList(),
    val stable: Boolean = true,
    val quietMs: Long = 0,
    val g: Long = 0,
    val seq: Long = 0,
) {
    val empty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
}

/** 按 ref 连续性做快照差分。 */
object Diff {

    fun compute(prev: SnapshotView?, next: SnapshotView, quietMs: Long = 0, stable: Boolean = true): Change {
        if (prev == null) {
            val added = next.snapshot.walk()
                .filter { id -> labelOf(next, id).isNotEmpty() }
                .mapNotNull { next.refOf(it) }
            return Change(added = added.take(20), stable = stable, quietMs = quietMs, g = next.snapshot.g, seq = next.snapshot.seq)
        }
        val added = ArrayList<String>(8)
        val changed = ArrayList<ChangedItem>(8)
        val prevSeen = HashSet<Int>(prev.size * 2)

        for (id in next.snapshot.walk()) {
            val ref = next.refOf(id) ?: continue
            val before = prev.idOf(ref)
            if (before == null) {
                if (isMeaningful(next.snapshot.node(id))) added.add((ref + " " + describe(next.snapshot.node(id))).trimEnd())
                continue
            }
            prevSeen.add(before)
            val a = prev.snapshot.node(before)
            val b = next.snapshot.node(id)
            if (a.text != b.text) changed.add(ChangedItem(ref, "text", a.text, b.text))
            else if (a.desc != b.desc) changed.add(ChangedItem(ref, "desc", a.desc, b.desc))
            if (a.checked != b.checked) changed.add(ChangedItem(ref, "checked", a.checked.toString(), b.checked.toString()))
            if (a.enabled != b.enabled) changed.add(ChangedItem(ref, "enabled", a.enabled.toString(), b.enabled.toString()))
            if (a.bounds != b.bounds && a.window == b.window && isMeaningful(b)) {
                changed.add(ChangedItem(ref, "bounds", a.bounds.toString(), b.bounds.toString()))
            }
        }

        val removed = ArrayList<String>(8)
        for ((ref, id) in prev.byRef) {
            if (id in prevSeen) continue
            val n = prev.snapshot.node(id)
            if (isMeaningful(n)) removed.add((ref + " " + describe(n)).trimEnd())
        }
        return Change(added.take(20), removed.take(20), changed.take(40), stable, quietMs, next.snapshot.g, next.snapshot.seq)
    }

    fun isMeaningful(n: Node): Boolean =
        n.label.isNotEmpty() || n.clickable || n.editable || n.scrollable || n.has(Flags.CHECKABLE)

    fun describe(n: Node): String {
        val t = n.label
        return if (t.isEmpty()) n.role else n.role + " \"" + truncate(t, 60) + "\""
    }

    private fun labelOf(v: SnapshotView, id: Int): String = v.snapshot.node(id).label

    fun truncate(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, max - 1) + "…"
}
