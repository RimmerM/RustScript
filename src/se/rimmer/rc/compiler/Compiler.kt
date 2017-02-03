package se.rimmer.rc.compiler

import se.rimmer.rc.compiler.parser.SourceLocation

open class Diagnostics {
    var warnings = 0
    var errors = 0

    open fun warning(text: String, where: SourceLocation) {
        warnings++
    }

    open fun error(text: String, where: SourceLocation) {
        errors++
    }
}

class PrintDiagnostics: Diagnostics() {
    override fun warning(text: String, where: SourceLocation) {
        super.warning(text, where)
        println("Warning: $text")
    }

    override fun error(text: String, where: SourceLocation) {
        super.error(text, where)
        println("Error: $text")
    }
}