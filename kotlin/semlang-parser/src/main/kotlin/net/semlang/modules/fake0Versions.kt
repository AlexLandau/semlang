package net.semlang.modules

import net.semlang.api.RawContext
import net.semlang.api.ValidatedModule
import net.semlang.parser.UnvalidatedModule
import net.semlang.parser.writeToString
import net.semlang.transforms.invalidate
import java.security.MessageDigest
import kotlin.experimental.xor

/**
 * A utility for computing a version under a "fake0" scheme that is similar in intention to hash-based versioning,
 * but different in implementation.
 */
// TODO: Consider adding the module name as an input to the hash
fun computeFake0Version(context: RawContext, upstreamModules: Collection<ValidatedModule>): String {
    return computeFake0Hash(context, upstreamModules).toHexString()
}

private fun computeFake0Hash(module: ValidatedModule): ByteArray {
    return computeFake0Hash(invalidate(module), module.upstreamModules.values)
}
private fun computeFake0Hash(context: RawContext, upstreamModules: Collection<ValidatedModule>): ByteArray {
    val md: MessageDigest = MessageDigest.getInstance("SHA-256")

    md.update(xor32ByteArrays(upstreamModules.map { computeFake0Hash(it) }))

    val moduleAsString = writeToString(context, deterministicMode = true)
    md.update(moduleAsString.toByteArray())

    return md.digest()
}

// Only accepts 256/8 = 32-length arrays
private fun xor32ByteArrays(arrays: List<ByteArray>): ByteArray {
    val combination = ByteArray(32, { i -> 0 })
    for (array in arrays) {
        if (array.size != 32) {
            error("Expected 32-byte arrays, but length was ${array.size}")
        }
        for (i in 0..31) {
            combination[i] = combination[i].xor(array[i])
        }
    }
    return combination
}

private fun ByteArray.toHexString(): String {
    return this.map { byte ->
        var asInt = byte.toInt()
        if (asInt < 0) {
            asInt += 256
        }
        asInt.toString(16)
    }.joinToString("")
}
