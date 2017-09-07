package net.semlang.api

import org.junit.Assert
import org.junit.Test

// TODO: Test case where two upstream contexts export the same ID (of the same or differing entity types)
// TODO: We need some kind of verification that e.g. when we export a function, its return type is also something
// that is exported from the module.
class ContextTests {
    @Test
    fun testFunctionVisibility() {
        testEntityVisibility(::createFunctionWithId,
                fun (moduleId: ModuleId, entities: Map<EntityId, ValidatedFunction>, upstreamModules: List<ValidatedModule>): ValidatedModule {
                    return ValidatedModule.create(moduleId, CURRENT_NATIVE_MODULE_VERSION, entities, mapOf(), mapOf(), upstreamModules)
                },
                { module, id ->
                    val resolved = module.resolve(EntityRef(null, id))
                    System.out.println("Resolved of $id was $resolved")
                    if (resolved == null) {
                        null
                    } else {
                        module.getInternalFunction(resolved.entityRef).function
                    }
                },
                { module, id -> module.getExportedFunction(id)?.function })
    }

    @Test
    fun testStructVisibility() {
        testEntityVisibility(::createStructWithId,
                fun (moduleId: ModuleId, entities: Map<EntityId, Struct>, upstreamModules: List<ValidatedModule>): ValidatedModule {
                    return ValidatedModule.create(moduleId, CURRENT_NATIVE_MODULE_VERSION, mapOf(), entities, mapOf(), upstreamModules)
                },
                { module, id ->
                    val resolved = module.resolve(EntityRef(null, id))
                    if (resolved == null) {
                        null
                    } else {
                        module.getInternalStruct(resolved.entityRef).struct
                    }
                },
                { module, id -> module.getExportedStruct(id)?.struct })
    }

    @Test
    fun testInterfaceVisibility() {
        testEntityVisibility(::createInterfaceWithId,
                fun (moduleId: ModuleId, entities: Map<EntityId, Interface>, upstreamModules: List<ValidatedModule>): ValidatedModule {
                    return ValidatedModule.create(moduleId, CURRENT_NATIVE_MODULE_VERSION, mapOf(), mapOf(), entities, upstreamModules)
                },
                { module, id ->
                    val resolved = module.resolve(EntityRef(null, id))
                    if (resolved == null) {
                        null
                    } else {
                        module.getInternalInterface(resolved.entityRef).interfac
                    }
                },
                { module, id -> module.getExportedInterface(id)?.interfac })
    }

    fun <T> testEntityVisibility(createEntity: (id: EntityId, uniqueAspect: Int, exported: Boolean) -> T,
                                 createModule: (moduleId: ModuleId, entities: Map<EntityId, T>, upstreamModules: List<ValidatedModule>) -> ValidatedModule,
                                 getInternalEntity: (module: ValidatedModule, id: EntityId) -> T?,
                                 getExportedEntity: (module: ValidatedModule, id: EntityId) -> T?) {
        val coincidentallySharedInternalId = EntityId.of("coincidentallySharedInternal")
        val upstreamEntityWithSharedId = createEntity(coincidentallySharedInternalId, 1, false)
        val downstreamEntityWithSharedId = createEntity(coincidentallySharedInternalId, 2, false)

        val upstreamInternalId = EntityId.of("upstreamInternal")
        val upstreamInternalEntity = createEntity(upstreamInternalId, 3, false)

        val upstreamExportedId = EntityId.of("upstreamExported")
        val upstreamExportedEntity = createEntity(upstreamExportedId, 4, true)

        val upstreamEntities = mapOf(upstreamInternalId to upstreamInternalEntity,
                upstreamExportedId to upstreamExportedEntity,
                coincidentallySharedInternalId to upstreamEntityWithSharedId)

        val downstreamInternalId = EntityId.of("downstreamInternal")
        val downstreamInternalEntity = createEntity(downstreamInternalId, 5, false)

        val downstreamExportedId = EntityId.of("downstreamExported")
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

private fun createFunctionWithId(id: EntityId, uniqueAspect: Int, exported: Boolean): ValidatedFunction {
    val block = TypedBlock(Type.INTEGER, listOf(), TypedExpression.Literal(Type.INTEGER, uniqueAspect.toString()))
    val annotations = if (exported) {
        listOf(Annotation("Exported", null))
    } else {
        listOf()
    }
    return ValidatedFunction(id, listOf(), listOf(), Type.INTEGER, block, annotations)
}

private fun createStructWithId(id: EntityId, uniqueAspect: Int, exported: Boolean): Struct {
    val member = Member(uniqueAspect.toString(), Type.INTEGER)
    val annotations = if (exported) {
        listOf(Annotation("Exported", null))
    } else {
        listOf()
    }
    return Struct(id, listOf(), listOf(member), null, annotations)
}

private fun createInterfaceWithId(id: EntityId, uniqueAspect: Int, exported: Boolean): Interface {
    val method = Method(uniqueAspect.toString(), listOf(), listOf(), Type.INTEGER)
    val annotations = if (exported) {
        listOf(Annotation("Exported", null))
    } else {
        listOf()
    }
    return Interface(id, listOf(), listOf(method), annotations)
}
