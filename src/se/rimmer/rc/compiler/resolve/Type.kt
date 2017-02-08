package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.Qualified
import se.rimmer.rc.compiler.parser.SimpleType
import se.rimmer.rc.compiler.parser.extend
import java.util.*
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

val primitiveTypes = Primitive.values().map(::PrimType)
val booleanType = primitiveTypes[Primitive.Bool.ordinal]

fun Type.isBoolean() = this is PrimType && this.prim === Primitive.Bool

class AliasBuilder(val ast: ASTTypeDecl)
class RecordBuilder(val ast: ASTDataDecl)

class TypeBuilder {
    val aliasMap = HashMap<Qualified, AliasBuilder>()
    val recordMap = HashMap<Qualified, RecordBuilder>()
}

fun resolveTypes(module: Module, decls: List<ASTDecl>) {
    // Prepare by adding all defined types.
    decls.forEach {
        when(it) {
            is ASTTypeDecl -> {
                if(module.types.containsKey(it.type.name)) {
                    throw ResolveError("redefinition of type ${it.type.name}")
                }
                module.types[it.type.name] = AliasType(it, it.type.kind.mapIndexed { i, _ -> GenType(i)}, unitType)
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
            is AliasType -> resolveAlias(module, t)
            is RecordType -> resolveRecord(module, t)
        }
    }

    module.constructors.forEach { t, u ->

    }
}

fun resolveType(module: Module, type: ASTType, tScope: SimpleType?): Type {
    return when(type) {
        is ASTTupType -> resolveTupType(module, type, tScope)
        is ASTGenType -> resolveGenType(module, type, tScope)
        is ASTAppType -> resolveAppType(module, type, tScope)
        is ASTConType -> resolveConType(module, type, tScope)
        is ASTFunType -> resolveFunType(module, type, tScope)
        is ASTArrayType -> resolveArrayType(module, type, tScope)
        is ASTMapType -> resolveMapType(module, type, tScope)
        else -> throw NotImplementedError()
    }
}

fun resolveConType(module: Module, type: ASTConType, tScope: SimpleType?): Type {
    // Try to find a user-defined type first.
    module.findType(type.name)?.let { return it }

    // Check for builtin types.
    if(type.name.qualifier.isEmpty()) {
        val primitive = primitiveTypes.firstOrNull { it.prim.sourceName == type.name.name }
        if(primitive != null) return primitive
    }

    throw ResolveError("unresolved type name ${type.name}")
}

fun resolveFunType(module: Module, type: ASTFunType, tScope: SimpleType?): Type {
    val args = type.args.mapIndexed { i, it ->
        FunArg(it.name, i, resolveType(module, type, tScope))
    }
    val ret = resolveType(module, type.ret, tScope)
    return FunType(args, ret)
}

fun resolveArrayType(module: Module, type: ASTArrayType, tScope: SimpleType?): Type {
    val content = resolveType(module, type.type, tScope)
    return ArrayType(content)
}

fun resolveMapType(module: Module, type: ASTMapType, tScope: SimpleType?): Type {
    val from = resolveType(module, type.from, tScope)
    val to = resolveType(module, type.from, tScope)
    return MapType(from, to)
}

fun resolveTupType(module: Module, type: ASTTupType, tScope: SimpleType?): Type {
    val tuple = TupType()
    type.fields.forEachIndexed { i, it ->
        val fieldType = resolveType(module, it.type, tScope)
        tuple.fields.add(Field(it.name, i, fieldType, tuple, it.mutable))
    }
    return tuple
}

fun resolveAppType(module: Module, type: ASTAppType, tScope: SimpleType?): Type {
    // Find the base type and instantiate it for these arguments.
    val base = resolveType(module, type.base, tScope)
    val args = type.apps.map { resolveType(module, type, tScope) }
    return when(base) {
        is AliasType -> instantiateType(module, base.to, args)
        is RecordType -> instantiateType(module, base, args)
        else -> throw ResolveError("${type.base} is not a generic type")
    }
}

fun resolveGenType(module: Module, type: ASTGenType, tScope: SimpleType?): Type {
    if(tScope != null) {
        val i = getGenIndex(type.name, tScope)
        if(i != null) return GenType(i)
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

private fun instantiateType(module: Module, type: Type, args: List<Type>): Type {
    return when(type) {
        is UnitType, is ErrorType, is PrimType -> type
        is RefType -> RefType(instantiateType(module, type.to, args))
        is AliasType -> {
            instantiateType(module, type, args)
        }
        is ArrayType -> ArrayType(instantiateType(module, type.content, args))
        is MapType -> MapType(instantiateType(module, type.from, args), instantiateType(module, type.to, args))
        is FunType -> FunType(type.args.map { FunArg(it.name, it.index, instantiateType(module, it.type, args)) }, instantiateType(module, type.result, args))
        is TupType -> {
            val tup = TupType()
            type.fields.forEach {
                tup.fields.add(Field(it.name, it.index, instantiateType(module, it.type, args), tup, it.mutable))
            }
            tup
        }
        else -> throw NotImplementedError()
    }
}

private fun getGenIndex(name: String, scope: SimpleType): Int? {
    val i = scope.kind.indexOfFirst { it == name }
    return if(i >= 0) i else null
}

private fun resolveAlias(module: Module, type: AliasType): AliasType {
    type.ast?.let {
        type.ast = null
        type.to = resolveType(module, it.target, it.type)
    }
    return type
}

private fun resolveRecord(module: Module, type: RecordType): RecordType {
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

private fun resolveCon(module: Module, it: Con) {

}