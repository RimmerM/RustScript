package se.rimmer.rc.compiler.resolve

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

fun resolveCon(module: Module, con: ASTConType): Con {
    return module.findCon(con.name) ?: throw ResolveError("constructor ${con.name} not found")
}

fun resolveApplicableType(module: Module, type: ASTType, gen: GenMap?): Type {
    val result = when(type) {
        is ASTTupType -> resolveTupType(module, type, gen)
        is ASTGenType -> resolveGenType(module, type, gen)
        is ASTAppType -> resolveAppType(module, type, gen)
        is ASTConType -> resolveConType(module, type, gen)
        is ASTFunType -> resolveFunType(module, type, gen)
        is ASTArrayType -> resolveArrayType(module, type, gen)
        is ASTMapType -> resolveMapType(module, type, gen)
        else -> throw NotImplementedError()
    }
    return resolveDefinition(module, result)
}

fun resolveType(module: Module, type: ASTType, gen: GenMap?): Type {
    val result = resolveApplicableType(module, type, gen)
    when(result) {
        is AliasType -> if(result.generics.isNotEmpty()) throw ResolveError("cannot use a generic type here")
        is RecordType -> if(result.generics.isNotEmpty()) throw ResolveError("cannot use a generic type here")
    }
    return result
}

fun resolveConType(module: Module, type: ASTConType, gen: GenMap?): Type {
    // Try to find a user-defined type first.
    module.findType(type.name)?.let { return it }
    throw ResolveError("unresolved type name ${type.name}")
}

fun resolveFunType(module: Module, type: ASTFunType, gen: GenMap?): Type {
    val args = type.args.mapIndexed { i, it ->
        FunArg(it.name, i, resolveType(module, type, gen))
    }
    val ret = resolveType(module, type.ret, gen)
    return FunType(args, ret)
}

fun resolveArrayType(module: Module, type: ASTArrayType, gen: GenMap?): Type {
    val content = resolveType(module, type.type, gen)
    return ArrayType(content)
}

fun resolveMapType(module: Module, type: ASTMapType, gen: GenMap?): Type {
    val from = resolveType(module, type.from, gen)
    val to = resolveType(module, type.from, gen)
    return MapType(from, to)
}

fun <T> findTupLayout(module: Module, lookup: LayoutLookup, fields: Iterator<T>, getType: (T) -> Type): TupLayout {
    if(fields.hasNext()) {
        val type = getType(fields.next())

        lookup.next[type]?.let {
            return findTupLayout(module, it, fields, getType)
        }

        val next = LayoutLookup(lookup.layout + type)
        lookup.next[type] = next
        return findTupLayout(module, next, fields, getType)
    } else {
        return lookup.layout
    }
}

fun resolveTupType(module: Module, type: ASTTupType, gen: GenMap?): Type {
    val layout = findTupLayout(module, module.typeContext.layouts, type.fields.iterator()) {resolveType(module, it.type, gen)}
    val tup = TupType(emptyList(), layout)
    val fields = type.fields.mapIndexed { i, it ->
        Field(it.name, i, layout[i], tup, it.mutable)
    }
    tup.fields = fields
    return tup
}

fun resolveAppType(module: Module, type: ASTAppType, gen: GenMap?): Type {
    // Find the base type and instantiate it for these arguments.
    val base = resolveApplicableType(module, type.base, gen)
    val args = type.apps.map { resolveType(module, type, gen) }
    return when(base) {
        is AliasType -> instantiateAlias(module, base, args)
        is RecordType -> instantiateRecord(module, base, args)
        else -> throw ResolveError("${type.base} is not a generic type")
    }
}

fun resolveGenType(module: Module, type: ASTGenType, gen: GenMap?): Type {
    if(gen != null) {
        gen[type.name]?.let { return it }
    }
    throw ResolveError("undefined generic type '${type.name}'")
}

fun typesCompatible(a: Type, b: Type): Boolean {
    if(a === b) return true
    return when(a) {
        is IntType -> b is IntType && a.kind === b.kind
        is FloatType -> b is FloatType && a.kind === b.kind
        is StringType -> b is StringType
        is ErrorType -> b is ErrorType
        is RefType -> b is RefType && typesCompatible(a.to, b.to)
        else -> throw NotImplementedError()
    }
}

private fun instantiateAlias(module: Module, type: AliasType, args: List<Type>): AliasType {
    return AliasType(null, type.name, instantiateType(module, type.to, args), type.derivedFrom ?: type)
}

private fun instantiateRecord(module: Module, type: RecordType, args: List<Type>): RecordType {
    val record = RecordType(null, type.name, type)
    type.constructors.forEach {
        val content = it.content?.let {
            instantiateType(module, it, args)
        }
        record.constructors.add(if(content !== it.content) Con(it.name, it.index, record, content) else it)
    }
    return record
}

private fun instantiateType(module: Module, type: Type, args: List<Type>): Type {
    return when(type) {
        is ErrorType, is IntType, is FloatType, is StringType -> type
        is GenType -> args[type.index]
        is RefType -> RefType(instantiateType(module, type.to, args))
        is AliasType -> instantiateAlias(module, type, args)
        is RecordType -> instantiateRecord(module, type, args)
        is ArrayType -> ArrayType(instantiateType(module, type.content, args))
        is MapType -> MapType(instantiateType(module, type.from, args), instantiateType(module, type.to, args))
        is FunType -> FunType(type.args.map { FunArg(it.name, it.index, instantiateType(module, it.type, args)) }, instantiateType(module, type.result, args))
        is TupType -> {
            val layout = findTupLayout(module, module.typeContext.layouts, type.layout.iterator()) {instantiateType(module, it, args)}
            val tup = TupType(emptyList(), layout)
            tup.fields = type.fields.map {
                Field(it.name, it.index, layout[it.index], tup, it.mutable)
            }
            tup
        }
        else -> throw NotImplementedError()
    }
}

// Finishes the definition of a type defined in the module, if needed.
fun resolveDefinition(module: Module, type: Type): Type {
    when(type) {
        is AliasType -> resolveAlias(module, type)
        is RecordType -> resolveRecord(module, type)
    }
    return type
}

// Resolves the target of an alias, if it was unresolved.
fun resolveAlias(module: Module, type: AliasType): AliasType {
    type.ast?.let {
        type.ast = null
        type.to = resolveType(module, it.target, type.generics)
    }
    return type
}

// Resolves the constructors in a record, if they were unresolved.
fun resolveRecord(module: Module, type: RecordType): RecordType {
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