package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.Literal
import java.util.*

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

// A value provided through a function parameter.
class Arg(block: Block, name: String?, type: Type, val index: Int): Value(block, name, type)

// A single operation that can be performed inside a function block.
open class Inst(block: Block, name: String?, type: Type, val usedValues: List<Value>): Value(block, name, type)

/*
 * Conversion instructions
 */

/** Truncates an integer/float or vector to a lower bit width */
class TruncInst(block: Block, name: String?, val from: Value, target: Type): Inst(block, name, target, listOf(from))

/** Widens an integer/float or vector to a higher bit width, filling the new space with either zeroes or the sign bit depending on the type. */
class WidenInst(block: Block, name: String?, val from: Value, target: Type): Inst(block, name, target, listOf(from))

class FloatToIntInst(block: Block, name: String?, val from: Value, type: IntType): Inst(block, name, type, listOf(from))
class IntToFloatInst(block: Block, name: String?, val from: Value, type: FloatType): Inst(block, name, type, listOf(from))

/*
 * Arithmetic instructions - these must be performed on two integers, float or vectors of the same type.
 */
class AddInst(block: Block, name: String?, val lhs: Value, val rhs: Value): Inst(block, name, lhs.type, listOf(lhs, rhs))
class SubInst(block: Block, name: String?, val lhs: Value, val rhs: Value): Inst(block, name, lhs.type, listOf(lhs, rhs))
class MulInst(block: Block, name: String?, val lhs: Value, val rhs: Value): Inst(block, name, lhs.type, listOf(lhs, rhs))
class DivInst(block: Block, name: String?, val lhs: Value, val rhs: Value): Inst(block, name, lhs.type, listOf(lhs, rhs))
class IDivInst(block: Block, name: String?, val lhs: Value, val rhs: Value): Inst(block, name, lhs.type, listOf(lhs, rhs))
class RemInst(block: Block, name: String?, val lhs: Value, val rhs: Value): Inst(block, name, lhs.type, listOf(lhs, rhs))

enum class Cmp { eq, neq, gt, ge, lt, le }
class CmpInst(block: Block, name: String?, val lhs: Value, val rhs: Value, val cmp: Cmp, val unsigned: Boolean): Inst(block, name, PrimTypes.boolType, listOf(rhs, lhs))

/*
 * Bitwise instructions - must be performed on integer types or integer vectors
 */
class ShlInst(block: Block, name: String?, val arg: Value, val amount: Value): Inst(block, name, arg.type, listOf(arg, amount))
class ShrInst(block: Block, name: String?, val arg: Value, val amount: Value): Inst(block, name, arg.type, listOf(arg, amount))
class AShrInst(block: Block, name: String?, val arg: Value, val amount: Value): Inst(block, name, arg.type, listOf(arg, amount))
class AndInst(block: Block, name: String?, val lhs: Value, val rhs: Value): Inst(block, name, lhs.type, listOf(lhs, rhs))
class OrInst(block: Block, name: String?, val lhs: Value, val rhs: Value): Inst(block, name, lhs.type, listOf(lhs, rhs))
class XorInst(block: Block, name: String?, val lhs: Value, val rhs: Value): Inst(block, name, lhs.type, listOf(lhs, rhs))

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

val Inst.isTerminating: Boolean get() = when(this) {
    is RetInst -> true
    is IfInst -> true
    is BrInst -> true
    else -> false
}