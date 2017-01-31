package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.FunDecl

fun Function.block(): Block {
    val block = Block(this)
    blocks.add(block)
    return block
}

class FunctionBuilder(val ast: FunDecl, val function: Function) {
    var block = function.body
}