import net.semlang.api.Type
import net.semlang.api.TypedExpression
import net.semlang.api.ValidatedModule

// So, the really short version is that lemmas can just be boolean-valued expressions that are always true... sort of...
// The bit that this misses is that you also want to be able to establish new unbound variables in places

data class Lemma(val expression: LemmaExpression)

// Here's one possible way of doing it?
sealed class LemmaExpression {
    data class SemlangExpression(val expression: TypedExpression): LemmaExpression()
    data class ForAny(val name: String, val type: Type, val subexpression: LemmaExpression): LemmaExpression()
    data class Implies(val cause: LemmaExpression, val effect: LemmaExpression): LemmaExpression()
}

// And we want to be able to extract lemmas from code
// Note: We might want these to get collected during the type validation step for us
fun getLemmas(module: ValidatedModule): Collection<Lemma> {
    TODO()
}

// Let's do some things in the standard library, maybe?
// There's an assume in here:
//@Export
//function List.drop<T>(list: List<T>, n: Natural): List<T> {
//    let end = List.size<T>(list)
//    let start = Natural.lesser(n, end)
//    Try.assume<List<T>>(List.subList<T>(list, start, end))
//}

// What would that lemma look like? (or the 'axiom' we want to go from, anyway)
// "then Try.isSuccess(List.subList(x,y,z))" is the effect
// Surround with "for any List x"
// Then the conditions (IIRC) are 1) size = List.size(x), 2) x is in [0, size], 3) z is in [0, size], 4) y <= z

// More importantly: What we usually want to talk about is the possible values of a particular expression within
// a function; we probably want to have a way to reference that and not just repeat the whole contents of the
// function, right? And we don't necessarily have unique names across all scopes...