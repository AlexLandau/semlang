package semlang.interpreter

import semlang.api.FunctionId
import semlang.api.Package
import semlang.interpreter.SemObject
import java.util.*

class NativeFunction(val id: FunctionId, val apply: (List<SemObject>) -> SemObject, val numArgs: Int)

fun getNativeFunctions(): Map<FunctionId, NativeFunction> {
    val map = HashMap<FunctionId, NativeFunction>()

    val integerPackage = Package(listOf("integer"))

    // integer.times
    val integerTimesId = FunctionId(integerPackage, "times")
    map.put(integerTimesId, NativeFunction(integerTimesId, { args: List<SemObject> ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Integer && right is SemObject.Integer) {
            SemObject.Integer(left.value * right.value)
        } else {
            throw IllegalArgumentException()
        }
    }, 2))

    // integer.plus
    val integerPlusId = FunctionId(integerPackage, "plus")
    map.put(integerPlusId, NativeFunction(integerPlusId, { args: List<SemObject> ->
        val left = args[0]
        val right = args[1]
        if (left is SemObject.Integer && right is SemObject.Integer) {
            SemObject.Integer(left.value + right.value)
        } else {
            throw IllegalArgumentException()
        }
    }, 2))

    return map
}
