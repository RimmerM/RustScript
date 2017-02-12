package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.*
import java.util.*
import se.rimmer.rc.compiler.parser.GenType as ASTGenType
import se.rimmer.rc.compiler.parser.Module as ASTModule
import se.rimmer.rc.compiler.parser.TupType as ASTTupType
import se.rimmer.rc.compiler.parser.FunType as ASTFunType
import se.rimmer.rc.compiler.parser.Type as ASTType
import se.rimmer.rc.compiler.parser.Decl as ASTDecl
import se.rimmer.rc.compiler.parser.Expr as ASTExpr
import se.rimmer.rc.compiler.parser.MultiExpr as ASTMultiExpr
import se.rimmer.rc.compiler.parser.Import as ASTImport

interface ModuleHandler {
    fun findModule(path: List<String>): Module?
}

data class Module(val name: Qualified) {
    val imports = HashMap<List<String>, Import>()
    val functions = HashMap<String, Function>()
    val templateFunctions = HashMap<String, Function>()
    val foreignFunctions = HashMap<String, ForeignFunction>()
    val types = HashMap<String, Type>()
    val typeClasses = HashMap<String, TypeClass>()
    val typeInstances = IdentityHashMap<TypeClass, ClassInstance>()
    val constructors = HashMap<String, Con>()
    val ops = HashMap<String, Operator>()

    // The symbols defined in this module that are visible externally.
    val exportedSymbols = HashSet<String>()

    var codegen: Any? = null
}

data class Import(
    val module: Module,
    val qualified: Boolean,
    val qualifier: List<String>,
    val includedSymbols: Set<String>?,
    val excludedSymbols: Set<String>?
)

fun Module.defineRecord(name: String): RecordType {
    val r = RecordType(null, this.name.extend(name), null)
    types[name] = r
    return r
}

fun Module.defineCon(to: RecordType, name: String, index: Int, content: Type?): Con {
    val q = to.name.extend(name)
    val con = Con(q, index, to, content)
    to.constructors.add(con)
    constructors[name] = con
    return con
}

fun Module.defineClass(name: String): TypeClass {
    val c = TypeClass(this.name.extend(name))
    typeClasses[name] = c
    return c
}

fun Module.defineInstance(to: TypeClass, type: Type): ClassInstance {
    val i = ClassInstance(this, listOf(type), to)
    typeInstances[to] = i
    return i
}

fun Module.defineFun(name: String): Function {
    val f = Function(this, this.name.extend(name))
    functions[name] = f
    return f
}

fun TypeClass.defineFun(name: String, ret: Type, vararg args: Pair<String, Type>) {
    val f = FunType(args.mapIndexed { i, it -> FunArg(it.first, i, it.second) }, ret)
    functions[name] = f
}

fun Function.defineArg(name: String, type: Type): Arg {
    val a = Arg(body, name, type, args.size)
    args[name] = a
    emptyArray<Unit>().map {  }
    return a
}

fun Module.findType(name: Qualified) = findHelper(this, typeFinder, name, true)
fun Module.findCon(name: Qualified) = findHelper(this, conFinder, name, true)
fun Module.findOperator(name: Qualified) = findHelper(this, opFinder, name, true)

private inline fun <T> mapFinder(crossinline map: Module.() -> Map<String, T>): Module.(String) -> T? = { map()[it] }

private val typeFinder = mapFinder { types }
private val conFinder = mapFinder { constructors }
private val opFinder = mapFinder { ops }

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
        val candidates = java.util.ArrayList<T>()
        module.imports.forEach { _, module ->
            val v = if(name.qualifier == module.qualifier) {
                findHelper(module.module, find, Qualified(name.name, emptyList(), name.isVar), false)
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

class ResolveError(text: String): Exception(text)

