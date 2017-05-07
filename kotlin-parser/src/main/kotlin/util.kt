import semlang.api.FunctionId
import semlang.api.HasFunctionId

fun <T: HasFunctionId> indexById(indexables: List<T>): Map<FunctionId, T> {
    return indexables.associateBy(HasFunctionId::id)
}
