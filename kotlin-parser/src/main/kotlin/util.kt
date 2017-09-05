package net.semlang.parser

import net.semlang.api.EntityId
import net.semlang.api.HasId

// TODO: Is this actually unused? Or subsumed by something elsewhere?
fun <T: HasId> indexById(indexables: List<T>): Map<EntityId, T> {
    return indexables.associateBy(HasId::id)
}
