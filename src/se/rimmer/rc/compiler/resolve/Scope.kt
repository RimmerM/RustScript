package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.Qualified
import java.util.*

fun Scope.findType(name: Qualified) = findHelper(this, typeFinder, name, true)
fun Scope.findConstructor(name: Qualified) = findHelper(this, constructorFinder, name, true)
fun Scope.findFunction(name: Qualified) = findHelper(this, callFinder, name, true)
fun Scope.findOperator(name: Qualified) = findHelper(this, opFinder, name, true)
fun Scope.findVariable(name: Qualified) = findHelper(this, varFinder, name, true)

private inline fun <T> mapFinder(crossinline map: Scope.() -> Map<String, T>): Scope.(String) -> T? = { map()[it] }

private val typeFinder = mapFinder { types }
private val constructorFinder = mapFinder { constructors }
private val opFinder = mapFinder { ops }
private val varFinder = mapFinder { definedVariables }

private val callFinder: Scope.(String) -> FunctionHead? = {
    functions[it] ?: definedVariables[it]?.let { if(it.type is FunType) it.type.head else null }
}

private fun <T> findHelper(scope: Scope, find: Scope.(String) -> T?, name: Qualified, followImports: Boolean): T? {
    // Lookup:
    // - If the name is unqualified, start by searching the current scope.
    // - For qualified names, check if we have an import under that qualifier, then search that.
    // - If nothing is found, search the parent scope while retaining any qualifiers.
    if(name.qualifier.isEmpty()) {
        val v = scope.find(name.name)
        if(v != null) return v
    }

    // Imports have equal weight, so multiple hits here is an error.
    if(followImports) {
        val candidates = ArrayList<T>()
        scope.imports.forEach { _, scope ->
            val v = if(name.qualifier == scope.qualifier) {
                findHelper(scope.scope, find, Qualified(name.name, emptyList()), false)
            } else {
                findHelper(scope.scope, find, name, false)
            }

            if(v != null) candidates.add(v)
        }

        if(candidates.size > 1) {
            throw ResolveError("ambiguous reference: found ${candidates.size} candidates for symbol '$name'")
        } else if(candidates.size == 1) {
            return candidates[0]
        }
    }

    if(scope.parent != null) {
        return findHelper(scope.parent, find, name, followImports)
    } else {
        return null
    }
}