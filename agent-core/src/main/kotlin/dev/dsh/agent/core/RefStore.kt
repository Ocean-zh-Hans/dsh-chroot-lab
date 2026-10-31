package dev.dsh.agent.core

/** 节点指纹：跨快照识别"同一个元素"的依据。 */
data class Fingerprint(
    val cls: String,
    val viewId: String,
    val text: String,
    val desc: String,
    val path: String,
    val bounds: Rect,
) {
    val structuralKey: String get() = cls + "|" + viewId + "|" + bounds

    companion object {
        fun of(n: Node) = Fingerprint(n.cls, n.viewId, n.text, n.desc, n.path, n.bounds)
    }
}

data class Binding(
    val ref: String,
    val nativeId: Long,
    val window: Int,
    val fp: Fingerprint,
    val lastSeq: Long,
)

/** 快照 + 本次分配的 ref 映射。 */
class SnapshotView(
    val snapshot: Snapshot,
    val refs: Map<Int, String>,
    val byRef: Map<String, Int>,
) {
    fun refOf(id: Int): String? = refs[id]
    fun idOf(ref: String): Int? = byRef[ref]
    val size: Int get() = snapshot.size
}

data class Candidate(val ref: String?, val nodeId: Int, val score: Double, val why: String)

data class Resolution(
    val nodeId: Int,
    val ref: String?,
    val relocated: Boolean,
    val score: Double,
    val candidates: List<Candidate> = emptyList(),
) {
    val ok: Boolean get() = nodeId >= 0
}

/**
 * ref 表：把"会话内稳定的名字"绑到快照节点上。
 *
 * 分配优先级：nativeId → 结构键（cls/viewId/bounds）→ 新分配。
 * 解析优先级：nativeId → 指纹打分（唯一且 >= RELOCATE_THRESHOLD）→ 失败但给候选。
 */
class RefStore(private val limit: Int = 8192) {

    private val bindings = LinkedHashMap<String, Binding>(256, 0.75f, true)
    private var counter = 1
    private var generation = 0L

    val generationNow: Long get() = generation

    fun bind(snap: Snapshot): SnapshotView {
        generation = snap.g
        val refs = HashMap<Int, String>(snap.size * 2)
        val byRef = HashMap<String, Int>(snap.size * 2)
        val used = HashSet<String>(snap.size * 2)

        val byNative = HashMap<Long, MutableList<Binding>>()
        val byStruct = HashMap<String, MutableList<Binding>>()
        for (b in bindings.values) {
            if (b.nativeId != 0L) byNative.getOrPut(b.nativeId) { ArrayList(1) }.add(b)
            byStruct.getOrPut(b.fp.structuralKey) { ArrayList(1) }.add(b)
        }

        // 1) nativeId 命中
        val pending = ArrayList<Int>(snap.size)
        for (n in snap.nodes) {
            val hit = if (n.nativeId != 0L) {
                byNative[n.nativeId]?.firstOrNull { it.ref !in used && it.window == n.window }
            } else null
            if (hit != null) {
                used.add(hit.ref)
                refs[n.id] = hit.ref
                byRef[hit.ref] = n.id
            } else {
                pending.add(n.id)
            }
        }

        // 2) 结构键命中（同窗口内唯一）
        val stillPending = ArrayList<Int>(pending.size)
        for (id in pending) {
            val n = snap.node(id)
            val cands = byStruct[Fingerprint.of(n).structuralKey]
                ?.filter { it.ref !in used && it.window == n.window }
            if (cands != null && cands.size == 1) {
                val b = cands[0]
                used.add(b.ref)
                refs[id] = b.ref
                byRef[b.ref] = id
            } else {
                stillPending.add(id)
            }
        }

        // 3) 新分配
        for (id in stillPending) {
            val n = snap.node(id)
            val ref = "@e" + counter++
            refs[id] = ref
            byRef[ref] = id
            put(Binding(ref, n.nativeId, n.window, Fingerprint.of(n), snap.seq))
        }

        // 刷新已有绑定
        for ((id, ref) in refs) {
            val n = snap.node(id)
            val prev = bindings[ref]
            if (prev != null) {
                bindings[ref] = prev.copy(
                    nativeId = if (n.nativeId != 0L) n.nativeId else prev.nativeId,
                    fp = Fingerprint.of(n),
                    lastSeq = snap.seq,
                )
            } else {
                put(Binding(ref, n.nativeId, n.window, Fingerprint.of(n), snap.seq))
            }
        }
        return SnapshotView(snap, refs, byRef)
    }

    private fun put(b: Binding) {
        bindings[b.ref] = b
        while (bindings.size > limit) {
            val eldest = bindings.keys.firstOrNull() ?: break
            bindings.remove(eldest)
        }
    }

    fun binding(ref: String): Binding? = bindings[ref]

    /** 在当前快照里解析 ref。找不到时返回 nodeId = -1 且带候选。 */
    fun resolve(view: SnapshotView, ref: String, threshold: Double = RELOCATE_THRESHOLD): Resolution {
        val bound = bindings[ref]
            ?: return Resolution(-1, ref, false, 0.0, emptyList())
        val snap = view.snapshot

        // 1) nativeId
        if (bound.nativeId != 0L) {
            val hit = snap.nodes.firstOrNull { it.nativeId == bound.nativeId && it.window == bound.window }
            if (hit != null) {
                val newRef = view.refOf(hit.id)
                return Resolution(hit.id, newRef, newRef != ref, 1.0)
            }
        }

        // 2) 打分
        val scored = snap.nodes
            .asSequence()
            .filter { it.window == bound.window }
            .map { Candidate(view.refOf(it.id), it.id, score(bound.fp, it), reason(bound.fp, it)) }
            .sortedByDescending { it.score }
            .take(5)
            .toList()

        val best = scored.firstOrNull()
        if (best != null && best.score >= threshold) {
            val second = scored.getOrNull(1)
            val margin = if (second == null) 1.0 else best.score - second.score
            if (second == null || margin >= 0.08) {
                return Resolution(best.nodeId, best.ref, best.ref != ref, best.score)
            }
            return Resolution(-1, ref, false, best.score, scored)
        }
        // 低于阈值不是“多个候选”，而是没找到；不要把 5 个相似节点误报成 AMBIGUOUS。
        return Resolution(-1, ref, false, best?.score ?: 0.0, emptyList())
    }

    /**
     * 证据合成：每个特征独立地"排除掉一部分不可能"，而不是线性加权。
     * 这样"文本+类+路径全同、位置小幅偏移"能到 0.78，而"只是同类"只有 0.2。
     */
    fun score(a: Fingerprint, n: Node): Double {
        val b = Fingerprint.of(n)
        var miss = 1.0
        if (a.viewId.isNotEmpty() && a.viewId == b.viewId) miss *= 0.40
        else if (a.viewId.isNotEmpty() && b.viewId.isNotEmpty()) miss *= 0.95
        miss *= 1.0 - 0.55 * textSim(a.text, b.text)
        miss *= if (a.cls == b.cls) 0.80 else 0.98
        if (a.path == b.path) miss *= 0.75
        miss *= 1.0 - 0.30 * proximity(a.bounds, b.bounds)
        return 1.0 - miss
    }

    private fun reason(a: Fingerprint, n: Node): String {
        val b = Fingerprint.of(n)
        val why = ArrayList<String>(3)
        if (a.viewId.isNotEmpty() && a.viewId == b.viewId) why.add("同 viewId")
        if (a.text == b.text && a.text.isNotEmpty()) why.add("同文本")
        if (a.cls == b.cls) why.add("同类")
        val d = a.bounds.centerDistance(b.bounds).toInt()
        if (d > 0) why.add("偏移 " + d + " px")
        return if (why.isEmpty()) "无共同特征" else why.joinToString("，")
    }

    companion object {
        const val RELOCATE_THRESHOLD = 0.72

        fun textSim(a: String, b: String): Double {
            if (a == b) return if (a.isEmpty()) 0.5 else 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0
            if (a.contains(b) || b.contains(a)) return 0.8
            return levenshteinRatio(a, b)
        }

        fun proximity(a: Rect, b: Rect): Double {
            val diag = Math.sqrt((a.w * a.w + a.h * a.h).toDouble()).coerceAtLeast(1.0)
            return (1.0 - a.centerDistance(b) / diag).coerceIn(0.0, 1.0)
        }

        fun levenshteinRatio(a: String, b: String): Double {
            val max = Math.max(a.length, b.length)
            if (max == 0) return 1.0
            return 1.0 - levenshtein(a, b).toDouble() / max
        }

        fun levenshtein(a: String, b: String): Int {
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                }
                val tmp = prev
                prev = cur
                cur = tmp
            }
            return prev[b.length]
        }
    }
}
