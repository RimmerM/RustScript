package se.rimmer.rc.compiler.parser

import java.math.BigDecimal
import java.math.BigInteger
import java.util.*

interface ParserListener {
    fun onWarning(location: Node, warning: String) {}
    fun onError(location: Node, error: String) {}
    fun onToken(token: Token) {}
}

class LexerAdapter(val listener: ParserListener): LexerListener {
    override fun onToken(token: Token) = listener.onToken(token)
    override fun onWarning(location: Node, warning: String) = listener.onWarning(location, warning)
    override fun onError(location: Node, error: String) = listener.onError(location, error)
}

class ModuleParser(module: Qualified, text: String, listener: ParserListener): Parser(module, text, LexerAdapter(listener)) {
    fun parseModule(target: Module) {
        withLevel {
            while(token.type == Token.Type.Semicolon) {
                while(token.type == Token.Type.Semicolon) {
                    eat()
                }

                when(token.type) {
                    Token.Type.kwImport -> target.imports.add(node {parseImport()})
                    Token.Type.kwInfixL, Token.Type.kwInfixR -> {
                        val op = node {parseFixity()}
                        target.ops[op.op.name.name] = op
                    }
                    else -> target.decls.add(parseTopDecl())
                }
            }
        }
    }

    fun parseImport(): Import {
        expect(Token.Type.kwImport, true)
        val qualified = if(token.type == Token.Type.VarID && token.idPayload == "qualified") { eat(); true } else false
        val name = parseQualified()

        val include = maybeParens { sepBy1(Token.Type.Comma) { parseID() } }

        val exclude = if(token.type == Token.Type.VarID && token.idPayload == "hiding") {
            eat()
            parens { sepBy1(Token.Type.Comma) { parseID() } }
        } else null

        val asName = if(token.type == Token.Type.kwAs) {
            eat()
            parseConID()
        } else null

        return Import(name, qualified || asName != null, asName ?: name.name, include, exclude)
    }

    fun parseTopDecl(): TopDecl  {
        val export = if(token.type == Token.Type.kwPub) {
            eat()
            true
        } else false

        return TopDecl(parseDecl(), export)
    }

    fun parseDecl(): Decl {
        return when(token.type) {
            Token.Type.kwData -> parseDataDecl()
            Token.Type.kwType -> parseTypeDecl()
            Token.Type.kwFn -> parseFunDecl()
            Token.Type.kwForeign -> parseForeignDecl()
            Token.Type.kwClass -> parseClassDecl()
            Token.Type.kwInstance -> parseInstanceDecl()
            else -> throw ParseError("Expected top-level declaration")
        }
    }

    fun parseFunDecl() = node {
        expect(Token.Type.kwFn, true)
        val name = parseVarID()
        val args = parens { sepBy(Token.Type.Comma) { parseArg(true) } }
        val ret = if(token.type == Token.Type.opArrowR) {
            eat()
            parseType()
        } else {
            null
        }

        val body = if(token.type == Token.Type.opEquals) {
            node {
                eat()
                val body = parseExpr()
                if(token.type == Token.Type.kwWhere) {
                    eat()
                    val decls = parseVarDecl()
                    MultiExpr(listOf(decls, body))
                } else body
            }
        } else {
            expect(Token.Type.opColon, true)
            parseBlock(isFun = true)
        }

        FunDecl(name, args, ret, body)
    }

    fun parseDataDecl() = node {
        expect(Token.Type.kwData, true)
        val name = parseSimpleType()
        expect(Token.Type.opEquals, true)
        val cons = sepBy1(Token.Type.opBar) {
            node {
                val conName = parseConID()
                if(token.type == Token.Type.ParenL) {
                    val content = parens {parseType()}
                    Con(conName, content)
                } else if(token.type == Token.Type.BraceL) {
                    val content = parseTupleType()
                    Con(conName, content)
                } else {
                    Con(conName, null)
                }
            }
        }
        DataDecl(name, cons)
    }

    fun parseTypeDecl() = node {
        expect(Token.Type.kwType, true)
        val name = parseSimpleType()
        expect(Token.Type.opEquals, true)
        val type = parseType()
        TypeDecl(name, type)
    }

    fun parseForeignDecl() = node {
        expect(Token.Type.kwForeign, true)
        val isFun = if(token.type == Token.Type.kwFn) {eat(); true} else false

        expect(Token.Type.VarID)
        val id = token.idPayload
        eat()
        if(!isFun) expect(Token.Type.opColon, true)
        val type = parseType()

        val from = if(token.type == Token.Type.VarID && token.idPayload == "from") {
            eat()
            expect(Token.Type.String)
            val from = token.idPayload
            eat()
            from
        } else null

        val internalName = if(token.type == Token.Type.kwAs) {
            eat()
            expect(Token.Type.VarID)
            val asName = token.idPayload
            eat()
            asName
        } else id

        ForeignDecl(id, internalName, from, type)
    }

    fun parseClassDecl() = node {
        expect(Token.Type.kwClass, true)
        val type = parseSimpleType()
        expect(Token.Type.opColon, true)
        val decls = withLevel {
            sepBy(Token.Type.Semicolon) {parseDecl()}
        }
        ClassDecl(type, decls)
    }

    fun parseInstanceDecl() = node {
        expect(Token.Type.kwInstance, true)
        val type = parseSimpleType()
        expect(Token.Type.opColon, true)
        val decls = withLevel {
            sepBy(Token.Type.Semicolon) {parseDecl()}
        }
        InstanceDecl(type, decls)
    }

    fun parseFixity() = node {
        val isRight = if(token.type == Token.Type.kwInfixL) {
            eat()
            false
        } else if(token.type == Token.Type.kwInfixR) {
            eat()
            true
        } else {
            throw IllegalArgumentException()
        }

        expect(Token.Type.Integer)
        val precedence = token.intPayload
        eat()

        val op = parseQop()
        Fixity(op, if(isRight) FixityKind.Right else FixityKind.Left, precedence.toInt())
    }

    fun parseBlock(isFun: Boolean): Expr {
        // To make the code more readable we avoid using '=' inside expressions, and use '->' instead.
        return if(token.type == if(isFun) Token.Type.opEquals else Token.Type.opArrowR) {
            eat()
            parseExpr()
        } else {
            expect(Token.Type.opColon, true)
            withLevel {
                parseExprSeq()
            }
        }
    }

    fun parseExprSeq() = node {
        val rows = sepBy1(Token.Type.Semicolon) { parseExpr() }
        if(rows.size == 1) rows[0] else MultiExpr(rows)
    }

    fun parseExpr() = parseTypedExpr()

    fun parseTypedExpr() = node {
        val expr = parseInfixExpr()
        if(token.type == Token.Type.kwAs) {
            eat()
            val type = parseType()
            CoerceExpr(expr, type)
        } else {
            expr
        }
    }

    fun parseInfixExpr(): Expr = node {
        val lhs = parsePrefixExpr()
        if(token.type == Token.Type.opEquals) {
            eat()
            AssignExpr(lhs, parseInfixExpr())
        } else if(token.type == Token.Type.VarSym || token.type == Token.Type.Grave) {
            val op = parseQop()
            InfixExpr(op, lhs, parseInfixExpr())
        } else {
            lhs
        }
    }

    fun parsePrefixExpr(): Expr = node {
        if(token.type == Token.Type.VarSym) {
            val op = node {
                val op = token.idPayload
                eat()
                VarExpr(Qualified(op, emptyList(), true))
            }
            val expr = parsePrefixExpr()
            PrefixExpr(op, expr)
        } else {
            parseLeftExpr()
        }
    }

    fun parseLeftExpr() = node {
        if(token.type == Token.Type.kwLet) {
            eat()
            parseVarDecl()
        } else if(token.type == Token.Type.kwMatch) {
            eat()
            val expr = parseInfixExpr()
            expect(Token.Type.opColon, true)
            val alts = withLevel {
                sepBy1(Token.Type.Semicolon) { parseAlt() }
            }
            CaseExpr(expr, alts)
        } else if(token.type == Token.Type.kwIf) {
            eat()
            if(token.type == Token.Type.opBar) {
                val cases = withLevel {
                    sepBy(Token.Type.Semicolon) {
                        expect(Token.Type.opBar, true)
                        val cond = node {
                            if(token.type == Token.Type.kw_ || token.type == Token.Type.kwElse) {
                                eat()
                                LitExpr(BoolLiteral(true))
                            } else {
                                parseInfixExpr()
                            }
                        }
                        IfCase(cond, parseBlock(isFun = false))
                    }
                }
                MultiIfExpr(cases)
            } else {
                val cond = parseInfixExpr()

                // Allow statement ends within an if-expression to allow then/else with the same indentation as if.
                if(token.type == Token.Type.Semicolon) eat()
                expect(Token.Type.kwThen, true)
                val then = if(token.type == Token.Type.opColon) {
                    parseBlock(isFun = false)
                } else {
                    parseExpr()
                }

                if(token.type == Token.Type.kwElse) {
                    eat()
                    val otherwise = if(token.type == Token.Type.opColon) {
                        parseBlock(isFun = false)
                    } else {
                        parseExpr()
                    }
                    IfExpr(cond, then, otherwise)
                } else {
                    IfExpr(cond, then, null)
                }
            }
        } else if(token.type == Token.Type.kwWhile) {
            eat()
            val cond = parseInfixExpr()
            expect(Token.Type.opColon, true)
            val body = parseExprSeq()
            WhileExpr(cond, body)
        } else if(token.type == Token.Type.kwReturn) {
            eat()
            val body = parseExpr()
            ReturnExpr(body)
        } else {
            parseAppExpr()
        }
    }

    fun parseAppExpr() = node {
        val base = parseBaseExpr()
        parseChain(base)
    }

    fun parseChain(base: Expr): Expr = node {
        if(token.type == Token.Type.ParenL) {
            val args = parens { sepBy(Token.Type.Comma) {parseTupArg()} }
            parseChain(AppExpr(base, args))
        } else if(token.type == Token.Type.opDot) {
            eat()
            val app = parseSelExpr()
            parseChain(FieldExpr(base, app))
        } else {
            base
        }
    }

    fun parseBaseExpr(): Expr {
        if(token.type == Token.Type.BraceL) {
            return parseTupExpr()
        } else if(token.type == Token.Type.BracketL) {
            return parseArrayExpr()
        } else if(token.type == Token.Type.ConID) {
            return node {
                val varExpr = node { VarExpr(parseQualified()) }
                if(varExpr.name.isVar) {
                    varExpr
                } else {
                    val type = ConType(varExpr.name)
                    type.locationFrom(varExpr)
                    if(token.type == Token.Type.ParenL) {
                        val args = parens { sepBy1(Token.Type.Comma) { parseExpr() } }
                        ConstructExpr(type, args)
                    } else if(token.type == Token.Type.BraceL) {
                        ConstructExpr(type, listOf(parseTupExpr()))
                    } else {
                        ConstructExpr(type, emptyList())
                    }
                }
            }
        } else if(token.type == Token.Type.ParenL) {
            return node {
                eat()
                if(token.type == Token.Type.ParenR) {
                    eat()
                    FunExpr(emptyList(), parseExpr())
                } else {
                    val e = parseExpr()

                    // Find out if this is a parenthesized or function expression.
                    // a comma or `) =>` indicates a function, anything else is either an error or an expression.
                    var result: Expr? = null
                    if(token.type == Token.Type.ParenR) {
                        eat()
                        if(token.type != Token.Type.opArrowD) {
                            result = NestedExpr(e)
                        }
                    }

                    if(result == null) {
                        val firstArg = if (e is VarExpr && e.name.qualifier.isEmpty()) {
                            Arg(e.name.name, null, null)
                        } else if (e is CoerceExpr && e.target is VarExpr && e.target.name.qualifier.isEmpty()) {
                            Arg(e.target.name.name, e.type, null)
                        } else {
                            throw ParseError("Expected ')' or a function argument")
                        }

                        val args: List<Arg>
                        if (token.type == Token.Type.Comma) {
                            eat()
                            args = listOf(firstArg) + sepBy(Token.Type.Comma) { parseArg(false) }
                            expect(Token.Type.ParenR, true)
                            expect(Token.Type.opArrowD, true)
                        } else if (token.type == Token.Type.opArrowD) {
                            eat()
                            args = listOf(firstArg)
                        } else {
                            throw ParseError("Expected ')' or a function expression")
                        }

                        FunExpr(args, parseExpr())
                    } else {
                        result
                    }
                }
            }
        } else {
            return parseSelExpr()
        }
    }

    fun parseSelExpr() = node {
        if(token.kind == Token.Kind.Literal) {
            if(token.type == Token.Type.String) {
                parseStringExpr()
            } else {
                LitExpr(parseLiteral())
            }
        } else if(token.type == Token.Type.VarID) {
            val id = token.idPayload
            eat()
            VarExpr(Qualified(id, emptyList(), true))
        } else if(token.type == Token.Type.ParenL) {
            NestedExpr(parens {parseExpr()})
        } else {
            throw ParseError("Expected an expression")
        }
    }

    fun parseVarDecl() = node {
        val list = withLevel {
            sepBy1(Token.Type.Semicolon) {parseDeclExpr()}
        }
        if(list.size == 1) list[0] else MultiExpr(list)
    }

    fun parseDeclExpr() = node {
        val mutable = if(token.type == Token.Type.kwMut) {
            eat()
            true
        } else false

        expect(Token.Type.VarID, false)
        val id = token.idPayload
        eat()

        if(token.type == Token.Type.opEquals) {
            eat()
            DeclExpr(id, parseExpr(), mutable)
        } else {
            DeclExpr(id, null, mutable)
        }
    }

    fun parseStringExpr() = node {
        expect(Token.Type.String)
        val string = token.idPayload
        eat()

        // Check if the string contains formatting.
        if(token.type == Token.Type.StartOfFormat) {
            val chunks = ArrayList<FormatChunk>()
            chunks.add(FormatChunk(string, null))

            // Parse one or more formatting expressions.
            // The first one consists of just the first string chunk.
            while(token.type == Token.Type.StartOfFormat) {
                eat()
                val expr = parseExpr()
                expect(Token.Type.EndOfFormat, true)
                expect(Token.Type.String)
                chunks.add(FormatChunk(token.idPayload, expr))
                eat()
            }
            FormatExpr(chunks)
        } else {
            LitExpr(StringLiteral(string))
        }
    }

    fun parseArrayExpr() = node {
        expect(Token.Type.BracketL, true)
        if(token.type == Token.Type.BracketR) {
            // Empty array
            eat()
            ArrayExpr(emptyList())
        } else if(token.type == Token.Type.opArrowD) {
            // Empty map
            eat()
            expect(Token.Type.BracketR, true)
            MapExpr(emptyList())
        } else {
            val first = parseExpr()
            if(token.type == Token.Type.opArrowD) {
                eat()
                val firstValue = parseExpr()
                if(token.type == Token.Type.Comma) {
                    eat()
                    val content = sepBy(Token.Type.Comma) {
                        val key = parseExpr()
                        expect(Token.Type.opArrowD, true)
                        val value = parseExpr()
                        key to value
                    }
                    expect(Token.Type.BracketR, true)
                    MapExpr(listOf(first to firstValue) + content)
                } else {
                    expect(Token.Type.BracketR, true)
                    MapExpr(listOf(first to firstValue))
                }
            } else if(token.type == Token.Type.Comma) {
                eat()
                val content = sepBy(Token.Type.Comma) {parseExpr()}
                expect(Token.Type.BracketR, true)
                ArrayExpr(listOf(first) + content)
            } else {
                expect(Token.Type.BracketR, true)
                ArrayExpr(listOf(first))
            }
        }
    }

    fun parseTupExpr() = node {
        braces {
            val first = parseExpr()
            if(token.type == Token.Type.opBar) {
                eat()
                val args = sepBy1(Token.Type.Comma) { parseTupArg() }
                TupUpdateExpr(first, args)
            } else {
                val args = if(first is AssignExpr) {
                    val target = first.target
                    if(target is VarExpr && target.name.isVar && target.name.qualifier.isEmpty()) {
                        listOf(TupArg(target.name.name, first.value)) + sepBy1(Token.Type.Comma) { parseTupArg() }
                    } else {
                        throw ParseError("Tuple fields must be names")
                    }
                } else {
                    listOf(TupArg(null, first)) + sepBy1(Token.Type.Comma) { parseTupArg() }
                }
                TupExpr(args)
            }
        }
    }

    fun parseTupArg(): TupArg {
        if(token.type == Token.Type.VarID) {
            val varExpr = node { VarExpr(Qualified(token.idPayload.apply {eat()}, emptyList(), true)) }
            if(token.type == Token.Type.opEquals) {
                eat()
                return TupArg(varExpr.name.name, parseExpr())
            } else {
                return TupArg(null, varExpr)
            }
        } else {
            return TupArg(null, parseExpr())
        }
    }

    fun parseAlt(): Alt {
        val pat = parsePat()
        val alias = if(token.type == Token.Type.opAt) {
            eat()
            expect(Token.Type.VarID)
            val id = token.idPayload
            eat()
            id
        } else null

        return Alt(pat, alias, parseBlock(isFun = false))
    }

    fun parsePat(): Pat = node {
        if(token.singleMinus) {
            eat()
            if(token.type == Token.Type.Integer || token.type == Token.Type.Float) {
                LitPat(parseLiteral().negate())
            } else {
                throw ParseError("Expected integer or float literal")
            }
        } else if(token.type == Token.Type.ConID) {
            val name = parseQualified()
            val list = if(token.type == Token.Type.ParenL) {
                parens { sepBy1(Token.Type.Comma) {parseLPat()} }
            } else if(token.type == Token.Type.BraceL) {
                listOf(parseLPat())
            } else {
                emptyList()
            }

            ConPat(name, list)
        } else {
            parseLPat()
        }
    }

    fun parseLPat() = node {
        if(token.kind == Token.Kind.Literal) {
            LitPat(parseLiteral())
        } else if(token.type == Token.Type.kw_ || token.type == Token.Type.kwElse) {
            eat()
            AnyPat(Unit)
        } else if(token.type == Token.Type.VarID) {
            val id = token.idPayload
            eat()
            VarPat(id)
        } else if(token.type == Token.Type.ParenL) {
            parens { parsePat() }
        } else if(token.type == Token.Type.BraceL) {
            val list = braces {
                sepBy1(Token.Type.Comma) {
                    if(token.type == Token.Type.VarID) {
                        val varPat = node { VarPat(token.idPayload.apply {eat()}) }
                        if(token.type == Token.Type.opEquals) {
                            eat()
                            PatternField(varPat.name, parsePat())
                        } else {
                            PatternField(null, varPat)
                        }
                    } else {
                        PatternField(null, parsePat())
                    }
                }
            }
            TupPat(list)
        } else {
            throw ParseError("Expected pattern")
        }
    }

    fun parseType(): Type = node {
        val args = maybeParens { sepBy(Token.Type.Comma) { parseTypeArg() } }
        if(args == null) {
            parseAType()
        } else if(token.type == Token.Type.opArrowR) {
            eat()
            FunType(args, parseType())
        } else {
            val arg = if(args.size == 1) args[0] else null
            if(arg != null && arg.name == null) {
                arg.type
            } else {
                throw ParseError("expected a type")
            }
        }
    }

    fun parseAType() = node {
        if(token.type == Token.Type.ConID) {
            val base = node {ConType(parseQualified())}

            // For cases where it is easily visible what's going on, we allow omitting parentheses.
            // This conveniently also prevents us from having to look too far ahead.
            val app = if(token.type == Token.Type.ParenL) {
                parens { sepBy1(Token.Type.Comma) {parseType()} }
            } else if(token.type == Token.Type.BraceL) {
                listOf(parseTupleType())
            } else if(token.type == Token.Type.ConID) {
                listOf(node { ConType(parseQualified()) })
            } else null

            if(app == null) {
                return base
            } else {
                return AppType(base, app)
            }
        } else if(token.type == Token.Type.VarID) {
            val name = token.idPayload
            eat()
            GenType(name)
        } else if(token.type == Token.Type.ParenL) {
            parens { parseType() }
        } else if(token.type == Token.Type.BraceL) {
            parseTupleType()
        } else if(token.type == Token.Type.BracketL) {
            eat()
            val from = parseType()
            if(token.type == Token.Type.opArrowD) {
                eat()
                val to = parseType()
                MapType(from, to)
            } else {
                ArrayType(from)
            }
        } else {
            throw ParseError("Expected a type")
        }
    }

    fun parseTupleType() = node {
        val fields = braces {
            sepBy1(Token.Type.Comma) {
                val mutable = if(token.type == Token.Type.kwMut) {
                    eat()
                    true
                } else false

                if(token.type == Token.Type.VarID) {
                    val gen = node {
                        val name = token.idPayload
                        eat()
                        GenType(name)
                    }

                    if(token.type == Token.Type.opColon) {
                        eat()
                        TupField(parseType(), gen.name, mutable)
                    } else {
                        TupField(gen, null, mutable)
                    }
                } else {
                    TupField(parseType(), null, mutable)
                }
            }
        }
        TupType(fields)
    }

    fun parseQualified(): Qualified {
        val name = parseConID()
        val qualifier = ArrayList<String>()
        var isVar = false

        qualifier.add(name)
        while(token.type == Token.Type.opDot) {
            eat()

            if(token.type == Token.Type.VarID || token.type == Token.Type.ConID) {
                qualifier.add(token.idPayload)
                isVar = token.type == Token.Type.VarID
                eat()
                if(isVar) break
            } else {
                throw ParseError("expected variable or constructor name")
            }
        }

        return Qualified(qualifier.last(), qualifier.dropLast(1), isVar)
    }

    fun parseArg(requireType: Boolean): Arg {
        val name = parseVarID()
        val type = if(requireType || token.type == Token.Type.opColon) {
            expect(Token.Type.opColon, true)
            parseType()
        } else {
            null
        }

        val default = if(token.type == Token.Type.opEquals) {
            parseExpr()
        } else {
            null
        }

        return Arg(name, type, default)
    }

    fun parseTypeArg() = parseArgDecl()

    fun parseArgDecl(): ArgDecl {
        if(token.type == Token.Type.VarID) {
            val gen = node { GenType(parseVarID()) }
            if(token.type == Token.Type.opColon) {
                expect(Token.Type.opColon, true)
                return ArgDecl(gen.name, parseType())
            } else {
                return ArgDecl(null, gen)
            }
        } else {
            return ArgDecl(null, parseType())
        }
    }

    fun parseSimpleType(): SimpleType {
        val name = parseConID()
        val kind = maybeParens { sepBy1(Token.Type.Comma) { parseID() } } ?: emptyList()
        return SimpleType(name, kind)
    }

    fun parseConID(): String {
        expect(Token.Type.ConID)
        val id = token.idPayload
        eat()
        return id
    }

    fun parseVarID(): String {
        // A VarID can be a string literal as well, in order to be able to use keyword variable names.
        if(token.type != Token.Type.VarID) {
            throw ParseError("expected variable name")
        }

        val id = token.idPayload
        eat()
        return id
    }

    fun parseID(): String {
        // An ID can be a string literal as well, in order to be able to use keyword variable names.
        if(token.type != Token.Type.VarID && token.type != Token.Type.ConID) {
            throw ParseError("expected variable name")
        }

        val id = token.idPayload
        eat()
        return id
    }

    fun parseQop() = node {
        if(token.type == Token.Type.VarSym) {
            val id = token.idPayload
            eat()
            VarExpr(Qualified(id, emptyList(), true))
        } else if(token.type == Token.Type.Grave) {
            eat()
            expect(Token.Type.VarID)
            val id = token.idPayload
            eat()
            expect(Token.Type.Grave, true)
            VarExpr(Qualified(id, emptyList(), true))
        } else {
            throw ParseError("Expected operator")
        }
    }

    fun parseLiteral(): Literal {
        val literal = when(token.type) {
            Token.Type.Integer -> IntLiteral(BigInteger.valueOf(token.intPayload))
            Token.Type.Float -> RationalLiteral(BigDecimal.valueOf(token.floatPayload))
            Token.Type.Char -> CharLiteral(token.charPayload)
            Token.Type.String -> StringLiteral(token.idPayload)
            else -> throw ParseError("Invalid literal type ${token.type}")
        }
        eat()
        return literal
    }
}