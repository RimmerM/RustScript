package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.Qualified
import se.rimmer.rc.compiler.parser.SimpleType
import se.rimmer.rc.compiler.parser.extend
import java.util.*
import se.rimmer.rc.compiler.parser.AppType as ASTAppType
import se.rimmer.rc.compiler.parser.ArrayType as ASTArrayType
import se.rimmer.rc.compiler.parser.ConType as ASTConType
import se.rimmer.rc.compiler.parser.FunType as ASTFunType
import se.rimmer.rc.compiler.parser.GenType as ASTGenType
import se.rimmer.rc.compiler.parser.MapType as ASTMapType
import se.rimmer.rc.compiler.parser.Module as ASTModule
import se.rimmer.rc.compiler.parser.TupType as ASTTupType
import se.rimmer.rc.compiler.parser.Type as ASTType
import se.rimmer.rc.compiler.parser.Decl as ASTDecl
import se.rimmer.rc.compiler.parser.DataDecl as ASTDataDecl
import se.rimmer.rc.compiler.parser.TypeDecl as ASTTypeDecl
import se.rimmer.rc.compiler.parser.ClassDecl as ASTClassDecl
import se.rimmer.rc.compiler.parser.InstanceDecl as ASTInstanceDecl

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
                module.types[it.type.name] = AliasType(it.type.kind.map {GenType()}, unitType, it)
            }
            is ASTDataDecl -> {
                if(module.types.containsKey(it.type.name)) {
                    throw ResolveError("redefinition of type ${it.type.name}")
                }

                // The constructors are declared here, but resolved later.
                val record = RecordType(it)
                val cons = it.cons.mapIndexed { i, (name) -> Constructor(module.name.extend(name), i, record) }
                cons.forEach {
                    if(module.constructors.containsKey(it.name.name)) {
                        throw ResolveError("redefinition of type constructor ${it.name.name}")
                    }
                    module.constructors[it.name.name] = it
                }

                record.constructors.addAll(cons)
                module.types[it.type.name]
            }
        }
    }

    // When all names are defined, start resolving the types.
    module.types.forEach { _, t ->
        resolveType()
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
    module.findType(type.name)?.let {
        return lazyResolve(it)
    }

    // Check for builtin types.
    if(type.name.qualifier.isEmpty()) {
        val primitive = primitiveTypes.firstOrNull { it.prim.sourceName == type.name.name }
        if(primitive != null) return primitive
    }

    throw ResolveError("unresolved type name ${type.name}")
}

fun resolveFunType(module: Module, type: ASTFunType, tScope: SimpleType?): Type {
    throw NotImplementedError()
}

fun resolveArrayType(module: Module, type: ASTArrayType, tScope: SimpleType?): Type {
    throw NotImplementedError()
}

fun resolveMapType(module: Module, type: ASTMapType, tScope: SimpleType?): Type {
    throw NotImplementedError()
}

fun resolveTupType(module: Module, type: ASTTupType, tScope: SimpleType?): Type {
    throw NotImplementedError()
}

fun resolveAppType(module: Module, type: ASTAppType, tScope: SimpleType?): Type {
    // Find the base type and instantiate it for these arguments.
    val base = resolveType(module, type.base, tScope)
    throw NotImplementedError()
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

private fun getGenIndex(name: String, scope: SimpleType): Int? {
    val i = scope.kind.indexOfFirst { it == name }
    return if(i >= 0) i else null
}

private fun lazyResolve(t: Type) = when(t) {
    is AliasType -> resolveAlias(t).target
    is RecordType -> resolveRecord(t)
    else -> t
}