package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.SimpleType
import se.rimmer.rc.compiler.parser.extend
import se.rimmer.rc.compiler.parser.AppType as ASTAppType
import se.rimmer.rc.compiler.parser.ArrayType as ASTArrayType
import se.rimmer.rc.compiler.parser.ClassDecl as ASTClassDecl
import se.rimmer.rc.compiler.parser.ConType as ASTConType
import se.rimmer.rc.compiler.parser.DataDecl as ASTDataDecl
import se.rimmer.rc.compiler.parser.Decl as ASTDecl
import se.rimmer.rc.compiler.parser.FunType as ASTFunType
import se.rimmer.rc.compiler.parser.GenType as ASTGenType
import se.rimmer.rc.compiler.parser.InstanceDecl as ASTInstanceDecl
import se.rimmer.rc.compiler.parser.MapType as ASTMapType
import se.rimmer.rc.compiler.parser.Module as ASTModule
import se.rimmer.rc.compiler.parser.TupType as ASTTupType
import se.rimmer.rc.compiler.parser.Type as ASTType
import se.rimmer.rc.compiler.parser.TypeDecl as ASTTypeDecl

fun Type.isBoolean() = this is IntType && this.kind === IntKind.Bool

fun resolveTypes(module: Module, decls: List<ASTDecl>) {
    // Prepare by adding all defined types.
    decls.forEach {
        when(it) {
            is ASTTypeDecl -> {
                if(module.types.containsKey(it.type.name)) {
                    throw ResolveError("redefinition of type ${it.type.name}")
                }
                module.types[it.type.name] = AliasType(it, unitType)
            }
            is ASTDataDecl -> {
                if(module.types.containsKey(it.type.name)) {
                    throw ResolveError("redefinition of type ${it.type.name}")
                }

                // The constructors are declared here, but resolved later.
                val record = RecordType(it, it.type.kind.mapIndexed { i, _ -> GenType(i)})
                val cons = it.cons.mapIndexed { i, (name) -> Con(module.name.extend(name), i, record) }
                cons.forEach {
                    if(module.constructors.containsKey(it.name.name)) {
                        throw ResolveError("redefinition of type constructor ${it.name.name}")
                    }
                    module.constructors[it.name.name] = it
                }

                record.constructors.addAll(cons)
                module.types[it.type.name] = record
            }
        }
    }

    // When all names are defined, start resolving the types.
    module.types.forEach { _, t ->
        when(t) {
            is AliasType -> defineAlias(module, t)
            is RecordType -> defineRecord(module, t)
        }
    }

    module.constructors.forEach { _, u -> defineCon(module, u) }
}

fun resolveType(module: Module, type: ASTType, gen: GenMap?): Type {
    val result = when(type) {
        is ASTTupType -> resolveTupType(module, type, context)
        is ASTGenType -> resolveGenType(module, type, context)
        is ASTAppType -> resolveAppType(module, type, context)
        is ASTConType -> resolveConType(module, type, context)
        is ASTFunType -> resolveFunType(module, type, context)
        is ASTArrayType -> resolveArrayType(module, type, context)
        is ASTMapType -> resolveMapType(module, type, context)
        else -> throw NotImplementedError()
    }

    when(result) {
        is AliasType -> defineAlias(module, result)
        is RecordType -> defineRecord(module, result)
    }
    return result
}

fun resolveConType(module: Module, type: ASTConType, gen: GenMap?): Type {
    // Try to find a user-defined type first.
    module.findType(type.name)?.let { return it }

    // Check for builtin types.
    if(type.name.qualifier.isEmpty()) {
        val primitive = primitiveTypes.firstOrNull { it.prim.sourceName == type.name.name }
        if(primitive != null) return primitive
    }

    throw ResolveError("unresolved type name ${type.name}")
}

fun resolveFunType(module: Module, type: ASTFunType, gen: GenMap?): Type {
    val args = type.args.mapIndexed { i, it ->
        FunArg(it.name, i, resolveType(module, type, context))
    }
    val ret = resolveType(module, type.ret, context)
    return FunType(args, ret)
}

fun resolveArrayType(module: Module, type: ASTArrayType, gen: GenMap?): Type {
    val content = resolveType(module, type.type, context)
    return ArrayType(content)
}

fun resolveMapType(module: Module, type: ASTMapType, gen: GenMap?): Type {
    val from = resolveType(module, type.from, context)
    val to = resolveType(module, type.from, context)
    return MapType(from, to)
}

fun resolveTupType(module: Module, type: ASTTupType, gen: GenMap?): Type {
    val tuple = TupType()
    type.fields.forEachIndexed { i, it ->
        val fieldType = resolveType(module, it.type, context)
        tuple.fields.add(Field(it.name, i, fieldType, tuple, it.mutable))
    }
    return tuple
}

fun resolveAppType(module: Module, type: ASTAppType, gen: GenMap?): Type {
    // Find the base type and instantiate it for these arguments.
    val base = resolveType(module, type.base, context)
    val args = type.apps.map { resolveType(module, type, context) }
    return when(base) {
        is AliasType -> {
            if(base.generics.size != args.size) {
                throw ResolveError("incorrect number of type arguments to type $base")
            }


            instantiateType(module, base.to, context)
        }
        is RecordType -> instantiateType(module, base, context)
        else -> throw ResolveError("${type.base} is not a generic type")
    }
}

fun resolveGenType(module: Module, type: ASTGenType, gen: GenMap?): Type {
    if(context != null) {
        context.generics[type.name]?.let { return it }
    }
    throw ResolveError("undefined generic type '${type.name}'")
}

fun typesCompatible(a: Type, b: Type): Boolean {
    if(a === b) return true
    return when(a) {
        is PrimType -> b is PrimType && a.prim == b.prim
        is UnitType -> b is UnitType
        is ErrorType -> b is ErrorType
        is RefType -> b is RefType && typesCompatible(a.to, b.to)
        else -> throw NotImplementedError()
    }
}

private fun instantiateType(module: Module, type: Type, gen: GenMap): Type {
    return when(type) {
        is UnitType, is ErrorType, is PrimType -> type
        is RefType -> RefType(instantiateType(module, type.to, context))
        is AliasType -> {
            instantiateType(module, type, context)
        }
        is ArrayType -> ArrayType(instantiateType(module, type.content, context))
        is MapType -> MapType(instantiateType(module, type.from, context), instantiateType(module, type.to, context))
        is FunType -> FunType(type.args.map { FunArg(it.name, it.index, instantiateType(module, it.type, context)) }, instantiateType(module, type.result, context))
        is TupType -> {
            val tup = TupType()
            type.fields.forEach {
                tup.fields.add(Field(it.name, it.index, instantiateType(module, it.type, context), tup, it.mutable))
            }
            tup
        }
        else -> throw NotImplementedError()
    }
}

private fun defineAlias(module: Module, type: AliasType): AliasType {
    type.ast?.let {
        type.ast = null
        it.type.kind.forEachIndexed { i, it ->
            type.generics[it] = GenType(i)
        }
        type.to = resolveType(module, it.target, type.generics)
    }
    return type
}

private fun defineRecord(module: Module, type: RecordType): RecordType {
    type.ast?.let { ast ->
        type.ast = null
        type.constructors.forEach {
            val cAst = ast.cons[it.index]
            if(cAst.content != null) {
                it.content = resolveType(module, cAst.content, null)
            }
        }
    }
    return type
}

private fun defineCon(module: Module, it: Con) {

}

