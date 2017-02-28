package se.rimmer.rc.compiler.compiler

import se.rimmer.rc.compiler.parser.Node

open class Diagnostics {
    var warnings = 0
    var errors = 0

    fun warning(text: String, where: Node?) {
        message(warningLevel, text, where)
    }

    fun error(text: String, where: Node?) {
        message(errorLevel, text, where)
    }

    open fun message(level: Int, text: String, where: Node?) {
        when(level) {
            warningLevel -> warnings++
            errorLevel -> errors++
        }
    }

    companion object {
        val messageLevel = 0
        val warningLevel = 1
        val errorLevel = 2
    }
}

class PrintDiagnostics: Diagnostics() {
    override fun message(level: Int, text: String, where: Node?) {
        super.message(level, text, where)
        val name = when(level) {
            warningLevel -> "Warning"
            errorLevel -> "Error"
            else -> "Note"
        }

        if(where == null) {
            println("$name: $text")
        } else {
            println("$name at ${where.print()}: $text")
        }
    }
}