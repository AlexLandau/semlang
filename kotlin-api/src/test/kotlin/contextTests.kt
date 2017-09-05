package net.semlang.api

import org.junit.Assert
import org.junit.Test

// TODO: Test case where two upstream contexts export the same ID (of the same or differing entity types)
/*
 * So, there are at least a couple of approaches I've considered here.
 * 1) Disallow having multiple dependencies with overlapping IDs; i.e., validation of the module that depends on both
 *    would fail. This seems unnecessarily draconian and would cause all kinds of pain and forks of modules.
 * All other approaches require at least some kind of labelling of "the foo that comes from module X":
 * 2) Require TypeScript-style import statements for anything from a dependency, allowing renamings.
 * 3) Require all types to be labelled in-situ with some identifier for their module.
 *    Proposal: myFunction from example.com:myModule:1.2.3 could be labelled as any of the following, if unique:
  *    ':myModule:myFunction'
  *    ':example.com:myModule:myFunction'
  *    ':example.com:myModule@1.2.3:myFunction'
  * 4) Allow the identifiers as in 3, but also allow the bare function name if it's unique.
  *
  * So what are the pros and cons here?
  * Let's just rule out (1) immediately -- way too inconvenient, despite being easiest to implement.
  *
  * Arguably we can leave programmer convenience to dialects, but... #3 is least convenient; #2 or #4 may be most
  * convenient, depending on the situation.
  *
  * We could also consider a "typealias" approach that could be combined with #3 or #4 to simulate #2 and otherwise
  * give a high level of programmer flexibility. This could also be applied at one level (sem1) and then automatically
  * transformed into the #3 or #4 proposal for another (sem0).
  *
  * There is the question of whether it helps for people to be able to reference import statements to determine which
  * module a given entity comes from. My personal suspicion is that people don't look at imports that much; this file
  * has its imports collapsed in my IDE right now. Tooling should provide this information, not boilerplate. It should
  * also be able to fill in newly necessary labels when a new dependency is added (or suggest options, at least).
  *
  * Instinctually, I'm leaning towards #4 plus typealiases (in some variants). This does require parser changes.
  * I'd like some tests set up first before implementing...
 */
// TODO: We need some kind of verification that e.g. when we export a function, its return type is also something
// that is exported from the module.
class ContextTests {
    @Test
    fun testFunctionVisibility() {
        testEntityVisibility(::createFunctionWithId,
                fun (moduleId: ModuleId, entities: Map<EntityId, ValidatedFunction>, upstreamModules: List<ValidatedModule>): ValidatedModule {
                    return ValidatedModule.create(moduleId, entities, mapOf(), mapOf(), upstreamModules)
                },
                { module, id -> module.getInternalFunction(EntityRef(null, id))?.function },
                { module, id -> module.getExportedFunction(id)?.function })
    }

    @Test
    fun testStructVisibility() {
        testEntityVisibility(::createStructWithId,
                fun (moduleId: ModuleId, entities: Map<EntityId, Struct>, upstreamModules: List<ValidatedModule>): ValidatedModule {
                    return ValidatedModule.create(moduleId, mapOf(), entities, mapOf(), upstreamModules)
                },
                { module, id -> module.getInternalStruct(EntityRef(null, id))?.struct },
                { module, id -> module.getExportedStruct(id)?.struct })
    }

    @Test
    fun testInterfaceVisibility() {
        testEntityVisibility(::createInterfaceWithId,
                fun (moduleId: ModuleId, entities: Map<EntityId, Interface>, upstreamModules: List<ValidatedModule>): ValidatedModule {
                    return ValidatedModule.create(moduleId, mapOf(), mapOf(), entities, upstreamModules)
                },
                { module, id -> module.getInternalInterface(EntityRef(null, id))?.interfac },
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
