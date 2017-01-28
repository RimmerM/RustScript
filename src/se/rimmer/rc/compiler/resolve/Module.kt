package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.*
import se.rimmer.rc.compiler.parser.GenType as ASTGenType
import se.rimmer.rc.compiler.parser.Module as ASTModule
import se.rimmer.rc.compiler.parser.TupType as ASTTupType
import se.rimmer.rc.compiler.parser.Type as ASTType
import se.rimmer.rc.compiler.parser.Decl as ASTDecl
import se.rimmer.rc.compiler.parser.Expr as ASTExpr
import se.rimmer.rc.compiler.parser.Import as ASTImport

class ResolveError(text: String): Exception(text)

interface ModuleHandler {
    fun findModule(path: List<String>): Scope?
}

fun resolveModule(ast: ASTModule, handler: ModuleHandler): Scope {
    /*
     * We need to do two passes here.
     * In the first pass we add each declared identifier to the appropriate list in its scope.
     * This makes sure that every dependent identifier can be found in the second pass,
     * where we resolve the content of each declared identifier.
     */

    val module = Scope(ast.name, null, null)
    prepareImports(module, handler, ast.imports)
    prepareScope(module, ast.decls, emptyList())
    resolveScope(module)
    return module
}

internal fun prepareImports(scope: Scope, handler: ModuleHandler, imports: List<ASTImport>) {
    imports.forEach {
        val source = it.source
        val path = source.qualifier + source.name
        val import = handler.findModule(path) ?: throw ResolveError("cannot find module $source")

        val symbols = if(it.include.isNotEmpty()) {
            it.include.forEach {
                if(it !in import.exportedSymbols) throw ResolveError("symbol $it is not exported by module $source")
            }
            it.include.toSet()
        } else if(it.exclude.isNotEmpty()) {
            import.exportedSymbols - it.exclude
        } else {
            import.exportedSymbols
        }

        scope.imports[path] = ImportedScope(import, it.qualified, listOf(it.localName), symbols)
    }

    // Implicitly import Prelude if the module doesn't do so by itself.
    val hasPrelude = scope.imports.values.find { it.scope.name.qualifier.isEmpty() && it.scope.name.name == "Prelude" } != null
    if(!hasPrelude) {
        val path = listOf("Prelude")
        val prelude = handler.findModule(path) ?: throw ResolveError("Cannot find module Prelude")
        scope.imports[path] = ImportedScope(prelude, false, emptyList(), emptySet())
    }
}

internal fun prepareScope(scope: Scope, decls: List<ASTDecl>, exprs: List<ASTExpr>) {
    // Perform the declaration pass.
    decls.forEach {
        when(it) {
            is FunDecl -> {
                if(scope.functions.containsKey(it.name)) {
                    throw ResolveError("redefinition of function ${it.name}")
                }

                val head = FunctionHead()
                val function = LocalFunction(scope.name.extend(it.name), scope, head)
                head.body = function
                scope.functions[it.name] = head
            }
            is ForeignDecl -> {
                if(it.type is FunType) {
                    val head = FunctionHead()
                    val function = ForeignFunction(scope.name.extend(it.internalName), it.externalName, head)
                    head.body = function
                    scope.functions[it.internalName] = head
                } else {
                    throw NotImplementedError()
                }
            }
            is TypeDecl -> {
                if(scope.types.containsKey(it.type.name)) {
                    throw ResolveError("redefinition of type ${it.type.name}")
                }
                scope.types[it.type.name] = AliasType(it, unknownType, scope)
            }
            is DataDecl -> {
                if(scope.types.containsKey(it.type.name)) {
                    throw ResolveError("redefinition of type ${it.type.name}")
                }

                // The constructors are declared here, but resolved later.
                val record = RecordType(it, scope)
                val cons = it.cons.mapIndexed { i, c -> Constructor(scope.name.extend(c.name), i, record) }
                cons.forEach {
                    if(scope.constructors.containsKey(it.name.name)) {
                        throw ResolveError("redefinition of type constructor ${it.name.name}")
                    }
                    scope.constructors[it.name.name] = it
                }

                record.constructors.addAll(cons)
                scope.types[it.type.name]
            }
            else -> throw NotImplementedError()
        }
    }
}

internal fun resolveScope(scope: Scope) {
    // Perform the resolve pass. All defined names in this scope are now available.
    // Symbols may be resolved lazily when used by other symbols,
    // so we just skip those that are already defined.
    scope.types.forEach { s, type ->
        when(type) {
            is AliasType -> resolveAlias(type)
            is RecordType -> resolveRecord(type)
            else -> throw NotImplementedError()
        }
    }

    scope.functions.forEach { t, f ->
        val body = f.body
        when(body) {
            is ForeignFunction -> resolveForeignFun(body)
            is LocalFunction -> resolveLocalFun(body)
            else -> throw NotImplementedError()
        }
    }
}

internal fun resolveAlias(type: AliasType): AliasType {
    type.ast?.let {
        type.ast = null
        type.target = resolveType(type.scope, it.target, it.type)
    }
    return type
}

internal fun resolveRecord(type: RecordType): RecordType {
    type.ast?.let { ast ->
        type.ast = null
        type.constructors.forEach {
            val cAst = ast.cons[it.index]
            if(cAst.content != null) {
                it.content = resolveType(type.scope, cAst.content, null)
            }
        }
    }
    return type
}

internal fun resolveForeignFun(f: ForeignFunction) {

}

internal fun resolveLocalFun(f: LocalFunction) {

}