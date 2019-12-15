package net.semlang.api.test

import net.semlang.api.*
import org.junit.Assert
import org.junit.Test

// TODO: Test case where two upstream contexts export the same ID (of the same or differing entity types)
// TODO: We need some kind of verification that e.g. when we export a function, its return type is also something
// that is exported from the module.
class ContextTests {
    @Test
    fun testFunctionVisibility() {
        testEntityVisibility(
                ::createFunctionWithId,
                fun (moduleId: ModuleUniqueId, entities: Map<EntityId, ValidatedFunction>, upstreamModules: List<ValidatedModule>): ValidatedModule {
                    return ValidatedModule.create(moduleId, CURRENT_NATIVE_MODULE_VERSION, entities, mapOf(), mapOf(), upstreamModules, mapOf())
                },
                { module, id ->
                    val resolved = module.resolve(EntityRef(null, id), ResolutionType.Function)
                    System.out.println("Resolved of $id was $resolved")
                    when (resolved) {
                        is EntityResolution -> module.getInternalFunction(resolved.entityRef).function
                        is ResolutionResult.Error -> null
                    }
                },
                { module, id -> module.getExportedFunction(id)?.function })
    }

    @Test
    fun testStructVisibility() {
        testEntityVisibility(
                ::createStructWithId,
                fun (moduleId: ModuleUniqueId, entities: Map<EntityId, Struct>, upstreamModules: List<ValidatedModule>): ValidatedModule {
                    return ValidatedModule.create(moduleId, CURRENT_NATIVE_MODULE_VERSION, mapOf(), entities, mapOf(), upstreamModules, mapOf())
                },
                { module, id ->
                    val resolved = module.resolve(EntityRef(null, id), ResolutionType.Type)
                    when (resolved) {
                        is EntityResolution -> module.getInternalStruct(resolved.entityRef).struct
                        is ResolutionResult.Error -> null
                    }
                },
                { module, id -> module.getExportedStruct(id)?.struct })
    }

    @Test
    fun testUnionVisibility() {
        testEntityVisibility(
                ::createUnionWithId,
                fun (moduleId: ModuleUniqueId, entities: Map<EntityId, Union>, upstreamModules: List<ValidatedModule>): ValidatedModule {
                    return ValidatedModule.create(moduleId, CURRENT_NATIVE_MODULE_VERSION, mapOf(), mapOf(), entities, upstreamModules, mapOf())
                },
                { module, id ->
                    val resolved = module.resolve(EntityRef(null, id), ResolutionType.Type)
                    when (resolved) {
                        is EntityResolution -> module.getInternalUnion(resolved.entityRef).union
                        is ResolutionResult.Error -> null
                    }
                },
                { module, id -> module.getExportedUnion(id)?.union })
    }

    fun <T> testEntityVisibility(createEntity: (id: EntityId, moduleId: ModuleUniqueId, uniqueAspect: Int, exported: Boolean) -> T,
                                 createModule: (moduleId: ModuleUniqueId, entities: Map<EntityId, T>, upstreamModules: List<ValidatedModule>) -> ValidatedModule,
                                 getInternalEntity: (module: ValidatedModule, id: EntityId) -> T?,
                                 getExportedEntity: (module: ValidatedModule, id: EntityId) -> T?) {
        val upstreamModuleId = ModuleUniqueId(ModuleName("example", "upstream"), "0123456")
        val downstreamModuleId = ModuleUniqueId(ModuleName("example", "downstream"), "0123456")

        val coincidentallySharedInternalId = EntityId.of("coincidentallySharedInternal")
        val upstreamEntityWithSharedId = createEntity(coincidentallySharedInternalId, upstreamModuleId, 1, false)
        val downstreamEntityWithSharedId = createEntity(coincidentallySharedInternalId, downstreamModuleId, 2, false)

        val upstreamInternalId = EntityId.of("upstreamInternal")
        val upstreamInternalEntity = createEntity(upstreamInternalId, upstreamModuleId, 3, false)

        val upstreamExportedId = EntityId.of("upstreamExported")
        val upstreamExportedEntity = createEntity(upstreamExportedId, upstreamModuleId, 4, true)

        val upstreamEntities = mapOf(upstreamInternalId to upstreamInternalEntity,
                upstreamExportedId to upstreamExportedEntity,
                coincidentallySharedInternalId to upstreamEntityWithSharedId)

        val downstreamInternalId = EntityId.of("downstreamInternal")
        val downstreamInternalEntity = createEntity(downstreamInternalId, downstreamModuleId, 5, false)

        val downstreamExportedId = EntityId.of("downstreamExported")
        val downstreamExportedEntity = createEntity(downstreamExportedId, downstreamModuleId, 6, true)

        val downstreamEntities = mapOf(downstreamInternalId to downstreamInternalEntity,
                downstreamExportedId to downstreamExportedEntity,
                coincidentallySharedInternalId to downstreamEntityWithSharedId)

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

private fun createFunctionWithId(id: EntityId, moduleId: ModuleUniqueId, uniqueAspect: Int, exported: Boolean): ValidatedFunction {
    val block = TypedBlock(
        NativeOpaqueType.INTEGER.getType(),
        listOf(),
        ValidatedStatement.Bare(
            NativeOpaqueType.INTEGER.getType(),
            TypedExpression.Literal(NativeOpaqueType.INTEGER.getType(), AliasType.NotAliased, uniqueAspect.toString())
        ),
        AliasType.NotAliased
    )
    val annotations = if (exported) {
        listOf(Annotation(EntityId.of("Export"), listOf()))
    } else {
        listOf()
    }
    return ValidatedFunction(id, listOf(), listOf(), NativeOpaqueType.INTEGER.getType(), block, annotations)
}

private fun createStructWithId(id: EntityId, moduleId: ModuleUniqueId, uniqueAspect: Int, exported: Boolean): Struct {
    val member = Member(uniqueAspect.toString(), NativeOpaqueType.INTEGER.getType())
    val annotations = if (exported) {
        listOf(Annotation(EntityId.of("Export"), listOf()))
    } else {
        listOf()
    }
    return Struct(id, moduleId, listOf(), listOf(member), null, annotations)
}

private fun createUnionWithId(id: EntityId, moduleId: ModuleUniqueId, uniqueAspect: Int, exported: Boolean): Union {
    val option = Option(uniqueAspect.toString(), null)
    val annotations = if (exported) {
        listOf(Annotation(EntityId.of("Export"), listOf()))
    } else {
        listOf()
    }
    return Union(id, moduleId, listOf(), listOf(option), annotations)
}
