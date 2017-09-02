package net.semlang.api

import org.junit.Assert
import org.junit.Test

class ContextTests {
    @Test
    fun testFunctionVisibility() {
        val coincidentallySharedInternalFunctionId = FunctionId.of("coincidentallySharedInternal")
        val upstreamFunctionWithSharedId = createFunctionWithId(coincidentallySharedInternalFunctionId, 1, false)
        val downstreamFunctionWithSharedId = createFunctionWithId(coincidentallySharedInternalFunctionId, 2, false)

        val upstreamInternalFunctionId = FunctionId.of("upstreamInternal")
        val upstreamInternalFunction = createFunctionWithId(upstreamInternalFunctionId, 3, false)

        val upstreamExportedFunctionId = FunctionId.of("upstreamExported")
        val upstreamExportedFunction = createFunctionWithId(upstreamExportedFunctionId, 4, true)

        val upstreamFunctions = mapOf(upstreamInternalFunctionId to upstreamInternalFunction,
                upstreamExportedFunctionId to upstreamExportedFunction,
                coincidentallySharedInternalFunctionId to upstreamFunctionWithSharedId)
        val upstreamExportedFunctions = setOf(upstreamExportedFunctionId)

        val downstreamInternalFunctionId = FunctionId.of("downstreamInternal")
        val downstreamInternalFunction = createFunctionWithId(downstreamInternalFunctionId, 5, false)

        val downstreamExportedFunctionId = FunctionId.of("downstreamExported")
        val downstreamExportedFunction = createFunctionWithId(downstreamExportedFunctionId, 6, true)

        val downstreamFunctions = mapOf(downstreamInternalFunctionId to downstreamInternalFunction,
                downstreamExportedFunctionId to downstreamExportedFunction,
                coincidentallySharedInternalFunctionId to downstreamFunctionWithSharedId)
        val downstreamExportedFunctions = setOf(downstreamExportedFunctionId)

        val upstreamModuleId = ModuleId("example", "upstream", "0.1")
        val downstreamModuleId = ModuleId("example", "downstream", "0.1")
        val upstreamContext = ValidatedModule.create(upstreamModuleId, upstreamFunctions, listOf())
        val downstreamContext = ValidatedModule.create(downstreamModuleId, downstreamFunctions, listOf(upstreamContext))

        // Upstream function IDs in the upstream context
        Assert.assertEquals(upstreamInternalFunction, upstreamContext.getInternalFunction(upstreamInternalFunctionId))
        Assert.assertEquals(null, upstreamContext.getExportedFunction(upstreamInternalFunctionId))
        Assert.assertEquals(upstreamExportedFunction, upstreamContext.getInternalFunction(upstreamExportedFunctionId))
        Assert.assertEquals(upstreamExportedFunction, upstreamContext.getExportedFunction(upstreamExportedFunctionId))
        Assert.assertEquals(upstreamFunctionWithSharedId, upstreamContext.getInternalFunction(coincidentallySharedInternalFunctionId))
        Assert.assertEquals(null, upstreamContext.getExportedFunction(coincidentallySharedInternalFunctionId))

        // Upstream function IDs in the downstream context
        Assert.assertEquals(null, downstreamContext.getInternalFunction(upstreamInternalFunctionId))
        Assert.assertEquals(null, downstreamContext.getExportedFunction(upstreamInternalFunctionId))
        Assert.assertEquals(upstreamExportedFunction, downstreamContext.getInternalFunction(upstreamExportedFunctionId))
        // Note that this doesn't get transitively exported by default:
        Assert.assertEquals(null, downstreamContext.getExportedFunction(upstreamExportedFunctionId))

        // Downstream function IDs in the downstream context
        Assert.assertEquals(downstreamInternalFunction, downstreamContext.getInternalFunction(downstreamInternalFunctionId))
        Assert.assertEquals(null, downstreamContext.getExportedFunction(downstreamInternalFunctionId))
        Assert.assertEquals(downstreamExportedFunction, downstreamContext.getInternalFunction(downstreamExportedFunctionId))
        Assert.assertEquals(downstreamExportedFunction, downstreamContext.getExportedFunction(downstreamExportedFunctionId))
        Assert.assertEquals(downstreamFunctionWithSharedId, downstreamContext.getInternalFunction(coincidentallySharedInternalFunctionId))
        Assert.assertEquals(null, downstreamContext.getExportedFunction(coincidentallySharedInternalFunctionId))
    }

    private fun createFunctionWithId(id: FunctionId, uniqueAspect: Int, exported: Boolean): ValidatedFunction {
        val block = TypedBlock(Type.INTEGER, listOf(), TypedExpression.Literal(Type.INTEGER, uniqueAspect.toString()))
        val annotations = if (exported) {
            listOf(Annotation("Exported", null))
        } else {
            listOf()
        }
        return ValidatedFunction(id, listOf(), listOf(), Type.INTEGER, block, annotations)
    }
}
