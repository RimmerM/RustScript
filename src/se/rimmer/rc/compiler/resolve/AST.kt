package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.*
import se.rimmer.rc.compiler.parser.MapType as ASTMapType
import se.rimmer.rc.compiler.parser.ArrayType as ASTArrayType
import se.rimmer.rc.compiler.parser.TupType as ASTTupType
import java.util.*

data class Scope(val name: Qualified, val parent: Scope?, val function: LocalFunction?) {
    val imports = HashMap<List<String>, ImportedScope>()
    val children = HashMap<String, Scope>()
    val shadows = HashMap<String, Var>()
    val captures = HashMap<String, Var>()
    val functions = HashMap<String, FunctionHead>()
    val types = HashMap<String, Type>()
    val constructors = HashMap<String, Constructor>()
    val ops = HashMap<String, Operator>()

    // The variables that have been defined to the current resolving point.
    // Used for lookups to make sure that variables are initialized before use.
    val definedVariables = HashMap<String, Var>()

    // The symbols defined in this module that are visible externally.
    val exportedSymbols = HashSet<String>()

    var codegen: Any? = null
}

data class ImportedScope(
    val scope: Scope,
    val qualified: Boolean,
    val qualifier: List<String>,
    val includedSymbols: Set<String>?,
    val excludedSymbols: Set<String>?
)

data class Operator(val precedence: Int, val isRight: Boolean)

data class Var(val name: String, val type: Type, val scope: Scope, val isConstant: Boolean, val isArg: Boolean) {
    var codegen: Any? = null
}

val Var.isVar: Boolean get() = !isConstant && !isArg

data class FunctionArg(val type: Type, val name: String?, val local: Var?)

class FunctionHead {
    val args = ArrayList<FunctionArg>()
    var ret: Type? = null
    var body: Function? = null
}

interface Function

class LocalFunction(var ast: FunDecl?, name: Qualified, parentScope: Scope, val head: FunctionHead): Function {
    val scope = Scope(name, parentScope, this)
    var content: Expr? = null


    var codegen: Any? = null
}

class ForeignFunction(var ast: ForeignDecl?, val name: Qualified, val externalName: String, val head: FunctionHead): Function

interface Type

data class UnknownType(val v: Unit): Type
val unknownType = UnknownType(Unit)

data class UnitType(val v: Unit): Type
val unitType = UnitType(Unit)

data class AliasType(var ast: TypeDecl?, var target: Type, val scope: Scope): Type
data class LVType(val target: Type): Type

/**
 * Generic types are defined by an index to the list of generic parameters of the parent scope.
 * For example, in the type 'Either(a, b)', 'b' is represented with the index 1.
 */
data class GenType(val index: Int): Type

enum class Primitive(val sourceName: kotlin.String) { Int("Int"), Double("Double"), Bool("Bool"), String("String") }
data class PrimType(val prim: Primitive): Type

data class FunType(val head: FunctionHead): Type

enum class RecordKind { Enum, Single, Multi }

data class RecordType(var ast: DataDecl?, val scope: Scope): Type {
    var kind = RecordKind.Multi
    val constructors = ArrayList<Constructor>()
}

class Field(val name: String?, val index: Int, val type: Type, val container: Type, val mutable: Boolean)

data class TupType(var ast: ASTTupType?, val fields: List<Field>): Type
data class ArrayType(var ast: ASTArrayType?, val content: Type): Type
data class MapType(var ast: ASTMapType?, val from: Type, val to: Type): Type

data class Constructor(val name: Qualified, val index: Int, val parent: RecordType, var content: Type? = null)

data class Use(val value: Value, val user: Any)

open class Value(val name: String?, val type: Type) {
    val uses = ArrayList<Use>()
}

class Lit(val literal: Literal, type: Type): Value(null, type)

class Block(val function: LocalFunction) {
    val instructions = ArrayList<Inst>()
    val symbols = HashMap<String, Value>()
}

open class Inst(name: String?, type: Type): Value(name, type)

/* Stack instructions. */
class AllocaInst(name: String?, type: Type): Inst(name, type)
class LoadInst(name: String?, type: Type, val value: Value): Inst(name, type)
class StoreInst(name: String?, val value: Value, val to: Value): Inst(name, unitType)
class LoadFieldInst(name: String?, type: Type, val from: Value, val field: Int): Inst(name, type)
class StoreFieldInst(name: String?, val value: Value, val to: Value, val field: Int): Inst(name, unitType)

/* Construction instructions. */
class RecordInst(name: String?, type: RecordType, val fields: List<Value>): Inst(name, type)
class TupInst(name: String?, type: TupType, val fields: List<Value>): Inst(name, type)
class ArrayInst(name: String?, type: ArrayType, val values: List<Value>): Inst(name, type)
class MapInst(name: String?, type: MapType, val pairs: List<Pair<Value, Value>>): Inst(name, type)
class FunInst(name: String?, type: FunType, val function: LocalFunction): Inst(name, type)

/* Operation instructions. */
class PrimInst(name: String?, type: Type, val op: PrimOp, val args: List<Value>): Inst(name, type)
class CallInst(name: String?, val function: FunctionHead, val args: List<Value>): Inst(name, function.ret!!)
class CallDynInst(name: String?, type: Type, val function: Value, val args: List<Value>): Inst(name, type)
class CastPrimInst(name: String?, type: Type, val source: Value): Inst(name, type)

/* Record value instructions. */
class GetFieldInst(name: String?, type: Type, val from: Value, val field: Int): Inst(name, type)
class UpdateFieldInst(name: String?, val value: Value, val from: Value, val field: Int): Inst(name, from.type)

/* Control flow. */
class IfInst(name: String?, val condition: Value, val then: Block, val otherwise: Block): Inst(name, unitType)
class BranchInst(name: String?, val to: Block): Inst(name, unitType)
class RetInst(val value: Value): Inst(null, value.type)
class PhiInst(name: String?, type: Type, val values: List<Pair<Value, Block>>): Inst(name, type)