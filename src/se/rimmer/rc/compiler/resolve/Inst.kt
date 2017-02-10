package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.Qualified

val Inst.isTerminating: Boolean get() = when(this) {
    is RetInst -> true
    is IfInst -> true
    is BrInst -> true
    else -> false
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

fun Block.primOp(name: String?, op: PrimOp, lhs: Value, rhs: Value) = inst {
    PrimInst(this, name, binaryOpType(op, lhs.type, rhs.type), op, listOf(lhs, rhs))
}

fun Block.primOp(name: String?, op: PrimOp, arg: Value) = inst {
    PrimInst(this, name, unaryOpType(op, arg.type), op, listOf(arg))
}

fun Block.cast(name: String?, )

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