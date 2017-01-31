package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.Qualified
import java.util.*

fun Module.findType(name: Qualified) = findHelper(this, typeFinder, name, true)
fun Module.findConstructor(name: Qualified) = findHelper(this, constructorFinder, name, true)
fun Module.findFunction(name: Qualified) = findHelper(this, callFinder, name, true)
fun Module.findOperator(name: Qualified) = findHelper(this, opFinder, name, true)
fun Module.findVariable(name: Qualified) = findHelper(this, varFinder, name, true)

private inline fun <T> mapFinder(crossinline map: Module.() -> Map<String, T>): Module.(String) -> T? = { map()[it] }

private val typeFinder = mapFinder { types }
private val constructorFinder = mapFinder { constructors }
private val opFinder = mapFinder { ops }
private val varFinder = mapFinder { emptyMap<String, Var>() }

private val callFinder: Module.(String) -> FunctionHead? = {
    functions[it] ?: definedVariables[it]?.let { if(it.type is FunType) it.type.head else null }
}

private fun <T> findHelper(module: Module, find: Module.(String) -> T?, name: Qualified, followImports: Boolean): T? {
    // Lookup:
    // - If the name is unqualified, start by searching the current scope.
    // - For qualified names, check if we have an import under that qualifier, then search that.
    // - If nothing is found, search the parent scope while retaining any qualifiers.
    if(name.qualifier.isEmpty()) {
        val v = module.find(name.name)
        if(v != null) return v
    }

    // Imports have equal weight, so multiple hits here is an error.
    if(followImports) {
        val candidates = ArrayList<T>()
        module.imports.forEach { _, module ->
            val v = if(name.qualifier == module.qualifier) {
                findHelper(module.module, find, Qualified(name.name, emptyList()), false)
            } else {
                findHelper(module.module, find, name, false)
            }

            if(v != null) candidates.add(v)
        }

        if(candidates.size > 1) {
            throw ResolveError("ambiguous reference: found ${candidates.size} candidates for symbol '$name'")
        } else if(candidates.size == 1) {
            return candidates[0]
        }
    }
    return null
}