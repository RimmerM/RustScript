package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.DataDecl
import se.rimmer.rc.compiler.parser.Qualified
import se.rimmer.rc.compiler.parser.TypeDecl
import java.util.*

interface Type

data class ErrorType(val v: Unit): Type
val errorType = ErrorType(Unit)

data class UnitType(val v: Unit): Type
val unitType = UnitType(Unit)

data class GenField(val name: String?, val type: Type, val mutable: Boolean, val gen: GenType)

class GenType(val index: Int): Type {
    val classes: MutableSet<TypeClass> = Collections.newSetFromMap(IdentityHashMap<TypeClass, Boolean>())
    val fields = HashMap<String, GenField>()
}

typealias GenMap = HashMap<String, GenType>

enum class IntKind(val bits: Int) { Bool(1), I8(8), I16(16), I32(32), I64(64) }
data class IntType(val width: Int, val kind: IntKind): Type
class StringType: Type

enum class FloatKind(val bits: Int) { F16(16), F32(32), F64(64) }
data class FloatType(val kind: FloatKind): Type

data class AliasType(var ast: TypeDecl?, var to: Type): Type {
    val generics = GenMap()
}

data class RefType(val to: Type): Type

data class FunArg(val name: String?, val index: Int, val type: Type)
data class FunType(val args: List<FunArg>, val result: Type): Type

enum class RecordKind { Enum, Single, Multi }

/**
 * A named, distinct ADT.
 * Special cases include enums (constructors only, no data) and structs (exactly one constructor with data inside).
 * @param derivedFrom If this is a specialisation of a generic version of the same type,
 * this indicates the base type that can be used for implicit conversions.
 */
data class RecordType(var ast: DataDecl?, val name: Qualified, val derivedFrom: RecordType?): Type {
    var kind = RecordKind.Multi
    val constructors = ArrayList<Con>()
    val generics = GenMap()
}

class Field(val name: String?, val index: Int, val type: Type, val container: Type, val mutable: Boolean)

class TupType: Type {
    val fields = ArrayList<Field>()
}

data class ArrayType(val content: Type): Type
data class MapType(val from: Type, val to: Type): Type

data class Con(val name: Qualified, val index: Int, val parent: RecordType, var content: Type? = null)

class GenScope {
    val types = ArrayList<GenType>()
}

class TypeClass(val name: Qualified) {
    val parameters = HashMap<String, GenType>()
    val functions = HashMap<String, FunType>()
}

class ClassInstance(val module: Module, val typeClass: TypeClass, val forType: Type) {
    val implementations = IdentityHashMap<FunType, Function>()
}

object PrimTypes {
    private val unsignedIntTypes = arrayOfNulls<IntType>(64)
    private val intTypes = arrayOfNulls<IntType>(64)
    private val floatTypes = FloatKind.values().map(::FloatType)

    val intType = int(32, true)
    val stringType = StringType()
    val boolType = IntType(1, IntKind.Bool).apply { unsignedIntTypes[0] = this }

    fun int(width: Int, signed: Boolean): IntType {
        if(width > 64) throw IllegalArgumentException("invalid integer size")

        val array = if(signed) intTypes else unsignedIntTypes
        array[width - 1]?.let { return it }

        val type = IntType(width, closestInt(width))
        array[width - 1] = type
        return type
    }

    fun float(kind: FloatKind) = floatTypes[kind.ordinal]

    private fun closestInt(width: Int) =
        if(width > 32) IntKind.I64
        else if(width > 16) IntKind.I32
        else if(width > 8) IntKind.I16
        else if(width > 1) IntKind.I8
        else IntKind.Bool
}