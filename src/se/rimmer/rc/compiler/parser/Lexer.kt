package se.rimmer.rc.compiler.parser

import se.rimmer.rc.compiler.Diagnostics

/**
 * A lexer for Haskell 2010 with unlimited lookahead.
 * This is implemented through the function PeekNext(), which returns the token after the provided one.
 * The lexer implements the layout rules by inserting the '{', ';' and '}' tokens according to the spec.
 * If no module specification is found at the start of the file,
 * it assumes that the base indentation level is the indentation of the first token.
 */
class Lexer(val text: String, var token: Token, val diagnostics: Diagnostics) {
    /**
     * Returns the next token from the stream.
     * On the next call to Next(), the returned token is overwritten with the data from that call.
     */
    fun next(): Token {
        parseToken()
        return token
    }

    private val hasMore: Boolean get() = p < text.length
    private val current: Char get() = text[p]
    private fun next(index: Int) = text[p + index]

    /**
     * Increments p until it no longer points to whitespace.
     * Updates the line statistics.
     */
    private fun skipWhitespace() {
        loop@ while(hasMore) {
            // Skip whitespace.
            if(!whiteChar_UpdateLine()) {
                // Check for single-line comments.
                if(current == '-' && next(1) == '-' && !isSymbol(next(2))) {
                    // Skip the current line.
                    p += 2
                    while(hasMore && current != '\n') {
                        p++
                    }

                    // If this is a newline, we update the location.
                    // If it is the file end, the caller will take care of it.
                    if(current == '\n') {
                        nextLine()
                        p++
                        continue@loop
                    }
                }

                // Check for multi-line comments.
                else if(current == '{' && next(1) == '-') {
                    // The current nested comment depth.
                    var level = 1

                    // Skip until the comment end.
                    p += 2
                    while(hasMore) {
                        // Update the com.youpic.codegen.getSource location if needed.
                        if(current == '\n') {
                            nextLine()
                        }

                        // Check for nested comments.
                        if(current == '{' && next(1) == '-') {
                            level++
                        }

                        // Check for comment end.
                        if(current == '-' && next(1) == '}') {
                            level--
                            if(level == 0) {
                                p += 2
                                continue@loop
                            }
                        }
                    }

                    // p now points to the first character after the comment, or the file end.
                    // Check if the comments were nested correctly.
                    diagnostics.warning("Incorrectly nested comment: missing $level comment terminator(s).")
                }

                break
            }

            // Check the next character.
            p++
        }
    }

    /**
     * Parses p as a UTF-8 code point and returns it as UTF-32.
     * If p doesn't contain valid UTF-32, warnings are generated and ' ' is returned.
     */
    private fun nextCodePoint(): Char {
        val c = current
        p++
        return c
    }

    /**
     * Indicates that the current source pointer is the start of a new line,
     * and updates the location.
     */
    private fun nextLine() {
        l = p + 1
        line++
        tabs = 0
    }

    /**
     * Checks if the current source character is white.
     * If it is a newline, the source location is updated.
     */
    private fun whiteChar_UpdateLine(): Boolean {
        if(current == '\n') {
            nextLine()
            return true
        }

        if(current == '\t') {
            tabs++
            return true
        }

        return isWhiteChar(current)
    }

    /**
     * Parses a string literal.
     * p must point to the first character after the start of the literal (").
     * If the literal is invalid, warnings or errors are generated and an empty string is returned.
     */
    private fun parseStringLiteral(): String {
        val builder = StringBuilder()
        p++
        while(true) {
            if(current == '\\') {
                // This is an escape sequence or gap.
                p++
                if(whiteChar_UpdateLine()) {
                    // This is a gap - we skip characters until the next '\'.
                    // Update the current com.youpic.codegen.getSource line if needed.
                    p++
                    while(whiteChar_UpdateLine()) {
                        p++
                    }

                    if(current != '\\') {
                        // The first character after a gap must be '\'.
                        diagnostics.warning("Missing gap end in string literal")
                    }

                    // Continue parsing the string.
                    p++
                } else {
                    builder.append(parseEscapedLiteral())
                }
            } else if(current == kFormatStart) {
                // Start a string format sequence.
                formatState = 1
                p++
                break
            } else {
                if (current == '\"') {
                    // Terminate the string.
                    p++
                    break
                } else if (!hasMore || current == '\n') {
                    // If the line ends without terminating the string, we issue a warning.
                    diagnostics.warning("Missing terminating quote in string literal")
                    break
                } else {
                    // Add this UTF-8 character to the string.
                    builder.append(nextCodePoint())
                }
            }
        }
        return builder.toString()
    }

    /**
     * Parses a character literal.
     * p must point to the first character after the start of the literal (').
     * If the literal is invalid, warnings are generated and ' ' is returned.
     */
    private fun parseCharLiteral(): Char {
        p++
        val c: Char

        if(current == '\\') {
            // This is an escape sequence.
            p++
            c = parseEscapedLiteral()
        } else {
            // This is a char literal.
            c = nextCodePoint()
        }

        // Ignore any remaining characters in the literal.
        // It needs to end on this line.
        val ch = current
        p++
        if(ch != '\'') {
            diagnostics.warning("Multi-character character constant")
            while (current != '\'') {
                if (!hasMore || current == '\n') {
                    diagnostics.warning("Missing terminating ' character in char literal")
                    break
                }
                p++
            }
        }
        return c
    }

    /**
     * Parses an escape sequence from a character literal.
     * p must point to the first character after '\'.
     * @return The code point generated. If the sequence is invalid, warnings are generated and ' ' is returned.
     */
    private fun parseEscapedLiteral(): Char {
        val c = current
        p++
        when(c) {
            '{' ->
                // The left brace is used to start a formatting sequence.
                // Escaping it will print a normal brace.
                return '{'
            'b' -> return '\b'
            'n' -> return '\n'
            'r' -> return '\r'
            't' -> return '\t'
            '\\' -> return '\\'
            '\'' -> return '\''
            '\"' -> return '\"'
            '0' -> return 0.toChar()
            'x' -> {
                // Hexadecimal literal.
                if(parseHexit(current) == null) {
                    diagnostics.error("\\x used with no following hex digits")
                    return ' '
                }
                return parseIntSequence(16, 8).toChar()
            }
            'o' -> {
                // Octal literal.
                if(parseOctit(current) == null) {
                    diagnostics.error("\\o used with no following octal digits")
                    return ' '
                }
                return parseIntSequence(8, 16).toChar()
            }
            else -> {
                if(Character.isDigit(current)) {
                    return parseIntSequence(10, 10).toChar()
                } else {
                    diagnostics.warning("Unknown escape sequence '$c'")
                    return ' '
                }
            }
        }
    }

    /**
     * Parses a character literal from a text sequence with a certain base.
     * Supported bases are 2, 8, 10, 16.
     * @param numChars The maximum number of characters to parse.
     * @return The code point generated from the sequence.
     */
    fun parseIntSequence(base: Int, numChars: Int): Long {
        var res = 0L
        var i = 0
        while(i < numChars) {
            val c = current
            val num = Character.digit(c, base)

            if(num != -1) {
                res *= base
                res += num
                i++
                p++
            } else {
                break
            }
        }
        return res
    }

    fun readFloat(): Double {
        var c = p
        var out = 0.0

        // Check sign.
        var neg = false
        if(text[c] == '+') {
            c++
        } else if(text[c] == '-') {
            c++
            neg = true
        }

        // Create part before decimal point.
        while(Character.isDigit(text[c])) {
            val n = Character.digit(text[c], 10)
            out *= 10.0
            out += n
            c++
        }

        // Check if there is a fractional part.
        if(text[c] == '.') {
            c++
            var dec = 0.0
            var dpl = 0

            while(Character.isDigit(text[c])) {
                val n = Character.digit(text[c], 10)
                dec *= 10.0
                dec += n

                dpl++
                c++
            }

            // We need to use a floating point power here in order to support more than 9 decimals.
            val power = Math.pow(10.0, dpl.toDouble())
            dec /= power
            out += dec
        }

        // Check if there is an exponent.
        if(text[c] == 'E' || text[c] == 'e') {
            c++

            // Check sign.
            var signNegative = false
            if(text[c] == '+') {
                c++
            } else if(text[c] == '-') {
                c++
                signNegative = true
            }

            // Has exp. part;
            var exp = 0.0

            while(Character.isDigit(text[c])) {
                val n = Character.digit(text[c], 10)
                exp *= 10.0
                exp += n
                c++
            }

            if(signNegative) exp = -exp

            val power = Math.pow(10.0, exp)
            out *= power
        }

        if(neg) out = -out

        p = c
        return out
    }

    /**
     * Parses an integer literal with a custom base.
     * Supported bases are 2, 8, 10, 16.
     * @return The parsed number.
     */
    fun parseIntLiteral(base: Int): Long {
        var res = 0L
        var c = Character.digit(current, base)
        while(c != -1) {
            res *= base
            res += c
            p++
            c = Character.digit(current, base)
        }
        return res
    }

    /**
     * Parses a numeric literal into the current token.
     * p must point to the first digit of the literal.
     */
    private fun parseNumericLiteral() {
        token.type = Token.Type.Integer
        token.kind = Token.Kind.Literal

        // Parse the type of this literal.
        if(next(1) == 'b' || next(1) == 'B') {
            if(isBit(next(2))) {
                // This is a binary literal.
                p += 2
                token.intPayload = parseIntLiteral(2)
            } else {
                // Parse a normal integer.
                token.intPayload = parseIntLiteral(10)
            }
        } else if(next(1) == 'o' || next(1) == 'O') {
            if(isOctit(next(2))) {
                // This is an octal literal.
                p += 2
                token.intPayload = parseIntLiteral(8)
            } else {
                // Parse a normal integer.
                token.intPayload = parseIntLiteral(10)
            }
        } else if(next(1) == 'x' || next(1) == 'X') {
            if(isHexit(next(2))) {
                // This is a hexadecimal literal.
                p += 2
                token.intPayload = parseIntLiteral(16)
            } else {
                // Parse a normal integer.
                token.intPayload = parseIntLiteral(10)
            }
        } else {
            // Check for a dot or exponent to determine if this is a float.
            var d = p + 1
            while(true) {
                if(text[d] == '.') {
                    // The first char after the dot must be numeric, as well.
                    if(isDigit(text[d + 1])) break
                } else if(text[d] == 'e' || text[d] == 'E') {
                    // This is an exponent. If it is valid, the next char needs to be a numeric,
                    // with an optional sign in-between.
                    if(text[d+1] == '+' || text[d+1] == '-') d++
                    if(isDigit(text[d + 1])) break
                } else if(!isDigit(text[d])) {
                    // This wasn't a valid float.
                    token.intPayload = parseIntLiteral(10)
                    return
                }

                d++
            }

            // Parse a float literal.
            token.type = Token.Type.Float
            token.floatPayload = readFloat()
        }
    }

    /**
     * Parses a constructor operator, reserved operator or variable operator.
     * p must point to the first symbol of the operator.
     */
    private fun parseSymbol() {
        val sym1 = isSymbol(next(1))
        val sym2 = sym1 && isSymbol(next(2))

        // Instead of setting this in many different cases, we make it the default and override it later.
        token.kind = Token.Kind.Keyword

        if(!sym1) {
            // Check for various reserved operators of length 1.
            if(current == ':') {
                // Single colon.
                token.type = Token.Type.opColon
            } else if(current == '.') {
                // Single dot.
                token.type = Token.Type.opDot
            } else if(current == '=') {
                // This is the reserved Equals operator.
                token.type = Token.Type.opEquals
            } else if(current == '\\') {
                // This is the reserved backslash operator.
                token.type = Token.Type.opBackSlash
            } else if(current == '|') {
                // This is the reserved bar operator.
                token.type = Token.Type.opBar
            } else if(current == '$') {
                // This is the reserved dollar operator.
                token.type = Token.Type.opDollar
            } else if(current == '@') {
                // This is the reserved at operator.
                token.type = Token.Type.opAt
            } else if(current == '~') {
                // This is the reserved tilde operator.
                token.type = Token.Type.opTilde
            } else {
                // This is a variable operator.
                token.kind = Token.Kind.Identifier
            }
        } else if(!sym2) {
            // Check for various reserved operators of length 2.
            if(current == ':' && next(1) == ':') {
                // This is the reserved ColonColon operator.
                token.type = Token.Type.opColonColon
            } else if(current == '=' && next(1) == '>') {
                // This is the reserved double-arrow operator.
                token.type = Token.Type.opArrowD
            } else if(current == '.' && next(1) == '.') {
                // This is the reserved DotDot operator.
                token.type = Token.Type.opDotDot
            }  else if(current == '<' && next(1) == '-') {
                // This is the reserved arrow-left operator.
                token.type = Token.Type.opArrowL
            } else if(current == '-' && next(1) == '>') {
                // This is the reserved arrow-right operator.
                token.type = Token.Type.opArrowR
            } else {
                // This is a variable operator.
                token.kind = Token.Kind.Identifier
            }
        } else {
            // This is a variable operator.
            token.kind = Token.Kind.Identifier
        }

        if(token.kind == Token.Kind.Identifier) {
            // Check if this is a constructor.
            if(current == ':') {
                token.type = Token.Type.ConSym
            } else {
                token.type = Token.Type.VarSym
            }

            // Parse a symbol sequence.
            // Get the length of the sequence, we already know that the first one is a symbol.
            var count = 1
            val start = p
            while(isSymbol(text[(++p)])) count++

            // Check for a single minus operator - used for parser optimization.
            token.singleMinus = count == 1 && text[start] == '-'

            // Convert to UTF-32 and save in the current qualified name..
            qualifier = text.substring(start, start + count)
        } else {
            // Skip to the next token.
            if(sym1) p += 2
            else p++
        }
    }

    /**
     * Parses any special unicode symbols.
     */
    private fun parseUniSymbol(): Boolean {
        val ch = current

        if(ch.toInt() > 255) return false

        var handled = false

        if(ch == '→') {
            token.type = Token.Type.opArrowR
            token.kind = Token.Kind.Keyword
            handled = true
        } else if(ch == '←') {
            token.type = Token.Type.opArrowL
            token.kind = Token.Kind.Keyword
            handled = true
        } else if(ch == 'λ') {
            token.type = Token.Type.opBackSlash
            token.kind = Token.Kind.Keyword
            handled = true
        } else if(ch == '≤') {
            token.type = Token.Type.VarSym
            token.kind = Token.Kind.Identifier
            qualifier = "<="
            handled = true
        } else if(ch == '≥') {
            token.type = Token.Type.VarSym
            token.kind = Token.Kind.Identifier
            qualifier = ">="
            handled = true
        } else if(ch == '≠') {
            token.type = Token.Type.VarSym
            token.kind = Token.Kind.Identifier
            qualifier = "!="
            handled = true
        }

        if(handled) {
            p++
        }

        return handled
    }

    /**
     * Parses a special symbol.
     * p must point to the first symbol of the sequence.
     */
    private fun parseSpecial() {
        token.kind = Token.Kind.Special
        token.type = when(current) {
            '(' -> Token.Type.ParenL
            ')' -> Token.Type.ParenR
            ',' -> Token.Type.Comma
            ';' -> Token.Type.Semicolon
            '[' -> Token.Type.BracketL
            ']' -> Token.Type.BracketR
            '`' -> Token.Type.Grave
            '{' -> Token.Type.BraceL
            '}' -> Token.Type.BraceR
            else -> throw IllegalArgumentException("Must be special symbol.")
        }
        p++
    }

    /**
     * Parses a qualified id (qVarID, qConID, qVarSym, qConSym) or constructor.
     */
    private fun parseQualifier() {
        val start = p
        var length = 1
        token.kind = Token.Kind.Identifier
        token.type = Token.Type.ConID

        while((p < text.length - 1) && isIdentifier(text[(++p)])) length++

        val str = text.substring(start, start + length)

        // We have a ConID.
        qualifier = str
    }

    /**
     * Parses a variable id or reserved id.
     */
    private fun parseVariable() {
        token.type = Token.Type.VarID
        token.kind = Token.Kind.Identifier

        // First, check if we have a reserved keyword.
        var c = p + 1

        /**
         * Compares the string at `c` to a string constant.
         * @param constant The constant string to compare to.
         */
        fun compare(constant: String): Boolean {
            val src = c
            var ci = 0
            while(ci < constant.length && constant[ci] == text[c]) {
                ci++
                c++
            }

            if(ci == constant.length) return true
            else {
                c = src
                return false
            }
        }

        when(current) {
            '_' -> token.type = Token.Type.kw_
            'a' -> {
                if(text[c] == 's') { c++; token.type = Token.Type.kwAs }
            }
            'c' -> {
                if(compare("lass")) token.type = Token.Type.kwClass
            }
            'd' -> {
                if(compare("ata")) token.type = Token.Type.kwData
                else if(compare("eriving")) token.type = Token.Type.kwDeriving
                else if(text[c] == 'o') { c++; token.type = Token.Type.kwDo }
            }
            'e' -> {
                if(compare("lse")) token.type = Token.Type.kwElse
            }
            'f' -> {
                if(compare("oreign")) token.type = Token.Type.kwForeign
                else if(text[c] == 'o' && text[c + 1] == 'r') { c += 2; token.type = Token.Type.kwFor }
                else if(text[c] == 'n') { c += 1; token.type = Token.Type.kwFn }
            }
            'i' -> {
                if(text[c] == 'f') { c++; token.type = Token.Type.kwIf }
                else if(compare("mport")) token.type = Token.Type.kwImport
                else if(text[c] == 'n' && !isIdentifier(text[c + 1])) { c++; token.type = Token.Type.kwIn }
                else if(compare("nfix")) {
                    if(text[c] == 'l') { c++; token.type = Token.Type.kwInfixL }
                    else if(text[c] == 'r') { c++; token.type = Token.Type.kwInfixR }
                    else token.type = Token.Type.kwInfix
                } else if(compare("nstance")) token.type = Token.Type.kwInstance
            }
            'l' -> {
                if(text[c] == 'e' && text[c+1] == 't') { c += 2; token.type = Token.Type.kwLet }
            }
            'm' -> {
                if(compare("odule")) token.type = Token.Type.kwModule
                else if(compare("atch")) token.type = Token.Type.kwMatch
            }
            'n' -> {
                if(compare("ewtype")) token.type = Token.Type.kwNewType
            }
            'o' -> {
                if(text[c] == 'f') { c++; token.type = Token.Type.kwOf }
            }
            'p' -> {
                if(compare("refix")) token.type = Token.Type.kwPrefix
            }
            't' -> {
                if(compare("hen")) token.type = Token.Type.kwThen
                else if(compare("ype")) token.type = Token.Type.kwType
            }
            'v' -> {
                if(text[c] == 'a' && text[c+1] == 'r') { c += 2; token.type = Token.Type.kwVar }
            }
            'w' -> {
                if(compare("here")) token.type = Token.Type.kwWhere
                else if(compare("hile")) token.type = Token.Type.kwWhile
            }
        }

        // We have to read the longest possible lexeme.
        // If a reserved keyword was found, we check if a longer lexeme is possible.
        if(token.type != Token.Type.VarID) {
            if(isIdentifier(text[c])) {
                token.type = Token.Type.VarID
            } else {
                p = c
                token.kind = Token.Kind.Keyword
                return
            }
        }

        // Read the identifier name.
        var length = 1
        val start = p
        while((p < text.length - 1) && isIdentifier(text[(++p)])) length++

        qualifier = text.substring(start, start + length)
    }

    /**
     * Parses the next token into mToken.
     * Updates mLocation with the new position of p.
     * If we have reached the end of the file, this will produce EOF tokens indefinitely.
     */
    private fun parseToken() {
        val b = p

        while(true) {
            // This needs to be reset manually.
            qualifier = ""
            token.singleMinus = false

            // Check if we are inside a string literal.
            if (formatState == 3) {
                token.sourceColumn = (p - l) + tabs * (kTabWidth - 1)
                token.sourceLine = line
                formatState = 0

                // Since string literals can span multiple lines, this may update mLocation.line.
                token.type = Token.Type.String
                token.kind = Token.Kind.Literal
                token.idPayload = parseStringLiteral()
            } else {
                // Skip any whitespace and comments.
                skipWhitespace()

                token.sourceColumn = (p - l) + tabs * (kTabWidth - 1)
                token.sourceLine = line
            }

            // Check for the end of the file.
            if (!hasMore) {
                token.kind = Token.Kind.Special
                if (blockCount > 0) token.type = Token.Type.EndOfBlock
                else token.type = Token.Type.EndOfFile
            }

            // Check if we need to insert a layout token.
            else if (token.sourceColumn == indentation && !isNewItem) {
                token.type = Token.Type.Semicolon
                token.kind = Token.Kind.Special
                isNewItem = true
                token.length = (p - b)
                break
            }

            // Check if we need to end a layout block.
            else if (token.sourceColumn < indentation) {
                token.type = Token.Type.EndOfBlock
                token.kind = Token.Kind.Special
            }

            // Check for start of string formatting.
            else if (formatState == 1) {
                token.kind = Token.Kind.Special
                token.type = Token.Type.StartOfFormat
                formatState = 2
            }

            // Check for end of string formatting.
            else if (formatState == 2 && current == kFormatEnd) {
                // Issue a format end and make sure the next token is parsed as a string literal.
                // Don't skip the character - ParseStringLiteral skips one at the beginning.
                token.kind = Token.Kind.Special
                token.type = Token.Type.EndOfFormat
                formatState = 3
            }

            // Check for integral literals.
            else if (isDigit(current)) {
                parseNumericLiteral()
            }

            // Check for character literals.
            else if (current == '\'') {
                token.charPayload = parseCharLiteral()
                token.kind = Token.Kind.Literal
                token.type = Token.Type.Char
            }

            // Check for string literals.
            else if (current == '\"') {
                // Since string literals can span multiple lines, this may update mLocation.line.
                token.type = Token.Type.String
                token.kind = Token.Kind.Literal
                token.idPayload = parseStringLiteral()
            }

            // Check for special operators.
            else if (isSpecial(current)) {
                parseSpecial()
            }

            // Parse symbols.
            else if (isSymbol(current)) {
                parseSymbol()
                token.idPayload = qualifier
            }

            // Parse special unicode symbols.
            else if (parseUniSymbol()) {
                if (token.kind == Token.Kind.Identifier)
                    token.idPayload = qualifier
            }

            // Parse ConIDs
            else if (Character.isUpperCase(current)) {
                parseQualifier()
                token.idPayload = qualifier
            }

            // Parse variables and reserved ids.
            else if (Character.isLowerCase(current) || current == '_') {
                parseVariable()
                token.idPayload = qualifier
            }

            // Unknown token - issue an error and skip it.
            else {
                diagnostics.warning("Unknown token: '$current'")
                p++
                continue
            }

            isNewItem = false
            token.length = (p - b)
            break
        }
    }

    companion object {
        val kTabWidth = 4
        val kFormatStart = '`'
        val kFormatEnd = '`'
    }

    var indentation = 0 // The current indentation level.
    var blockCount = 0 // The current number of indentation blocks.
    var p = 0 // The current source pointer.
    var l = 0 // The first character of the current line.
    var qualifier = "" // The current qualified name being built up.
    var line = 0 // The current source line.
    var tabs = 0 // The number of tabs processed on the current line.
    var isNewItem = false // Indicates that a new item was started by the previous token.
    var formatState = 0 // Indicates that we are currently inside a formatting string literal.
}

class SaveLexer(val lexer: Lexer) {
    val p = lexer.p
    val l = lexer.l
    val line = lexer.line
    val indent = lexer.indentation
    val newItem = lexer.isNewItem
    val tabs = lexer.tabs
    val blockCount = lexer.blockCount
    val formatting = lexer.formatState

    fun restore() {
        lexer.p = p
        lexer.l = l
        lexer.line = line
        lexer.indentation = indent
        lexer.isNewItem = newItem
        lexer.tabs = tabs
        lexer.blockCount = blockCount
        lexer.formatState = formatting
    }
}
