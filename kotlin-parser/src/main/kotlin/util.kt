package net.semlang.parser

import net.semlang.api.FunctionId
import net.semlang.api.HasFunctionId

fun <T: HasFunctionId> indexById(indexables: List<T>): Map<FunctionId, T> {
    return indexables.associateBy(HasFunctionId::id)
}
