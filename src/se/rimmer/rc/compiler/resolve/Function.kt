package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.ForeignDecl
import se.rimmer.rc.compiler.parser.Qualified

data class Operator(val precedence: Int, val isRight: Boolean)

class ForeignFunction(
    var ast: ForeignDecl?,
    val module: Module,
    val name: Qualified,
    val externalName: String,
    val from: String?,
    var type: FunType? = null
)

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

    // Always inline this as early as possible.
    var alwaysInline = false

    var codegen: Any? = null
}

fun Function.block(): Block {
    val block = Block(this)
    blocks.add(block)
    return block
}

class FunctionBuilder(val function: Function) {
    var block = function.body
}