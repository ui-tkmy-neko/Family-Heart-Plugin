package nekouidaga.net.familyheartplugin.cache

import nekouidaga.net.familyheartplugin.model.FamilyRelationship
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class RelationshipCache {
    enum class State { LOADING, LOADED, FAILED }
    private data class Entry(val generation: Long, val relationships: List<FamilyRelationship>, val state: State)
    private val generation = AtomicLong(0)
    private val m = ConcurrentHashMap<UUID, Entry>()

    fun state(u: UUID): State = m[u]?.state ?: State.LOADING
    fun isLoaded(u: UUID): Boolean = m[u]?.state == State.LOADED
    fun markLoading(u: UUID): Long {
        val g = generation.incrementAndGet()
        m[u] = Entry(g, m[u]?.relationships ?: emptyList(), State.LOADING)
        return g
    }
    fun markFailed(u: UUID, g: Long) {
        m.compute(u) { _, old -> if (old == null || g >= old.generation) Entry(g, old?.relationships ?: emptyList(), State.FAILED) else old }
    }

    fun get(u: UUID): List<FamilyRelationship> = m[u]?.relationships ?: emptyList()

    fun replace(u: UUID, v: List<FamilyRelationship>, g: Long = generation.incrementAndGet()) {
        m.compute(u) { _, old -> if (old == null || g >= old.generation) Entry(g, v.toList(), State.LOADED) else old }
    }
    fun replaceMany(values: Map<UUID, List<FamilyRelationship>>, g: Long = generation.incrementAndGet()) {
        values.forEach { (u, v) -> replace(u, v, g) }
    }
    fun clear(u: UUID) { m.remove(u) }
    fun clearAll() { m.clear() }
    fun allLoadedEntries(): Map<UUID, List<FamilyRelationship>> = m.filterValues { it.state == State.LOADED }.mapValues { it.value.relationships }
}
