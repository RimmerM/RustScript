package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.Qualified
import java.util.*

fun Scope.findType(name: Qualified) = findHelper(this, {types}, name, true)
fun Scope.findConstructor(name: Qualified) = findHelper(this, {constructors}, name, true)
fun Scope.findFunction(name: Qualified) = findHelper(this, {functions}, name, true)
fun Scope.findOperator(name: Qualified) = findHelper(this, {ops}, name, true)

private fun <T> findHelper(scope: Scope, map: Scope.() -> Map<String, T>, name: Qualified, followImports: Boolean): T? {
    // Lookup:
    // - If the name is unqualified, start by searching the current scope.
    // - For qualified names, check if we have an import under that qualifier, then search that.
    // - If nothing is found, search the parent scope while retaining any qualifiers.
    if(name.qualifier.isEmpty()) {
        val v = scope.map()[name.name]
        if(v != null) return v
    }

    // Imports have equal weight, so multiple hits here is an error.
    if(followImports) {
        val candidates = ArrayList<T>()
        scope.imports.forEach { list, scope ->
            val v = if(name.qualifier == scope.qualifier) {
                findHelper(scope.scope, map, Qualified(name.name, emptyList()), false)
            } else {
                findHelper(scope.scope, map, name, false)
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
        return findHelper(scope.parent, map, name, followImports)
    } else {
        return null
    }
}