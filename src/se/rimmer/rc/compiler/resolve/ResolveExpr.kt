package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.*
import java.util.*
import se.rimmer.rc.compiler.parser.AppExpr as ASTAppExpr
import se.rimmer.rc.compiler.parser.ArrayExpr as ASTArrayExpr
import se.rimmer.rc.compiler.parser.AssignExpr as ASTAssignExpr
import se.rimmer.rc.compiler.parser.CaseExpr as ASTCaseExpr
import se.rimmer.rc.compiler.parser.CoerceExpr as ASTCoerceExpr
import se.rimmer.rc.compiler.parser.ConstructExpr as ASTConstructExpr
import se.rimmer.rc.compiler.parser.DeclExpr as ASTDeclExpr
import se.rimmer.rc.compiler.parser.Expr as ASTExpr
import se.rimmer.rc.compiler.parser.FieldExpr as ASTFieldExpr
import se.rimmer.rc.compiler.parser.FormatExpr as ASTFormatExpr
import se.rimmer.rc.compiler.parser.FunExpr as ASTFunExpr
import se.rimmer.rc.compiler.parser.IfExpr as ASTIfExpr
import se.rimmer.rc.compiler.parser.InfixExpr as ASTInfixExpr
import se.rimmer.rc.compiler.parser.LitExpr as ASTLitExpr
import se.rimmer.rc.compiler.parser.MapExpr as ASTMapExpr
import se.rimmer.rc.compiler.parser.MultiExpr as ASTMultiExpr
import se.rimmer.rc.compiler.parser.MultiIfExpr as ASTMultiIfExpr
import se.rimmer.rc.compiler.parser.NestedExpr as ASTNestedExpr
import se.rimmer.rc.compiler.parser.PrefixExpr as ASTPrefixExpr
import se.rimmer.rc.compiler.parser.ReturnExpr as ASTReturnExpr
import se.rimmer.rc.compiler.parser.TupExpr as ASTTupExpr
import se.rimmer.rc.compiler.parser.TupUpdateExpr as ASTTupUpdateExpr
import se.rimmer.rc.compiler.parser.VarExpr as ASTVarExpr
import se.rimmer.rc.compiler.parser.WhileExpr as ASTWhileExpr

fun FunctionBuilder.resolveExpr(ast: ASTExpr, name: String?, resultUsed: Boolean, typeHint: Type?): Value {
    return when(ast) {
        is ASTMultiExpr -> resolveMulti(ast, name, resultUsed, typeHint)
        is ASTLitExpr -> resolveLit(name, ast)
        is ASTAppExpr -> resolveCall(name, ast, typeHint)
        is ASTNestedExpr -> resolveExpr(ast.expr, name, resultUsed, typeHint)
        is ASTPrefixExpr -> resolvePrefix(name, ast, typeHint)
        is ASTInfixExpr -> resolveInfix(name, ast, typeHint)
        is ASTReturnExpr -> resolveReturn(ast)
        is ASTAssignExpr -> resolveAssign(ast)
        is ASTVarExpr -> resolveVar(ast, false)
        is ASTDeclExpr -> resolveDecl(ast)
        is ASTIfExpr -> resolveIf(name, ast, resultUsed, typeHint)
        is ASTMultiIfExpr -> resolveMultiIf(name, ast, resultUsed, typeHint)
        is ASTWhileExpr -> resolveWhile(name, ast)
        is ASTFieldExpr -> resolveField(name, ast)
        is ASTCoerceExpr -> resolveCoerce(name, ast)
        is ASTConstructExpr -> resolveConstruct(name, ast)
        is ASTTupExpr -> resolveTup(name, ast)
        is ASTTupUpdateExpr -> resolveTupUpdate(name, ast)
        is ASTArrayExpr -> resolveArray(name, ast, typeHint)
        is ASTMapExpr -> resolveMap(name, ast)
        is ASTFunExpr -> resolveFun(name, ast)
        is ASTFormatExpr -> resolveFormat(name, ast)
        is ASTCaseExpr -> resolveCase(name, ast, resultUsed)
        else -> throw NotImplementedError()
    }
}

private fun FunctionBuilder.resolveMulti(ast: ASTMultiExpr, name: String?, resultUsed: Boolean, typeHint: Type?): Value {
    if(resultUsed) {
        // Expressions that are part of a statement list are never used, unless they are the last in the list.
        var value: Value? = null
        ast.list.forEachIndexed { i, expr ->
            val last = i == ast.list.size - 1
            val v = resolveExpr(expr, name, if(last) resultUsed else false, if(last) typeHint else null)
            if(last) value = v
        }

        return value!!
    } else {
        ast.list.forEach { resolveExpr(it, null, false, null) }
        return Value(block, null, unitType)
    }
}

private fun FunctionBuilder.makeHomogeneous(name: String?, exprs: List<ASTExpr>, typeHint: Type?): List<Value> {
    // TODO: Handle generics and return type polymorphism
    return exprs.map { resolveExpr(it, name, true, typeHint) }
}

private fun FunctionBuilder.resolveReturn(ast: ASTReturnExpr): Value {
    return block.ret(resolveExpr(ast.expr, null, true, null))
}

private fun FunctionBuilder.resolveLit(name: String?, ast: ASTLitExpr): Value {
    val type = when(ast.literal) {
        is IntLiteral -> PrimTypes.intType
        is RationalLiteral -> PrimTypes.float(FloatKind.F64)
        is BoolLiteral -> PrimTypes.boolType
        is StringLiteral -> PrimTypes.stringType
        is CharLiteral -> PrimTypes.intType
        else -> throw NotImplementedError()
    }
    return Lit(block, name, type, ast.literal)
}

private fun FunctionBuilder.resolveDecl(ast: ASTDeclExpr): Value {
    if(ast.mutable) {
        val value = resolveExpr(ast.content ?: throw NotImplementedError(), null, true, null)
        val ref = block.alloca(ast.name, value.type)
        block.store(null, ref, value)
    } else {
        val content = ast.content ?: throw ResolveError("immutable variables must be initialized")
        resolveExpr(content, ast.name, true, null)
    }
    return Value(block, null, unitType)
}

private fun FunctionBuilder.resolveVar(ast: ASTVarExpr, asRV: Boolean): Value {
    val value = block.findValue(ast.name) ?: throw ResolveError("no variable ${ast.name} found")
    if(!asRV && value.type is RefType) {
        return block.load(null, value)
    } else {
        return value
    }
}

private fun FunctionBuilder.resolveAssign(ast: ASTAssignExpr) = when(ast.target) {
    is ASTVarExpr -> {
        val v = resolveVar(ast.target, true)
        if(v.type !is RefType) {
            throw ResolveError("type is not assignable")
        }
        block.store(null, v, resolveExpr(ast.value, null, true, v.type.to))
    }
    is ASTFieldExpr -> {
        throw NotImplementedError()
    }
    else -> throw ResolveError("assign target is not assignable")
}

private fun FunctionBuilder.resolveIf(name: String?, ast: ASTIfExpr, resultUsed: Boolean, typeHint: Type?): Value {
    val hint = if(resultUsed) typeHint else null
    val cond = resolveExpr(ast.cond, null, resultUsed, PrimTypes.boolType)
    if(!cond.type.isBoolean()) throw ResolveError("if condition must be a boolean")

    val then = function.block()
    val `else` = function.block()

    block.`if`(cond, then, `else`)
    block = then
    val thenValue = resolveExpr(ast.then, null, resultUsed, hint)
    val thenBlock = block

    val elseValue = ast.otherwise?.let {
        block = `else`
        resolveExpr(ast.otherwise, null, resultUsed, hint)
    }

    val elseBlock = ast.otherwise?.let { block }

    if(resultUsed) {
        if(elseValue == null || elseBlock == null || elseBlock.complete || thenBlock.complete) {
            throw ResolveError("if expression doesn't produce a result in every case")
        }

        if(!typesCompatible(thenValue.type, elseValue.type)) {
            throw ResolveError("if and else branches produce differing types")
        }

        val after = function.block()
        block = after
        thenBlock.br(after)
        elseBlock.br(after)
        return after.phi(name, thenValue.type, listOf(thenValue to thenValue.block, elseValue to elseValue.block))
    } else {
        if(elseBlock == null) {
            thenBlock.br(`else`)
            block = `else`
        } else if(!(thenBlock.complete && elseBlock.complete)) {
            val after = function.block()
            block = after
            thenBlock.br(after)
            elseBlock.br(after)
        }
        return Value(thenBlock, null, unitType)
    }
}

private fun FunctionBuilder.resolveMultiIf(name: String?, ast: ASTMultiIfExpr, resultUsed: Boolean, typeHint: Type?): Value {
    val hint = if(resultUsed) typeHint else null
    val results = ArrayList<Pair<Value, Block>>()
    var hasElse = false

    for(it in ast.cases) {
        val cond = resolveExpr(it.cond, null, true, PrimTypes.boolType)
        if(!cond.type.isBoolean()) throw ResolveError("if condition must be a boolean")

        if(alwaysTrue(cond)) {
            val result = resolveExpr(it.then, null, resultUsed, hint)
            results.add(result to block)
            block = function.block()
            hasElse = true
            break
        } else {
            val then = function.block()
            val next = function.block()
            block.`if`(cond, then, next)
            block = then
            val result = resolveExpr(it.then, null, resultUsed, hint)
            results.add(result to block)
            block = next
        }
    }

    val next = block
    if(resultUsed) {
        if(!hasElse) {
            throw ResolveError("if expression doesn't produce a result in every case")
        }

        var prev: Value? = null
        results.forEach { v ->
            v.second.br(next)
            prev?.let {
                if(!typesCompatible(it.type, v.first.type)) {
                    throw ResolveError("if and else branches produce differing types")
                }
            }
            prev = v.first
        }

        prev?.let {
            return next.phi(name, it.type, results)
        }

        throw ResolveError("if expression doesn't produce a result in every case")
    } else {
        if(results.all { it.second.returns }) {
            function.blocks.remove(block)
            block = function.blocks.last()
        } else {
            results.forEach {
                it.second.br(next)
            }
        }
        return Value(block, null, unitType)
    }
}

private fun FunctionBuilder.resolveWhile(name: String?, ast: ASTWhileExpr): Value {
    val condBlock = function.block()
    block.br(condBlock)
    block = condBlock
    val cond = resolveExpr(ast.cond, null, true, PrimTypes.boolType)

    val thenBlock = function.block()
    val quit = function.block()
    block.`if`(cond, thenBlock, quit)
    block = thenBlock
    resolveExpr(ast.content, null, false, null)
    block.br(condBlock)

    block = quit
    return Value(quit, name, unitType)
}

private fun FunctionBuilder.resolveCall(name: String?, ast: ASTAppExpr, typeHint: Type?): Value {
    // If the operand is a field expression we need special handling, since there are several options:
    // - the field operand is an actual field of its target and has a function type, which we call.
    // - the field operand is not a field, and we produce a function call with the target as first parameter.
    if(ast.callee is ASTFieldExpr) {
        val target = resolveExpr(ast.callee.target, null, true, null)
        val static = testStaticField(null, target, ast.callee.field)
        if(static != null) return resolveDynCall(name, static, ast.args)
    }

    val args = ast.args.map { resolveExpr(it.content, null, true, null) }
    if(ast.callee is ASTVarExpr) {

    }

    throw NotImplementedError()
}

private fun FunctionBuilder.resolveDynCall(name: String?, value: Value, args: List<TupArg>): Value {
    throw NotImplementedError()
}

private fun FunctionBuilder.resolvePrefix(name: String?, ast: ASTPrefixExpr, typeHint: Type?): Value {
    val args = listOf(TupArg(null, ast.arg))
    val call = ASTAppExpr(ast.op, args)
    call.locationFrom(ast)
    return resolveCall(name, call, typeHint)
}

private fun FunctionBuilder.resolveInfix(name: String?, unorderedAst: ASTInfixExpr, typeHint: Type?): Value {
    val ast = if(unorderedAst.ordered) unorderedAst else reorder(block.function.module, unorderedAst, 0)
    val args = listOf(TupArg(null, ast.lhs), TupArg(null, ast.rhs))
    val call = ASTAppExpr(ast.op, args)
    return resolveCall(name, call, typeHint)
}

private fun FunctionBuilder.resolveField(name: String?, ast: ASTFieldExpr): Value {
    val target = resolveExpr(ast.target, null, true, null)
    val static = testStaticField(name, target, ast.field)
    if(static != null) return static

    // TODO: Array & Map types.
    throw ResolveError("type ${target.type} does not contain the requested field")
}

private fun FunctionBuilder.testStaticField(name: String?, target: Value, ast: ASTExpr): Value? {
    val targetType = canonicalType(target.type)
    val staticField = when(ast) {
        is ASTVarExpr -> {
            val n = ast.name
            if(n.qualifier.isEmpty() && n.isVar) {
                findStaticField(targetType, n.name, null)
            } else {
                null
            }
        }
        is ASTLitExpr -> {
            if(ast.literal is StringLiteral) {
                findStaticField(targetType, ast.literal.v, null)
            } else if(ast.literal is IntLiteral) {
                findStaticField(targetType, null, ast.literal.v.toInt())
            } else {
                null
            }
        }
        else -> null
    } ?: return null

    when(target.type) {
        is RefType -> return block.loadField(name, target, staticField.index, staticField.type)
        else -> return block.getField(name, target, staticField.index, staticField.type)
    }
}

private fun FunctionBuilder.resolveFun(name: String?, ast: ASTFunExpr): Value {
    throw NotImplementedError()
}

private fun FunctionBuilder.resolveArray(name: String?, ast: ASTArrayExpr, typeHint: Type?): Value {
    val values = makeHomogeneous(null, ast.values, null)
    val type = if(values.isEmpty()) {
        if(typeHint is ArrayType) {
            ArrayType(typeHint.content)
        } else {
            throw NotImplementedError("cannot resolve undetermined expressions yet")
        }
    } else {
        values[0].type
    }

    return block.array(name, ArrayType(type), values)
}

private fun FunctionBuilder.resolveMap(name: String?, ast: ASTMapExpr): Value {
    throw NotImplementedError()
}

private fun FunctionBuilder.resolveFormat(name: String?, ast: ASTFormatExpr): Value {
    throw NotImplementedError()
}

private fun FunctionBuilder.resolveCoerce(name: String?, ast: ASTCoerceExpr): Value {
    val to = resolveType(function.module, ast.type, null)
    val expr = resolveExpr(ast.target, null, true, to)
    if(!typesCompatible(expr.type, to)) {

    }

    throw NotImplementedError()
}

private fun FunctionBuilder.resolveConstruct(name: String?, ast: ASTConstructExpr): Value {
    val type = resolveType(function.module, ast.type, null)
    when(type) {
        is RecordType -> {

        }
        is AliasType -> {

        }
    }

    throw NotImplementedError()
}

private fun FunctionBuilder.resolveTup(name: String?, ast: ASTTupExpr): Value {
    val args = ArrayList<Value>()
    val type = TupType()
    ast.args.forEachIndexed { i, it ->
        val v = resolveExpr(it.content, null, true, null)
        args.add(v)
        type.fields.add(Field(it.name, i, v.type, type, v.type is RefType))
    }
    return block.tup(name, type, args)
}

private fun FunctionBuilder.resolveTupUpdate(name: String?, ast: ASTTupUpdateExpr): Value {
    val target = resolveExpr(ast.value, null, true, null)
    throw NotImplementedError()
}

private fun FunctionBuilder.resolveCase(name: String?, ast: ASTCaseExpr, resultUsed: Boolean): Value {
    throw NotImplementedError()
}

private fun opInfo(module: Module, name: Qualified): Operator {
    val op = module.findOperator(name)
    if(op != null) return op
    return Operator(9, false)
}

private fun reorder(module: Module, ast: ASTInfixExpr, min: Int): ASTInfixExpr {
    var lhs = ast
    while(lhs.rhs is ASTInfixExpr && !lhs.ordered) {
        val first = opInfo(module, lhs.op.name)
        if(first.precedence < min) break

        val rhs = lhs.rhs as ASTInfixExpr
        val second = opInfo(module, rhs.op.name)
        if(second.precedence > first.precedence || (second.precedence == first.precedence && second.isRight)) {
            lhs.rhs = reorder(module, rhs, second.precedence)
            if(lhs.rhs == rhs) {
                lhs.ordered = true
                break
            }
        } else {
            lhs.ordered = true
            lhs.rhs = rhs.lhs
            rhs.lhs = lhs
            lhs = rhs
        }
    }
    return lhs
}

private fun alwaysTrue(v: Value) = when(v) {
    is Lit -> v.literal is BoolLiteral && v.literal.v
    else -> false
}

// Returns an applicable field inside a static aggregate type, if any.
private fun findStaticField(type: Type, stringField: String?, intField: Int?): Field? {
   if(type is TupType) {
        type.fields.forEachIndexed { i, it ->
            if(it.name == stringField) {
                return it
            }

            if(i == intField) {
                return it
            }
        }
    }
    return null
}

// Returns the type that a particular type would behave as for lookup purposes.
private fun canonicalType(it: Type): Type {
    when(it) {
        is RefType -> return it.to
        is RecordType -> {
            if(it.constructors.size == 1) {
                it.constructors.first().content?.let { return it }
            }
            return it
        }
        else -> return it
    }
}