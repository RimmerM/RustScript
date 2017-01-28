package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.SimpleType
import se.rimmer.rc.compiler.parser.AppType as ASTAppType
import se.rimmer.rc.compiler.parser.ArrayType as ASTArrayType
import se.rimmer.rc.compiler.parser.ConType as ASTConType
import se.rimmer.rc.compiler.parser.FunType as ASTFunType
import se.rimmer.rc.compiler.parser.GenType as ASTGenType
import se.rimmer.rc.compiler.parser.MapType as ASTMapType
import se.rimmer.rc.compiler.parser.Module as ASTModule
import se.rimmer.rc.compiler.parser.TupType as ASTTupType
import se.rimmer.rc.compiler.parser.Type as ASTType

val primitiveTypes = Primitive.values().map(::PrimType)

fun resolveType(scope: Scope, type: ASTType, tScope: SimpleType?): Type {
    return when(type) {
        is ASTTupType -> resolveTupType(scope, type, tScope)
        is ASTGenType -> resolveGenType(scope, type, tScope)
        is ASTAppType -> resolveAppType(scope, type, tScope)
        is ASTConType -> resolveConType(scope, type, tScope)
        is ASTFunType -> resolveFunType(scope, type, tScope)
        is ASTArrayType -> resolveArrayType(scope, type, tScope)
        is ASTMapType -> resolveMapType(scope, type, tScope)
        else -> throw NotImplementedError()
    }
}

fun resolveConType(scope: Scope, type: ASTConType, tScope: SimpleType?): Type {
    // Try to find a user-defined type first.
    scope.findType(type.name)?.let {
        return lazyResolve(it)
    }

    // Check for builtin types.
    if(type.name.qualifier.isEmpty()) {
        val primitive = primitiveTypes.firstOrNull { it.prim.sourceName == type.name.name }
        if(primitive != null) return primitive
    }

    throw ResolveError("unresolved type name ${type.name}")
}

fun resolveFunType(scope: Scope, type: ASTFunType, tScope: SimpleType?): Type {
    throw NotImplementedError()
}

fun resolveArrayType(scope: Scope, type: ASTArrayType, tScope: SimpleType?): Type {
    throw NotImplementedError()
}

fun resolveMapType(scope: Scope, type: ASTMapType, tScope: SimpleType?): Type {
    throw NotImplementedError()
}

fun resolveTupType(scope: Scope, type: ASTTupType, tScope: SimpleType?): Type {
    throw NotImplementedError()
}

fun resolveAppType(scope: Scope, type: ASTAppType, tScope: SimpleType?): Type {
    // Find the base type and instantiate it for these arguments.
    val base = resolveType(scope, type.base, tScope)
    throw NotImplementedError()
}

fun resolveGenType(scope: Scope, type: ASTGenType, tScope: SimpleType?): Type {
    if(tScope != null) {
        val i = getGenIndex(type.name, tScope)
        if(i != null) return GenType(i)
    }
    throw ResolveError("undefined generic type '${type.name}'")
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