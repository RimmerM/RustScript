package se.rimmer.rc.compiler

import se.rimmer.rc.compiler.parser.Node

open class Diagnostics {
    var warnings = 0
    var errors = 0

    open fun warning(text: String, where: Node) {
        warnings++
    }

    open fun error(text: String, where: Node) {
        errors++
    }
}

class PrintDiagnostics: Diagnostics() {
    override fun warning(text: String, where: Node) {
        super.warning(text, where)
        println("Warning at ${where.print()}: $text")
    }

    override fun error(text: String, where: Node) {
        super.error(text, where)
        println("Error at ${where.print()}: $text")
    }
}