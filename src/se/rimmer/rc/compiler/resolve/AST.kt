package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.DataDecl
import se.rimmer.rc.compiler.parser.ForeignDecl
import se.rimmer.rc.compiler.parser.Literal
import se.rimmer.rc.compiler.parser.Qualified
import java.util.*
import se.rimmer.rc.compiler.parser.ArrayType as ASTArrayType
import se.rimmer.rc.compiler.parser.MapType as ASTMapType
import se.rimmer.rc.compiler.parser.TupType as ASTTupType
import se.rimmer.rc.compiler.parser.TypeDecl as ASTTypeDecl

data class Module(val name: Qualified) {
    val imports = HashMap<List<String>, Import>()
    val functions = HashMap<String, Function>()
    val templateFunctions = HashMap<String, Function>()
    val foreignFunctions = HashMap<String, ForeignFunction>()
    val types = HashMap<String, Type>()
    val constructors = HashMap<String, Con>()
    val ops = HashMap<String, Operator>()

    // The symbols defined in this module that are visible externally.
    val exportedSymbols = HashSet<String>()

    var codegen: Any? = null
}

data class Import(
    val module: Module,
    val qualified: Boolean,
    val qualifier: List<String>,
    val includedSymbols: Set<String>?,
    val excludedSymbols: Set<String>?
)

data class Operator(val precedence: Int, val isRight: Boolean)

class ForeignFunction(
    var ast: ForeignDecl?,
    val module: Module,
    val name: Qualified,
    val externalName: String,
    val from: String?,
    var type: FunType? = null
)

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

enum class Primitive(val sourceName: kotlin.String) { Int("Int"), Double("Double"), Bool("Bool"), String("String") }
data class PrimType(val prim: Primitive): Type

data class AliasType(var ast: ASTTypeDecl?, val generics: List<GenType>, var to: Type): Type
data class RefType(val to: Type): Type

data class FunArg(val name: String?, val index: Int, val type: Type)
data class FunType(val args: List<FunArg>, val result: Type): Type

enum class RecordKind { Enum, Single, Multi }

data class RecordType(var ast: DataDecl?, val generics: List<GenType>): Type {
    var kind = RecordKind.Multi
    val constructors = ArrayList<Con>()
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

class ClassInstance(val types: List<Type>, val typeClass: TypeClass) {
    val implementations = HashMap<String, Function>()
}

class Function(val module: Module, val name: Qualified) {
    // The function entry point.
    val body = Block(this)

    // All defined blocks in this function.
    val blocks = arrayListOf(body)

    // All basic blocks that return at the end.
    val returns = ArrayList<RetInst>()

    // The return type of this function.
    var returnType: Type? = null

    // The incoming set of normal function arguments.
    val args = HashMap<String, Value>()

    var codegen: Any? = null
}

// A sequence of instructions that are executed without interruption.
class Block(val function: Function) {
    val instructions = ArrayList<Inst>()

    // The defined values with a name in this block up to this point.
    val namedValues = HashMap<String, Value>()

    // All blocks that can branch to this one.
    val incoming: MutableSet<Block> = Collections.newSetFromMap(IdentityHashMap<Block, Boolean>())

    // All blocks this one can possibly branch to.
    val outgoing: MutableSet<Block> = Collections.newSetFromMap(IdentityHashMap<Block, Boolean>())

    // The closest block that always executes before this one.
    var preceding: Block? = null

    // The closest block that always executes after this one.
    var succeeding: Block? = null

    // Set if this block returns at the end.
    var returns = false

    // Set when the block contains a terminating instruction.
    // Appending instructions after this is set will have no effect.
    var complete = false

    var codegen: Any? = null
}

// A single usage of a value by an instruction.
data class Use(val value: Value, val user: Inst)

// A local register containing the result of some operation.
open class Value(val block: Block, val name: String?, val type: Type) {
    // Each instruction that uses this value.
    val uses = ArrayList<Use>()

    // Each block this value is used by.
    val blockUses: MutableSet<Block> = Collections.newSetFromMap(IdentityHashMap<Block, Boolean>())

    // Data for use by the code generator.
    var codegen: Any? = null
}

// An immediate value that can be used by instructions.
class Lit(block: Block, name: String?, type: Type, val literal: Literal): Value(block, name, type)

// A single operation that can be performed inside a function block.
open class Inst(block: Block, name: String?, type: Type, val usedValues: List<Value>): Value(block, name, type)

/* Stack instructions. */
class AllocaInst(block: Block, name: String?, type: Type): Inst(block, name, type, emptyList())
class LoadInst(block: Block, name: String?, type: Type, val value: Value): Inst(block, name, type, listOf(value))
class StoreInst(block: Block, name: String?, val value: Value, val to: Value): Inst(block, name, unitType, listOf(value, to))
class LoadFieldInst(block: Block, name: String?, type: Type, val from: Value, val field: Int): Inst(block, name, type, listOf(from))
class StoreFieldInst(block: Block, name: String?, val value: Value, val to: Value, val field: Int): Inst(block, name, unitType, listOf(value, to))

/* Construction instructions. */
class RecordInst(block: Block, name: String?, type: RecordType, val con: Con, val fields: List<Value>): Inst(block, name, type, fields)
class TupInst(block: Block, name: String?, type: TupType, val fields: List<Value>): Inst(block, name, type, fields)
class ArrayInst(block: Block, name: String?, type: ArrayType, val values: List<Value>): Inst(block, name, type, values)
class MapInst(block: Block, name: String?, type: MapType, val pairs: List<Pair<Value, Value>>): Inst(block, name, type, pairs.flatMap { it.toList() })
class FunInst(block: Block, name: String?, type: FunType, val function: Function, val captures: List<Value>): Inst(block, name, type, captures)

/* Operation instructions. */
class PrimInst(block: Block, name: String?, type: Type, val op: PrimOp, val args: List<Value>): Inst(block, name, type, args)
class CallInst(block: Block, name: String?, val function: Function, val args: List<Value>): Inst(block, name, function.returnType!!, args)
class CallDynInst(block: Block, name: String?, type: Type, val function: Value, val args: List<Value>): Inst(block, name, type, args)
class CallForeignInst(block: Block, name: String?, type: Type, val function: ForeignFunction, val args: List<Value>): Inst(block, name, type, args)
class CastPrimInst(block: Block, name: String?, type: Type, val source: Value): Inst(block, name, type, listOf(source))

/* Record value instructions. */
class GetFieldInst(block: Block, name: String?, type: Type, val from: Value, val field: Int): Inst(block, name, type, listOf(from))
class UpdateFieldInst(block: Block, name: String?, val from: Value, val updates: List<Pair<Int, Value>>): Inst(block, name, from.type, updates.map {it.second} + from)

/* Control flow. */
class IfInst(block: Block, name: String?, val condition: Value, val then: Block, val otherwise: Block): Inst(block, name, unitType, listOf(condition))
class BrInst(block: Block, name: String?, val to: Block): Inst(block, name, unitType, emptyList())
class RetInst(block: Block, val value: Value): Inst(block, null, value.type, listOf(value))
class PhiInst(block: Block, name: String?, type: Type, val values: List<Pair<Value, Block>>): Inst(block, name, type, values.map { it.first })