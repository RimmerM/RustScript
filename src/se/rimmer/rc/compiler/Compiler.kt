package se.rimmer.rc.compiler

open class Diagnostics {
    var warnings = 0
    var errors = 0

    open fun warning(text: String) {
        warnings++
    }

    open fun error(text: String) {
        errors++
    }
}

class PrintDiagnostics: Diagnostics() {
    override fun warning(text: String) {
        super.warning(text)
        println("Warning: $text")
    }

    override fun error(text: String) {
        super.error(text)
        println("Error: $text")
    }
}