package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.*
import se.rimmer.rc.compiler.parser.Module as ASTModule

fun resolveModule(ast: ASTModule, typeContext: Types, handler: ModuleHandler): Module {
    val module = Module(ast.name, typeContext)

    // Resolve the module contents in usage order.
    // Types use imports but nothing else, globals use types and imports, functions use everything.
    // Note that the initialization of globals is handled in the function pass,
    // since this requires knowledge of the whole module.
    prepareImports(module, handler, ast.imports)
    resolveTypes(module, ast.decls.map { it.it })
    resolveFunctions(module, ast.decls.map { it.it })
    return module
}

private fun resolveTypes(module: Module, decls: List<Decl>) {
    // Prepare by adding all defined types.
    decls.forEach {
        when(it) {
            is TypeDecl -> {
                module.defineAlias(it.type.name, PrimTypes.unitType).apply {
                    ast = it
                    it.type.kind.forEachIndexed { i, it ->
                        generics[it] = GenType(i)
                    }
                }
            }
            is DataDecl -> {
                module.defineRecord(it.type.name).apply {
                    ast = it
                    it.type.kind.forEachIndexed { i, it ->
                        generics[it] = GenType(i)
                    }

                    // The constructors are declared here, but resolved later.
                    it.cons.forEachIndexed { i, it ->
                        module.defineCon(this, it.name, i, PrimTypes.unitType)
                    }
                }
            }
        }
    }

    // When all names are defined, start resolving the types.
    module.types.forEach { _, t ->
        resolveDefinition(module, t)
    }
}

private fun prepareImports(module: Module, handler: ModuleHandler, imports: List<se.rimmer.rc.compiler.parser.Import>) {
    imports.forEach {
        val source = it.from
        val path = source.qualifier + source.name
        val import = handler.findModule(path) ?: throw ResolveError("cannot find module $source")

        val included = if(it.include != null && it.include.isNotEmpty()) {
            it.include.forEach {
                if(it !in import.exportedSymbols) throw ResolveError("symbol $it is not exported by module $source")
            }
            it.include.toSet()
        } else null

        val excluded = if(it.exclude != null && it.exclude.isNotEmpty()) it.exclude.toSet() else null
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

private fun resolveFunctions(module: Module, decls: List<Decl>) {
    // Prepare by resolving all function signatures.
    decls.forEach {
        when(it) {
            is FunDecl -> {
                if(module.functions.containsKey(it.name)) {
                    throw ResolveError("redefinition of function ${it.name}")
                }

                val function = Function(module, module.name.extend(it.name))
                module.functions[it.name] = function
                resolveFun(function, it)
            }
            is ForeignDecl -> {
                val type = resolveType(module, it.type, null)
                if(type is FunType) {
                    val function = ForeignFunction(module, module.name.extend(it.internalName), it.externalName, it.from, type)
                    module.foreignFunctions[it.internalName] = function
                } else {
                    throw NotImplementedError()
                }
            }
        }
    }
}
