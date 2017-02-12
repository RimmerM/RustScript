package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.Qualified
import java.util.*

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

val Block.isFirst: Boolean get() = incoming.isEmpty()
val Block.isLast: Boolean get() = outgoing.isEmpty()

// Finds an initialized value in this block.
fun Block.findValue(name: Qualified): Value? {
    if(name.qualifier.isEmpty()) {
        namedValues[name.name]?.let { return it }
        preceding?.let { return it.findValue(name) }
        function.args[name.name]?.let { return it }
    }

    // TODO: Global variables
    return null
}

private fun Block.use(value: Value, user: Inst): Value {
    value.uses.add(Use(value, user))
    value.blockUses.add(this)
    return value
}

private inline fun Block.inst(f: Block.() -> Inst): Inst {
    val inst = f()
    inst.usedValues.forEach { use(it, inst) }

    if(!complete) {
        instructions.add(inst)

        if(inst.name != null) {
            namedValues[inst.name] = inst
        }

        if(inst.isTerminating) {
            complete = true
        }

        if(inst is RetInst) {
            returns = true
            function.returns.add(inst)
        } else if(inst is IfInst) {
            outgoing.add(inst.then)
            outgoing.add(inst.otherwise)
            inst.then.incoming.add(this)
            inst.otherwise.incoming.add(this)
        } else if(inst is BrInst) {
            outgoing.add(inst.to)
            inst.to.incoming.add(this)
        }
    }
    return inst
}

fun Block.trunc(name: String?, from: Value, type: Type) = inst {
    TruncInst(this, name, from, type)
}

fun Block.widen(name: String?, from: Value, type: Type) = inst {
    WidenInst(this, name, from, type)
}

fun Block.floatToInt(name: String?, from: Value, type: IntType) = inst {
    FloatToIntInst(this, name, from, type)
}

fun Block.intToFloat(name: String?, from: Value, type: FloatType) = inst {
    IntToFloatInst(this, name, from, type)
}

fun Block.add(name: String?, lhs: Value, rhs: Value) = inst {
    AddInst(this, name, lhs, rhs)
}

fun Block.sub(name: String?, lhs: Value, rhs: Value) = inst {
    SubInst(this, name, lhs, rhs)
}

fun Block.mul(name: String?, lhs: Value, rhs: Value) = inst {
    MulInst(this, name, lhs, rhs)
}

fun Block.div(name: String?, lhs: Value, rhs: Value) = inst {
    DivInst(this, name, lhs, rhs)
}

fun Block.idiv(name: String?, lhs: Value, rhs: Value) = inst {
    IDivInst(this, name, lhs, rhs)
}

fun Block.rem(name: String?, lhs: Value, rhs: Value) = inst {
    RemInst(this, name, lhs, rhs)
}

fun Block.cmp(name: String?, cmp: Cmp, unsigned: Boolean, lhs: Value, rhs: Value) = inst {
    CmpInst(this, name, lhs, rhs, cmp, unsigned)
}

fun Block.shl(name: String?, v: Value, amount: Value) = inst {
    ShlInst(this, name, v, amount)
}

fun Block.shr(name: String?, v: Value, amount: Value) = inst {
    ShrInst(this, name, v, amount)
}

fun Block.ashr(name: String?, v: Value, amount: Value) = inst {
    AShrInst(this, name, v, amount)
}

fun Block.and(name: String?, lhs: Value, rhs: Value) = inst {
    AndInst(this, name, lhs, rhs)
}

fun Block.or(name: String?, lhs: Value, rhs: Value) = inst {
    OrInst(this, name, lhs, rhs)
}

fun Block.xor(name: String?, lhs: Value, rhs: Value) = inst {
    XorInst(this, name, lhs, rhs)
}

fun Block.alloca(name: String?, type: Type) = inst {
    AllocaInst(this, name, RefType(type))
}

fun Block.load(name: String?, ref: Value) = inst {
    LoadInst(this, name, (ref.type as RefType).to, ref)
}

fun Block.store(name: String?, ref: Value, value: Value) = inst {
    StoreInst(this, name, value, ref)
}

fun Block.loadField(name: String?, from: Value, field: Int, fieldType: Type) = inst {
    LoadFieldInst(this, name, fieldType, from, field)
}

fun Block.storeField(name: String?, value: Value, field: Int, fieldValue: Value) = inst {
    StoreFieldInst(this, name, fieldValue, value, field)
}

fun Block.getField(name: String?, from: Value, field: Int, fieldType: Type) = inst {
    GetFieldInst(this, name, fieldType, from, field)
}

fun Block.updateField(name: String?, value: Value, updates: List<Pair<Int, Value>>) = inst {
    UpdateFieldInst(this, name, value, updates)
}

fun Block.call(name: String?, function: Function, args: List<Value>) = inst {
    CallInst(this, name, function, args)
}

fun Block.ret(value: Value) = inst {
    RetInst(this, value)
}

fun Block.`if`(condition: Value, then: Block, otherwise: Block) = inst {
    IfInst(this, null, condition, then, otherwise)
}

fun Block.br(to: Block) = inst {
    BrInst(this, null, to)
}

fun Block.phi(name: String?, type: Type, alts: List<Pair<Value, Block>>) = inst {
    PhiInst(this, name, type, alts)
}

fun Block.tup(name: String?, type: TupType, fields: List<Value>) = inst {
    TupInst(this, name, type, fields)
}

fun Block.array(name: String?, type: ArrayType, values: List<Value>) = inst {
    ArrayInst(this, name, type, values)
}

fun Block.map(name: String?, type: MapType, values: List<Pair<Value, Value>>) = inst {
    MapInst(this, name, type, values)
}