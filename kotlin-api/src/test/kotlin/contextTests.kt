package net.semlang.api

import org.junit.Assert
import org.junit.Test

// TODO: Test case where two upstream contexts export the same ID (of the same or differing entity types)
class ContextTests {
    @Test
    fun testFunctionVisibility() {
        testEntityVisibility(::createFunctionWithId,
                fun (moduleId: ModuleId, entities: Map<FunctionId, ValidatedFunction>, upstreamModules: List<ValidatedModule>): ValidatedModule {
                    return ValidatedModule.create(moduleId, entities, mapOf(), mapOf(), upstreamModules)
                },
                ValidatedModule::getInternalFunction,
                ValidatedModule::getExportedFunction)
    }

    @Test
    fun testStructVisibility() {
        testEntityVisibility(::createStructWithId,
                fun (moduleId: ModuleId, entities: Map<FunctionId, Struct>, upstreamModules: List<ValidatedModule>): ValidatedModule {
                    return ValidatedModule.create(moduleId, mapOf(), entities, mapOf(), upstreamModules)
                },
                ValidatedModule::getInternalStruct,
                ValidatedModule::getExportedStruct)
    }

    @Test
    fun testInterfaceVisibility() {
        testEntityVisibility(::createInterfaceWithId,
                fun (moduleId: ModuleId, entities: Map<FunctionId, Interface>, upstreamModules: List<ValidatedModule>): ValidatedModule {
                    return ValidatedModule.create(moduleId, mapOf(), mapOf(), entities, upstreamModules)
                },
                ValidatedModule::getInternalInterface,
                ValidatedModule::getExportedInterface)
    }

    fun <T> testEntityVisibility(createEntity: (id: FunctionId, uniqueAspect: Int, exported: Boolean) -> T,
                                 createModule: (moduleId: ModuleId, entities: Map<FunctionId, T>, upstreamModules: List<ValidatedModule>) -> ValidatedModule,
                                 getInternalEntity: (module: ValidatedModule, id: FunctionId) -> T?,
                                 getExportedEntity: (module: ValidatedModule, id: FunctionId) -> T?) {
        val coincidentallySharedInternalId = FunctionId.of("coincidentallySharedInternal")
        val upstreamEntityWithSharedId = createEntity(coincidentallySharedInternalId, 1, false)
        val downstreamEntityWithSharedId = createEntity(coincidentallySharedInternalId, 2, false)

        val upstreamInternalId = FunctionId.of("upstreamInternal")
        val upstreamInternalEntity = createEntity(upstreamInternalId, 3, false)

        val upstreamExportedId = FunctionId.of("upstreamExported")
        val upstreamExportedEntity = createEntity(upstreamExportedId, 4, true)

        val upstreamEntities = mapOf(upstreamInternalId to upstreamInternalEntity,
                upstreamExportedId to upstreamExportedEntity,
                coincidentallySharedInternalId to upstreamEntityWithSharedId)

        val downstreamInternalId = FunctionId.of("downstreamInternal")
        val downstreamInternalEntity = createEntity(downstreamInternalId, 5, false)

        val downstreamExportedId = FunctionId.of("downstreamExported")
        val downstreamExportedEntity = createEntity(downstreamExportedId, 6, true)

        val downstreamEntities = mapOf(downstreamInternalId to downstreamInternalEntity,
                downstreamExportedId to downstreamExportedEntity,
                coincidentallySharedInternalId to downstreamEntityWithSharedId)

        val upstreamModuleId = ModuleId("example", "upstream", "0.1")
        val downstreamModuleId = ModuleId("example", "downstream", "0.1")
        val upstreamContext = createModule(upstreamModuleId, upstreamEntities, listOf())
        val downstreamContext = createModule(downstreamModuleId, downstreamEntities, listOf(upstreamContext))

        // Upstream IDs in the upstream context
        Assert.assertEquals(upstreamInternalEntity,     getInternalEntity(upstreamContext, upstreamInternalId))
        Assert.assertEquals(null,                       getExportedEntity(upstreamContext, upstreamInternalId))
        Assert.assertEquals(upstreamExportedEntity,     getInternalEntity(upstreamContext, upstreamExportedId))
        Assert.assertEquals(upstreamExportedEntity,     getExportedEntity(upstreamContext, upstreamExportedId))
        Assert.assertEquals(upstreamEntityWithSharedId, getInternalEntity(upstreamContext, coincidentallySharedInternalId))
        Assert.assertEquals(null,                       getExportedEntity(upstreamContext, coincidentallySharedInternalId))

        // Upstream IDs in the downstream context
        Assert.assertEquals(null,                   getInternalEntity(downstreamContext, upstreamInternalId))
        Assert.assertEquals(null,                   getExportedEntity(downstreamContext, upstreamInternalId))
        Assert.assertEquals(upstreamExportedEntity, getInternalEntity(downstreamContext, upstreamExportedId))
        // Note that this doesn't get transitively exported by default:
        Assert.assertEquals(null,                   getExportedEntity(downstreamContext, upstreamExportedId))

        // Downstream IDs in the downstream context
        Assert.assertEquals(downstreamInternalEntity,     getInternalEntity(downstreamContext, downstreamInternalId))
        Assert.assertEquals(null,                         getExportedEntity(downstreamContext, downstreamInternalId))
        Assert.assertEquals(downstreamExportedEntity,     getInternalEntity(downstreamContext, downstreamExportedId))
        Assert.assertEquals(downstreamExportedEntity,     getExportedEntity(downstreamContext, downstreamExportedId))
        Assert.assertEquals(downstreamEntityWithSharedId, getInternalEntity(downstreamContext, coincidentallySharedInternalId))
        Assert.assertEquals(null,                         getExportedEntity(downstreamContext, coincidentallySharedInternalId))
    }
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

private fun createStructWithId(id: FunctionId, uniqueAspect: Int, exported: Boolean): Struct {
    val member = Member(uniqueAspect.toString(), Type.INTEGER)
    val annotations = if (exported) {
        listOf(Annotation("Exported", null))
    } else {
        listOf()
    }
    return Struct(id, listOf(), listOf(member), null, annotations)
}


private fun createInterfaceWithId(id: FunctionId, uniqueAspect: Int, exported: Boolean): Interface {
    val method = Method(uniqueAspect.toString(), listOf(), listOf(), Type.INTEGER)
    val annotations = if (exported) {
        listOf(Annotation("Exported", null))
    } else {
        listOf()
    }
    return Interface(id, listOf(), listOf(method), annotations)
}
