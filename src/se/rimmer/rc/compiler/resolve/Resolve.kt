package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.*
import se.rimmer.rc.compiler.parser.GenType as ASTGenType
import se.rimmer.rc.compiler.parser.Module as ASTModule
import se.rimmer.rc.compiler.parser.TupType as ASTTupType
import se.rimmer.rc.compiler.parser.Type as ASTType

class ResolveError(text: String): Exception(text)

class Resolver(val ast: ASTModule) {
    val module = Scope(ast.name, null, null)

    fun resolve() {
        /*
         * We need to do two passes here.
         * In the first pass we add each declared identifier to the appropriate list in its scope.
         * This makes sure that every dependent identifier can be found in the second pass,
         * where we resolve the content of each declared identifier.
         */

        // Perform the declaration pass.
        ast.decls.forEach {
            when(it) {
                is FunDecl -> {
                    if(module.functions.containsKey(it.name)) {
                        throw ResolveError("redefinition of function ${it.name}")
                    }

                    val head = FunctionHead()
                    val function = LocalFunction(module.name.extend(it.name), module, head)
                    head.body = function
                    module.functions[it.name] = head
                }
                is ForeignDecl -> {
                    if(it.type is FunType) {
                        val head = FunctionHead()
                        val function = ForeignFunction(module.name.extend(it.internalName), it.externalName, head)
                        head.body = function
                        module.functions[it.internalName] = head
                    } else {
                        throw NotImplementedError()
                    }
                }
                is TypeDecl -> {
                    if(module.types.containsKey(it.type.name)) {
                        throw ResolveError("redefinition of type ${it.type.name}")
                    }
                    module.types[it.type.name] = AliasType(it, unknownType, module)
                }
                is DataDecl -> {
                    if(module.types.containsKey(it.type.name)) {
                        throw ResolveError("redefinition of type ${it.type.name}")
                    }

                    // The constructors are declared here, but resolved later.
                    val record = RecordType(it, module)
                    val cons = it.cons.mapIndexed { i, c -> Constructor(module.name.extend(c.name), i, record) }
                    cons.forEach {
                        if(module.constructors.containsKey(it.name.name)) {
                            throw ResolveError("redefinition of type constructor ${it.name.name}")
                        }
                        module.constructors[it.name.name] = it
                    }

                    record.constructors.addAll(cons)
                    module.types[it.type.name]
                }
                else -> throw NotImplementedError()
            }
        }

        // Perform the resolve pass. All defined names in this scope are now available.
        // Symbols may be resolved lazily when used by other symbols,
        // so we just skip those that are already defined.
        module.types.forEach { s, type ->
            when(type) {
                is AliasType -> resolveAlias(type)
                is RecordType -> resolveRecord(type)
                else -> throw NotImplementedError()
            }
        }
    }

    fun resolveAlias(type: AliasType): AliasType {
        type.ast?.let {
            type.ast = null
            type.target = resolveType(type.scope, it.target, it.type)
        }
        return type
    }

    fun resolveRecord(type: RecordType): RecordType {
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
}

fun resolve(ast: ASTModule) = Resolver(ast).resolve()