package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.*
import se.rimmer.rc.compiler.parser.GenType as ASTGenType
import se.rimmer.rc.compiler.parser.Module as ASTModule
import se.rimmer.rc.compiler.parser.TupType as ASTTupType
import se.rimmer.rc.compiler.parser.FunType as ASTFunType
import se.rimmer.rc.compiler.parser.Type as ASTType
import se.rimmer.rc.compiler.parser.Decl as ASTDecl
import se.rimmer.rc.compiler.parser.Expr as ASTExpr
import se.rimmer.rc.compiler.parser.MultiExpr as ASTMultiExpr
import se.rimmer.rc.compiler.parser.Import as ASTImport

class ResolveError(text: String): Exception(text)

interface ModuleHandler {
    fun findModule(path: List<String>): Module?
}

fun resolveModule(ast: ASTModule, handler: ModuleHandler): Module {
    val module = Module(ast.name)

    // Resolve the module contents in usage order.
    // Types use imports but nothing else, globals use types and imports, functions use everything.
    // Note that the initialization of globals is handled in the function pass,
    // since this requires knowledge of the whole module.
    prepareImports(module, handler, ast.imports)
    resolveTypes(module, ast.decls)
    resolveGlobals(module, ast.decls)
    resolveFunctions(module, ast.decls)
    return module
}

internal fun prepareImports(module: Module, handler: ModuleHandler, imports: List<ASTImport>) {
    imports.forEach {
        val source = it.source
        val path = source.qualifier + source.name
        val import = handler.findModule(path) ?: throw ResolveError("cannot find module $source")

        val included = if(it.include.isNotEmpty()) {
            it.include.forEach {
                if(it !in import.exportedSymbols) throw ResolveError("symbol $it is not exported by module $source")
            }
            it.include.toSet()
        } else null

        val excluded = if(it.exclude.isNotEmpty()) it.exclude.toSet() else null
        module.imports[path] = Import(import, it.qualified, listOf(it.localName), included, excluded)
    }

    // Implicitly import Prelude if the module doesn't do so by itself.
    val hasPrelude = module.imports.values.find { it.module.name.qualifier.isEmpty() && it.module.name.name == "Prelude" } != null
    if(!hasPrelude) {
        val path = listOf("Prelude")
        val prelude = handler.findModule(path) ?: throw ResolveError("Cannot find module Prelude")
        module.imports[path] = Import(prelude, false, emptyList(), null, null)
    }
}

internal fun resolveGlobals(module: Module, decls: List<ASTDecl>) {

}

internal fun resolveFunctions(module: Module, decls: List<ASTDecl>) {
    // Prepare by resolving all function signatures.
    decls.forEach {
        when(it) {
            is FunDecl -> {
                if(module.functions.containsKey(it.name)) {
                    throw ResolveError("redefinition of function ${it.name}")
                }

                val function = Function(module, module.name.extend(it.name))
                module.functions[it.name] = function
            }
            is ForeignDecl -> {
                if(it.type is ASTFunType) {
                    val function = ForeignFunction(it, module, module.name.extend(it.internalName), it.externalName, it.from, null)
                    module.foreignFunctions[it.internalName] = function
                } else {
                    throw NotImplementedError()
                }
            }
        }
    }
}

internal fun resolveScope(module: Module) {
    // Perform the resolve pass. All defined names in this scope are now available.
    // Symbols may be resolved lazily when used by other symbols,
    // so we just skip those that are already defined.
    module.types.forEach { _, type ->
        when(type) {
            is AliasType -> resolveAlias(type)
            is RecordType -> resolveRecord(type)
            else -> throw NotImplementedError()
        }
    }

    module.foreignFunctions.forEach { _, f -> resolveForeignFun(f) }

    module.functions.forEach { _, f ->
        resolveFunction(f)

        val body = f.body
        when(body) {
            is ForeignFunction -> resolveForeignFun(scope, body)
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
    f.ast?.let {
        val type = it.type as ASTFunType
        f.head.ret = resolveType(scope, type.ret, null)
        type.args.forEach {
            f.head.args.add(FunctionArg(resolveType(scope, it.type, null), null, null))
        }
        f.ast = null
    }
}

internal fun resolveFun(f: Function) {
    f.ast?.let {
        it.args.forEach {
            val type = resolveType(f.scope, it.type ?: throw NotImplementedError(), null)
            val variable = Var(it.name, type, f.scope, true, true)
            f.scope.definedVariables[variable.name] = variable
            f.head.args.add(FunctionArg(type, it.name, variable))
        }

        val expectedReturn = it.ret?.let { resolveType(f.scope, it, null) }
        val resultUsed = when(it.body) {
            is ASTMultiExpr -> false
            else -> true
        }

        val content = resolveExpr(f.scope, it.body, resultUsed)
        val body = when(content.kind) {
            is MultiExpr, is RetExpr -> content
            else -> ExprNode(RetExpr(content), content.type, false)
        }

        if(resultUsed) {
            if(expectedReturn != null && !typesCompatible(expectedReturn, body.type)) {
                throw ResolveError("declared type and actual type of function ${f.scope.name} don't match")
            }
            f.head.ret = body.type
        } else if(f.returnPoints.isEmpty()) {
            if(expectedReturn != null && !typesCompatible(expectedReturn, unitType)) {
                throw ResolveError("function ${f.scope.name} is declared to return a value, but doesn't")
            }
            f.head.ret = unitType
        } else {
            var previous: Type? = null
            for(p in f.returnPoints) {
                if(previous != null) {
                    if(!typesCompatible(previous, p.type)) {
                        throw ResolveError("types of return statements in function ${f.scope.name} don't match")
                    }
                }
                previous = p.type
            }

            if(expectedReturn != null && !typesCompatible(expectedReturn, previous!!)) {
                throw ResolveError("declared type and actual type of function ${f.scope.name} don't match")
            }
            f.head.ret = previous!!
        }

        f.content = body
        f.ast = null
    }
}