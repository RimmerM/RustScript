package se.rimmer.rc.compiler.compiler

import se.rimmer.rc.compiler.parser.ParserListener
import se.rimmer.rc.compiler.resolve.Types

class Context(val diagnostics: Diagnostics, val parserListener: ParserListener, val types: Types)